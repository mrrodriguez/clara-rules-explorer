--- Picker: `vim.ui.select` over decoded navigate targets (built-in, no hard
-- Telescope dependency).  0 targets -> notify, 1 -> direct jump, N -> picker.

local M = {}

local function direction_word(direction)
  if direction == "producer" then
    return "producer"
  elseif direction == "consumer" or direction == "type" then
    return "consumer"
  end
  return "target"
end

local function target_label(target)
  local label = target.name or ""
  if target.via == "retract" then label = label .. " (retract)" end
  return label
end

--- Dispatch on target count and jump via `jump_fn(target)`.
function M.choose_or_jump(direction, type_name, targets, jump_fn)
  local n = targets and #targets or 0
  if n == 0 then
    vim.notify("No " .. direction_word(direction) .. " of " .. (type_name or "?"), vim.log.levels.INFO)
    return
  end
  if n == 1 then
    jump_fn(targets[1])
    return
  end
  local items = {}
  for i, t in ipairs(targets) do
    items[i] = target_label(t)
  end
  local prompt = (direction_word(direction):gsub("^%l", string.upper)) .. ": "
  vim.ui.select(items, { prompt = prompt }, function(choice)
    if not choice then return end
    for i, item in ipairs(items) do
      if item == choice then
        jump_fn(targets[i])
        return
      end
    end
  end)
end

return M
