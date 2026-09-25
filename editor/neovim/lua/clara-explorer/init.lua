--- Clara Explorer Neovim client: commands, dispatcher, context gathering.
-- Structural skeleton via `structural` (tree-sitter); token resolution via
-- `token` (byte-based port); transport via `conjure`; UI via `picker`/`jump`.

local edn = require("clara-explorer.edn")
local token = require("clara-explorer.token")
local structural = require("clara-explorer.structural")
local conjure = require("clara-explorer.conjure")
local picker = require("clara-explorer.picker")
local jump = require("clara-explorer.jump")

local M = {}

--- Byte offset of a 0-indexed (row, col) in a buffer's line array.
local function offset_of(lines, row, col)
  local acc = 0
  for i = 1, row do
    acc = acc + #lines[i] + 1
  end
  return acc + col
end

--- Gather navigation context at (row, col) in `bufnr` (0-indexed row/col).
-- Returns `{production, kind, side, caller_ns, token}`.
function M.context(bufnr, row, col)
  local enc = structural.enclosing_production(bufnr, row, col)
  local caller_ns = conjure.current_ns()
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local src = table.concat(lines, "\n")
  local cursor = offset_of(lines, row, col)

  if enc then
    local form_start = offset_of(lines, enc.range[1], enc.range[2])
    local side = token.side_at_point(src, form_start, cursor)
    local tok = token.token_at_point(src, form_start, side, cursor)
    local production = (enc.name and caller_ns) and (caller_ns .. "/" .. enc.name) or nil
    return { production = production, kind = enc.kind, side = side, caller_ns = caller_ns, token = tok }
  end

  return {
    production = nil,
    kind = nil,
    side = nil,
    caller_ns = caller_ns,
    token = token.token_at_point(src, nil, nil, cursor),
  }
end

--- Handle a decoded navigate result: relay `error`, else choose/jump to targets.
function M.handle_result(result, caller_ns)
  if result.error then
    vim.notify(result.error, vim.log.levels.INFO)
    return
  end
  picker.choose_or_jump(
    result.direction,
    result.type,
    result.targets,
    function(target) jump.jump(target, { caller_ns = caller_ns }) end
  )
end

--- Shared dispatcher for producer (`"lhs"`) / consumer (`"rhs"`) navigation.
function M.navigate(side)
  if not conjure.connected() then
    vim.notify("Not connected to a Conjure Clojure REPL", vim.log.levels.WARN)
    return
  end
  local bufnr = vim.api.nvim_get_current_buf()
  local win = vim.api.nvim_get_current_win()
  local crow, ccol = unpack(vim.api.nvim_win_get_cursor(0))
  local ctx = M.context(bufnr, crow - 1, ccol)

  if not ctx.token then
    vim.notify("not on a fact type", vim.log.levels.INFO)
    return
  end
  if side == "rhs" and ctx.kind == "query" then
    vim.notify("queries have no RHS", vim.log.levels.INFO)
    return
  end
  if not ctx.production and side == "lhs" then
    vim.notify("not inside a rule/query", vim.log.levels.INFO)
    return
  end

  -- Resolve the token to fully-qualified form first; on any failure the raw
  -- token goes through and the server resolves it as before.
  conjure.resolve_token({
    caller_ns = ctx.caller_ns,
    token = ctx.token,
    bufnr = bufnr,
    win = win,
    on_resolved = function(fq)
      local resolved_token = fq or ctx.token
      if conjure.bb_transport_p() then
        conjure.bb_selection(function(selection)
          if not selection then return end
          local input = conjure.navigate_input({
            production = ctx.production,
            side = side,
            caller_ns = ctx.caller_ns,
            token = resolved_token,
          })
          conjure.bb_eval(selection, input, function(result, err)
            if vim.api.nvim_win_is_valid(win) then vim.api.nvim_set_current_win(win) end
            if err then
              vim.notify(err, vim.log.levels.ERROR)
              return
            end
            if not result then
              vim.notify("clara-explorer: no result from navigate", vim.log.levels.ERROR)
              return
            end
            M.handle_result(result, ctx.caller_ns)
          end)
        end)
        return
      end

      local code = conjure.navigate_code({
        production = ctx.production,
        side = side,
        caller_ns = ctx.caller_ns,
        token = resolved_token,
      })

      conjure.eval_edn({
        code = code,
        bufnr = bufnr,
        win = win,
        on_value = function(value, _, cb_win)
          if vim.api.nvim_win_is_valid(cb_win) then vim.api.nvim_set_current_win(cb_win) end
          local result, err = edn.decode(value)
          if err then
            vim.notify(err, vim.log.levels.ERROR)
            return
          end
          M.handle_result(result, ctx.caller_ns)
        end,
        on_error = function(msg) vim.notify(msg, vim.log.levels.ERROR) end,
      })
    end,
  })
