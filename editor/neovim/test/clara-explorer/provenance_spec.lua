--- Tier 2: the fact type provenance view.  The backend is stubbed at
-- `provenance.request`, so these tests pin exactly what the plan's P matrix
-- asks: labels, path-keyed toggling, pre-expanded depth, `<Tab>` request shape,
-- error rows, the cache, stale rows, and a `defquery` cursor.

local conjure = require("clara-explorer.conjure")
local provenance = require("clara-explorer.provenance")

local function with_restore(tbl, key, stub, fn)
  local orig = tbl[key]
  tbl[key] = stub
  local ok, err = pcall(fn)
  tbl[key] = orig
  assert(ok, err)
end

local function fact_type(type_name, short, children, extra)
  local node = {
    kind = "fact-type",
    ["type"] = type_name,
    ["type-short"] = short or type_name,
    children = children or {},
  }
  for key, value in pairs(extra or {}) do
    node[key] = value
  end
  return node
end

local function production(name, children, extra)
  local node = {
    kind = "production",
    name = name,
    ns = name:match("^([^/]+)/"),
    ["type"] = "rule",
    ["cycle?"] = false,
    ["seen?"] = false,
  }
  if children ~= nil then node.children = children end
  for key, value in pairs(extra or {}) do
    node[key] = value
  end
  return node
end

local function message(text) return { kind = "message", text = text } end

local function buffer() return vim.fn.bufnr("clara-explorer://provenance") end

local function lines() return vim.api.nvim_buf_get_lines(buffer(), 0, -1, false) end

--- The 1-based line index of the first row containing NEEDLE.
local function row_of(needle)
  for i, line in ipairs(lines()) do
    if line:find(needle, 1, true) then return i end
  end
  error("no row containing " .. needle .. " in:\n" .. table.concat(lines(), "\n"))
end

local function any_row_contains(needle)
  for _, line in ipairs(lines()) do
    if line:find(needle, 1, true) then return true end
  end
  return false
end

--- Open the view with a stubbed root answer; returns the buffer number.
local function open_with(node, input)
  input = input
    or {
      node = { kind = "cursor", input = { caller_ns = "ns", token = "Doc" } },
      direction = "producer",
      depth = 0,
      ancestors = {},
    }
  with_restore(provenance, "request", function(_, _, cb) cb(node) end, function() provenance.open_root(input, 0) end)
  return buffer()
end

--- Move the cursor to the first row containing NEEDLE.
local function cursor_on(needle) vim.api.nvim_win_set_cursor(0, { row_of(needle), 0 }) end

describe("provenance.node_key", function()
  it("uses the type, name, or text", function()
    assert.are.same("T", provenance.node_key({ kind = "fact-type", ["type"] = "T" }))
    assert.are.same("x/alpha", provenance.node_key({ kind = "production", name = "x/alpha" }))
    assert.are.same("boom", provenance.node_key({ kind = "message", text = "boom" }))
  end)
end)

describe("provenance.depth", function()
  it("defaults to 0", function()
    with_restore(vim.g, "clara_explorer_provenance_depth", nil, function() assert.are.same(0, provenance.depth()) end)
  end)

  it("accepts an integer or full, and refuses anything else", function()
    with_restore(vim.g, "clara_explorer_provenance_depth", 3, function() assert.are.same(3, provenance.depth()) end)
    with_restore(
      vim.g,
      "clara_explorer_provenance_depth",
      "full",
      function() assert.are.same("full", provenance.depth()) end
    )
    with_restore(vim.g, "clara_explorer_provenance_depth", -2, function() assert.are.same(0, provenance.depth()) end)
  end)
end)

