--- Clara fact-type token resolution — a self-contained port of the Emacs
-- `clara-explorer.el` §9.2 heuristics.  Engine-agnostic: works on a flat buffer
-- string with 0-indexed byte offsets, and depends on neither tree-sitter nor
-- `vim` (so `token_spec.lua` runs without the clojure parser).
--
-- The port mirrors the elisp `forward-sexp`/`down-list` logic with a small
-- byte-based sexp walker.  Symbol reading uses the same token character class
-- the elisp uses: `A-Za-z0-9._:/!?*+<>-`.

local M = {}

local TOKEN_CHARS = "[A-Za-z0-9._:/!?*+<>-]"

local function is_token_char(c)
  if c == "" then return false end
  return c:match(TOKEN_CHARS) ~= nil
end

-- 0-indexed byte offset helpers
local function byte_at(src, o) return src:sub(o + 1, o + 1) end

local function slice(src, a, b) return src:sub(a + 1, b) end

local function is_delim(c)
  if c == "" then return true end
  if c:match("[%s,]") then return true end
  return c == "("
    or c == ")"
    or c == "["
    or c == "]"
    or c == "{"
    or c == "}"
    or c == "'"
    or c == '"'
    or c == "`"
    or c == "~"
    or c == "@"
    or c == "^"
    or c == "#"
    or c == ";"
end

--- Skip whitespace, commas and `;` line comments.  `i` is a 0-indexed offset.
function M.skip_ws(src, i)
  local n = #src
  while i <= n do
    local c = byte_at(src, i)
    if c == ";" then
      local nl = src:find("\n", i + 1, true)
      if not nl then return n + 1 end
      i = nl -- 0-indexed offset of the char after the newline
    elseif c:match("[%s,]") then
      i = i + 1
    else
      break
    end
  end
  return i
end

local function skip_string(src, i)
  -- i is the 0-indexed offset of the opening `"`.
  -- Returns the 0-indexed offset just past the closing `"`, or nil.
  local j = i + 2
  while true do
    local c = src:sub(j, j)
    if c == "" then
      return nil
    elseif c == "\\" then
      j = j + 2
    elseif c == '"' then
      return j
    else
      j = j + 1
    end
  end
end

local function match_paren(src, i)
  -- i is the 0-indexed offset of an opening `(`, `[` or `{`.
  -- Returns the 0-indexed offset just past the matching close, or nil.
  local open = byte_at(src, i)
  local close = open == "(" and ")" or (open == "[" and "]" or "}")
  local depth = 0
  local n = #src
  local j = i
  while j <= n do
    local c = byte_at(src, j)
    if c == '"' then
      local e = skip_string(src, j)
      if not e then return nil end
      j = e
    elseif c == ";" then
      local nl = src:find("\n", j + 1, true)
      if not nl then return nil end
      j = nl
    elseif c == open then
      depth = depth + 1
      j = j + 1
    elseif c == close then
      depth = depth - 1
      j = j + 1
      if depth == 0 then return j end
    else
      j = j + 1
    end
  end
  return nil
end

local function skip_sexp_atom(src, i)
  local n = #src
  local j = i
  while j <= n and not is_delim(byte_at(src, j)) do
    j = j + 1
  end
  return j
end

local function forward_sexp(src, i)
  local n = #src
  if i > n then return nil end
  local c = byte_at(src, i)
  if c == "" then
    return nil
  elseif c == "(" or c == "[" or c == "{" then
    return match_paren(src, i)
  elseif c == '"' then
    return skip_string(src, i)
  elseif c == "^" or c == "'" or c == "`" or c == "~" or c == "@" or c == "#" then
    local j = M.skip_ws(src, i + 1)
    if j > n then return nil end
    return forward_sexp(src, j)
  else
    return skip_sexp_atom(src, i)
  end
end

--- Position of the top-level `=>` in the list at `form_start`, or nil.
function M.top_level_arrow(src, form_start)
  local form_end = match_paren(src, form_start)
  if not form_end then return nil end
  local i = form_start + 1
  while i < form_end do
    i = M.skip_ws(src, i)
    if i >= form_end then break end
    if slice(src, i, i + 2) == "=>" then return i end
    local j = forward_sexp(src, i)
    if not j or j <= i then break end
    i = j
  end
  return nil
end

local function skip_metadata(src, i)
  local n = #src
  while i <= n and byte_at(src, i) == "^" do
    i = M.skip_ws(src, i + 1)
    local j = forward_sexp(src, i)
    if not j or j <= i then return i end
    i = M.skip_ws(src, j)
  end
  return i
