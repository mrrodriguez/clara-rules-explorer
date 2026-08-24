--- Self-contained EDN subset reader for the closed `NavigateResponse` grammar.
-- Dependency-free (Lua standard library only) and the *only* module that knows
-- the wire format.  Every other module consumes plain Lua tables.
--
-- Grammar (§5.1 of the plan):
--   maps `{…}`, vectors `[…]`, keywords `:kw`, strings `"…"` (Clojure escapes),
--   integers (incl. negative), `true`, `false`, `nil`; commas are whitespace.
--
-- Mapping to Lua:
--   keyword map keys  -> string keys  (`:direction` -> key `"direction"`)
--   keyword values    -> strings      (`:retract`   -> `"retract"`)
--   `nil`             -> `nil` (absent map key — semantically correct here)
--   vectors           -> array tables (1-indexed)
--
-- Anything outside this grammar (symbols, sets, ratios, tagged literals,
-- floats, bigints) fails loudly rather than guessing.

local M = {}

local function is_ws(c)
  if c == "" then return false end
  return c:match("[%s,]") ~= nil
end

local function skip_ws(s, i)
  while is_ws(s:sub(i, i)) do
    i = i + 1
  end
  return i
end

local function utf8_char(cp)
  if cp < 0x80 then
    return string.char(cp)
  elseif cp < 0x800 then
    return string.char(0xC0 + math.floor(cp / 0x40), 0x80 + (cp % 0x40))
  elseif cp < 0x10000 then
    return string.char(0xE0 + math.floor(cp / 0x1000), 0x80 + (math.floor(cp / 0x40) % 0x40), 0x80 + (cp % 0x40))
  else
    return string.char(
      0xF0 + math.floor(cp / 0x40000),
      0x80 + (math.floor(cp / 0x1000) % 0x40),
      0x80 + (math.floor(cp / 0x40) % 0x40),
      0x80 + (cp % 0x40)
    )
  end
end

local function parse_string(s, i)
  -- i is the Lua index of the opening `"`.
  local parts = {}
  local j = i + 1
  while true do
    local c = s:sub(j, j)
    if c == "" then
      return nil, i, "unterminated string"
    elseif c == '"' then
      return table.concat(parts), j + 1
    elseif c == "\\" then
      local e = s:sub(j + 1, j + 1)
      if e == '"' then
        parts[#parts + 1] = '"'
        j = j + 2
      elseif e == "\\" then
        parts[#parts + 1] = "\\"
        j = j + 2
      elseif e == "n" then
        parts[#parts + 1] = "\n"
        j = j + 2
      elseif e == "t" then
        parts[#parts + 1] = "\t"
        j = j + 2
      elseif e == "r" then
        parts[#parts + 1] = "\r"
        j = j + 2
      elseif e == "b" then
        parts[#parts + 1] = "\b"
        j = j + 2
      elseif e == "f" then
        parts[#parts + 1] = "\f"
        j = j + 2
      elseif e == "u" then
        local hex = s:sub(j + 2, j + 5)
        if not hex:match("^%x%x%x%x$") then return nil, j, "invalid \\u escape" end
        parts[#parts + 1] = utf8_char(tonumber(hex, 16))
        j = j + 6
      else
        return nil, j, ("unsupported escape \\%s"):format(e)
      end
    else
      parts[#parts + 1] = c
      j = j + 1
    end
  end
end

local function parse_keyword(s, i)
  -- i is the Lua index of the leading `:`.
  local j = i + 1
  while s:sub(j, j) == ":" do
    j = j + 1
  end
  local start = j
  while true do
    local c = s:sub(j, j)
    if c == "" or c:match('[%s,{}%[%]()";]') then break end
    j = j + 1
  end
  if j == start then return nil, i, "empty keyword" end
  return s:sub(start, j - 1), j
end

local function parse_int(s, i)
  local j = i
  if s:sub(j, j) == "-" then j = j + 1 end
  local digits = s:match("^%d+", j)
  if not digits then return nil, i, "invalid number" end
  j = j + #digits
  local c = s:sub(j, j)
  if c == "." or c == "N" or c == "M" or c == "/" or c:match("[eE]") then
    return nil, i, "unsupported numeric literal (only integers)"
  end
  return tonumber(s:sub(i, j - 1)), j
end

local function parse_literal(s, i)
  if s:sub(i, i + 3) == "true" then
    return true, i + 4
  elseif s:sub(i, i + 4) == "false" then
    return false, i + 5
  elseif s:sub(i, i + 2) == "nil" then
    return nil, i + 3
  end
  return nil, i, "unknown literal"
end

local parse_value -- forward declaration

local function parse_map(s, i)
  local result = {}
  i = skip_ws(s, i + 1)
  if s:sub(i, i) == "}" then return result, i + 1 end
  while true do
    i = skip_ws(s, i)
    local key, ki, kerr = parse_value(s, i)
    if kerr then return nil, i, kerr end
    if type(key) ~= "string" then return nil, i, ("map key must be a keyword or string, got %s"):format(type(key)) end
    i = skip_ws(s, ki)
    local val, vi, verr = parse_value(s, i)
    if verr then return nil, i, verr end
    if val ~= nil then result[key] = val end
    i = skip_ws(s, vi)
    local c = s:sub(i, i)
    if c == "}" then
      return result, i + 1
    elseif c == "" then
      return nil, i, "unterminated map"
    end
    -- otherwise a comma/whitespace-separated next pair
  end
end

local function parse_vec(s, i)
  local result = {}
  i = skip_ws(s, i + 1)
  if s:sub(i, i) == "]" then return result, i + 1 end
  local n = 0
  while true do
    i = skip_ws(s, i)
    local val, vi, verr = parse_value(s, i)
    if verr then return nil, i, verr end
    n = n + 1
    result[n] = val
    i = skip_ws(s, vi)
    local c = s:sub(i, i)
    if c == "]" then
      return result, i + 1
    elseif c == "" then
      return nil, i, "unterminated vector"
    end
  end
end

parse_value = function(s, i)
  i = skip_ws(s, i)
  local c = s:sub(i, i)
  if c == "" then
    return nil, i, "unexpected end of input"
  elseif c == "{" then
    return parse_map(s, i)
  elseif c == "[" then
    return parse_vec(s, i)
  elseif c == '"' then
    return parse_string(s, i)
  elseif c == ":" then
    return parse_keyword(s, i)
  elseif c == "t" or c == "f" or c == "n" then
    return parse_literal(s, i)
  elseif c == "-" or c:match("%d") then
    return parse_int(s, i)
  else
    return nil, i, ("unexpected character %q"):format(c)
  end
end

function M.decode(s)
  if type(s) ~= "string" then return nil, "edn: input must be a string" end
  local v, i, e = parse_value(s, 1)
  if e then return nil, e end
  i = skip_ws(s, i)
  if s:sub(i, i) ~= "" then return nil, ("edn: trailing characters at byte %d"):format(i) end
  return v
end

return M