describe("provenance.request", function()
  local input = { node = { kind = "production", name = "x/alpha" }, direction = "producer", depth = 0, ancestors = {} }

  it("P5 turns an {:error …} answer into a message node (nREPL)", function()
    with_restore(conjure, "with_transport", function(cb) cb("nrepl") end, function()
      with_restore(conjure, "eval_edn", function(opts) opts.on_value('{:error "no fact type found"}') end, function()
        local node
        provenance.request(input, 0, function(n) node = n end)
        assert.are.same("message", node.kind)
        assert.are.same("no fact type found", node.text)
      end)
    end)
  end)

  it("P5 turns a transport failure into a message node (bb exit)", function()
    with_restore(conjure, "with_transport", function(cb) cb("bb") end, function()
      with_restore(conjure, "bb_selection", function(cb) cb('{:root "/r" :units []}') end, function()
        with_restore(
          conjure,
          "bb_eval",
          function(_, _, cb) cb(nil, "clara-explorer: bb transport failed (exit 3)") end,
          function()
            local node
            provenance.request(input, 0, function(n) node = n end)
            assert.are.same("message", node.kind)
            assert.truthy(node.text:find("exit 3", 1, true))
          end
        )
      end)
    end)
  end)

  it("sends the nested input over bb with --provenance", function()
    local seen
    with_restore(conjure, "with_transport", function(cb) cb("bb") end, function()
      with_restore(conjure, "bb_selection", function(cb) cb('{:root "/r" :units []}') end, function()
        with_restore(conjure, "bb_eval", function(_, encoded, cb, opts)
          seen = { encoded = encoded, flag = opts and opts.flag }
          cb({ node = message("ok") }, nil)
        end, function()
          provenance.request(input, 0, function() end)
        end)
      end)
    end)
    assert.are.same("--provenance", seen.flag)
    assert.truthy(seen.encoded:find(":node {:kind :production", 1, true))
  end)

  it("sends client/fact-type-provenance over nREPL", function()
    local code
    with_restore(conjure, "with_transport", function(cb) cb("nrepl") end, function()
      with_restore(conjure, "eval_edn", function(opts)
        code = opts.code
        opts.on_value('{:node {:kind :message :text "ok"}}')
      end, function()
        provenance.request(input, 0, function() end)
      end)
    end)
    assert.truthy(code:find("client/fact-type-provenance", 1, true))
  end)
end)

