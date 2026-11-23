--- Fact type provenance view: a split buffer rendering the backend's
-- `:fact-type` / `:production` / `:message` tree.
--
-- Thin by design (progress §3): it gathers the cursor context, builds the
-- operation input, calls `client/fact-type-provenance` (nREPL) or
-- `--provenance` (bb), caches returned children, and renders the contract's
-- fields.  It never reads analysis data, computes closures, bridges, cycles, or
-- short fact-type names — `:type-short`, `:matches[].short`, and `:bridge?`
-- arrive from the backend.
--
-- Paths alternate fact-type and production names, so the same production under
-- two sibling fact types has two paths and toggles independently.  A path is a
-- list of node keys; `path_key` joins it into the table key the state maps use.

local conjure = require("clara-explorer.conjure")
local edn = require("clara-explorer.edn")
local jump = require("clara-explorer.jump")

local M = {}

local BUFFER_NAME = "clara-explorer://provenance"

local ns_id = vim.api.nvim_create_namespace("clara-explorer-provenance")

--- Per-buffer view state, keyed by buffer number.
-- direction       `"producer"` | `"consumer"`
-- root            the root `:node` of the rendered tree
-- input           the root operation input, for `R`
-- origin_bufnr    the buffer whose REPL answers (see `conjure.eval_edn`)
-- expanded        path key -> true for open production rows
-- children        path key -> children fetched after the root
-- nodes           path key -> node, rebuilt on every render
-- line_paths      line number -> path key, rebuilt on every render
local views = {}

local function path_key(path) return table.concat(path, "\0") end

--- The path component for NODE: its fact-type name, production name, or message
--- text.
function M.node_key(node)
  if node.kind == "fact-type" then return node.type end
  if node.kind == "production" then return node.name end
  return node.text or "?"
end

--- A display label as a list of `{text, group}` chunks.  The renderer
--- concatenates them and applies one extmark per grouped run, so dimmed parts
--- follow the user's colorscheme (`Comment` is the conventional dim group).
local function chunk(text, group) return { text or "", group } end

--- The retract aside for an edge that is not insert-only, or nil.
local function vias_chunk(node)
  local vias = node.vias
  if type(vias) == "table" and #vias > 1 then return chunk(" (insert+retract)", "WarningMsg") end
  if (type(vias) == "table" and vias[1] == "retract") or node.via == "retract" then
    return chunk(" (retract)", "WarningMsg")
  end
  return nil
end

