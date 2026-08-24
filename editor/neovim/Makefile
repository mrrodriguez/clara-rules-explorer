.PHONY: test format format-check lint check clean

NVIM ?= nvim
STYLUA ?= stylua
SELENE ?= selene

# Tier 1 (pure Lua) + Tier 2 (mocked transport), headless, no user config.
# Tree-sitter structural cases skip cleanly when the clojure parser is absent.
test:
	@$(NVIM) --headless --noplugin -i NONE -u test/minimal_init.lua \
	  -c "lua require('plenary.test_harness').test_directory('test', { minimal_init = 'test/minimal_init.lua' })"

format:
	@$(STYLUA) lua plugin test

format-check:
	@$(STYLUA) --check lua plugin test

lint:
	@$(SELENE) lua plugin test

# One-command gate — run before committing / after edits: format-check + lint + test.
check: format-check lint test

clean:
	@rm -rf target
	@echo "cleaned editor/neovim/target"