end

--- Position just after the production name at `form_start` (skips head,
-- metadata and name).
function M.after_head(src, form_start)
  local i = form_start + 1
  local j = forward_sexp(src, i) -- head
  if not j then return form_start + 1 end
  i = M.skip_ws(src, j)
  i = skip_metadata(src, i)
  local k = forward_sexp(src, i) -- name
  if not k then return i end
  return k
end

--- Docstring bounds `{beg, end_}` after the production name, or nil.
function M.docstring_bounds(src, form_start)
  local i = M.skip_ws(src, M.after_head(src, form_start))
  if byte_at(src, i) == '"' then
    local e = skip_string(src, i)
    if e then return { beg = i, end_ = e } end
  end
  return nil
end

--- Fact-type token when point is inside the production docstring.
function M.docstring_token_at_point(src, form_start, cursor)
  local bounds = M.docstring_bounds(src, form_start)
  if not bounds then return nil end
  local str_beg, str_end = bounds.beg, bounds.end_
  if not (cursor >= str_beg + 1 and cursor <= str_end) then return nil end
  local inner = slice(src, str_beg + 1, str_end - 1)
  local len = #inner
  local offset = cursor - str_beg - 1
  if
    offset > 0
    and offset <= len
    and (offset == len or not is_token_char(inner:sub(offset + 1, offset + 1)))
    and is_token_char(inner:sub(offset, offset))
  then
    offset = offset - 1
  end
  if offset >= 0 and offset < len and is_token_char(inner:sub(offset + 1, offset + 1)) then
    local start = offset
    local end_ = offset + 1
    while start > 0 and is_token_char(inner:sub(start, start)) do
      start = start - 1
    end
    while end_ < len and is_token_char(inner:sub(end_ + 1, end_ + 1)) do
      end_ = end_ + 1
    end
    local tok = inner:sub(start + 1, end_)
    if tok ~= "" and (tok:match("[:/.]") or tok:match("^[A-Z]") or tok:match("->")) then return tok end
  end
  return nil
end

local function skip_fact_binding(src, i)
  if byte_at(src, i) == "?" then
    local j = forward_sexp(src, i)
    if j then
      j = M.skip_ws(src, j)
      if slice(src, j, j + 2) == "<-" then return M.skip_ws(src, j + 2) end
    end
  end
  return i
end

--- Bounds of the first element of an accumulator's `:from [TYPE …]`, or nil.
function M.accumulator_type_bounds(src, i)
  local j = forward_sexp(src, i)
  if not j or j <= i then return nil end
  j = M.skip_ws(src, j)
  if slice(src, j, j + 5) == ":from" then
    j = forward_sexp(src, j)
    if not j then return nil end
    j = M.skip_ws(src, j)
    if byte_at(src, j) == "[" then
      local type_beg = M.skip_ws(src, j + 1)
      local type_end = forward_sexp(src, type_beg)
      if type_end then return { beg = type_beg, end_ = type_end } end
    end
  end
  return nil
end

local LOGICAL = { [":and"] = true, [":or"] = true, [":not"] = true, [":exists"] = true }

function M.logical_operator_p(kw) return LOGICAL[kw] == true end

local function wrapper_inner_type_bounds(src, i, orig, wrapper_end)
  while i < wrapper_end do
    i = M.skip_ws(src, i)
    if i >= wrapper_end then break end
    local c = byte_at(src, i)
    if c == "[" then
      local inner_beg = i
      local inner_end = match_paren(src, i)
      if inner_end and orig >= inner_beg and orig <= inner_end then
        return M.type_bounds_in_condition_at_point(src, inner_beg, orig)
      else
        i = inner_end or wrapper_end
      end
    elseif c == "(" then
      i = match_paren(src, i) or wrapper_end
    else
      i = forward_sexp(src, i) or wrapper_end
    end
  end
  return nil
end

--- Fact-type bounds for the condition vector at `beg`, recursing into logical
-- wrappers to the inner condition containing `orig`.
function M.type_bounds_in_condition_at_point(src, beg, orig)
  local i = M.skip_ws(src, M.skip_ws(src, beg + 1))
  i = skip_fact_binding(src, i)
  local acc = M.accumulator_type_bounds(src, i)
  if acc then return acc end
  local c = byte_at(src, i)
  local c2 = byte_at(src, i + 1)
  if c == ":" and c2:match("[a-z]") then
    local kw_start = i
    local kw_end = forward_sexp(src, i)
    if not kw_end then return nil end
    local kw = slice(src, kw_start, kw_end)
    if M.logical_operator_p(kw) then
      local wrapper_end = match_paren(src, beg)
      if not wrapper_end then return nil end
      return wrapper_inner_type_bounds(src, kw_end, orig, wrapper_end)
    end
    return { beg = kw_start, end_ = kw_end }
  end
  local type_end = forward_sexp(src, i)
  if not type_end then return nil end
  return { beg = i, end_ = type_end }
