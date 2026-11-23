--- Locate a production's source, then show it.
--
-- A jump is two steps, in this order, because a failed jump must leave every
-- window, the jump list, and point exactly as they were:
--
--   1. *locate* — find a file without displaying or selecting anything: the
--      loaded var's `:file`/`:line` from the REPL (which reaches a namespace
--      loaded from a checkout that is not on the classpath), then an open
--      buffer whose own `ns` form names the namespace, then the source roots.
--   2. *show* — push the jump list, choose the window (one already showing the
--      file, else the previous window so a provenance split stays in place),
--      open the file, and jump to the reported line or search for the form.
--
-- Navigate's var-backed targets keep Conjure's `def-str` (the picker runs from
-- the source buffer, where Conjure can resolve its client); the provenance view
-- routes every visit through locate, so a production from another repo is
-- reachable whether or not its namespace is loaded.

local M = {}

local conjure = require("clara-explorer.conjure")
local edn = require("clara-explorer.edn")
local structural = require("clara-explorer.structural")

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
-- makes the structural metacharacters readable, and `\V` matches a macro name
-- literally without escaping its punctuation.  Rule names are Clojure symbols,
-- so they never contain a backslash.

-- Same symbol character class as `token.lua`'s TOKEN_CHARS.
local SYMBOL_CHARS = "A-Za-z0-9._:/!?*+<>-"
local NON_SYMBOL = "[^" .. SYMBOL_CHARS .. "]"

