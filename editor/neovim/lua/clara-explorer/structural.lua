--- Tree-sitter structural skeleton: find the enclosing production.  This is the
-- only parser-dependent module; side detection and token resolution live in
-- `token.lua` (byte-based sexp-walker port).  It also owns the production-macro
-- table, since recognizing a production head is the same question here and in
-- `jump.lua`'s definition-site search.

local M = {}

--- The built-in production macros: unqualified macro name -> `"rule"` | `"query"`.
local DEFAULT_PRODUCTION_MACROS = { defrule = "rule", defquery = "query" }

--- The configured production macros: unqualified name -> `"rule"` | `"query"`.
-- `g:clara_explorer_production_macros` replaces the defaults entirely, so a
-- project whose rules are built by `defdata`/`rule` sets its own list.  The
-- variable is read per call, so a change applies without reloading the plugin.
-- An entry whose value is neither kind is dropped rather than guessed: the kind
-- decides the "queries have no RHS" guard, so a wrong answer would refuse a
-- valid navigation.  Entries are documented as macros whose first argument
-- (after any metadata) is the production name.
function M.production_macros()
  local configured = vim.g.clara_explorer_production_macros
  if configured == nil then return DEFAULT_PRODUCTION_MACROS end
  local macros = {}
  for name, kind in pairs(configured) do
    if type(name) == "string" and (kind == "rule" or kind == "query") then macros[name] = kind end
  end
  return macros
end

--- The production kind for head symbol HEAD, or nil when HEAD is not a
-- production macro.
function M.production_kind(head) return M.production_macros()[head] end

--- True when the clojure parser is loadable (Neovim 0.10 `has_parser` API,
-- falling back to the 0.12 `vim.treesitter.language.get_lang` API).
function M.has_parser()
  if vim.treesitter.has_parser then
    local ok, res = pcall(vim.treesitter.has_parser, "clojure")
    return ok and res == true
  end
  if vim.treesitter.language and vim.treesitter.language.get_lang then
    local ok, res = pcall(vim.treesitter.language.get_lang, "clojure")
    return ok and res ~= nil
  end
  return false
end

local function named_children(node)
  local out = {}
  for child in node:iter_children() do
    if child:named() and child:type() ~= "comment" then out[#out + 1] = child end
  end
  return out
end

--- Unqualified name of a `sym_lit` node (trailing `sym_name` child).
local function sym_unqualified(node, bufnr)
  for child in node:iter_children() do
    if child:named() and child:type() == "sym_name" then return vim.treesitter.get_node_text(child, bufnr) end
  end
  -- Fallback: strip leading metadata and take the last whitespace token.
  local text = vim.treesitter.get_node_text(node, bufnr)
  return text:match("([^%s]+)$") or text
end

--- Enclosing production at (row, col): `{name, kind, range}` or nil.
-- `kind` is `"rule"` or `"query"`; `range` is `{srow, scol, erow, ecol}`
-- (0-indexed, end-exclusive).
function M.enclosing_production(bufnr, row, col)
  if not M.has_parser() then return nil end
  local ok, parser = pcall(vim.treesitter.get_parser, bufnr, "clojure", {})
  if not ok then return nil end
  local tree = parser:parse()[1]
  local root = tree:root()
  local node = root:descendant_for_range(row, col, row, col)
  while node and not node:named() do
    node = node:parent()
  end
  local cur = node
  while cur do
    if cur:type() == "list_lit" then
      local kids = named_children(cur)
      local head = kids[1]
      if head and head:type() == "sym_lit" then
        local head_name = sym_unqualified(head, bufnr)
        local kind = M.production_kind(head_name)
        if kind then
          local name_node = kids[2]
          local name = name_node and name_node:type() == "sym_lit" and sym_unqualified(name_node, bufnr)
          if name then
            local srow, scol, erow, ecol = cur:range()
            return {
              name = name,
              kind = kind,
              range = { srow, scol, erow, ecol },
            }
          end
        end
      end
    end
    cur = cur:parent()
  end
  return nil
end

return M