describe("provenance view", function()
  after_each(function() vim.cmd("silent! only") end)

  it("P1 renders the contract's labels without deriving short names", function()
    open_with(fact_type("java.lang.Object", "Object", {
      production("la/approved?", {}, {
        via = "insert",
        matches = { { name = "com.example.DocumentCheck", short = "DocumentCheck" } },
        ["bridge?"] = true,
      }),
      fact_type(":loan-app/application-outcome", ":loan-app/application-outcome", {}, { ["external?"] = true }),
      production("la/find-outcome", {}, { ["type"] = "query", unit = "loan-app-ruleset" }),
      production("la/self-loop", {}, { ["cycle?"] = true }),
    }))

    -- `:type-short` beside the dimmed full type, both from the contract.
    assert.truthy(any_row_contains("Object java.lang.Object"))
    -- A production: unqualified name, ns, `via` only when the backend says bridge.
    assert.truthy(any_row_contains("approved? la"))
    assert.truthy(any_row_contains("via DocumentCheck"))
    assert.truthy(any_row_contains("@loan-app-ruleset"))
    assert.truthy(any_row_contains("(external)"))
    assert.truthy(any_row_contains("(query)"))
    assert.truthy(any_row_contains("↻ cycle"))
    -- A keyword fact-type name is never shortened, so it appears exactly once.
    local keyword_rows = 0
    for _, line in ipairs(lines()) do
      if line:find(":loan-app/application-outcome", 1, true) then keyword_rows = keyword_rows + 1 end
    end
    assert.are.same(1, keyword_rows)
  end)

  it("P1 prints no via suffix for a direct edge", function()
    open_with(fact_type("T", "T", {
      production("x/direct", {}, { via = "insert", ["bridge?"] = false, matches = { { name = "T", short = "T" } } }),
    }))
    assert.is_false(any_row_contains("via T"))
  end)

  it("P1 labels consumer leaves, retract edges, and seen rows", function()
    open_with(
      fact_type("T", "T", {
        production("x/no-outputs", {}, { via = "consume" }),
        production("x/retract-only", {}, { via = "retract", vias = { "retract" }, ["bridge?"] = false }),
        production("x/both", {}, { via = "retract", vias = { "insert", "retract" } }),
        production("x/unknown", {}, { via = "consume", outputs = "unknown" }),
        production("x/seen", {}, { ["seen?"] = true }),
      }),
      {
        node = { kind = "cursor", input = { token = "T" } },
        direction = "consumer",
        depth = 0,
        ancestors = {},
      }
    )
    assert.truthy(any_row_contains("no-outputs x (no outputs)"))
    assert.truthy(any_row_contains("retract-only x"))
    assert.truthy(any_row_contains("(retract)"))
    assert.truthy(any_row_contains("(insert+retract)"))
    assert.truthy(any_row_contains("(outputs unknown)"))
    assert.truthy(any_row_contains("↑ shown above"))
  end)

  it("P2 the same production under two sibling fact types toggles independently", function()
    open_with(fact_type("Root", "Root", {
      production("x/mid", {
        fact_type("T1", "T1", { production("x/shared", { fact_type("U1", "U1", {}) }) }),
        fact_type("T2", "T2", { production("x/shared", { fact_type("U2", "U2", {}) }) }),
      }),
    }))
    -- Seeded expanded: both copies show their children.
    assert.truthy(any_row_contains("U1"))
    assert.truthy(any_row_contains("U2"))
    local first = "Root\0x/mid\0T1\0x/shared"
    local second = "Root\0x/mid\0T2\0x/shared"
    local state = provenance.view_state(buffer())
    assert.is_true(state.expanded[first] and state.expanded[second])

    -- Collapse the first copy only; the second stays open.
    cursor_on("shared x")
    provenance.toggle(buffer())
    state = provenance.view_state(buffer())
    assert.is_nil(state.expanded[first])
    assert.is_true(state.expanded[second])
    assert.is_false(any_row_contains("U1"))
    assert.truthy(any_row_contains("U2"))
  end)

  it("P3 a deeper response renders expanded with no extra request", function()
    local calls = 0
    local node = fact_type("Ta", "Ta", {
      production("x/alpha", { fact_type("Tb", "Tb", { production("x/deep", { fact_type("Tc", "Tc", {}) }) }) }),
    })
    with_restore(
      provenance,
      "request",
      function(_, _, cb)
        calls = calls + 1
        cb(node)
      end,
      function()
        provenance.open_root({
          node = { kind = "cursor", input = { token = "Ta" } },
          direction = "producer",
          depth = 1,
          ancestors = {},
        }, 0)
      end
    )
    assert.are.same(1, calls)
    assert.truthy(any_row_contains("deep x"))
    assert.truthy(any_row_contains("Tc"))
  end)

  it("P4 sends the path's productions (minus the row) as :ancestors", function()
    open_with(fact_type("Ta", "Ta", { production("x/alpha") }))
    local captured
    with_restore(provenance, "request", function(input, _, cb)
      captured = input
      cb(production("x/alpha", { fact_type("Tb", "Tb", { production("x/beta") }) }))
    end, function()
      cursor_on("alpha x")
      provenance.toggle(buffer())
    end)
    assert.are.same("production", captured.node.kind)
    assert.are.same("x/alpha", captured.node.name)
    assert.are.same("producer", captured.direction)
    assert.are.same(0, captured.depth)
    assert.are.same({}, captured.ancestors)

    -- One level deeper: beta's ancestors are the productions above it, minus itself.
    with_restore(provenance, "request", function(input, _, cb)
      captured = input
      cb(production("x/beta", { fact_type("Tc", "Tc", {}) }))
    end, function()
      cursor_on("beta x")
      provenance.toggle(buffer())
    end)
    assert.are.same("x/beta", captured.node.name)
    assert.are.same({ "x/alpha" }, captured.ancestors)
  end)

  it("P4 sends nothing for cycle, leaf, and outputs-unknown rows", function()
    open_with(fact_type("T", "T", {
      production("x/cycle", {}, { ["cycle?"] = true }),
      production("x/leaf", {}),
      production("x/unknown", {}, { outputs = "unknown" }),
    }))
    local calls = 0
    local notified = {}
    with_restore(provenance, "request", function(_, _, cb)
      calls = calls + 1
      cb(message("unexpected"))
    end, function()
      with_restore(vim, "notify", function(msg) notified[#notified + 1] = msg end, function()
        for _, name in ipairs({ "cycle x", "leaf x", "unknown x" }) do
          cursor_on(name)
          provenance.toggle(buffer())
        end
      end)
    end)
    assert.are.same(0, calls)
    assert.are.same(3, #notified)
  end)

  it("P4 a :seen? row expands on demand", function()
    open_with(fact_type("T", "T", { production("x/seen", nil, { ["seen?"] = true }) }))
    local calls = 0
    with_restore(provenance, "request", function(_, _, cb)
      calls = calls + 1
      cb(production("x/seen", { fact_type("Ua", "Ua", {}) }))
    end, function()
      cursor_on("seen x")
      provenance.toggle(buffer())
    end)
    assert.are.same(1, calls)
    assert.truthy(any_row_contains("Ua"))
  end)

  it("P5 a root error renders as a message row", function()
    open_with(message("no fact type found"))
    assert.are.same({ "no fact type found" }, lines())
  end)

  it("P5 an expansion error renders as a message row and retries later", function()
    open_with(fact_type("T", "T", { production("x/alpha") }))
    local calls = 0
    with_restore(provenance, "request", function(_, _, cb)
      calls = calls + 1
      cb(message("no production named x/alpha"))
    end, function()
      cursor_on("alpha x")
      provenance.toggle(buffer())
    end)
    assert.are.same(1, calls)
    assert.truthy(any_row_contains("no production named x/alpha"))

    -- A cached message row retries rather than showing a stale error.
    with_restore(provenance, "request", function(_, _, cb)
      calls = calls + 1
      cb(production("x/alpha", { fact_type("Ub", "Ub", {}) }))
    end, function()
      cursor_on("alpha x")
      provenance.toggle(buffer()) -- collapse
      cursor_on("alpha x")
      provenance.toggle(buffer()) -- cached message => retry
    end)
    assert.are.same(2, calls)
    assert.truthy(any_row_contains("Ub"))
  end)

  it("P6 collapse then expand reads the cache", function()
    open_with(fact_type("T", "T", { production("x/alpha") }))
    local calls = 0
    with_restore(provenance, "request", function(_, _, cb)
      calls = calls + 1
      cb(production("x/alpha", { fact_type("Uc", "Uc", {}) }))
    end, function()
      cursor_on("alpha x")
      provenance.toggle(buffer()) -- fetch
      assert.truthy(any_row_contains("Uc"))
      cursor_on("alpha x")
      provenance.toggle(buffer()) -- collapse
      assert.is_false(any_row_contains("Uc"))
      cursor_on("alpha x")
      provenance.toggle(buffer()) -- expand from cache
    end)
    assert.are.same(1, calls)
    assert.truthy(any_row_contains("Uc"))
  end)

  it("P7 a stale row asks for a refresh, and a fact-type row is not a jump target", function()
    open_with(fact_type("T", "T", {}))
    local notified = {}
    with_restore(vim, "notify", function(msg) notified[#notified + 1] = msg end, function()
      provenance.view_state(buffer()).line_paths[1] = "missing\0row"
      vim.api.nvim_win_set_cursor(0, { 1, 0 })
      provenance.visit(buffer())
    end)
    assert.truthy(notified[1]:find("stale row", 1, true))

    notified = {}
    with_restore(vim, "notify", function(msg) notified[#notified + 1] = msg end, function()
      provenance.load(buffer(), {
        node = { kind = "cursor", input = { token = "T" } },
        direction = "producer",
        depth = 0,
        ancestors = {},
      }, fact_type("T", "T", {}))
      vim.api.nvim_win_set_cursor(0, { 1, 0 })
      provenance.visit(buffer())
    end)
    assert.truthy(notified[1]:find("not a production row", 1, true))
  end)

  it("P8 accepts a cursor inside a defquery", function()
    local captured
    local ctx = { production = "ns/my-q", kind = "query", side = "lhs", caller_ns = "ns", token = "Doc" }
    with_restore(conjure, "connected", function() return true end, function()
      with_restore(require("clara-explorer"), "context", function() return ctx end, function()
        with_restore(conjure, "resolve_token", function(opts) opts.on_resolved("fq.Doc") end, function()
          with_restore(provenance, "open_root", function(input) captured = input end, function()
            with_restore(
              vim.api,
              "nvim_win_get_cursor",
              function() return { 1, 0 } end,
              function() provenance.open("consumer") end
            )
          end)
        end)
      end)
    end)
    assert.are.same("consumer", captured.direction)
    assert.are.same("cursor", captured.node.kind)
    assert.are.same("fq.Doc", captured.node.input.token)
    assert.are.same("lhs", captured.node.input.side)
    assert.are.same("ns/my-q", captured.node.input.production)
    assert.are.same({}, captured.ancestors)
  end)
end)
