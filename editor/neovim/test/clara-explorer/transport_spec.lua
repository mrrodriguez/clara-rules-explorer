--- Tier 2: real modules, mocked Conjure transport + picker + jump.  Asserts the
-- Clojure payload is built correctly, EDN is parsed, 0/1/N dispatch works,
-- the jump path is invoked with the right target, and the `cb` error path
-- surfaces nREPL errors.

local conjure = require("clara-explorer.conjure")
local init = require("clara-explorer.init")
local jump_mod = require("clara-explorer.jump")

local function stub_eval(fn) package.loaded["conjure.eval"] = { ["eval-str"] = fn } end

local function with_restore(tbl, key, stub, fn)
  local orig = tbl[key]
  tbl[key] = stub
  local ok, err = pcall(fn)
  tbl[key] = orig
  assert(ok, err)
end

describe("conjure.navigate_code", function()
  it("builds the shared client/navigate form", function()
    local code = conjure.navigate_code({
      production = "ns/rule",
      side = "rhs",
      caller_ns = "ns",
      token = "map->X",
    })
    assert.are.same(
      "(do (require 'clara.server.graph.client)\n     (clara.server.graph.client/navigate "
        .. '{:production "ns/rule" :side :rhs :caller-ns "ns" :token "map->X"}))',
      code
    )
  end)

  it("omits :production for the global path", function()
    local code = conjure.navigate_code({ production = nil, side = "rhs", caller_ns = "ns", token = "map->X" })
    assert.is_nil(code:find(":production", 1, true))
    assert.truthy(code:find(":caller-ns", 1, true))
  end)

  it("escapes strings in tokens", function()
    local code = conjure.navigate_code({ production = nil, side = nil, caller_ns = nil, token = 'a"b' })
    assert.truthy(code:match('\\"'))
  end)
end)

describe("conjure.err_summary", function()
  it("returns head plus Caused by line", function()
    local summary = conjure.err_summary("boom\n  at x\nCaused by: reason\n  at y")
    assert.are.same("boom | Caused by: reason", summary)
  end)

  it("returns empty for empty input", function()
    assert.are.same("", conjure.err_summary(""))
    assert.are.same("", conjure.err_summary(nil))
  end)
end)

describe("conjure.eval_edn", function()
  it("delivers the value string to on_value with captured bufnr/win", function()
    stub_eval(function(opts) opts["on-result"]('{:direction :producer :type "X" :targets []}') end)
    local got_val, got_buf, got_win
    conjure.eval_edn({
      code = "x",
      bufnr = 1,
      win = 2,
      on_value = function(v, b, w)
        got_val, got_buf, got_win = v, b, w
      end,
      on_error = function() error("should not surface an error") end,
    })
    assert.are.same('{:direction :producer :type "X" :targets []}', got_val)
    assert.are.same(1, got_buf)
    assert.are.same(2, got_win)
  end)

  it("surfaces nREPL err/ex via the cb path", function()
    stub_eval(
      function(opts) opts.cb({ err = "boom\n  at x\nCaused by: reason", ex = "clojure.lang.ExceptionInfo" }) end
    )
    local err_msg
    conjure.eval_edn({
      code = "x",
      on_value = function() error("should not produce a value") end,
      on_error = function(m) err_msg = m end,
    })
    assert.truthy(err_msg:match("clojure.lang.ExceptionInfo"))
    assert.truthy(err_msg:match("Caused by: reason"))
  end)

  it("on-result wins over a later cb message", function()
    stub_eval(function(opts)
      opts["on-result"]("value")
      opts.cb({ err = "late" })
    end)
    local value_count = 0
    conjure.eval_edn({
      code = "x",
      on_value = function() value_count = value_count + 1 end,
      on_error = function() error("late cb must be ignored") end,
    })
    assert.are.same(1, value_count)
  end)
end)

describe("init.handle_result dispatch", function()
  it("0 targets -> notify", function()
    with_restore(
      vim,
      "notify",
      function(msg) assert.are.same("No consumer of X", msg) end,
      function() init.handle_result({ direction = "consumer", type = "X", targets = {} }, nil) end
    )
  end)

  it("1 target -> direct jump with the target", function()
    with_restore(
      jump_mod,
      "jump",
      function(target, opts)
        assert.are.same("a/b", target.name)
        assert.are.same("caller.ns", opts.caller_ns)
      end,
      function() init.handle_result({ direction = "producer", type = "X", targets = { { name = "a/b" } } }, "caller.ns") end
    )
  end)

  it("N targets -> picker with labels, then jump the choice", function()
    local seen_items
    with_restore(vim.ui, "select", function(items, _opts, cb)
      seen_items = items
      cb(items[2])
    end, function()
      with_restore(
        jump_mod,
        "jump",
        function(target) assert.are.same("c/d", target.name) end,
        function()
          init.handle_result({
            direction = "consumer",
            type = "X",
            targets = {
              { name = "a/b", via = "insert" },
              { name = "c/d", via = "retract" },
            },
          }, nil)
        end
      )
    end)
    assert.are.same({ "a/b", "c/d (retract)" }, seen_items)
  end)

  it("relays a decoded :error map", function()
    with_restore(
      vim,
      "notify",
      function(msg) assert.are.same("no fact type found under cursor", msg) end,
      function() init.handle_result({ error = "no fact type found under cursor" }, nil) end
    )
  end)
end)

describe("init.perform_swap", function()
  it("evals swap-session! with the trimmed opts", function()
    with_restore(
      conjure,
      "eval_edn",
      function(o) assert.truthy(o.code:find("swap-session! {:session foo}", 1, true)) end,
      function() init.perform_swap("  {:session foo}  ", 1) end
    )
  end)

  it("evals the 0-arity default when opts are empty", function()
    with_restore(
      conjure,
      "eval_edn",
      function(o) assert.truthy(o.code:find("swap-session!)", 1, true), "expected 0-arity swap-session! form") end,
      function() init.perform_swap("   ", 1) end
    )
  end)
end)
