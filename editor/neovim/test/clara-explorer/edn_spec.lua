--- Tier 1: pure-Lua EDN subset reader tests (no network, no JVM, no parser).

local edn = require("clara-explorer.edn")

describe("edn.decode", function()
  it("decodes a NavigateResult map with keyword keys and values", function()
    local v = edn.decode('{:direction :producer :type "my.Type" :targets []}')
    assert.are.same("producer", v.direction)
    assert.are.same("my.Type", v.type)
    assert.are.same({}, v.targets)
  end)

  it("maps keyword keys to string keys", function()
    local v = edn.decode('{:var? true :name "a/b"}')
    assert.are.same(true, v["var?"])
    assert.are.same("a/b", v.name)
  end)

  it("maps keyword values to strings", function()
    local v = edn.decode("{:via :retract :direction :consumer}")
    assert.are.same("retract", v.via)
    assert.are.same("consumer", v.direction)
  end)

  it("decodes nested target vectors", function()
    local v = edn.decode(
      '{:targets [{:name "a/b" :ns "a" :type "rule" :via :insert :source {:var? true :file "a.clj" :line 1 :column 1}}]}'
    )
    assert.are.same(1, #v.targets)
    local t = v.targets[1]
    assert.are.same("a/b", t.name)
    assert.are.same("a", t.ns)
    assert.are.same("rule", t.type)
    assert.are.same("insert", t.via)
    assert.are.same(true, t.source["var?"])
    assert.are.same("a.clj", t.source.file)
    assert.are.same(1, t.source.line)
    assert.are.same(1, t.source.column)
  end)

  it("treats EDN nil values as absent map keys", function()
    local v = edn.decode('{:production nil :type "X"}')
    assert.is_nil(v.production)
    assert.are.same("X", v.type)
  end)

  it("treats commas as whitespace", function()
    local v = edn.decode("{:a 1, :b 2, :c [1, 2, 3]}")
    assert.are.same(1, v.a)
    assert.are.same(2, v.b)
    assert.are.same({ 1, 2, 3 }, v.c)
  end)

  it("decodes string escapes", function()
    assert.are.same('a"b', edn.decode('"a\\"b"'))
    assert.are.same("a\\b", edn.decode('"a\\\\b"'))
    assert.are.same("a\nb", edn.decode('"a\\nb"'))
    assert.are.same("a\tb", edn.decode('"a\\tb"'))
    assert.are.same("A", edn.decode('"\\u0041"'))
  end)

  it("decodes negative integers", function()
    assert.are.same(-5, edn.decode("-5"))
    assert.are.same(0, edn.decode("0"))
    assert.are.same(42, edn.decode("42"))
  end)

  it("decodes booleans", function()
    assert.are.same(true, edn.decode("true"))
    assert.are.same(false, edn.decode("false"))
  end)

  it("decodes a full error map", function()
    local v = edn.decode('{:error "no explorer system registered"}')
    assert.are.same("no explorer system registered", v.error)
  end)

  describe("failure modes", function()
    it("rejects symbols", function()
      local _, err = edn.decode("foo")
      assert.is_not_nil(err)
    end)

    it("rejects sets", function()
      local _, err = edn.decode('#{"a"}')
      assert.is_not_nil(err)
    end)

    it("rejects floats", function()
      local _, err = edn.decode("1.5")
      assert.is_not_nil(err)
    end)

    it("rejects trailing characters", function()
      local _, err = edn.decode("{} garbage")
      assert.is_not_nil(err)
    end)

    it("rejects an unterminated string", function()
      local _, err = edn.decode('"abc')
      assert.is_not_nil(err)
    end)

    it("rejects non-string input", function()
      local _, err = edn.decode(nil)
      assert.is_not_nil(err)
    end)

    it("rejects an unsupported escape", function()
      local _, err = edn.decode('"a\\q"')
      assert.is_not_nil(err)
    end)

    it("rejects an invalid \\u escape", function()
      local _, err = edn.decode('"\\u00ZZ"')
      assert.is_not_nil(err)
    end)

    it("rejects an unterminated map", function()
      local _, err = edn.decode("{:a 1")
      assert.is_not_nil(err)
    end)

    it("rejects an unterminated vector", function()
      local _, err = edn.decode("[1 2")
      assert.is_not_nil(err)
    end)

    it("rejects a non-keyword map key", function()
      local _, err = edn.decode("{1 :a}")
      assert.is_not_nil(err)
    end)
  end)
end)
