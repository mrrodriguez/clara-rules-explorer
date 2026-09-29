--- Tier 2: babashka transport — the `bb editor_client.bb` shell-out executor,
-- registry-selection prompt/cache, and the bb-mode dispatch in `init.lua`
-- (navigate / refresh / swap-session / toggle / select-unit). Mirrors
-- `editor/emacs/test/clara-explorer-test.el`'s babashka-transport tests.

local conjure = require("clara-explorer.conjure")
local init = require("clara-explorer.init")

local function with_restore(tbl, key, stub, fn)
  local orig = tbl[key]
  tbl[key] = stub
  local ok, err = pcall(fn)
  tbl[key] = orig
  assert(ok, err)
end

local function stub_eval(fn) package.loaded["conjure.eval"] = { ["eval-str"] = fn } end

describe("conjure.navigate_input", function()
  it("builds the EDN NavigateInput map", function()
    local input = conjure.navigate_input({
      production = "ns/rule",
      side = "rhs",
      caller_ns = "ns",
      token = "map->X",
    })
    assert.are.same('{:production "ns/rule" :side :rhs :caller-ns "ns" :token "map->X"}', input)
  end)

  it("omits :production for the global path", function()
    local input = conjure.navigate_input({ production = nil, side = "rhs", caller_ns = "ns", token = "map->X" })
    assert.is_nil(input:find(":production", 1, true))
    assert.truthy(input:find(":caller-ns", 1, true))
  end)

  it("escapes strings in tokens", function()
    local input = conjure.navigate_input({ production = nil, side = nil, caller_ns = nil, token = 'a"b' })
    assert.truthy(input:match('\\"'))
  end)
end)

describe("conjure.transport", function()
  it("defaults to auto", function()
    with_restore(vim.g, "clara_explorer_transport", nil, function() assert.are.same("auto", conjure.transport()) end)
  end)

  it("explicit bb resolves without probing", function()
    with_restore(vim.g, "clara_explorer_transport", "bb", function()
      assert.are.same("bb", conjure.transport())
      assert.are.same("bb", conjure.effective_transport())
      assert.is_true(conjure.bb_transport_p())
    end)
  end)

  it("auto defaults to bb before any probe", function()
    with_restore(vim.g, "clara_explorer_transport", nil, function()
      with_restore(conjure, "server_available_cache", nil, function()
        assert.are.same("bb", conjure.effective_transport())
        assert.is_true(conjure.bb_transport_p())
      end)
    end)
  end)

  it("auto uses nrepl when the last probe found a server", function()
    with_restore(vim.g, "clara_explorer_transport", nil, function()
      with_restore(conjure, "server_available_cache", true, function()
        assert.are.same("nrepl", conjure.effective_transport())
        assert.is_false(conjure.bb_transport_p())
      end)
    end)
  end)
end)

describe("conjure.server_available", function()
  it("reports true and caches it", function()
    stub_eval(function(opts) opts["on-result"]("true") end)
    local got, cached
    with_restore(conjure, "server_available_cache", nil, function()
      conjure.server_available(function(v) got = v end)
      cached = conjure.server_available_cache
    end)
    assert.is_true(got)
    assert.is_true(cached)
  end)

  it("reports false on a probe error", function()
    stub_eval(function(opts) opts.cb({ err = "boom", ex = "clojure.lang.ExceptionInfo" }) end)
    local got, cached
    with_restore(conjure, "server_available_cache", nil, function()
      conjure.server_available(function(v) got = v end)
      cached = conjure.server_available_cache
    end)
    assert.is_false(got)
    assert.is_false(cached)
  end)
end)

describe("conjure.with_transport", function()
  it("passes explicit transports through synchronously", function()
    with_restore(vim.g, "clara_explorer_transport", "nrepl", function()
      local got
      conjure.with_transport(function(t) got = t end)
      assert.are.same("nrepl", got)
    end)
  end)

  it("auto probes the server", function()
    with_restore(vim.g, "clara_explorer_transport", nil, function()
      with_restore(conjure, "server_available", function(cb) cb(true) end, function()
        local got
        conjure.with_transport(function(t) got = t end)
        assert.are.same("nrepl", got)
      end)
    end)
  end)
end)

describe("conjure.record_error", function()
  it("stores the full error for :ClaraExplorerLastError", function()
    with_restore(conjure, "last_error", nil, function()
      with_restore(package.loaded, "conjure.log", nil, function()
        conjure.record_error({ code = "(boom)", err = "line1\nline2", ex = "boom" })
        assert.are.same("(boom)", conjure.last_error.code)
        assert.are.same("boom", conjure.last_error.ex)
      end)
    end)
  end)
end)

