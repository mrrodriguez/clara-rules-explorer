--- Tier 1: pure-Lua token resolution tests — port of the Emacs corpus
-- (`editor/emacs/test/clara-explorer-test.el`).  No parser, no JVM, no vim.

local token = require("clara-explorer.token")

--- 0-indexed byte offset of the first occurrence of `needle`.
local function first(src, needle)
  local s = src:find(needle, 1, true)
  assert(s, "needle not found: " .. needle)
  return s - 1
end

--- 0-indexed byte offset just after the first occurrence of `needle`.
local function after(src, needle) return first(src, needle) + #needle end

--- Cursor "just after `needle`", then resolve the token like `M-x navigate`.
local function search_token(src, form_open, needle)
  local fs = first(src, form_open)
  local cursor = after(src, needle)
  local side = token.side_at_point(src, fs, cursor)
  return token.token_at_point(src, fs, side, cursor)
end

local function bounds_text(src, bounds) return src:sub(bounds.beg + 1, bounds.end_) end

describe("symbol_at_point", function()
  it("reads map->Foo, Foo., ::kw and alias/x as one token", function()
    local src = "(map->ApplicationOutcome {:a 1})"
    assert.are.same("map->ApplicationOutcome", token.symbol_at_point(src, after(src, "map->ApplicationOutcome")))
    local src2 = "DocumentCheck."
    assert.are.same("DocumentCheck.", token.symbol_at_point(src2, after(src2, "DocumentCheck.")))
    local src3 = "::supporting-document"
    assert.are.same("::supporting-document", token.symbol_at_point(src3, after(src3, "supporting-document")))
    local src4 = "laf/map->DocumentCheck"
    assert.are.same("laf/map->DocumentCheck", token.symbol_at_point(src4, after(src4, "DocumentCheck")))
  end)

  it("looks back one char when the cursor is past the token", function()
    local src = "[Application (= ?x 1)]"
    assert.are.same("Application", token.symbol_at_point(src, after(src, "Application")))
  end)

  it("returns nil on whitespace", function()
    local src = "   "
    assert.is_nil(token.symbol_at_point(src, 1))
  end)
end)

