--- Tier 2: jump module — fallback regex, var vs non-var dispatch, resource
-- resolution (file:/jar:), and the namespace-file search fallback.

local jump = require("clara-explorer.jump")

local function with_restore(tbl, key, stub, fn)
  local orig = tbl[key]
  tbl[key] = stub
  local ok, err = pcall(fn)
  tbl[key] = orig
  assert(ok, err)
end

describe("jump.fallback_regex", function()
  local function matches(pattern, text) return vim.regex(pattern):match_str(text) ~= nil end

  it("matches aliased and unaliased defrule/defquery heads", function()
    assert.is_true(matches(jump.fallback_regex("my-rule"), "(r/defrule my-rule [A] => 1)"))
    assert.is_true(matches(jump.fallback_regex("my-rule"), "(defrule my-rule [A] => 1)"))
    assert.is_true(matches(jump.fallback_regex("my-q"), "(r/defquery my-q [] [A])"))
  end)

  it(
    "skips metadata between the head and the name",
    function() assert.is_true(matches(jump.fallback_regex("my-rule"), "(r/defrule ^:private my-rule [A] => 1)")) end
  )

  it(
    "does not match a different name",
    function() assert.is_false(matches(jump.fallback_regex("my-rule"), "(r/defrule other-rule [A] => 1)")) end
  )
end)

describe("jump.jump", function()
  it("routes var-backed targets to Conjure def-str", function()
    local got
    with_restore(package.loaded, "conjure.client.clojure.nrepl.action", {
      ["def-str"] = function(opts) got = opts end,
    }, function() jump.jump({ name = "my.ns/my-rule", source = { ["var?"] = true } }, { caller_ns = "my.ns" }) end)
    assert.are.same("my.ns/my-rule", got.code)
    assert.are.same("my.ns", got.context)
  end)

  it("routes non-var targets through the injected eval", function()
    local got
    jump.jump({ name = "my.ns/my-rule", ns = "my.ns", source = { ["var?"] = false } }, {
      eval_edn = function(o) got = o end,
    })
    assert.truthy(got)
    assert.truthy(got.code:find("clojure.java.io/resource", 1, true))
    assert.truthy(got.code:find("my.ns", 1, true))
  end)
end)

describe("jump.goto_fallback", function()
  it("opens a file: resource and searches for the production", function()
    local edited, searched
    with_restore(vim.cmd, "edit", function(path) edited = path end, function()
      with_restore(vim.cmd, "normal", function() end, function()
        with_restore(vim.fn, "search", function(pattern)
          searched = pattern
          return 5
        end, function()
          jump.goto_fallback(
            { name = "my.ns/my-rule", ns = "my.ns" },
            function(o) o.on_value("file:/repo/src/my/ns.clj") end
          )
        end)
      end)
    end)
    assert.are.same("/repo/src/my/ns.clj", edited)
    assert.are.same(jump.fallback_regex("my-rule"), searched)
  end)

  it("converts a jar: resource to a zipfile: URL", function()
    local edited
    with_restore(vim.g, "loaded_zipPlugin", "v32", function()
      with_restore(vim.cmd, "edit", function(path) edited = path end, function()
        with_restore(vim.cmd, "normal", function() end, function()
          with_restore(vim.fn, "search", function() return 1 end, function()
            jump.goto_fallback(
              { name = "my.ns/my-rule", ns = "my.ns" },
              function(o) o.on_value("jar:file:/repo/lib.jar!/my/ns.clj") end
            )
          end)
        end)
      end)
    end)
    assert.are.same("zipfile:///repo/lib.jar::my/ns.clj", edited)
  end)

  it("notifies when the namespace cannot be resolved", function()
    local msg
    with_restore(vim, "notify", function(m) msg = m end, function()
      jump.goto_fallback({ name = "my.ns/my-rule", ns = "my.ns" }, function(o) o.on_value(nil) end)
    end)
    assert.are.same("clara-explorer: cannot resolve namespace my.ns", msg)
  end)
end)
