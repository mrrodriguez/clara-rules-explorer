--- Tree-sitter structural skeleton: find the enclosing `defrule`/`defquery`
-- production.  This is the only parser-dependent module; side detection and
-- token resolution live in `token.lua` (byte-based sexp-walker port).

local M = {}

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
        if head_name == "defrule" or head_name == "defquery" then
          local name_node = kids[2]
          local name = name_node and name_node:type() == "sym_lit" and sym_unqualified(name_node, bufnr)
          if name then
            local srow, scol, erow, ecol = cur:range()
            return {
              name = name,
              kind = head_name == "defrule" and "rule" or "query",
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