describe("conjure.show_last_error", function()
  it("notifies when there is no last error", function()
    local notified
    with_restore(conjure, "last_error", nil, function()
      with_restore(vim, "notify", function(msg) notified = msg end, function() conjure.show_last_error() end)
    end)
    assert.are.same("clara-explorer: no last error", notified)
  end)
end)

describe("init.transport_status", function()
  it("shows the effective transport first in a scratch buffer", function()
    local shown_name, shown_lines
    with_restore(vim.g, "clara_explorer_transport", "nrepl", function()
      with_restore(conjure, "connected", function() return true end, function()
        with_restore(conjure, "with_transport", function(cb) cb("nrepl") end, function()
          with_restore(conjure, "show_scratch", function(name, lines)
            shown_name, shown_lines = name, lines
          end, function() init.transport_status() end)
        end)
      end)
    end)
    assert.are.same("clara-explorer://transport-status", shown_name)
    assert.truthy(vim.tbl_contains(shown_lines, "effective       nrepl"))
    assert.truthy(vim.tbl_contains(shown_lines, "configured      nrepl"))
    assert.truthy(vim.tbl_contains(shown_lines, "connected       yes"))
  end)
end)

describe("conjure.registry_root", function()
  it("reads CLARA_RULES_EXPLORER_REGISTRY when the defcustom is nil", function()
    with_restore(vim.g, "clara_explorer_registry_root", nil, function()
      with_restore(
        vim.env,
        "CLARA_RULES_EXPLORER_REGISTRY",
        "/reg",
        function() assert.are.same("/reg", conjure.registry_root()) end
      )
    end)
  end)

  it("prefers g:clara_explorer_registry_root", function()
    with_restore(vim.g, "clara_explorer_registry_root", "/custom", function()
      with_restore(
        vim.env,
        "CLARA_RULES_EXPLORER_REGISTRY",
        "/env",
        function() assert.are.same("/custom", conjure.registry_root()) end
      )
    end)
  end)

  it("returns nil when neither is set", function()
    with_restore(vim.g, "clara_explorer_registry_root", nil, function()
      with_restore(vim.env, "CLARA_RULES_EXPLORER_REGISTRY", nil, function() assert.is_nil(conjure.registry_root()) end)
    end)
  end)
end)

describe("conjure.bb_script", function()
  it("resolves the symlink beside the module when unconfigured", function()
    with_restore(
      vim.g,
      "clara_explorer_bb_script",
      nil,
      function() assert.truthy(conjure.bb_script():match("editor_client%.bb$")) end
    )
  end)

  it("prefers g:clara_explorer_bb_script", function()
    with_restore(
      vim.g,
      "clara_explorer_bb_script",
      "/elsewhere/editor_client.bb",
      function() assert.are.same("/elsewhere/editor_client.bb", conjure.bb_script()) end
    )
  end)
end)

describe("conjure.bb_list_unit_repos", function()
  it("shells out to --list-units and decodes the sorted EDN vector", function()
    local captured
    with_restore(conjure, "bb_script", function() return "/p/editor_client.bb" end, function()
      with_restore(vim.fn, "filereadable", function() return 1 end, function()
        with_restore(vim, "system", function(cmd, opts)
          captured = { cmd = cmd, opts = opts }
          return {
            wait = function()
              return {
                code = 0,
                stdout = '["composed/loan-app-plus-disposition" "loan-app-ruleset" "loan-disposition-ruleset"]',
                stderr = "",
              }
            end,
          }
        end, function()
          assert.are.same(
            { "composed/loan-app-plus-disposition", "loan-app-ruleset", "loan-disposition-ruleset" },
            conjure.bb_list_unit_repos("/root")
          )
          assert.are.same({ "bb", "/p/editor_client.bb", "--list-units", "/root" }, captured.cmd)
          assert.are.same(true, captured.opts.text)
        end)
      end)
    end)
  end)

  it("notifies and returns empty when the script fails", function()
    with_restore(conjure, "bb_script", function() return "/p/editor_client.bb" end, function()
      with_restore(vim.fn, "filereadable", function() return 1 end, function()
        with_restore(vim, "system", function()
          return { wait = function() return { code = 3, stdout = "", stderr = "oops" } end }
        end, function()
          local msg
          with_restore(
            vim,
            "notify",
            function(m) msg = m end,
            function() assert.are.same({}, conjure.bb_list_unit_repos("/root")) end
          )
          assert.truthy(msg:find("exit 3", 1, true))
        end)
      end)
    end)
  end)

  it("notifies and returns empty when bb reports an :error", function()
    with_restore(conjure, "bb_script", function() return "/p/editor_client.bb" end, function()
      with_restore(vim.fn, "filereadable", function() return 1 end, function()
        with_restore(vim, "system", function()
          return { wait = function() return { code = 0, stdout = '{:error "not a directory"}', stderr = "" } end }
        end, function()
          local msg
          with_restore(
            vim,
            "notify",
            function(m) msg = m end,
            function() assert.are.same({}, conjure.bb_list_unit_repos("/root")) end
          )
          assert.are.same("clara-explorer: not a directory", msg)
        end)
      end)
    end)
  end)
end)

