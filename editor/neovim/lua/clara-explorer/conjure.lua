--- Conjure transport: eval-str wrapper, error surfacing, async plumbing.
-- The Conjure `conjure.eval` API contract:
--   * eval via the `eval["eval-str"]` accessor (bracket form, not dotted)
--   * the value callback key is `["on-result"]` (fires only when there is a
--     `value`; never on a Clojure exception)
--   * errors surface via the `cb` key (full nREPL response), inspecting
--     `resp.err` / `resp.ex` / `resp["root-ex"]`.

local M = {}

local function eval_module()
  local ok, eval = pcall(require, "conjure.eval")
  if ok and eval and eval["eval-str"] then return eval end
  return nil
end

--- True when a Conjure Clojure nREPL session is connected.
function M.connected()
  local ok, server = pcall(require, "conjure.client.clojure.nrepl.server")
  if not ok or not server or not server["connected?"] then return false end
  local ok2, res = pcall(server["connected?"])
  return ok2 and res == true
end

--- Current buffer namespace (like `cider-current-ns`), or nil.
function M.current_ns()
  local ok, extract = pcall(require, "conjure.extract")
  if ok and extract and extract.context then
    local ok2, ns = pcall(extract.context)
    if ok2 and type(ns) == "string" and ns ~= "" then return ns end
  end
  local ctx = vim.b["conjure#context"]
  if type(ctx) == "string" and ctx ~= "" then return ctx end
  return nil
end

local ESC = { ["\\"] = "\\\\", ['"'] = '\\"', ["\n"] = "\\n", ["\t"] = "\\t", ["\r"] = "\\r" }

--- Serialize a Lua string as an EDN string literal.
function M.edn_string(s) return '"' .. (s:gsub('[\\"\n\t\r]', ESC)) .. '"' end

--- Build the Clojure `client/navigate` form for a payload
-- `{production, side, caller_ns, token}` (mirrors the elisp `--navigate-code`).
function M.navigate_code(payload)
  local parts = {}
  if payload.production then parts[#parts + 1] = ":production " .. M.edn_string(payload.production) end
  if payload.side then parts[#parts + 1] = ":side :" .. payload.side end
  if payload.caller_ns then parts[#parts + 1] = ":caller-ns " .. M.edn_string(payload.caller_ns) end
  parts[#parts + 1] = ":token " .. M.edn_string(payload.token)
  return "(do (require 'clara.server.graph.client)\n     (clara.server.graph.client/navigate "
    .. "{"
    .. table.concat(parts, " ")
    .. "}))"
end

--- Concise summary of an nREPL stack-trace string (head + `Caused by:` line).
function M.err_summary(err)
  if type(err) ~= "string" or err == "" then return "" end
  local lines = {}
  for line in err:gmatch("[^\n]+") do
    lines[#lines + 1] = line
  end
  local head = lines[1] or ""
  local cause
  for _, line in ipairs(lines) do
    if line:match("^Caused by:") then
      cause = line
      break
    end
  end
  if cause then return head .. " | " .. cause end
  return head
end

--- Eval `opts.code` over Conjure; on a value call `opts.on_value(value, bufnr, win)`;
-- on an nREPL error call `opts.on_error(summary, bufnr, win)`.  `bufnr`/`win`
-- are captured at call time (the eval is async).
function M.eval_edn(opts)
  local eval = eval_module()
  local bufnr = opts.bufnr or vim.api.nvim_get_current_buf()
  local win = opts.win or vim.api.nvim_get_current_win()
  if not eval then
    if opts.on_error then opts.on_error("clara-explorer: conjure.eval unavailable", bufnr, win) end
    return
  end

  local settled = false
  local function finish(fn, ...)
    if settled then return end
    settled = true
    fn(...)
  end

  eval["eval-str"]({
    code = opts.code,
    origin = "clara-explorer",
    ["on-result"] = function(value)
      finish(opts.on_value or function() end, value, bufnr, win)
    end,
    cb = function(resp)
      if settled then return end
      local err = resp.err
      local ex = resp.ex or resp["root-ex"]
      if err and err ~= "" then
        local summary = M.err_summary(err)
        finish(
          opts.on_error or function() end,
          "clara-explorer: " .. (ex or "eval failed") .. (summary ~= "" and (" — " .. summary) or ""),
          bufnr,
          win
        )
      elseif ex and ex ~= "" then
        finish(opts.on_error or function() end, "clara-explorer: " .. ex, bufnr, win)
      end
    end,
  })
end

return M
