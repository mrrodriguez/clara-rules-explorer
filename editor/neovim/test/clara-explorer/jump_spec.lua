--- Tier 2: jump module — definition-site regexes, locate (REPL → open buffer →
-- source roots), and the show step (jump-list push, window choice, line vs
-- search).  Fixtures are written under `target/` so `make clean` removes them.

local conjure = require("clara-explorer.conjure")
local jump = require("clara-explorer.jump")

local function with_restore(tbl, key, stub, fn)
  local orig = tbl[key]
  tbl[key] = stub
  local ok, err = pcall(fn)
  tbl[key] = orig
  assert(ok, err)
end

--- An absolute scratch directory under the plugin's `target/`, emptied first so
-- a repeated run cannot see a previous run's fixtures.
local function scratch(name)
  local dir = vim.fs.normalize(vim.fn.getcwd() .. "/target/provenance-spec/" .. name)
  vim.fn.delete(dir, "rf")
  vim.fn.mkdir(dir, "p")
  return dir
end

--- Write LINES to PATH (creating parent directories) and return PATH.
local function write_file(path, lines)
  vim.fn.mkdir(vim.fn.fnamemodify(path, ":h"), "p")
  vim.fn.writefile(lines, path)
  return path
end

--- A decoded `{:file … :line …}` answer, as the source-location form prints it.
local function loc_answer(file, line)
  local parts = { ":file " .. conjure.edn_string(file) }
  if line then parts[#parts + 1] = (":line %d"):format(line) end
  return "{" .. table.concat(parts, " ") .. "}"
end

--- A loaded buffer visiting PATH with LINES, without reading PATH from disk.
local function buffer_visiting(path, lines)
  local buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_buf_set_name(buf, path)
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
  return buf
end

--- Run `jump.locate` with an injected eval and return `location, reason`.
local function locate(target, eval_edn)
  local location, reason
  jump.locate(target, { eval_edn = eval_edn }, function(loc, why)
    location, reason = loc, why
  end)
  return location, reason
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

  it("matches names ending in punctuation", function()
    assert.is_true(matches(jump.fallback_regex("app-outcome-approved?"), "(r/defrule app-outcome-approved? [A] => 1)"))
    assert.is_true(matches(jump.fallback_regex("process-loan!"), "(r/defquery process-loan! [] [A])"))
  end)

  it("C5 an empty macro list matches nothing, and the whole-symbol fallback still does", function()
    with_restore(vim.g, "clara_explorer_production_macros", {}, function()
      assert.is_false(matches(jump.fallback_regex("my-rule"), "(r/defrule my-rule [A] => 1)"))
      assert.is_true(matches(jump.symbol_token_regex("my-rule"), "(r/defrule my-rule [A] => 1)"))
    end)
  end)

  it("C2 follows a custom macro list", function()
    with_restore(vim.g, "clara_explorer_production_macros", { defdata = "rule" }, function()
      assert.is_true(matches(jump.fallback_regex("my-data"), "(d/defdata my-data [A] => 1)"))
      assert.is_false(matches(jump.fallback_regex("my-rule"), "(r/defrule my-rule [A] => 1)"))
    end)
  end)
end)

describe("jump.symbol_token_regex", function()
  local function matches(pattern, text) return vim.regex(pattern):match_str(text) ~= nil end

  it("matches whole symbols ending in punctuation", function()
    assert.is_true(matches(jump.symbol_token_regex("my-thing?"), "x my-thing? y"))
    assert.is_true(matches(jump.symbol_token_regex("fact*"), "x fact* y"))
    assert.is_true(matches(jump.symbol_token_regex("done!"), "x done! y"))
  end)

  it("does not match a prefix of a longer symbol", function()
    assert.is_false(matches(jump.symbol_token_regex("my-thing"), "x my-thing? y"))
    assert.is_false(matches(jump.symbol_token_regex("fact"), "x fact* y"))
  end)

  it("matches a bare symbol bounded by delimiters or line edges", function()
    assert.is_true(matches(jump.symbol_token_regex("my-thing"), "x my-thing y"))
    assert.is_true(matches(jump.symbol_token_regex("my-thing"), "(my-thing)"))
    assert.is_true(matches(jump.symbol_token_regex("my-thing?"), "my-thing?"))
  end)
end)

