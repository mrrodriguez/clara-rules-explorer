--- Jump to a decoded navigate target (§6.2).  Var-backed targets reuse
-- Conjure's `def-str` (nREPL `info` op -> absolute `file:`/`jar:` path);
-- non-var targets eval `(clojure.java.io/resource …)` and fall back to a
-- namespace-file regex search.  Pushes the jump list for `C-o` first.

local M = {}

local conjure = require("clara-explorer.conjure")

local function def_str_module()
  local ok, action = pcall(require, "conjure.client.clojure.nrepl.action")
  if ok and action and action["def-str"] then return action end
  return nil
end

--- Push the current position onto the jump list.
function M.push_jump()
  pcall(vim.cmd, "normal! m'")
  local ok, win = pcall(vim.api.nvim_get_current_win)
  if ok then
    pcall(vim.fn.settagstack, win, {
      items = { { tagname = "clara-explorer", from = vim.fn.getpos(".") } },
    }, "a")
  end
end

--- Open a resolved `file:`/`jar:` resource URL string, if possible.
local function open_resource(url)
  if not url or url == "" or url == "nil" then return false end
  -- `(some-> (clojure.java.io/resource …) str)` yields `file:/…` or `jar:…`.
  if url:match("^file:") then
    local path = url:gsub("^file:", "")
    vim.cmd.edit(path)
    return true
  elseif url:match("^jar:") then
    -- Let Conjure's own jar handling (zipfile://) take over via edit.
    vim.cmd.edit(url:gsub("^jar:", ""))
    return true
  end
  return false
end

--- Regex fallback: open the namespace file and search for `(defrule|defquery NAME`.
function M.goto_fallback(target, eval_str)
  local ns = target.ns
  local name = target.name
  if not ns or not name then
    vim.notify("clara-explorer: cannot resolve source for target", vim.log.levels.WARN)
    return
  end
  local unqualified = name:match("/([^/]+)$") or name
  local resource_form = "(do (require 'clojure.string)\n     (some-> (clojure.java.io/resource (str (clojure.string/replace "
    .. conjure.edn_string(ns)
    .. ' "." "/") ".clj")) str))'
  local function on_value(url)
    if open_resource(url) then
      vim.cmd.normal({ "gg", bang = true })
      local found = vim.fn.search("(.*def" .. "rule\\|def" .. "query" .. ".*" .. vim.pesc(unqualified), "w")
      -- simpler, robust: search for the bare name as a whole word
      if not found or found == 0 then found = vim.fn.search("\\<" .. vim.pesc(unqualified) .. "\\>", "w") end
      if not found or found == 0 then
        vim.notify("clara-explorer: production " .. name .. " not found in " .. ns, vim.log.levels.WARN)
      end
    else
      vim.notify("clara-explorer: cannot resolve namespace " .. ns, vim.log.levels.WARN)
    end
  end
  eval_str({
    code = resource_form,
    on_value = on_value,
    on_error = function(msg) vim.notify(msg, vim.log.levels.ERROR) end,
  })
end

--- Jump to `target` (decoded table with `name`, `ns`, `source.var?`, …).
function M.jump(target, opts)
  opts = opts or {}
  M.push_jump()
  local source = target.source or {}
  if source["var?"] then
    local action = def_str_module()
    if action then
      action["def-str"]({ code = target.name, context = opts.caller_ns })
      return
    end
  end
  local eval_str = opts.eval_str or function(o) conjure.eval_edn(o) end
  M.goto_fallback(target, eval_str)
end

return M
