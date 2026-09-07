--- Tier 1 (parser-gated): tree-sitter structural skeleton tests.  Skipped when
-- the clojure parser is not installed.

local structural = require("clara-explorer.structural")

local function buf_with(lines)
  local buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_set_current_buf(buf)
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
  return buf
end

--- 0-indexed (row, col) of the first occurrence of `needle` in `lines`.
local function find_pos(lines, needle)
  for r, line in ipairs(lines) do
    local s = line:find(needle, 1, true)
    if s then return r - 1, s - 1 end
  end
  error("needle not found: " .. needle)
end

if not structural.has_parser() then
  describe("structural", function()
    it(
      "clojure parser unavailable",
      function() pending("clojure tree-sitter parser not installed — skipping structural tests") end
    )
  end)
  return
end

describe("structural.enclosing_production", function()
  it("finds an aliased defrule and its kind", function()
    local lines = { "(r/defrule my-rule [A] => (println 1))" }
    local buf = buf_with(lines)
    local row, col = find_pos(lines, "my-rule")
    local enc = structural.enclosing_production(buf, row, col)
    assert.is_not_nil(enc)
    assert.are.same("my-rule", enc.name)
    assert.are.same("rule", enc.kind)
  end)

  it("is alias-agnostic", function()
    local buf = buf_with({ "(my.alias/defrule bar [Y] => 2)" })
    local row, col = find_pos({ "(my.alias/defrule bar [Y] => 2)" }, "bar")
    local enc = structural.enclosing_production(buf, row, col)
    assert.are.same("bar", enc.name)
    assert.are.same("rule", enc.kind)
  end)

  it("finds a defquery as kind query", function()
    local buf = buf_with({ "(r/defquery my-q [?x] [A])" })
    local row, col = find_pos({ "(r/defquery my-q [?x] [A])" }, "my-q")
    local enc = structural.enclosing_production(buf, row, col)
    assert.are.same("my-q", enc.name)
    assert.are.same("query", enc.kind)
  end)

  it("skips metadata on the name", function()
    local buf = buf_with({ "(r/defrule ^:my-meta my-rule [A] => 1)" })
    local row, col = find_pos({ "(r/defrule ^:my-meta my-rule [A] => 1)" }, "my-rule")
    local enc = structural.enclosing_production(buf, row, col)
    assert.are.same("my-rule", enc.name)
  end)

  it("returns nil outside a production", function()
    local buf = buf_with({ "(ns test)", "(defn helper [] 1)" })
    local row, col = find_pos({ "(defn helper [] 1)" }, "helper")
    assert.is_nil(structural.enclosing_production(buf, row, col))
  end)

  it("returns the form range", function()
    local buf = buf_with({ "(r/defrule my-rule [A] => 1)" })
    local row, col = find_pos({ "(r/defrule my-rule [A] => 1)" }, "my-rule")
    local enc = structural.enclosing_production(buf, row, col)
    assert.are.same({ 0, 0, 0, #"(r/defrule my-rule [A] => 1)" }, enc.range)
  end)
end)