describe("conjure.bb_prompt_selection", function()
  it("builds the selection EDN from the chosen repo", function()
    local notified
    with_restore(conjure, "registry_root", function() return "/reg" end, function()
      with_restore(vim.fn, "isdirectory", function() return 1 end, function()
        with_restore(
          conjure,
          "bb_list_unit_repos",
          function(_root) return { "loan-app-ruleset", "loan-disposition-ruleset" } end,
          function()
            with_restore(vim.ui, "select", function(items, _opts, cb) cb(items[1]) end, function()
              with_restore(vim, "notify", function(msg) notified = msg end, function()
                local got
                conjure.bb_prompt_selection(function(sel) got = sel end)
                assert.are.same('{:root "/reg" :units [{:repo "loan-app-ruleset"}]}', got)
                assert.is_nil(notified)
              end)
            end)
          end
        )
      end)
    end)
  end)

  it("splits a repo@branch unit-key into :repo and :branch", function()
    local notified
    with_restore(conjure, "registry_root", function() return "/reg" end, function()
      with_restore(vim.fn, "isdirectory", function() return 1 end, function()
        with_restore(
          conjure,
          "bb_list_unit_repos",
          function(_root) return { "loan-disposition-ruleset@alt" } end,
          function()
            with_restore(vim.ui, "select", function(items, _opts, cb) cb(items[1]) end, function()
              with_restore(vim, "notify", function(msg) notified = msg end, function()
                local got
                conjure.bb_prompt_selection(function(sel) got = sel end)
                assert.are.same('{:root "/reg" :units [{:repo "loan-disposition-ruleset" :branch "alt"}]}', got)
                assert.is_nil(notified)
              end)
            end)
          end
        )
      end)
    end)
  end)

  it("notifies when the registry root is missing", function()
    local notified
    with_restore(conjure, "registry_root", function() return nil end, function()
      with_restore(vim, "notify", function(msg) notified = msg end, function()
        local got = "sentinel"
        conjure.bb_prompt_selection(function(sel) got = sel end)
        assert.is_nil(got)
        assert.truthy(notified:find("CLARA_RULES_EXPLORER_REGISTRY", 1, true))
      end)
    end)
  end)

  it("notifies when there are no units", function()
    local notified
    with_restore(conjure, "registry_root", function() return "/reg" end, function()
      with_restore(vim.fn, "isdirectory", function() return 1 end, function()
        with_restore(conjure, "bb_list_unit_repos", function() return {} end, function()
          with_restore(vim, "notify", function(msg) notified = msg end, function()
            local got = "sentinel"
            conjure.bb_prompt_selection(function(sel) got = sel end)
            assert.is_nil(got)
            assert.truthy(notified:find("no units", 1, true))
          end)
        end)
      end)
    end)
  end)
end)

describe("conjure.bb_selection", function()
  it("caches the prompted selection", function()
    conjure.bb_selection_cache = nil
    local prompts = 0
    with_restore(conjure, "bb_prompt_selection", function(cb)
      prompts = prompts + 1
      cb("{sel}")
    end, function()
      local got
      conjure.bb_selection(function(sel) got = sel end)
      assert.are.same("{sel}", got)
      conjure.bb_selection(function(sel) got = sel end)
      assert.are.same("{sel}", got)
      assert.are.same(1, prompts)
    end)
  end)

  it("bb_select_unit clears the cache and re-prompts", function()
    conjure.bb_selection_cache = "{old}"
    with_restore(conjure, "bb_prompt_selection", function(cb) cb("{new}") end, function()
      local got
      conjure.bb_select_unit(function(sel) got = sel end)
      assert.are.same("{new}", got)
      assert.are.same("{new}", conjure.bb_selection_cache)
    end)
  end)
end)