describe("jump.source_loc_code", function()
  it("asks for the var metadata first, then the munged resources", function()
    local code = jump.source_loc_code("my.ns-with-hyphens", "my.ns-with-hyphens/my-rule")
    assert.truthy(code:find("(meta (resolve name))", 1, true))
    assert.truthy(code:find('"my.ns-with-hyphens/my-rule"', 1, true))
    assert.truthy(code:find("(munge ", 1, true))
    assert.truthy(code:find('"my.ns-with-hyphens"', 1, true))
    assert.truthy(code:find('".clj"', 1, true))
    assert.truthy(code:find('".cljc"', 1, true))
    assert.truthy(code:find("{:file u :line (:line m)}", 1, true))
    assert.truthy(code:find('(clojure.string/starts-with? f "/")', 1, true))
    assert.truthy(code:find('(str "file:" f)', 1, true))
  end)
end)

describe("jump.ns_name_from_text", function()
  it(
    "reads a plain ns form",
    function() assert.are.same("my.ns", jump.ns_name_from_text("(ns my.ns\n  (:require [x]))")) end
  )

  it("skips ^ metadata, including a map", function()
    assert.are.same("my.ns", jump.ns_name_from_text("(ns ^:no-doc my.ns)"))
    assert.are.same("my.ns", jump.ns_name_from_text('(ns ^{:doc "a; b"} my.ns)'))
    assert.are.same("my.ns", jump.ns_name_from_text("(ns ^:a ^:b my.ns)"))
  end)

  it(
    "ignores a commented-out ns form",
    function() assert.are.same("my.ns", jump.ns_name_from_text(";; (ns old.ns)\n(ns my.ns)")) end
  )

  it("returns nil when there is no ns form", function()
    assert.is_nil(jump.ns_name_from_text("(+ 1 2)"))
    assert.is_nil(jump.ns_name_from_text(nil))
  end)
end)