--- Vim regex alternation over the configured production macro names.
-- Each name is matched literally (`\V` … `\m`) and the modes switch back to
-- very magic for the `|`.  An empty list becomes `\%x00` — a NUL byte, which a
-- buffer line cannot hold — so the head search matches nothing and only the
-- whole-symbol fallback can find the name.
local function head_alternation()
  local names = {}
  for name in pairs(structural.production_macros()) do
    names[#names + 1] = name
  end
  if #names == 0 then return [[\%x00]] end
  table.sort(names)
  local parts = {}
  for _, name in ipairs(names) do
    parts[#parts + 1] = [[\V]] .. name .. [[\m\v]]
  end
  return table.concat(parts, "|")
end

--- Vim regex for the definition-site search: `(alias/<macro> NAME` /
-- `(<macro> NAME`, with optional `^meta` between the head and the name.  The
-- macros come from `structural.production_macros`; the name must be followed by
-- whitespace, `)` or end-of-line.
function M.fallback_regex(rule_name)
  local head = [[\v\(([^ \t\n()]*/)?(]] .. head_alternation() .. [[)(\s+\^[^ \t\n()]*)*\s+]]
  return head .. [[\V]] .. rule_name .. [[\v\ze(\s|$|\))]]
end

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

--- The filesystem path a location names, or nil for a jar URL, a REPL buffer
-- name, or anything else that is not a readable file.
local function file_path(path)
  if type(path) ~= "string" or path == "" or path:sub(1, 1) == "*" then return nil end
  local abs = path:match("^file:(.+)$") or path
  if abs:match("^/") or abs:match("^%a:[/\\]") then return abs end
  return nil
end

--- Read up to `count` lines of PATH without opening a buffer.
local function read_head(path, count)
  local ok, lines = pcall(vim.fn.readfile, path, "", count)
  if not ok or type(lines) ~= "table" then return nil end
  return lines
end

--- Open a located source in the current window: an absolute path, a `file:`
-- URL, or a `jar:` URL (through Neovim's zipfile support).
local function open_source(path)
  if not path or path == "" or path == "nil" then return false end
  if path:match("^file:") then
    vim.cmd.edit((path:gsub("^file:", "")))
    return true
  end
  local zip = zipfile_url(path)
  if zip then
    vim.cmd.edit(zip)
    return true
  end
  if path:match("^/") or path:match("^%a:[/\\]") then
    vim.cmd.edit(path)
    return true
  end
  return false
end

--- The window to show a located file in: the one already showing it, else the
-- previous window (so a provenance split stays where it is).
local function navigation_window(path)
  local file = file_path(path)
  if file then
    local buf = vim.fn.bufnr(file)
    if buf ~= -1 then
      local win = vim.fn.bufwinid(buf)
      if win ~= -1 then return win end
    end
  end
  local ok, nr = pcall(vim.fn.winnr, "#")
  if ok and nr and nr > 0 then return vim.fn.win_getid(nr) end
  return vim.api.nvim_get_current_win()
end

-- ---------------------------------------------------------------------------
-- Namespace names

--- Skip the Clojure form starting at byte `i`; returns the byte after it.
local function skip_form(text, i)
  local c = text:sub(i, i)
  local closer = ({ ["("] = ")", ["["] = "]", ["{"] = "}" })[c]
  if closer then
    local depth = 0
    local n = #text
    while i <= n do
      local ch = text:sub(i, i)
      if ch == '"' then
        i = skip_form(text, i) -- a string, via the branch below
        if not i then return nil end
      elseif ch == c then
        depth = depth + 1
        i = i + 1
      elseif ch == closer then
        depth = depth - 1
        i = i + 1
        if depth == 0 then return i end
      else
        i = i + 1
      end
    end
    return nil
  end
  if c == '"' then
    local j = i + 1
    while true do
      local ch = text:sub(j, j)
      if ch == "" then return nil end
      if ch == "\\" then
        j = j + 2
      elseif ch == '"' then
        return j + 1
      else
        j = j + 1
      end
    end
  end
  local _, e = text:find('^[^%s%[%]{}()",;]+', i)
  return e and e + 1 or nil
end

--- The namespace named by a Clojure source's `ns` form, or nil.
-- Reads text only, so a rejected candidate leaves nothing behind.  The form is
-- `(ns ^meta name …)`; metadata is skipped form-by-form, so `^:kw`,
-- `^{:doc …}` and stacked metadata all work.  Line comments are stripped first
-- (a docstring holding `;` would confuse that, which is why only the head of
-- the file is read and the risk is accepted — same as the Emacs client).
function M.ns_name_from_text(text)
  if type(text) ~= "string" then return nil end
  local body = text:gsub(";.-\n", "\n")
  local _, ns_end = body:find("%(%s*ns%s")
  if not ns_end then return nil end
  local i = ns_end + 1
  while true do
    i = body:find("%S", i)
    if not i then return nil end
    if body:sub(i, i) == "^" then
      i = skip_form(body, i + 1) -- the metadata form; the name follows
      if not i then return nil end
    else
      return body:match("^[%w%._%-%*/+!?<>=:]+", i)
    end
  end
end

--- The namespace of the source file at PATH, or nil.
function M.ns_name_of_file(path)
  local lines = read_head(path, 200)
  if not lines then return nil end
  return M.ns_name_from_text(table.concat(lines, "\n"))
end

--- NS's source path relative to a source directory, without an extension.
-- Dots become separators and dashes underscores, as Clojure munges a namespace
-- into a resource name.
function M.ns_relative_path(ns) return (ns:gsub("%.", "/"):gsub("%-", "_")) end

local function is_clojure_source(name) return name:match("%.clj[cs]?$") ~= nil end

--- The source-root walk's default skip list: compile output and vendor trees.
local DEFAULT_SKIP_DIRECTORIES = { "node_modules", "target", "out" }

--- Directory names the source-root walk never enters.  A configured list
-- replaces the defaults, so a project can add its own build directory.
local function skip_directories()
  local configured = vim.g.clara_explorer_source_roots_skip_directories
  if type(configured) == "table" and #configured > 0 then return configured end
  return DEFAULT_SKIP_DIRECTORIES
end

--- True when STR ends in SUFFIX.
local function ends_with(str, suffix) return #str >= #suffix and str:sub(-#suffix) == suffix end

--- Walk ROOT at any depth, collecting files whose path ends in
-- `/<relative>.clj` or `/<relative>.cljc`.  Dot-directories and the skip list
-- are not entered; a compiled `.cljc` copy shares its source's `ns` form, so
-- the `ns` check alone cannot reject it.  Symlinked directories are not
-- followed, matching Emacs's `directory-files-recursively`.
local function walk_source_root(root, relative, out, skipped)
  local ok, iter = pcall(vim.fs.dir, root)
  if not ok or type(iter) ~= "function" then return end
  local clj = "/" .. relative .. ".clj"
  local cljc = "/" .. relative .. ".cljc"
  for name, kind in iter do
    local path = root .. "/" .. name
    if kind == "file" and (ends_with(path, clj) or ends_with(path, cljc)) then
      out[#out + 1] = path
    elseif kind == "directory" and name:sub(1, 1) ~= "." and not skipped[name] then
      walk_source_root(path, relative, out, skipped)
    end
  end
end

--- Files under ROOTS that could hold NS: roots in order, shallowest first in
-- each.  The walk has no depth limit, so a root may be one checkout or a
-- directory of them, and a ruleset nested a few directories into a repo is
-- still found.
function M.source_root_candidates(ns, roots)
  local relative = M.ns_relative_path(ns)
  local skipped = {}
  for _, name in ipairs(skip_directories()) do
    skipped[name] = true
  end
  local out = {}
  for _, root in ipairs(roots or {}) do
    local normalized = vim.fs.normalize(root)
    if vim.fn.isdirectory(normalized) == 1 then walk_source_root(normalized:gsub("/+$", ""), relative, out, skipped) end
  end
  table.sort(out, function(a, b) return select(2, a:gsub("/", "")) < select(2, b:gsub("/", "")) end)
  return out
end

--- The directories `g:clara_explorer_source_roots` names, resolving its default.
-- The default is the parent of the REPL's project directory (a directory of
-- sibling checkouts); a configured list replaces it.
function M.source_roots(project_dir)
  local configured = vim.g.clara_explorer_source_roots
  if type(configured) == "table" and #configured > 0 then return configured end
  if type(project_dir) == "string" and project_dir ~= "" then
    return { vim.fs.dirname(vim.fs.normalize(project_dir)) }
  end
  return {}
end

--- Cached answer for the REPL's project directory.  It is a property of the
-- JVM, so it is asked once per Neovim session; `M.clear_caches` (called by
-- `:ClaraExplorerRefresh`) drops it after a reconnect.
M.project_dir_cache = nil

--- Clear the cached REPL project directory.
function M.clear_caches() M.project_dir_cache = nil end

local PROJECT_MARKERS = { "deps.edn", "project.clj", "bb.edn", ".git" }

--- The REPL's project directory, for the default source root.
-- Asks the REPL itself (`user.dir`, the JVM's working directory, matching
-- Emacs's `nrepl-project-dir`) through ORIGIN_BUFNR, and falls back to the
-- origin buffer's project root when there is no usable REPL.  Calls `cb(dir)`
-- with the directory or nil.
function M.project_dir(origin_bufnr, eval_edn, cb)
  if M.project_dir_cache then
    cb(M.project_dir_cache)
    return
  end
  local function fallback()
    local root
    if origin_bufnr and vim.api.nvim_buf_is_valid(origin_bufnr) then
      root = vim.fs.root(origin_bufnr, PROJECT_MARKERS)
    end
    cb(root)
  end
  eval_edn({
    code = '(System/getProperty "user.dir")',
    origin_bufnr = origin_bufnr,
    on_value = function(value)
      local ok, dir = pcall(edn.decode, value)
      if ok and type(dir) == "string" and dir ~= "" then
        M.project_dir_cache = dir
        cb(dir)
      else
        fallback()
      end
    end,
    on_error = fallback,
  })
end

-- ---------------------------------------------------------------------------
-- Locate

--- Source-location eval form: where the REPL places a production, as EDN
-- `{:file … :line …}`.  The loaded var's metadata comes first — it carries the
-- absolute file and line even when the namespace was loaded from a checkout
-- that is not on the classpath, where no resource exists — then the munged
-- `.clj` / `.cljc` classpath resource, with no line.  Two `%s` slots: the
-- fully-qualified name and the namespace, both as EDN strings.
local SOURCE_LOC_FORM = [[(do (require 'clojure.string 'clojure.java.io)
     (let [name (symbol %s)
           path (str (clojure.string/replace (munge %s) "." "/"))
           m (try (meta (resolve name)) (catch Throwable _ nil))
           as-url (fn [f]
                    (cond
                      (nil? f) nil
                      (clojure.string/starts-with? f "/") (str "file:" f)
                      (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" f) f
                      :else (some-> (clojure.java.io/resource f) str)))]
       (or (when-let [u (as-url (:file m))]
             {:file u :line (:line m)})
           (when-let [u (some-> (clojure.java.io/resource (str path ".clj")) str)]
             {:file u})
           (when-let [u (some-> (clojure.java.io/resource (str path ".cljc")) str)]
             {:file u}))))]]

--- The source-location form for NAME in NS.
function M.source_loc_code(ns, name) return SOURCE_LOC_FORM:format(conjure.edn_string(name), conjure.edn_string(ns)) end

--- The unqualified part of a fully-qualified production NAME.
local function unqualified_name(name) return (name or ""):match("/([^/]+)$") or name end

--- True when the reported line of PATH really holds NAME.
-- A reported line is trusted only when the rule name is on it: an edited buffer
-- drifts from the loaded var's line, and jumping to a stale line is worse than
-- searching.
local function line_holds_name(path, line, name)
  local file = file_path(path)
  if not file or not name or not line then return false end
  local lines = read_head(file, math.max(line, 1))
  local text = lines and lines[line]
  return text ~= nil and text:find(name, 1, true) ~= nil
end

--- The open buffer whose own `ns` form names NS, as a path, or nil.
-- Buffer-local evidence only: the connection's current namespace must not decide
-- this, or every buffer would answer NS while the REPL sits in it.
local function open_buffer_for_ns(ns)
  for _, buf in ipairs(vim.api.nvim_list_bufs()) do
    local name = vim.api.nvim_buf_get_name(buf)
    if vim.api.nvim_buf_is_loaded(buf) and is_clojure_source(name) then
      local lines = vim.api.nvim_buf_get_lines(buf, 0, 200, false)
      if M.ns_name_from_text(table.concat(lines, "\n")) == ns then return name end
    end
  end
  return nil
end

--- A source-root candidate whose `ns` form names NS, or nil.
local function source_root_file(ns, roots)
  for _, path in ipairs(M.source_root_candidates(ns, roots)) do
    if M.ns_name_of_file(path) == ns then return path end
  end
  return nil
end

--- Locate production TARGET without displaying or selecting anything.
-- Calls `cb(location)` with `{path = …}` or `{path = …, line = …}`, or
-- `cb(nil, reason)` when nothing found it.  `opts` carries `origin_bufnr` (the
-- buffer whose REPL answers) and an optional `eval_edn` for tests.
--
-- Order: the REPL's var metadata and resource (step 1), an already-open buffer
-- whose `ns` form names the namespace (step 2), then the source roots (step 3).
-- Only a line that really holds the production is kept.
function M.locate(target, opts, cb)
  opts = opts or {}
  local eval_edn = opts.eval_edn or function(o) conjure.eval_edn(o) end
  local ns = target and target.ns
  local name = target and target.name
  cb = cb or function() end
  if not ns or not name then
    cb(nil, "clara-explorer: cannot resolve source for target")
    return
  end
  local unqualified = unqualified_name(name)

  local function fall_through()
    local open = open_buffer_for_ns(ns)
    if open then
      cb({ path = open })
      return
    end
    M.project_dir(opts.origin_bufnr, eval_edn, function(project)
      local found = source_root_file(ns, M.source_roots(project))
      if found then
        cb({ path = found })
        return
      end
      cb(
        nil,
        ("clara-explorer: cannot locate %s — load it, open its source, or set g:clara_explorer_source_roots"):format(
          ns
        )
      )
    end)
  end

  eval_edn({
    code = M.source_loc_code(ns, name),
    origin_bufnr = opts.origin_bufnr,
    on_value = function(value)
      local ok, decoded = pcall(edn.decode, value)
      local file = ok and type(decoded) == "table" and decoded.file or nil
      if type(file) ~= "string" or file == "" or file:sub(1, 1) == "*" then
        fall_through()
        return
      end
      local location = { path = file }
      local line = decoded.line
      if line and line_holds_name(file, line, unqualified) then location.line = line end
      cb(location)
    end,
    on_error = fall_through,
  })
end

--- Show a located source: push the jump, choose the window, open it, and jump to
-- the reported line or search for the production form.
function M.show(location, target)
  M.push_jump()
  local win = navigation_window(location.path)
  if win and win ~= vim.api.nvim_get_current_win() then pcall(vim.api.nvim_set_current_win, win) end
  if not open_source(location.path) then
    vim.notify("clara-explorer: cannot open " .. tostring(location.path), vim.log.levels.WARN)
    return
  end
  local unqualified = unqualified_name(target and target.name)
  if location.line and line_holds_name(location.path, location.line, unqualified) then
    pcall(vim.api.nvim_win_set_cursor, 0, { location.line, 0 })
    return
  end
  vim.cmd.normal({ "gg", bang = true })
  if not unqualified then return end
  local found = vim.fn.search(M.fallback_regex(unqualified), "w")
  if not found or found == 0 then found = vim.fn.search(M.symbol_token_regex(unqualified), "w") end
  if not found or found == 0 then
    vim.notify(("clara-explorer: production %s not found in %s"):format(target.name, target.ns), vim.log.levels.WARN)
  end
end

--- Jump to `target` (decoded table with `name`, `ns`, `source.var?`, …).
-- Var-backed targets reuse Conjure's `def-str` when it is loadable; everything
-- else locates first and shows only on success.
function M.jump(target, opts)
  opts = opts or {}
  local source = target.source or {}
  if source["var?"] then
    local action = def_str_module()
    if action then
      M.push_jump()
      action["def-str"]({ code = target.name, context = opts.caller_ns })
      return
    end
  end
  M.locate(target, opts, function(location, reason)
    if not location then
      vim.notify(reason or "clara-explorer: cannot locate production", vim.log.levels.WARN)
      return
    end
    M.show(location, target)
  end)
end

return M