describe("conjure.bb_eval", function()
  it("runs bb and parses the EDN result", function()
    local captured
    with_restore(conjure, "bb_script", function() return "/p/editor_client.bb" end, function()
      with_restore(vim.fn, "filereadable", function() return 1 end, function()
        with_restore(vim, "system", function(cmd, opts, on_exit)
          captured = { cmd = cmd, opts = opts }
          on_exit({ code = 0, stdout = "{:direction :consumer}", stderr = "" })
          return true
        end, function()
          local result, err
          local done = false
          conjure.bb_eval("{sel}", "{input}", function(r, e)
            result, err = r, e
            done = true
          end)
          vim.wait(1000, function() return done end, 10)
          assert.are.same({ "bb", "/p/editor_client.bb", "{sel}", "{input}" }, captured.cmd)
          assert.are.same(true, captured.opts.text)
          assert.are.same({ direction = "consumer" }, result)
          assert.is_nil(err)
        end)
      end)
    end)
  end)

  it("surfaces a non-zero exit", function()
    with_restore(conjure, "bb_script", function() return "/p/editor_client.bb" end, function()
      with_restore(vim.fn, "filereadable", function() return 1 end, function()
        with_restore(vim, "system", function(_cmd, _opts, on_exit)
          on_exit({ code = 3, stdout = "", stderr = "oops" })
          return true
        end, function()
          local result, err
          local done = false
          conjure.bb_eval("{sel}", "{input}", function(r, e)
            result, err = r, e
            done = true
          end)
          vim.wait(1000, function() return done end, 10)
          assert.is_nil(result)
          assert.truthy(err:find("exit 3", 1, true))
          assert.truthy(err:find("oops", 1, true))
        end)
      end)
    end)
  end)

  it("surfaces invalid EDN", function()
    with_restore(conjure, "bb_script", function() return "/p/editor_client.bb" end, function()
      with_restore(vim.fn, "filereadable", function() return 1 end, function()
        with_restore(vim, "system", function(_cmd, _opts, on_exit)
          on_exit({ code = 0, stdout = "not edn {", stderr = "" })
          return true
        end, function()
          local result, err
          local done = false
          conjure.bb_eval("{sel}", "{input}", function(r, e)
            result, err = r, e
            done = true
          end)
          vim.wait(1000, function() return done end, 10)
          assert.is_nil(result)
          assert.truthy(err:find("invalid EDN", 1, true))
        end)
      end)
    end)
  end)

  it("surfaces a spawn failure (bb missing)", function()
    with_restore(conjure, "bb_script", function() return "/p/editor_client.bb" end, function()
      with_restore(vim.fn, "filereadable", function() return 1 end, function()
        with_restore(vim, "system", function() error("ENOENT") end, function()
          local result, err
          conjure.bb_eval("{sel}", "{input}", function(r, e)
            result, err = r, e
          end)
          assert.is_nil(result)
          assert.truthy(err:find("bb not found", 1, true))
        end)
      end)
    end)
  end)

  it("surfaces a missing script", function()
    with_restore(conjure, "bb_script", function() return "/p/editor_client.bb" end, function()
      with_restore(vim.fn, "filereadable", function() return 0 end, function()
        local result, err
        conjure.bb_eval("{sel}", "{input}", function(r, e)
          result, err = r, e
        end)
        assert.is_nil(result)
        assert.truthy(err:find("editor_client.bb not found", 1, true))
      end)
    end)
  end)
end)