end

--- `:ClaraExplorerRefresh` — re-derive annotations and re-warm the analysis.
function M.refresh()
  if conjure.bb_transport_p() then
    vim.notify(
      "clara-explorer: refresh is a no-op in bb mode (re-persist the artifacts to pick up changes)",
      vim.log.levels.INFO
    )
    return
  end
  if not conjure.connected() then
    vim.notify("Not connected to a Conjure Clojure REPL", vim.log.levels.WARN)
    return
  end
  conjure.eval_edn({
    code = "(do (require 'clara.server.graph.server)\n     (clara.server.graph.server/reload-annotations!))",
    on_value = function() vim.notify("clara-explorer: analysis refreshed", vim.log.levels.INFO) end,
    on_error = function(msg) vim.notify(msg, vim.log.levels.ERROR) end,
  })
end

local swap_opts_by_buf = {}

--- Eval a session-swap (or the registered default) and cache the last opts.
function M.perform_swap(raw, bufnr)
  local trimmed = (raw or ""):gsub("^%s+", ""):gsub("%s+$", "")
  local use_default = trimmed == ""
  if not use_default then swap_opts_by_buf[bufnr] = trimmed end
  local code
  if use_default then
    code = "(do (require 'clara.server.graph.client)\n     (clara.server.graph.client/swap-session!))"
  else
    code = "(do (require 'clara.server.graph.client)\n     (clara.server.graph.client/swap-session! " .. trimmed .. "))"
  end
  conjure.eval_edn({
    code = code,
    on_value = function() vim.notify("clara-explorer: session swapped", vim.log.levels.INFO) end,
    on_error = function(msg) vim.notify(msg, vim.log.levels.ERROR) end,
  })
end

--- `:ClaraExplorerSwapSession` — prompt (re-prompt when `bang` is `"!"`), cache
-- last per buffer.
function M.swap_session(bang)
  if conjure.bb_transport_p() then
    vim.notify("clara-explorer: swap-session is a no-op in bb mode", vim.log.levels.INFO)
    return
  end
  if not conjure.connected() then
    vim.notify("Not connected to a Conjure Clojure REPL", vim.log.levels.WARN)
    return
  end
  local bufnr = vim.api.nvim_get_current_buf()
  local cached = swap_opts_by_buf[bufnr]
  if bang ~= "!" and cached ~= nil then
    M.perform_swap(cached, bufnr)
    return
  end
  vim.ui.input({ prompt = "Swap opts (EDN map, empty for default): " }, function(input)
    if input == nil then
      return -- cancelled
    end
    M.perform_swap(input, bufnr)
  end)
end

--- `:ClaraExplorerToggleTransport` — toggle between the nREPL and bb transports.
function M.toggle_transport()
  vim.g.clara_explorer_transport = conjure.bb_transport_p() and "nrepl" or "bb"
  vim.notify("clara-explorer: transport is now " .. vim.g.clara_explorer_transport, vim.log.levels.INFO)
end

--- `:ClaraExplorerSelectUnit` — re-prompt for the bb transport's registry unit.
function M.select_unit()
  if not conjure.bb_transport_p() then
    vim.notify(
      "clara-explorer: warning — unit selection only affects the bb transport (currently nrepl)",
      vim.log.levels.WARN
    )
    return
  end
  conjure.bb_select_unit(function(sel)
    if sel then vim.notify("clara-explorer: bb unit set", vim.log.levels.INFO) end
  end)
end

return M
