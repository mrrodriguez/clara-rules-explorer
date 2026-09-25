--- Tier 2: babashka transport — the `bb editor_client.bb` shell-out executor,
-- registry-selection prompt/cache, and the bb-mode dispatch in `init.lua`
-- (navigate / refresh / swap-session / toggle / select-unit). Mirrors the
-- elisp babashka-transport tests.

local conjure = require("clara-explorer.conjure")
local init = require("clara-explorer.init")

local function with_restore(tbl, key, stub, fn)
  local orig = tbl[key]
  tbl[key] = stub
  local ok, err = pcall(fn)
  tbl[key] = orig
  assert(ok, err)
end

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
  it("defaults to nrepl", function()
    with_restore(vim.g, "clara_explorer_transport", nil, function()
      assert.are.same("nrepl", conjure.transport())
      assert.is_false(conjure.bb_transport_p())
    end)
  end)

  it("reflects the configured transport", function()
    with_restore(vim.g, "clara_explorer_transport", "bb", function()
      assert.are.same("bb", conjure.transport())
      assert.is_true(conjure.bb_transport_p())
    end)
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
  it("strips the root prefix and sorts", function()
    local function fake_fnamemodify(p, mods)
      if mods == ":p" then return p end
      if mods == ":p:h" then return p:match("^(.*)/[^/]+$") or "" end
      return p
    end
    with_restore(vim.fn, "fnamemodify", fake_fnamemodify, function()
      with_restore(
        vim.fn,
        "globpath",
        function(root, _pattern)
          assert.are.same("/root", root)
          return {
            "/root/loan-app-ruleset/rules-inspect-manifest.edn",
            "/root/composed/loan-app-plus-disposition/rules-inspect-manifest.edn",
            "/root/loan-disposition-ruleset/rules-inspect-manifest.edn",
          }
        end,
        function()
          assert.are.same(
            { "composed/loan-app-plus-disposition", "loan-app-ruleset", "loan-disposition-ruleset" },
            conjure.bb_list_unit_repos("/root")
          )
        end
      )
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
    with_restore(conjure, "connected", function() return true end, function()
      with_restore(conjure, "bb_transport_p", function() return true end, function()
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
    end)
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
    with_restore(conjure, "bb_transport_p", function() return true end, function()
      with_restore(conjure, "connected", function() error("must not be called") end, function()
        with_restore(vim, "notify", function(msg) notified = msg end, function() init.refresh() end)
      end)
    end)
    assert.truthy(notified:find("no-op", 1, true))
  end)

  it("swap_session is a no-op in bb mode", function()
    local notified
    with_restore(conjure, "bb_transport_p", function() return true end, function()
      with_restore(conjure, "connected", function() error("must not be called") end, function()
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