end

--- Non-orig-aware variant (logical wrappers return nil) — used directly by tests.
function M.type_bounds_in_condition(src, beg)
  local i = M.skip_ws(src, beg + 1)
  i = skip_fact_binding(src, i)
  local acc = M.accumulator_type_bounds(src, i)
  if acc then return acc end
  local c = byte_at(src, i)
  local c2 = byte_at(src, i + 1)
  if c == ":" and c2:match("[a-z]") then
    local kw_start = i
    local kw_end = forward_sexp(src, i)
    if not kw_end then return nil end
    local kw = slice(src, kw_start, kw_end)
    if M.logical_operator_p(kw) then return nil end
    return { beg = kw_start, end_ = kw_end }
  end
  local type_end = forward_sexp(src, i)
  if not type_end then return nil end
  return { beg = i, end_ = type_end }
end

--- LHS fact type at point: the fact type of the condition containing the cursor.
function M.lhs_type_at_point(src, form_start, cursor)
  local form_end = match_paren(src, form_start)
  if not form_end then return nil end
  local lhs_end = M.top_level_arrow(src, form_start) or form_end
  local i = M.after_head(src, form_start)
  i = M.skip_ws(src, i)
  if byte_at(src, i) == '"' then
    local e = skip_string(src, i)
    if not e then return nil end
    i = M.skip_ws(src, e)
  end
  if byte_at(src, i) == "{" then
    local e = match_paren(src, i)
    if not e then return nil end
    i = M.skip_ws(src, e)
  end
  i = M.skip_ws(src, i)
  while i < lhs_end do
    i = M.skip_ws(src, i)
    if i >= lhs_end then break end
    local cond_beg = i
    local cond_end = forward_sexp(src, i)
    if not cond_end then break end
    if byte_at(src, cond_beg) == "[" and cursor >= cond_beg and cursor <= cond_end then
      local bounds = M.type_bounds_in_condition_at_point(src, cond_beg, cursor)
      if bounds then return slice(src, bounds.beg, bounds.end_) end
    end
    i = cond_end
  end
  return nil
end

local function is_props_key(k)
  local name = k:gsub("^:+", "")
  return name == "clara-rules/insert-types"
    or name == "clara-rules/retract-types"
    or name == "insert-types"
    or name == "retract-types"
end

--- Element at point when the cursor is inside an attr-map insert/retract-types
-- vector, else nil.
function M.props_type_at_point(src, form_start, cursor)
  local i = M.skip_ws(src, M.after_head(src, form_start))
  if byte_at(src, i) == '"' then
    local e = skip_string(src, i)
    if not e then return nil end
    i = M.skip_ws(src, e)
  end
  if byte_at(src, i) ~= "{" then return nil end
  local map_beg = i
  local map_end = match_paren(src, i)
  if not map_end or not (cursor >= map_beg and cursor <= map_end) then return nil end
  local j = map_beg + 1
  while j < map_end do
    j = M.skip_ws(src, j)
    if j >= map_end then break end
    local k_beg = j
    local k_end = forward_sexp(src, j)
    if not k_end then break end
    local k_str = slice(src, k_beg, k_end)
    j = M.skip_ws(src, k_end)
    local v_beg = j
    local v_end = forward_sexp(src, j)
    if not v_end then break end
    if is_props_key(k_str) and byte_at(src, v_beg) == "[" and cursor >= v_beg and cursor <= v_end then
      local e = v_beg + 1
      while e < v_end do
        e = M.skip_ws(src, e)
        if e >= v_end then break end
        local e_beg = e
        local e_end = forward_sexp(src, e)
        if not e_end then break end
        if cursor >= e_beg and cursor <= e_end then return slice(src, e_beg, e_end) end
        e = e_end
      end
    end
    j = v_end
  end
  return nil
end