--- The `via <short types>` suffix for a production that reaches the parent type
--- through the hierarchy; nil when the edge is direct.
local function matches_chunk(node)
  if not node["bridge?"] then return nil end
  local matches = node.matches
  if type(matches) ~= "table" or #matches == 0 then return nil end
  local shorts = {}
  for _, match in ipairs(matches) do
    shorts[#shorts + 1] = match.short or match.name
  end
  return chunk(" via " .. table.concat(shorts, ", "), "Comment")
end

--- NODE's chunks: the contract's short type, dimmed full type, and markers.
local function fact_type_chunks(node)
  local short = node["type-short"]
  local full = node.type
  local out = {}
  if short and full and short ~= full then
    out[#out + 1] = chunk(short)
    out[#out + 1] = chunk(" " .. full, "Comment")
  else
    out[#out + 1] = chunk(short or full or "?")
  end
  if node["external?"] then out[#out + 1] = chunk(" (external)", "Comment") end
  local vias = vias_chunk(node)
  if vias then out[#out + 1] = vias end
  return out
end

--- The unqualified part of a fully-qualified production NAME.
local function unqualified_name(name) return (name or ""):match("/([^/]+)$") or name end

--- NODE's chunks: unqualified rule name, dimmed ns, unit, and markers.
-- EXPANDED suppresses `shown above` on a row that is open here.
local function production_chunks(node, direction, expanded)
  local out = { chunk(unqualified_name(node.name), "Identifier") }
  if node.ns then out[#out + 1] = chunk(" " .. node.ns, "Comment") end
  if node.type == "query" then out[#out + 1] = chunk(" (query)", "Comment") end
  if node.unit then out[#out + 1] = chunk(" @" .. node.unit, "Comment") end
  local leaf = type(node.children) == "table" and #node.children == 0
  if node.outputs then
    out[#out + 1] = chunk(" (outputs unknown)", "Comment")
  elseif leaf and direction == "consumer" and node.type ~= "query" then
    out[#out + 1] = chunk(" (no outputs)", "Comment")
  end
  local matches = matches_chunk(node)
  if matches then out[#out + 1] = matches end
  local vias = vias_chunk(node)
  if vias then out[#out + 1] = vias end
  if node["cycle?"] then out[#out + 1] = chunk(" ↻ cycle", "Comment") end
  if node["seen?"] and not expanded then out[#out + 1] = chunk(" ↑ shown above", "Comment") end
  return out
end

--- NODE's label chunks for the row at KEY.
local function row_chunks(node, state, key)
  if node.kind == "fact-type" then return fact_type_chunks(node) end
  if node.kind == "production" then return production_chunks(node, state.direction, state.expanded[key]) end
  return { chunk(node.text or "?", "Comment") }
end

--- True when NODE carries a non-empty `:children` list.
local function has_children(node) return type(node.children) == "table" and #node.children > 0 end

--- Mark expanded — and cache — every production below NODE whose children came
--- populated, so a `:depth N` / `:full` response renders expanded with no fetch.
local function seed(state, node, path)
  for _, child in ipairs(node.children or {}) do
    local child_path = vim.list_extend({}, path)
    child_path[#child_path + 1] = M.node_key(child)
    local key = path_key(child_path)
    if child.kind == "production" and has_children(child) then
      state.children[key] = child.children
      state.expanded[key] = true
    end
    seed(state, child, child_path)
  end
end

--- The child nodes to render under NODE at KEY.  Fact types are transparent; a
--- production's children show only while it is expanded, from the cache when the
--- level was fetched later.
local function visible_children(state, node, key)
  if node.kind == "fact-type" then return node.children or {} end
  if node.kind == "production" then
    if not state.expanded[key] then return {} end
    return state.children[key] or node.children or {}
  end
  return {}
end

--- Rows for NODE at PATH, depth-first, recording the path -> node map.
local function collect_rows(state, node, path, indent, rows)
  local key = path_key(path)
  state.nodes[key] = node
  rows[#rows + 1] = { key = key, indent = indent, chunks = row_chunks(node, state, key) }
  for _, child in ipairs(visible_children(state, node, key)) do
    local child_path = vim.list_extend({}, path)
    child_path[#child_path + 1] = M.node_key(child)
    collect_rows(state, child, child_path, indent + 1, rows)
  end
end

--- The buffer's lines and highlight runs for STATE's current tree.
local function render_rows(state)
  local rows = {}
  collect_rows(state, state.root, { M.node_key(state.root) }, 0, rows)
  local lines, marks, line_paths = {}, {}, {}
  for i, row in ipairs(rows) do
    local text = string.rep("  ", row.indent)
    local col = #text
    for _, part in ipairs(row.chunks) do
      local piece = part[1]
      if part[2] and piece ~= "" then
        marks[#marks + 1] = { row = i - 1, start = col, finish = col + #piece, group = part[2] }
      end
      text = text .. piece
      col = col + #piece
    end
    lines[i] = text
    line_paths[i] = row.key
  end
  return lines, marks, line_paths
end

--- Reprint STATE's tree in buffer BUFNR.
function M.render(bufnr)
  local state = views[bufnr]
  if not state or not vim.api.nvim_buf_is_valid(bufnr) then return end
  local lines, marks, line_paths = render_rows(state)
  if #lines == 0 then lines = { "" } end
  vim.api.nvim_set_option_value("modifiable", true, { buf = bufnr })
  vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, lines)
  vim.api.nvim_buf_clear_namespace(bufnr, ns_id, 0, -1)
  for _, mark in ipairs(marks) do
    vim.api.nvim_buf_set_extmark(bufnr, ns_id, mark.row, mark.start, {
      end_col = mark.finish,
      hl_group = mark.group,
    })
  end
  vim.api.nvim_set_option_value("modifiable", false, { buf = bufnr })
  state.line_paths = line_paths
end

--- A leaf node carrying TEXT, used for transport and backend errors.
local function message_node(text) return { kind = "message", text = text or "unknown error" } end

--- The `:node` of a decoded provenance answer, or a message node.
local function result_node(result, err)
  if err then return message_node(err) end
  if type(result) ~= "table" then return message_node("no result from fact-type-provenance") end
  if result.error then return message_node(result.error) end
  return result.node or message_node("no :node in fact-type-provenance result")
end

--- Post INPUT to the provenance operation and call `cb(node)`.  Both an
--- `{:error …}` answer and a transport failure become `:message` nodes, so a
--- failed root call or expansion never breaks the view.
function M.request(input, origin_bufnr, cb)
  conjure.with_transport(function(transport)
    conjure.debug_log("provenance transport=" .. tostring(transport))
    if transport == "bb" then
      conjure.bb_selection(function(selection)
        if not selection then
          cb(message_node("clara-explorer: no registry selection"))
          return
        end
        conjure.bb_eval(
          selection,
          conjure.provenance_input(input),
          function(result, err) cb(result_node(result, err)) end,
          { flag = "--provenance" }
        )
      end)
      return
    end
    conjure.eval_edn({
      code = conjure.provenance_code(input),
      origin_bufnr = origin_bufnr,
      passive = true,
      on_value = function(value)
        local ok, decoded = pcall(edn.decode, value)
        if not ok then
          cb(message_node("clara-explorer: provenance returned invalid EDN"))
          return
        end
        cb(result_node(decoded, nil))
      end,
      on_error = function(msg) cb(message_node(msg)) end,
    })
  end)
end

--- The configured initial depth: `0` (default), a non-negative integer, or
-- `"full"` (sent as `:depth :full`).
function M.depth()
  local configured = vim.g.clara_explorer_provenance_depth
  if configured == nil or configured == "" then return 0 end
  if configured == "full" then return "full" end
  local n = tonumber(configured)
  if n and n >= 0 then return math.floor(n) end
  return 0
end

--- Production names strictly above the production at PATH, minus the row
-- itself.  Node kinds decide this, not path parity, so it holds for any root.
local function production_ancestors(state, path)
  local out = {}
  for i = 1, #path - 2 do
    local node = state.nodes[path_key(vim.list_slice(path, 1, i))]
    if node and node.kind == "production" then out[#out + 1] = node.name end
  end
  return out
end

--- True when NODE can fetch another level: not a cycle, not outputs-unknown,
-- not a known leaf.
local function expandable(node) return not node["cycle?"] and not node.outputs and node.children == nil end

--- The node on the cursor's row, plus its path key, or nil.
local function cursor_row(state, bufnr)
  local row = vim.api.nvim_win_get_cursor(0)[1]
  if vim.api.nvim_get_current_buf() ~= bufnr then
    local win = vim.fn.bufwinid(bufnr)
    if win == -1 then return nil end
    row = vim.api.nvim_win_get_cursor(win)[1]
  end
  local key = state.line_paths[row]
  return key, key and state.nodes[key]
end

--- Expand or collapse the production on the cursor's row.
function M.toggle(bufnr)
  local state = views[bufnr]
  if not state then return end
  local key, node = cursor_row(state, bufnr)
  if not key then return end
  if not node then
    vim.notify("clara-explorer: stale row; press R to refresh the view", vim.log.levels.INFO)
    return
  end
  if node.kind == "message" then
    vim.notify(node.text, vim.log.levels.INFO)
    return
  end
  if node.kind ~= "production" then
    vim.notify("clara-explorer: fact types stay expanded; expand a production", vim.log.levels.INFO)
    return
  end
  if state.expanded[key] then
    state.expanded[key] = nil
    M.render(bufnr)
    return
  end
  if not expandable(node) then
    vim.notify("clara-explorer: nothing to expand", vim.log.levels.INFO)
    return
  end
  local cached = state.children[key]
  local cached_error = cached and #cached == 1 and cached[1].kind == "message"
  if cached and not cached_error then
    state.expanded[key] = true
    M.render(bufnr)
    return
  end
  local path = vim.split(key, "\0", { plain = true })
  local input = {
    node = { kind = "production", name = node.name },
    direction = state.direction,
    depth = 0,
    ancestors = production_ancestors(state, path),
  }
  M.request(input, state.origin_bufnr, function(fetched)
    if not vim.api.nvim_buf_is_valid(bufnr) then return end
    if fetched.kind == "message" then
      state.children[key] = { fetched }
    else
      state.children[key] = fetched.children or {}
      seed(state, fetched, path)
    end
    state.expanded[key] = true
    M.render(bufnr)
  end)
end

--- Jump to the production on the cursor's row.  Every node — `:var?` or not —
--- is located through the REPL the view was opened from, so a production from
--- another repo is reachable whether or not its namespace is loaded.  A failed
--- locate changes no window and pushes nothing.
function M.visit(bufnr)
  local state = views[bufnr]
  if not state then return end
  local key, node = cursor_row(state, bufnr)
  if not key then return end
  if not node then
    vim.notify("clara-explorer: stale row; press R to refresh the view", vim.log.levels.INFO)
    return
  end
  if node.kind ~= "production" then
    vim.notify("clara-explorer: not a production row (RET jumps from a production row)", vim.log.levels.INFO)
    return
  end
  local target = { name = node.name, ns = node.ns }
  jump.locate(target, { origin_bufnr = state.origin_bufnr }, function(location, reason)
    if not location then
      vim.notify(reason or "clara-explorer: cannot locate production", vim.log.levels.WARN)
      return
    end
    jump.show(location, target)
  end)
end

--- Re-run the root call from the stored input.
function M.refresh(bufnr)
  local state = views[bufnr]
  if not state or not state.input then return end
  M.request(state.input, state.origin_bufnr, function(node)
    if not vim.api.nvim_buf_is_valid(bufnr) then return end
    M.load(bufnr, state.input, node)
  end)
end

--- Close the view.
function M.close(bufnr)
  local win = vim.fn.bufwinid(bufnr)
  if win ~= -1 and #vim.api.nvim_tabpage_list_wins(0) > 1 then vim.api.nvim_win_close(win, true) end
end

--- Reset STATE to a fresh tree rooted at NODE and reprint it.
function M.load(bufnr, input, node)
  local state = views[bufnr]
  if not state then return end
  state.root = node
  state.input = input
  state.direction = input.direction
  state.expanded = {}
  state.children = {}
  state.nodes = {}
  seed(state, node, { M.node_key(node) })
  M.render(bufnr)
end

--- The view state for BUFNR, or nil.  Exposed for `provenance_spec.lua` (and
-- for debugging a live view) so a test can assert on the path-keyed tables
-- without guessing at buffer text.
function M.view_state(bufnr) return views[bufnr] end

--- Create (or reuse) the view buffer, its options, and its keymaps.
local function ensure_buffer()
  local existing = vim.fn.bufnr(BUFFER_NAME)
  local bufnr = existing ~= -1 and existing or vim.api.nvim_create_buf(false, true)
  if existing == -1 then vim.api.nvim_buf_set_name(bufnr, BUFFER_NAME) end
  vim.api.nvim_set_option_value("buftype", "nofile", { buf = bufnr })
  vim.api.nvim_set_option_value("bufhidden", "wipe", { buf = bufnr })
  vim.api.nvim_set_option_value("swapfile", false, { buf = bufnr })
  vim.api.nvim_set_option_value("modifiable", false, { buf = bufnr })
  vim.api.nvim_set_option_value("filetype", "clara-provenance", { buf = bufnr })
  views[bufnr] = views[bufnr]
    or {
      direction = nil,
      root = nil,
      input = nil,
      origin_bufnr = nil,
      expanded = {},
      children = {},
      nodes = {},
      line_paths = {},
    }
  -- Global `<Plug>` targets, so a user can rebind any view key.
  vim.keymap.set("n", "<Plug>(ClaraExplorerProvenanceToggle)", function() M.toggle(bufnr) end, {
    buffer = bufnr,
    desc = "clara-explorer: expand/collapse a provenance row",
  })
  vim.keymap.set("n", "<Plug>(ClaraExplorerProvenanceVisit)", function() M.visit(bufnr) end, {
    buffer = bufnr,
    desc = "clara-explorer: jump to the production on this row",
  })
  vim.keymap.set("n", "<Plug>(ClaraExplorerProvenanceRefresh)", function() M.refresh(bufnr) end, {
    buffer = bufnr,
    desc = "clara-explorer: re-run the provenance root call",
  })
  vim.keymap.set("n", "<Plug>(ClaraExplorerProvenanceClose)", function() M.close(bufnr) end, {
    buffer = bufnr,
    desc = "clara-explorer: close the provenance view",
  })
  vim.keymap.set("n", "<Tab>", "<Plug>(ClaraExplorerProvenanceToggle)", { buffer = bufnr })
  vim.keymap.set("n", "<CR>", "<Plug>(ClaraExplorerProvenanceVisit)", { buffer = bufnr })
  vim.keymap.set("n", "R", "<Plug>(ClaraExplorerProvenanceRefresh)", { buffer = bufnr })
  vim.keymap.set("n", "q", "<Plug>(ClaraExplorerProvenanceClose)", { buffer = bufnr })
  return bufnr
end

--- Show BUFNR: reuse the window already showing it, else split.
local function show_buffer(bufnr)
  local win = vim.fn.bufwinid(bufnr)
  if win ~= -1 then
    vim.api.nvim_set_current_win(win)
  else
    vim.cmd("sbuffer " .. bufnr)
  end
end

--- Open the provenance view for the fact type at the cursor, walking DIRECTION.
-- `direction` is `"producer"` (upstream) or `"consumer"` (downstream); a cursor
-- inside a `defquery` is allowed, unlike consumer *navigate*.
function M.open(direction)
  if not conjure.connected() then
    vim.notify("Not connected to a Conjure Clojure REPL", vim.log.levels.WARN)
    return
  end
  local bufnr = vim.api.nvim_get_current_buf()
  local win = vim.api.nvim_get_current_win()
  local crow, ccol = unpack(vim.api.nvim_win_get_cursor(0))
  -- `init.context` is the shared gatherer; requiring it lazily keeps this module
  -- loadable without the command dispatcher.
  local ctx = require("clara-explorer").context(bufnr, crow - 1, ccol)
  conjure.debug_log("provenance open direction=" .. tostring(direction) .. " ctx=" .. vim.inspect(ctx))
  if not ctx.token then
    vim.notify("not on a fact type", vim.log.levels.INFO)
    return
  end
  conjure.resolve_token({
    caller_ns = ctx.caller_ns,
    token = ctx.token,
    bufnr = bufnr,
    win = win,
    on_resolved = function(fq)
      local input = {
        node = {
          kind = "cursor",
          input = {
            production = ctx.production,
            -- Structural side only: the traversal direction is `direction`.
            side = ctx.side,
            caller_ns = ctx.caller_ns,
            token = fq or ctx.token,
          },
        },
        direction = direction,
        depth = M.depth(),
        ancestors = {},
      }
      M.open_root(input, bufnr)
    end,
  })
end

--- Open the view for a fully built root INPUT, asking ORIGIN_BUFNR's REPL.
function M.open_root(input, origin_bufnr)
  local bufnr = ensure_buffer()
  local state = views[bufnr]
  state.origin_bufnr = origin_bufnr
  state.direction = input.direction
  state.input = input
  state.root = message_node("querying…")
  M.render(bufnr)
  show_buffer(bufnr)
  M.request(input, origin_bufnr, function(node)
    if not vim.api.nvim_buf_is_valid(bufnr) then return end
    M.load(bufnr, input, node)
  end)
end

vim.api.nvim_create_autocmd("BufWipeout", {
  callback = function(args) views[args.buf] = nil end,
})

return M