describe("skip_ws", function()
  it("skips spaces, tabs, newlines, commas and line comments", function()
    assert.are.same(#"  \t\r\n,,  \n", token.skip_ws("  \t\r\n,,  \nnext", 0))
    assert.are.same(#"; comment\n", token.skip_ws("; comment\nnext", 0))
    assert.are.same(#" , ; c1\n ; c2\n ", token.skip_ws(" , ; c1\n ; c2\n next", 0))
  end)
end)

describe("top_level_arrow", function()
  it("finds the top-level => and skips nested forms", function()
    local src = "(r/defrule foo [A (= {:x 1})] => (println 1))"
    local pos = token.top_level_arrow(src, first(src, "(r/defrule foo"))
    assert.is_not_nil(pos)
    assert.are.same("=>", src:sub(pos + 1, pos + 2))
  end)

  it("returns nil for queries (no =>)", function()
    local src = "(r/defquery foo [] [A])"
    assert.is_nil(token.top_level_arrow(src, first(src, "(r/defquery foo")))
  end)
end)

describe("side_at_point", function()
  it("LHS before =>, RHS after", function()
    local src = "(r/defrule foo [A] => (r/insert! B))"
    local fs = first(src, "(r/defrule foo")
    assert.are.same("lhs", token.side_at_point(src, fs, after(src, "[A]")))
    assert.are.same("rhs", token.side_at_point(src, fs, after(src, "B")))
  end)

  it("query is always lhs", function()
    local src = "(r/defquery foo [] [A] [B])"
    local fs = first(src, "(r/defquery foo")
    assert.are.same("lhs", token.side_at_point(src, fs, after(src, "[B]")))
  end)

  it("props vectors are rhs", function()
    local src = "(r/defrule foo {:clara-rules/insert-types [::a ::b]} [?x <- ::a] => 1)"
    local fs = first(src, "(r/defrule foo")
    assert.are.same("rhs", token.side_at_point(src, fs, after(src, "::b")))
  end)
end)

describe("type_bounds_in_condition", function()
  it("plain fact type", function()
    local src = "(r/defrule foo [Application (= ?x 1)] => 1)"
    local beg = first(src, "[Application")
    assert.are.same("Application", bounds_text(src, token.type_bounds_in_condition(src, beg)))
  end)

  it("with fact binding", function()
    local src = "(r/defrule foo [?d <- ::supporting-document] => 1)"
    local beg = first(src, "[?d")
    assert.are.same("::supporting-document", bounds_text(src, token.type_bounds_in_condition(src, beg)))
  end)

  it("accumulator :from type", function()
    local src = "(r/defrule foo [?acc <- (acc/all) :from [:my-thing [this] (= ?x 1)]] => 1)"
    local beg = first(src, "[?acc")
    assert.are.same(":my-thing", bounds_text(src, token.type_bounds_in_condition(src, beg)))
  end)

  it("accumulator vector type", function()
    local src = "(r/defrule foo [?acc <- (acc/all) :from [[:my-thing]]] => 1)"
    local beg = first(src, "[?acc")
    assert.are.same("[:my-thing]", bounds_text(src, token.type_bounds_in_condition(src, beg)))
  end)

  it("parameterized accumulator call", function()
    local src = "(r/defrule foo [?acc <- (my-sort-by-acc :x) :from [:my-thing [this] (= ?x 1)]] => 1)"
    local beg = first(src, "[?acc")
    assert.are.same(":my-thing", bounds_text(src, token.type_bounds_in_condition(src, beg)))
  end)

  it("bare-symbol accumulator", function()
    local src = "(r/defrule foo [?acc <- my-shared-accum :from [:my-thing [this] (= ?x 1)]] => 1)"
    local beg = first(src, "[?acc")
    assert.are.same(":my-thing", bounds_text(src, token.type_bounds_in_condition(src, beg)))
  end)

  it("plain type not misidentified as accumulator", function()
    local src = "(r/defrule foo [Application (= ?x 1)] => 1)"
    local beg = first(src, "[Application")
    assert.are.same("Application", bounds_text(src, token.type_bounds_in_condition(src, beg)))
  end)

  it("boolean group returns nil", function()
    local src = "(r/defrule foo [:and [A] [B]] => 1)"
    local beg = first(src, "[:and")
    assert.is_nil(token.type_bounds_in_condition(src, beg))
  end)
end)

describe("lhs_type_at_point (logical wrappers)", function()
  it(
    ":not wrapper keyword",
    function()
      assert.are.same(
        ":my-ns/my-type",
        search_token("(ns test) (r/defrule x-rule [:not [:my-ns/my-type]] => 1)", "(r/defrule x-rule", ":my-ns/my-type")
      )
    end
  )

  it(
    ":exists wrapper",
    function()
      assert.are.same(
        ":my-type",
        search_token("(ns test) (r/defrule x-rule [:exists [:my-type]] => 1)", "(r/defrule x-rule", ":my-type")
      )
    end
  )

  it(
    ":not wrapper with constraints",
    function()
      assert.are.same(
        "MyType",
        search_token("(ns test) (r/defrule x-rule [:not [MyType (= ?x 1)]] => 1)", "(r/defrule x-rule", "MyType")
      )
    end
  )

  it(":and containing :not and :exists", function()
    local src = "(ns test) (r/defrule x-rule [:and [:not [:my-ns/my-type]] [:exists [:my-type]]] => 1)"
    assert.are.same(":my-ns/my-type", search_token(src, "(r/defrule x-rule", ":my-ns/my-type"))
    assert.are.same(":my-type", search_token(src, "(r/defrule x-rule", ":my-type"))
  end)

  it("nested :not around :and", function()
    local src = "(ns test) (r/defrule x-rule [:not [:and [P] [Q]]] => 1)"
    assert.are.same("P", search_token(src, "(r/defrule x-rule", "[P]"))
    assert.are.same("Q", search_token(src, "(r/defrule x-rule", "[Q]"))
  end)

  it(":not outside a binding", function()
    local src = "(ns test) (r/defrule x-rule [?a <- MyType] [:not [OtherType]] => 1)"
    assert.are.same("OtherType", search_token(src, "(r/defrule x-rule", "OtherType"))
  end)
end)

describe("lhs_type_at_point (accumulator cases)", function()
  it("cursor on the :from type", function()
    local src =
      "(ns test) (r/defrule foo\n  [?acc <- (acc/all) :from [:my-thing [this] (= ?x (:x this))]]\n  =>\n  (println 1))"
    assert.are.same(":my-thing", search_token(src, "(r/defrule foo", ":my-thing"))
  end)

  it("cursor on a constraint still resolves the :from type", function()
    local src =
      "(ns test) (r/defrule foo\n  [?acc <- (acc/all) :from [:my-thing [this] (= ?x (:x this))]]\n  =>\n  (println 1))"
    assert.are.same(":my-thing", search_token(src, "(r/defrule foo", "[this]"))
  end)

  it("vector type", function()
    local src = "(ns test) (r/defrule foo\n  [?acc <- (acc/all) :from [[:my-thing]]]\n  =>\n  (println 1))"
    assert.are.same("[:my-thing]", search_token(src, "(r/defrule foo", ":my-thing"))
  end)

  it("qualified vector type", function()
    local src = "(ns test) (r/defrule foo\n  [?acc <- (acc/all) :from [[:my-thing :qualifier]]]\n  =>\n  (println 1))"
    assert.are.same("[:my-thing :qualifier]", search_token(src, "(r/defrule foo", ":my-thing"))
  end)

  it("bare-symbol accumulator resolves from cursor on the symbol", function()
    local src = "(ns test) (r/defrule foo\n  [?acc <- my-shared-accum :from [:my-thing]]\n  =>\n  (println 1))"
    assert.are.same(":my-thing", search_token(src, "(r/defrule foo", "my-shared-accum"))
  end)
end)

describe("lhs_type_at_point (plain, tuple, string, keyword)", function()
  it(
    "plain class",
    function()
      assert.are.same(
        "Application",
        search_token(
          "(ns test) (r/defrule foo\n  [Application (= ?x 1)]\n  =>\n  (println 1))",
          "(r/defrule foo",
          "Application"
        )
      )
    end
  )

  it("tuple vector", function()
    local src = '(ns test) (r/defrule foo\n  [?s <- [:loan/status "verified"]]\n  =>\n  (println 1))'
    assert.are.same('[:loan/status "verified"]', search_token(src, "(r/defrule foo", ":loan/status"))
    assert.are.same('[:loan/status "verified"]', search_token(src, "(r/defrule foo", "verified"))
  end)

  it("string fact", function()
    local src = '(ns test) (r/defrule foo\n  [?x <- "my-string"]\n  =>\n  (println 1))'
    assert.are.same('"my-string"', search_token(src, "(r/defrule foo", "my-string"))
  end)

  it("::keyword binding", function()
    local src = "(ns test) (r/defrule foo\n  [?d <- ::supporting-document]\n  =>\n  (println 1))"
    assert.are.same("::supporting-document", search_token(src, "(r/defrule foo", "supporting-document"))
  end)

  it("with docstring", function()
    local src = '(ns test) (r/defrule foo "docstring"\n  [?acc <- (acc/all) :from [:my-thing]]\n  =>\n  (println 1))'
    assert.are.same(":my-thing", search_token(src, "(r/defrule foo", ":my-thing"))
  end)
end)

describe("props_type_at_point", function()
  it("insert-types element", function()
    local src = "(r/defrule foo {:clara-rules/insert-types [::a ::b]} [?x <- ::a] => 1)"
    local fs = first(src, "(r/defrule foo")
    assert.are.same("::b", token.token_at_point(src, fs, "rhs", after(src, "::b")))
  end)

  it("retract-types element", function()
    local src = "(r/defrule foo {:clara-rules/retract-types [::c]} [?x <- ::c] => 1)"
    local fs = first(src, "(r/defrule foo")
    assert.are.same("::c", token.token_at_point(src, fs, "rhs", after(src, "::c")))
  end)

  it("vector-type props element", function()
    local src = "(r/defrule foo {:clara-rules/insert-types [[:my-thing :qual]]} [?x <- :a] => 1)"
    local fs = first(src, "(r/defrule foo")
    assert.are.same("[:my-thing :qual]", token.token_at_point(src, fs, "rhs", after(src, ":my-thing")))
  end)
end)

describe("docstring_token_at_point", function()
  it("plain keyword", function()
    local src = '(r/defrule my-rule "inserts a :my-type when something" [Application] => 1)'
    local fs = first(src, "(r/defrule my-rule")
    assert.are.same(":my-type", token.docstring_token_at_point(src, fs, after(src, ":my-type")))
  end)

  it("::keyword", function()
    local src = '(r/defrule my-rule "uses ::supporting-document" [Application] => 1)'
    local fs = first(src, "(r/defrule my-rule")
    assert.are.same("::supporting-document", token.docstring_token_at_point(src, fs, after(src, "supporting-document")))
  end)

  it("qualified class", function()
    local src = '(r/defrule my-rule "see clojure.lang.PersistentVector" [Application] => 1)'
    local fs = first(src, "(r/defrule my-rule")
    assert.are.same(
      "clojure.lang.PersistentVector",
      token.docstring_token_at_point(src, fs, after(src, "PersistentVector"))
    )
  end)

  it("returns nil for a plain word", function()
    local src = '(r/defrule my-rule "hello world" [Application] => 1)'
    local fs = first(src, "(r/defrule my-rule")
    assert.is_nil(token.docstring_token_at_point(src, fs, after(src, "world")))
  end)

  it("resolves the token at the end of the docstring", function()
    local src = '(r/defrule my-rule "inserts a :my-type" [Application] => 1)'
    local fs = first(src, "(r/defrule my-rule")
    assert.are.same(":my-type", token.docstring_token_at_point(src, fs, after(src, ":my-type")))
  end)
end)

describe("vector_fact_at_point", function()
  it("tuple vector from the keyword or the value", function()
    local src = '(r/defrule foo [?s <- [:loan/status "verified"]] => 1)'
    assert.are.same('[:loan/status "verified"]', token.vector_fact_at_point(src, after(src, ":loan/status")))
    assert.are.same('[:loan/status "verified"]', token.vector_fact_at_point(src, after(src, "verified")))
  end)

  it("rejects plain condition vectors", function()
    local src = "(r/defrule foo [Application (= ?x 1)] => 1)"
    assert.is_nil(token.vector_fact_at_point(src, after(src, "Application")))
  end)

  it("rejects logical wrapper vectors", function()
    local src = "(r/defrule foo [:not [:my-ns/my-type]] => 1)"
    assert.is_nil(token.vector_fact_at_point(src, after(src, ":not")))
  end)
end)

describe("string_at_point", function()
  it("inside and on the quote", function()
    assert.are.same('"my-string"', token.string_at_point('["my-string"]', after('["my-string"]', "my-string")))
    assert.are.same('"my-string"', token.string_at_point('["my-string"]', first('["my-string"]', '"my-string')))
  end)
end)

describe("token_at_point routing", function()
  it("RHS ctor symbol", function()
    local src = "(ns test) (r/defrule foo [Application] => (r/insert! (map->ApplicationOutcome {:a 1})))"
    local fs = first(src, "(r/defrule foo")
    local cursor = after(src, "map->ApplicationOutcome")
    assert.are.same("rhs", token.side_at_point(src, fs, cursor))
    assert.are.same("map->ApplicationOutcome", token.token_at_point(src, fs, "rhs", cursor))
  end)

  it("RHS vector", function()
    local src = '(ns test) (r/defrule foo\n  [Application]\n  =>\n  (r/insert! [:loan/status "verified"]))'
    local fs = first(src, "(r/defrule foo")
    local cursor = after(src, "verified")
    assert.are.same("rhs", token.side_at_point(src, fs, cursor))
    assert.are.same('[:loan/status "verified"]', token.token_at_point(src, fs, "rhs", cursor))
  end)

  it("global path (outside a rule) uses the symbol fallback", function()
    local src = "(ns test) (defn make-document-check [] (laf/map->DocumentCheck {:app-id 1}))"
    assert.are.same("laf/map->DocumentCheck", token.token_at_point(src, nil, nil, after(src, "DocumentCheck")))
  end)
end)

describe("metadata on the production name", function()
  it("skips ^:meta and docstring for LHS type", function()
    local src = '(r/defrule ^:my-meta my-rule "doc" [Application] => 1)'
    assert.are.same("Application", search_token(src, "(r/defrule ^:my-meta my-rule", "Application"))
  end)

  it("props with metadata and docstring", function()
    local src = '(r/defrule ^:my-meta my-rule "doc" {:clara-rules/insert-types [::a ::b]} [?x <- ::a] => 1)'
    local fs = first(src, "(r/defrule ^:my-meta my-rule")
    assert.are.same("rhs", token.side_at_point(src, fs, after(src, "::b")))
    assert.are.same("::b", token.token_at_point(src, fs, "rhs", after(src, "::b")))
  end)
end)