describe("jump.locate", function()
  it("J1 trusts the loaded var's file and line off the classpath", function()
    local dir = scratch("j1")
    local file = write_file(dir .. "/src/my/ns.clj", { "(ns my.ns)", "(r/defrule my-rule [A] => 1)" })
    local location = locate(
      { name = "my.ns/my-rule", ns = "my.ns" },
      function(opts) opts.on_value(loc_answer("file:" .. file, 2)) end
    )
    assert.are.same({ path = "file:" .. file, line = 2 }, location)
  end)

  it("J2 passes the reported line through; show decides whether to trust it", function()
    local dir = scratch("j2")
    local file = write_file(dir .. "/src/my/ns.clj", { "(ns my.ns)", "(r/defrule my-rule [A] => 1)" })
    local location = locate(
      { name = "my.ns/my-rule", ns = "my.ns" },
      function(opts) opts.on_value(loc_answer(file, 9)) end
    )
    assert.are.same({ path = file, line = 9 }, location)
  end)

  it("skips the REPL without a Conjure connection instead of waiting on it", function()
    local root = scratch("no-conn/roots")
    local file = write_file(root .. "/repo/src/my/ns.clj", { "(ns my.ns)" })
    local evaluated = false
    local location, reason
    with_restore(conjure, "connected", function() return false end, function()
      with_restore(conjure, "eval_edn", function() evaluated = true end, function()
        with_restore(vim.g, "clara_explorer_source_roots", { root }, function()
          jump.locate({ name = "my.ns/my-rule", ns = "my.ns" }, {}, function(loc, why)
            location, reason = loc, why
          end)
        end)
      end)
    end)
    assert.is_false(evaluated)
    assert.is_nil(reason)
    assert.are.same({ path = file }, location)
  end)

  it("J3 uses an open buffer whose ns form names the namespace when the REPL fails", function()
    local dir = scratch("j3")
    local path = write_file(dir .. "/src/my/ns.clj", { "(ns my.ns)" })
    local buf = buffer_visiting(path, { "(ns my.ns)", "(r/defrule my-rule [A] => 1)" })
    local location = locate({ name = "my.ns/my-rule", ns = "my.ns" }, function(opts) opts.on_error("no repl") end)
    vim.api.nvim_buf_delete(buf, { force = true })
    assert.are.same({ path = path }, location)
  end)

  it("refuses an empty or buffer-name file answer", function()
    local buf = buffer_visiting(scratch("j3b") .. "/other.ns.clj", { "(ns other.ns)" })
    local location = locate(
      { name = "other.ns/a-rule", ns = "other.ns" },
      function(opts) opts.on_value('{:file ""}') end
    )
    vim.api.nvim_buf_delete(buf, { force = true })
    assert.are.same({ path = scratch("j3b") .. "/other.ns.clj" }, location)
  end)

  it("J4 finds the namespace through the source roots", function()
    local root = scratch("j4/roots")
    local shallow = write_file(root .. "/two/src/my/ns.clj", { "(ns my.ns)" })
    write_file(root .. "/one/modules/rules/src/main/clojure/my/ns.clj", { "(ns my.ns)" })
    local wrong = write_file(root .. "/wrong/deep/deeper/deepest/src/my/ns.clj", { "(ns other.ns)" })
    write_file(root .. "/node_modules/src/my/ns.clj", { "(ns my.ns)" })
    write_file(root .. "/out/src/my/ns.clj", { "(ns my.ns)" })
    write_file(root .. "/target/src/my/ns.clj", { "(ns my.ns)" })
    write_file(root .. "/.hidden/src/my/ns.clj", { "(ns my.ns)" })

    local candidates = jump.source_root_candidates("my.ns", { root })
    assert.are.same(3, #candidates)
    assert.are.same(shallow, candidates[1])
    for _, path in ipairs(candidates) do
      local relative = path:sub(#root + 1)
      for _, skipped in ipairs({ "/node_modules/", "/out/", "/target/", "/.hidden/" }) do
        assert.is_nil(relative:find(skipped, 1, true), relative)
      end
    end
    -- The wrong-ns candidate is a path match that only the `ns` check rejects.
    assert.are.same("other.ns", jump.ns_name_of_file(wrong))

    with_restore(vim.g, "clara_explorer_source_roots", { root }, function()
      local location = locate({ name = "my.ns/my-rule", ns = "my.ns" }, function(opts) opts.on_error("no repl") end)
      assert.are.same({ path = shallow }, location)
    end)
  end)

  it("J4 orders candidates by root, then depth, then path", function()
    local first = scratch("j4-order/first")
    local second = scratch("j4-order/second")
    local deep = write_file(first .. "/repo/modules/src/my/ns.clj", { "(ns my.ns)" })
    local shallow_b = write_file(first .. "/b-repo/src/my/ns.clj", { "(ns my.ns)" })
    local shallow_a = write_file(first .. "/a-repo/src/my/ns.cljc", { "(ns my.ns)" })
    local shallower_in_second = write_file(second .. "/src/my/ns.clj", { "(ns my.ns)" })
    assert.are.same(
      { shallow_a, shallow_b, deep, shallower_in_second },
      jump.source_root_candidates("my.ns", { first, second })
    )
  end)

  it("J4 an empty skip list skips nothing but dot-directories", function()
    local root = scratch("j4-skip")
    local built = write_file(root .. "/repo/target/my/ns.clj", { "(ns my.ns)" })
    write_file(root .. "/repo/.cpcache/my/ns.clj", { "(ns my.ns)" })
    with_restore(
      vim.g,
      "clara_explorer_source_roots_skip_directories",
      {},
      function() assert.are.same({ built }, jump.source_root_candidates("my.ns", { root })) end
    )
    assert.are.same({}, jump.source_root_candidates("my.ns", { root }))
  end)

  it("J6 reports the remedy and finds nothing", function()
    with_restore(vim.g, "clara_explorer_source_roots", { scratch("j6/empty") }, function()
      local location, reason = locate(
        { name = "no.such.ns/a-rule", ns = "no.such.ns" },
        function(opts) opts.on_error("no repl") end
      )
      assert.is_nil(location)
      assert.truthy(reason:find("g:clara_explorer_source_roots", 1, true))
      assert.truthy(reason:find("(no repl)", 1, true))
    end)
  end)

  it("reports a missing target", function()
    local _, reason = locate(nil, function() end)
    assert.truthy(reason:find("cannot resolve source", 1, true))
  end)
end)

describe("jump.source_roots", function()
  it("J5 defaults to the parent of the REPL's project directory", function()
    with_restore(vim.g, "clara_explorer_source_roots", nil, function()
      assert.are.same({ "/a/b" }, jump.source_roots("/a/b/project"))
      assert.are.same({}, jump.source_roots(nil))
    end)
  end)

  it("J5 a configured list replaces the default", function()
    with_restore(
      vim.g,
      "clara_explorer_source_roots",
      { "/x", "/y" },
      function() assert.are.same({ "/x", "/y" }, jump.source_roots("/a/b/project")) end
    )
  end)
end)

describe("jump.jump", function()
  it("routes var-backed targets to Conjure def-str", function()
    local got
    with_restore(package.loaded, "conjure.client.clojure.nrepl.action", {
      ["def-str"] = function(opts) got = opts end,
    }, function()
      with_restore(
        jump,
        "push_jump",
        function() end,
        function() jump.jump({ name = "my.ns/my-rule", source = { ["var?"] = true } }, { caller_ns = "my.ns" }) end
      )
    end)
    assert.are.same("my.ns/my-rule", got.code)
    assert.are.same("my.ns", got.context)
  end)

  it("locates a non-var target before showing it", function()
    local located, shown
    with_restore(jump, "locate", function(target, _, cb)
      located = target
      cb({ path = "/repo/src/my/ns.clj" })
    end, function()
      with_restore(
        jump,
        "show",
        function(location) shown = location end,
        function() jump.jump({ name = "my.ns/my-rule", ns = "my.ns", source = { ["var?"] = false } }) end
      )
    end)
    assert.are.same("my.ns/my-rule", located.name)
    assert.are.same("/repo/src/my/ns.clj", shown.path)
  end)

  it("J6 notifies and shows nothing when the target cannot be located", function()
    local notified, shown = nil, false
    with_restore(jump, "locate", function(_, _, cb) cb(nil, "cannot locate my.ns") end, function()
      with_restore(jump, "show", function() shown = true end, function()
        with_restore(
          vim,
          "notify",
          function(msg) notified = msg end,
          function() jump.jump({ name = "my.ns/my-rule", ns = "my.ns", source = { ["var?"] = false } }) end
        )
      end)
    end)
    assert.is_false(shown)
    assert.are.same("cannot locate my.ns", notified)
  end)
end)

describe("jump.show", function()
  --- Two windows: the first visits A, the second (current) visits B.
  local function two_windows(name)
    local dir = scratch(name)
    local a = write_file(dir .. "/a.clj", { "(ns a)", "(r/defrule rule-a [A] => 1)" })
    local b = write_file(dir .. "/b.clj", { "(ns b)" })
    vim.cmd("only")
    vim.cmd.edit(a)
    local win_a = vim.api.nvim_get_current_win()
    vim.cmd("vsplit " .. b)
    return a, win_a
  end

  it("J7 prefers the window already showing the file", function()
    local a, win_a = two_windows("j7")
    with_restore(
      vim,
      "notify",
      function() end,
      function() jump.show({ path = a, line = 2 }, { name = "a/rule-a", ns = "a" }) end
    )
    assert.are.same(win_a, vim.api.nvim_get_current_win())
    assert.are.same({ 2, 0 }, vim.api.nvim_win_get_cursor(0))
    vim.cmd("only")
  end)

  it("J8 pushes the jump before changing windows", function()
    local a, win_a = two_windows("j8")
    local origin = vim.api.nvim_get_current_win()
    local pushed_win
    with_restore(jump, "push_jump", function() pushed_win = vim.api.nvim_get_current_win() end, function()
      with_restore(
        vim,
        "notify",
        function() end,
        function() jump.show({ path = a, line = 2 }, { name = "a/rule-a", ns = "a" }) end
      )
    end)
    assert.are.same(origin, pushed_win)
    assert.are.same(win_a, vim.api.nvim_get_current_win())
    vim.cmd("only")
  end)

  it("J2 checks the reported line in the shown buffer, unsaved edits included", function()
    local a, win_a = two_windows("j2-show")
    local buf = vim.api.nvim_win_get_buf(win_a)
    -- Unsaved: the rule moved from line 2 (on disk) to line 3 (in the buffer).
    vim.api.nvim_buf_set_lines(buf, 1, 1, false, { "" })
    assert.is_true(vim.bo[buf].modified)
    local target = { name = "a/rule-a", ns = "a" }
    with_restore(vim, "notify", function() end, function() jump.show({ path = a, line = 3 }, target) end)
    assert.are.same(win_a, vim.api.nvim_get_current_win())
    assert.are.same(buf, vim.api.nvim_get_current_buf())
    assert.are.same({ 3, 0 }, vim.api.nvim_win_get_cursor(0))

    -- The disk line no longer holds the rule in the buffer, so the form is searched.
    local searched = {}
    with_restore(vim.fn, "search", function(pattern)
      searched[#searched + 1] = pattern
      return 3
    end, function()
      with_restore(vim.cmd, "normal", function() end, function() jump.show({ path = a, line = 2 }, target) end)
    end)
    assert.are.same(jump.fallback_regex("rule-a"), searched[1])
    vim.api.nvim_buf_set_option(buf, "modified", false)
    vim.cmd("only")
  end)

  it("trusts a line only when the whole symbol is on it", function()
    local dir = scratch("j-symbol")
    local file =
      write_file(dir .. "/a.clj", { "(ns a)", "(r/defrule rule-a-longer [A] => 1)", "(r/defrule rule-a [A] => 1)" })
    vim.cmd("only")
    local searched = {}
    with_restore(vim.fn, "search", function(pattern)
      searched[#searched + 1] = pattern
      return 3
    end, function()
      with_restore(
        vim.cmd,
        "normal",
        function() end,
        function() jump.show({ path = file, line = 2 }, { name = "a/rule-a", ns = "a" }) end
      )
    end)
    assert.are.same(jump.fallback_regex("rule-a"), searched[1])
  end)

  it("searches for the form when no line was trusted", function()
    local a = two_windows("j9")
    local searched = {}
    with_restore(vim.fn, "search", function(pattern)
      searched[#searched + 1] = pattern
      return 2
    end, function()
      with_restore(
        vim.cmd,
        "normal",
        function() end,
        function() jump.show({ path = a }, { name = "a/rule-a", ns = "a" }) end
      )
    end)
    assert.are.same(jump.fallback_regex("rule-a"), searched[1])
    vim.cmd("only")
  end)

  it("opens a jar: URL through the zipfile form", function()
    local edited
    with_restore(vim.g, "loaded_zipPlugin", "v32", function()
      with_restore(vim.cmd, "edit", function(path) edited = path end, function()
        with_restore(vim.cmd, "normal", function() end, function()
          with_restore(
            vim.fn,
            "search",
            function() return 1 end,
            function()
              jump.show({ path = "jar:file:/repo/lib.jar!/my/ns.clj" }, { name = "my.ns/my-rule", ns = "my.ns" })
            end
          )
        end)
      end)
    end)
    assert.are.same("zipfile:///repo/lib.jar::my/ns.clj", edited)
  end)

  it("strips the file: scheme for :edit", function()
    local nargs, edited
    with_restore(vim.cmd, "edit", function(...)
      nargs = select("#", ...)
      edited = ...
    end, function()
      with_restore(vim.cmd, "normal", function() end, function()
        with_restore(
          vim.fn,
          "search",
          function() return 1 end,
          function() jump.show({ path = "file:/repo/src/my/ns.clj" }, { name = "my.ns/my-rule", ns = "my.ns" }) end
        )
      end)
    end)
    assert.are.same(1, nargs)
    assert.are.same("/repo/src/my/ns.clj", edited)
  end)
end)