describe("init.navigate bb dispatch", function()
  local ctx = { production = "ns/rule", kind = "rule", side = "lhs", caller_ns = "ns", token = "Doc" }

  local function with_bb_nav_env(overrides, fn)
    with_restore(
      conjure,
      "connected",
      function() return overrides.connected == nil and true or overrides.connected end,
      function()
        with_restore(conjure, "with_transport", function(cb) cb("bb") end, function()
          with_restore(vim.api, "nvim_get_current_buf", function() return 1 end, function()
            with_restore(vim.api, "nvim_get_current_win", function() return 1 end, function()
              with_restore(vim.api, "nvim_win_get_cursor", function() return { 1, 0 } end, function()
                with_restore(init, "context", function() return ctx end, function()
                  with_restore(conjure, "resolve_token", overrides.resolve_token, function()
                    with_restore(
                      conjure,
                      "eval_edn",
                      function() error("nREPL must not be used in bb mode") end,
                      function()
                        with_restore(
                          conjure,
                          "bb_selection",
                          overrides.bb_selection,
                          function() with_restore(conjure, "bb_eval", overrides.bb_eval, fn) end
                        )
                      end
                    )
                  end)
                end)
              end)
            end)
          end)
        end)
      end
    )
  end

  it("sends the resolved token over bb with the selection", function()
    local seen_sel, seen_input
    with_bb_nav_env({
      resolve_token = function(o) o.on_resolved("fq.Doc") end,
      bb_selection = function(cb) cb("{sel}") end,
      bb_eval = function(sel, input)
        seen_sel, seen_input = sel, input
      end,
    }, function() init.navigate("lhs") end)
    assert.are.same("{sel}", seen_sel)
    assert.truthy(seen_input:find(':token "fq.Doc"', 1, true))
    assert.truthy(seen_input:find(':production "ns/rule"', 1, true))
    assert.truthy(seen_input:find(':caller-ns "ns"', 1, true))
  end)

  it("falls back to the raw token when resolution fails", function()
    local seen_input
    with_bb_nav_env({
      resolve_token = function(o) o.on_resolved(nil) end,
      bb_selection = function(cb) cb("{sel}") end,
      bb_eval = function(_sel, input) seen_input = input end,
    }, function() init.navigate("lhs") end)
    assert.truthy(seen_input:find(':token "Doc"', 1, true))
  end)

  it("relays a decoded :error result", function()
    local notified
    with_bb_nav_env({
      resolve_token = function(o) o.on_resolved("fq.Doc") end,
      bb_selection = function(cb) cb("{sel}") end,
      bb_eval = function(_sel, _input, cb) cb({ error = "no fact type found under cursor" }, nil) end,
    }, function()
      with_restore(vim, "notify", function(msg) notified = msg end, function() init.navigate("lhs") end)
    end)
    assert.are.same("no fact type found under cursor", notified)
  end)
end)

describe("init refresh/swap bb no-ops", function()
  it("refresh is a no-op in bb mode", function()
    local notified
    with_restore(conjure, "connected", function() return true end, function()
      with_restore(conjure, "with_transport", function(cb) cb("bb") end, function()
        with_restore(vim, "notify", function(msg) notified = msg end, function() init.refresh() end)
      end)
    end)
    assert.truthy(notified:find("no-op", 1, true))
  end)

  it("swap_session is a no-op in bb mode", function()
    local notified
    with_restore(conjure, "connected", function() return true end, function()
      with_restore(conjure, "with_transport", function(cb) cb("bb") end, function()
        with_restore(vim, "notify", function(msg) notified = msg end, function() init.swap_session(nil) end)
      end)
    end)
    assert.truthy(notified:find("no-op", 1, true))
  end)
end)

describe("init.toggle_transport", function()
  it("switches between nrepl and bb", function()
    local messages = {}
    with_restore(vim.g, "clara_explorer_transport", "nrepl", function()
      with_restore(vim, "notify", function(msg) messages[#messages + 1] = msg end, function()
        init.toggle_transport()
        assert.are.same("bb", vim.g.clara_explorer_transport)
        init.toggle_transport()
        assert.are.same("nrepl", vim.g.clara_explorer_transport)
      end)
    end)
    assert.truthy(messages[1]:find("bb", 1, true))
    assert.truthy(messages[2]:find("nrepl", 1, true))
  end)
end)

describe("init.select_unit", function()
  it("warns under the nrepl transport", function()
    local notified
    with_restore(conjure, "bb_transport_p", function() return false end, function()
      with_restore(conjure, "bb_select_unit", function() error("must not prompt") end, function()
        with_restore(vim, "notify", function(msg) notified = msg end, function() init.select_unit() end)
      end)
    end)
    assert.truthy(notified:find("warning", 1, true))
  end)

  it("prompts under the bb transport", function()
    local notified
    with_restore(conjure, "bb_transport_p", function() return true end, function()
      with_restore(conjure, "bb_select_unit", function(cb) cb("{unit}") end, function()
        with_restore(vim, "notify", function(msg) notified = msg end, function() init.select_unit() end)
      end)
    end)
    assert.truthy(notified:find("bb unit set", 1, true))
  end)
end)