--- Innermost open delimiter (`(`, `[` or `{`) enclosing `probe`, or nil.
local function innermost_open_delim(src, probe)
  local stack = {}
  local i = 0
  local n = #src
  local found
  while i < n do
    if found == nil and i >= probe then found = stack[#stack] end
    local c = byte_at(src, i)
    if c == '"' then
      local e = skip_string(src, i)
      if not e then break end
      i = e
    elseif c == ";" then
      local nl = src:find("\n", i + 1, true)
      if not nl then break end
      i = nl
    elseif c == "(" or c == "[" or c == "{" then
      stack[#stack + 1] = { char = c, pos = i }
      i = i + 1
    elseif c == ")" or c == "]" or c == "}" then
      while #stack > 0 do
        local top = stack[#stack]
        stack[#stack] = nil
        local matches = (top.char == "(" and c == ")")
          or (top.char == "[" and c == "]")
          or (top.char == "{" and c == "}")
        if matches then break end
      end
      i = i + 1
    else
      i = i + 1
    end
  end
  return found
end

local function string_span_at(src, cursor)
  local i = 0
  local n = #src
  while i < n do
    local c = byte_at(src, i)
    if c == '"' then
      local e = skip_string(src, i)
      if not e then return nil end
      if cursor > i and cursor < e then return { beg = i, end_ = e } end
      i = e
    elseif c == ";" then
      local nl = src:find("\n", i + 1, true)
      if not nl then return nil end
      i = nl
    else
      i = i + 1
    end
  end
  return nil
end

--- String literal at point (the whole `"…"` text), or nil.
function M.string_at_point(src, cursor)
  local span = string_span_at(src, cursor)
  if span then return slice(src, span.beg, span.end_) end
  if byte_at(src, cursor) == '"' then
    local e = skip_string(src, cursor)
    if e then return slice(src, cursor, e) end
  end
  return nil
end

local function is_logical_wrapper_vector(txt)
  local head = txt:match("^%[%s*:(%a+)")
  if not head then return false end
  for _, op in ipairs({ "and", "or", "not", "exists" }) do
    if head:sub(1, #op) == op then
      local nextc = head:sub(#op + 1, #op + 1)
      if nextc == "" or not nextc:match("[%w_]") then return true end
    end
  end
  return false
end

--- Innermost keyword-led tuple vector at point (e.g. `[:loan/status "verified"]`),
-- or nil.  Rejects logical wrappers, `=` and forbidden `()?` chars.
function M.vector_fact_at_point(src, cursor)
  local span = string_span_at(src, cursor)
  local probe = span and span.beg or cursor
  local d = innermost_open_delim(src, probe)
  if d and d.char == "[" then
    local e = match_paren(src, d.pos)
    if e and cursor >= d.pos and cursor <= e then
      local txt = slice(src, d.pos, e)
      if
        txt:match("^%[%s*:")
        and not is_logical_wrapper_vector(txt)
        and not txt:match("[()%?]")
        and not txt:match("=")
      then
        return txt
      end
    end
  end
  return nil
end

--- Symbol at point using the token character class, with a one-char look-back.
function M.symbol_at_point(src, cursor)
  local n = #src
  if n == 0 then return nil end
  local after = byte_at(src, cursor)
  local before = byte_at(src, cursor - 1)
  local idx
  if is_token_char(after) then
    idx = cursor
  elseif is_token_char(before) then
    idx = cursor - 1
  else
    return nil
  end
  local start = idx
  while start > 0 and is_token_char(byte_at(src, start - 1)) do
    start = start - 1
  end
  local end_ = idx + 1
  while end_ < n and is_token_char(byte_at(src, end_)) do
    end_ = end_ + 1
  end
  return slice(src, start, end_)
end

--- LHS vs RHS at the cursor within the production form at `form_start`.
-- Props vectors are `:rhs`; otherwise compare the cursor to the top-level `=>`.
function M.side_at_point(src, form_start, cursor)
  if M.props_type_at_point(src, form_start, cursor) ~= nil then return "rhs" end
  local arrow = M.top_level_arrow(src, form_start)
  if arrow then return cursor < arrow and "lhs" or "rhs" end
  return "lhs"
end

--- The fact-type token at point (the §4.4 routing order).
function M.token_at_point(src, form_start, side, cursor)
  if form_start then
    local doc = M.docstring_token_at_point(src, form_start, cursor)
    if doc then return doc end
    local props = M.props_type_at_point(src, form_start, cursor)
    if props then return props end
    if side == "lhs" then
      local lhs = M.lhs_type_at_point(src, form_start, cursor)
      if lhs then return lhs end
    end
  end
  local vec = M.vector_fact_at_point(src, cursor)
  if vec then return vec end
  local str = M.string_at_point(src, cursor)
  if str then return str end
  return M.symbol_at_point(src, cursor)
end

return M
