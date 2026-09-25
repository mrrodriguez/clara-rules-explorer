--- Conjure transport: eval-str wrapper, error surfacing, async plumbing.
-- The Conjure `conjure.eval` API contract:
--   * eval via the `eval["eval-str"]` accessor (bracket form, not dotted)
--   * the value callback key is `["on-result"]` (fires only when there is a
--     `value`; never on a Clojure exception)
--   * errors surface via the `cb` key (full nREPL response), inspecting
--     `resp.err` / `resp.ex` / `resp["root-ex"]`.

local edn = require("clara-explorer.edn")

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

--- Build the EDN `NavigateInput` map for a payload — the same keys as
-- `navigate_code`, but a plain EDN map (the bb transport reads EDN, not a form).
function M.navigate_input(payload)
  local parts = {}
  if payload.production then parts[#parts + 1] = ":production " .. M.edn_string(payload.production) end
  if payload.side then parts[#parts + 1] = ":side :" .. payload.side end
  if payload.caller_ns then parts[#parts + 1] = ":caller-ns " .. M.edn_string(payload.caller_ns) end
  parts[#parts + 1] = ":token " .. M.edn_string(payload.token)
  return "{" .. table.concat(parts, " ") .. "}"
end

--- Directory of this module file, for locating the resolve template.
local this_dir = (debug.getinfo(1, "S").source:sub(2):match("^(.*/)") or "./")

--- Canonical resolve-form template text.
-- Read once at load; a missing file fails fast.
local resolve_template = (function()
  local template_path = this_dir .. "editor-resolve-form.clj"
  local fh = io.open(template_path, "r")
  if not fh then error("clara-explorer: resolve template not found at " .. template_path) end
  local text = fh:read("*a")
  fh:close()
  return text
end)()

--- Build the self-contained resolve form for CALLER_NS and TOKEN.
function M.resolve_code(caller_ns, token)
  return resolve_template:format(M.edn_string(caller_ns or ""), M.edn_string(token))
end

--- Resolve TOKEN via one eval; call `opts.on_resolved(fq_or_nil)`. Any
-- transport or decode failure resolves to nil so the caller falls back to
-- the raw token. `opts` carries `caller_ns`, `token`, `bufnr`, `win`.
function M.resolve_token(opts)
  if not opts or not opts.token then
    if opts and opts.on_resolved then opts.on_resolved(nil) end
    return
  end
  M.eval_edn({
    code = M.resolve_code(opts.caller_ns, opts.token),
    bufnr = opts.bufnr,
    win = opts.win,
    on_value = function(value)
      local ok, decoded = pcall(edn.decode, value)
      if ok and type(decoded) == "string" then
        opts.on_resolved(decoded)
      else
        opts.on_resolved(nil)
      end
    end,
    on_error = function() opts.on_resolved(nil) end,
  })
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

--- babashka transport — shell out to `bb editor_client.bb` over the
-- persisted artifacts instead of evaling over Conjure.

--- The configured transport: `"nrepl"` (default) or `"bb"`.
function M.transport() return vim.g.clara_explorer_transport or "nrepl" end

--- True when navigation uses the babashka transport.
function M.bb_transport_p() return M.transport() == "bb" end

--- Registry root for the bb transport: `g:clara_explorer_registry_root` when
-- set, else `CLARA_RULES_EXPLORER_REGISTRY`; expanded to an absolute path.
function M.registry_root()
  local root = vim.g.clara_explorer_registry_root
  if not root or root == "" then root = vim.env.CLARA_RULES_EXPLORER_REGISTRY end
  if not root or root == "" then return nil end
  return vim.fn.fnamemodify(root, ":p")
end

--- Path to `editor_client.bb`: `g:clara_explorer_bb_script` when set, else the
-- repo symlink beside this module.
function M.bb_script()
  local script = vim.g.clara_explorer_bb_script
  if script and script ~= "" then return script end
  return vim.fn.fnamemodify(this_dir .. "editor_client.bb", ":p")
end

--- Repo-relative unit directories (the dir holding rules-inspect-manifest.edn)
-- under ROOT, sorted — mirrors the elisp `clara-explorer--bb-list-unit-repos`.
function M.bb_list_unit_repos(root)
  local root_abs = vim.fn.fnamemodify(root, ":p")
  local prefix = root_abs:gsub("/+$", "") .. "/"
  local repos = {}
  for _, path in ipairs(vim.fn.globpath(root_abs, "**/rules-inspect-manifest.edn", 0, 1) or {}) do
    local dir = vim.fn.fnamemodify(path, ":p:h")
    local rel = dir
    if vim.startswith(rel, prefix) then rel = rel:sub(#prefix + 1) end
    rel = rel:gsub("/+$", "")
    if rel ~= "" then repos[#repos + 1] = rel end
  end
  table.sort(repos)
  return repos
end

--- Prompt for a single-unit registry selection under the registry root.
-- Calls `cb(selection_edn)` with the EDN map string, or `cb(nil)` when
-- cancelled/absent. Mirrors the elisp `clara-explorer--bb-prompt-selection`.
function M.bb_prompt_selection(cb)
  local root = M.registry_root()
  if not root or vim.fn.isdirectory(root) ~= 1 then
    vim.notify(
      "clara-explorer: set CLARA_RULES_EXPLORER_REGISTRY (or g:clara_explorer_registry_root) to a registry root",
      vim.log.levels.ERROR
    )
    cb(nil)
    return
  end
  local repos = M.bb_list_unit_repos(root)
  if #repos == 0 then
    vim.notify("clara-explorer: no units (rules-inspect-manifest.edn) found under " .. root, vim.log.levels.ERROR)
    cb(nil)
    return
  end
  vim.ui.select(repos, { prompt = "Unit repo (under " .. root .. "): " }, function(repo)
    if not repo then
      cb(nil)
      return
    end
    cb("{:root " .. M.edn_string(root) .. " :units [{:repo " .. M.edn_string(repo) .. "}]}")
  end)
end

M.bb_selection_cache = nil

--- The cached bb registry-selection EDN, prompting once when unset.
function M.bb_selection(cb)
  if M.bb_selection_cache then
    cb(M.bb_selection_cache)
    return
  end
  M.bb_prompt_selection(function(sel)
    if sel then M.bb_selection_cache = sel end
    cb(sel)
  end)
end

--- Re-prompt for the bb transport's registry unit (clears the cache).
function M.bb_select_unit(cb)
  M.bb_selection_cache = nil
  M.bb_selection(cb)
end

--- Run `bb editor_client.bb SELECTION INPUT` and call `cb(result, err)` with
-- the decoded EDN result (or `cb(nil, err)` on transport/EDN failure). Mirrors
-- the elisp `clara-explorer--bb-eval`.
function M.bb_eval(selection_edn, input_edn, cb)
  local script = M.bb_script()
  if vim.fn.filereadable(script) ~= 1 then
    cb(nil, "clara-explorer: editor_client.bb not found at " .. script)
    return
  end
  local function on_exit(out)
    vim.schedule(function()
      if out.code ~= 0 then
        local msg
        if out.code then
          msg = "clara-explorer: bb transport failed (exit " .. out.code .. ")"
        else
          msg = "clara-explorer: bb transport failed (terminated by signal " .. tostring(out.signal) .. ")"
        end
        local details = vim.trim((out.stderr or "") .. (out.stdout or ""))
        if details ~= "" then msg = msg .. ": " .. details end
        cb(nil, msg)
        return
      end
      local result, err = edn.decode(out.stdout or "")
      if err then
        cb(nil, "clara-explorer: bb transport returned invalid EDN: " .. err)
        return
      end
      cb(result, nil)
    end)
  end
  local ok, spawn_err = pcall(vim.system, { "bb", script, selection_edn, input_edn }, { text = true }, on_exit)
  if not ok then cb(nil, "clara-explorer: bb not found (" .. tostring(spawn_err) .. ")") end
end

return M
