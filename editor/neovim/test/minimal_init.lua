-- Minimal init for plenary tests: adds plenary.nvim and this plugin to
-- `runtimepath`, loads nothing else.  Runs under `nvim --headless --noplugin`
-- (no user config).  Does not hard-code any machine-specific path: plenary is
-- located via `PLENARY_DIR` or the standard package-manager data dirs.

local function find_plenary()
  local candidates = {
    os.getenv("PLENARY_DIR"),
    vim.fn.stdpath("data") .. "/lazy/plenary.nvim",
    vim.fn.stdpath("data") .. "/site/pack/packer/start/plenary.nvim",
    vim.fn.stdpath("data") .. "/plugged/plenary.nvim",
  }
  for _, dir in pairs(candidates) do
    if dir and dir ~= "" and vim.fn.isdirectory(dir) == 1 then return dir end
  end
  error("plenary.nvim not found — set PLENARY_DIR or install plenary.nvim")
end

local this_file = debug.getinfo(1, "S").source:sub(2)
local test_dir = vim.fn.fnamemodify(this_file, ":h")
local plugin_root = vim.fn.fnamemodify(test_dir, ":h")

vim.opt.runtimepath:prepend(plugin_root)
vim.opt.runtimepath:append(find_plenary())

-- Avoid sandbox writes for swap/sha files during headless runs.
vim.opt.swapfile = false
vim.opt.shadafile = "NONE"
