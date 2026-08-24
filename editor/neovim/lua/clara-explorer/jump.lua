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

-- Vim regexes are built from Lua long-bracket strings (no `\\` doubling in
-- source) and Vim's `\v` (very magic) / `\V` (very nomagic) switches: `\v`
-- makes the structural metacharacters readable, and `\V` matches the rule name
-- literally without escaping its punctuation.  Rule names are Clojure symbols,
-- so they never contain a backslash.

-- Same symbol character class as `token.lua`'s TOKEN_CHARS.
local SYMBOL_CHARS = "A-Za-z0-9._:/!?*+<>-"
local NON_SYMBOL = "[^" .. SYMBOL_CHARS .. "]"

-- `(alias/defrule` / `(defquery` head, optional `^meta` tokens, then whitespace.
local PRODUCTION_HEAD = [[\v\(([^ \t\n()]*/)?def(rule|query)(\s+\^[^ \t\n()]*)*\s+]]

--- Vim regex for the definition-site search: `(alias/defrule NAME` /
-- `(defquery NAME`, with optional `^meta` between the head and the name.  The
-- name must be followed by whitespace, `)` or end-of-line.
function M.fallback_regex(rule_name) return PRODUCTION_HEAD .. [[\V]] .. rule_name .. [[\v\ze(\s|$|\))]] end

--- Vim regex matching `rule_name` as a whole Clojure symbol — bounded by
-- non-symbol characters (or line start/end), so trailing `?`/`!`/`*`/`+` are
-- part of the name (Vim's `\<`/`\>` word boundaries would split on them).
function M.symbol_token_regex(rule_name)
  return [[\v(^|]] .. NON_SYMBOL .. [[)\zs\V]] .. rule_name .. [[\v\ze(]] .. NON_SYMBOL .. [[|$)]]
end

--- Convert a `jar:file:/…!/entry` resource URL to Neovim's `zipfile://…::entry`
-- form (mirrors Conjure's own jar-path handling in
-- `conjure.client.clojure.nrepl.action`).
local function zipfile_url(url)
  local zip, entry = url:match("^jar:file:(.+)!/(.+)$")
  if not zip or not entry then return nil end
  local ver = tonumber((vim.g.loaded_zipPlugin or "v0"):sub(2)) or 0
  if ver > 31 then return "zipfile://" .. zip .. "::" .. entry end
  return "zipfile:" .. zip .. "::" .. entry
end

--- Open a resolved `file:`/`jar:` resource URL string, or false when the URL
-- cannot be opened by Neovim.
local function open_resource(url)
  if not url or url == "" or url == "nil" then return false end
  if url:match("^file:") then
    vim.cmd.edit(url:gsub("^file:", ""))
    return true
  end
  local zip = zipfile_url(url)
  if zip then
    vim.cmd.edit(zip)
    return true
  end
  return false
end

--- Regex fallback: open the namespace file and search for `(defrule|defquery NAME`.
function M.goto_fallback(target, eval_edn)
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
      local found = vim.fn.search(M.fallback_regex(unqualified), "w")
      if not found or found == 0 then found = vim.fn.search(M.symbol_token_regex(unqualified), "w") end
      if not found or found == 0 then
        vim.notify("clara-explorer: production " .. name .. " not found in " .. ns, vim.log.levels.WARN)
      end
    else
      vim.notify("clara-explorer: cannot resolve namespace " .. ns, vim.log.levels.WARN)
    end
  end
  eval_edn({
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
  local eval_edn = opts.eval_edn or function(o) conjure.eval_edn(o) end
  M.goto_fallback(target, eval_edn)
end

return M
