--- Tier 1 (parser-gated): tree-sitter structural skeleton tests.  Skipped when
-- the clojure parser is not installed.

local structural = require("clara-explorer.structural")

local function buf_with(lines)
  local buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_set_current_buf(buf)
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
  return buf
end

--- Run FN with `g:clara_explorer_production_macros` set to MACROS, then restore.
local function with_macros(macros, fn)
  local orig = vim.g.clara_explorer_production_macros
  vim.g.clara_explorer_production_macros = macros
  local ok, err = pcall(fn)
  vim.g.clara_explorer_production_macros = orig
  assert(ok, err)
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

describe("structural production macros", function()
  it("C1 default list recognizes defrule/defquery (aliased too) and not g/defdata", function()
    with_macros(nil, function()
      local lines = { "(r/defrule my-rule [A] => 1)", "(defquery my-q [] [A])", "(g/defdata my-data [A] => 1)" }
      local buf = buf_with(lines)
      local row, col = find_pos(lines, "my-rule")
      assert.are.same("rule", structural.enclosing_production(buf, row, col).kind)
      row, col = find_pos(lines, "my-q")
      assert.are.same("query", structural.enclosing_production(buf, row, col).kind)
      row, col = find_pos(lines, "my-data")
      assert.is_nil(structural.enclosing_production(buf, row, col))
    end)
  end)

  it("C2 a custom list replaces the defaults", function()
    with_macros({ defdata = "rule", rule = "rule" }, function()
      local lines = { "(d/defdata my-data [A] => 1)", "(rule my-plain-rule [A] => 1)", "(r/defrule my-rule [A] => 1)" }
      local buf = buf_with(lines)
      local row, col = find_pos(lines, "my-data")
      assert.are.same("rule", structural.enclosing_production(buf, row, col).kind)
      row, col = find_pos(lines, "my-plain-rule")
      assert.are.same("rule", structural.enclosing_production(buf, row, col).kind)
      row, col = find_pos(lines, "my-rule")
      assert.is_nil(structural.enclosing_production(buf, row, col))
    end)
  end)

  it("C3 the kind comes from the list", function()
    with_macros({ defquery2 = "query" }, function()
      local lines = { "(r/defquery2 my-q [] [A])" }
      local buf = buf_with(lines)
      local row, col = find_pos(lines, "my-q")
      assert.are.same("query", structural.enclosing_production(buf, row, col).kind)
    end)
  end)

  it("C4 nested forms are still productions", function()
    with_macros(nil, function()
      local lines = { "(let [x 1] (r/defrule my-rule [A] => 1))", "(do (defquery my-q [] [A]))" }
      local buf = buf_with(lines)
      local row, col = find_pos(lines, "my-rule")
      assert.are.same("my-rule", structural.enclosing_production(buf, row, col).name)
      row, col = find_pos(lines, "my-q")
      assert.are.same("my-q", structural.enclosing_production(buf, row, col).name)
    end)
  end)

  it("C5 an empty list recognizes nothing", function()
    with_macros({}, function()
      local lines = { "(r/defrule my-rule [A] => 1)" }
      local buf = buf_with(lines)
      local row, col = find_pos(lines, "my-rule")
      assert.is_nil(structural.enclosing_production(buf, row, col))
      assert.are.same({}, structural.production_macros())
    end)
  end)
end)
