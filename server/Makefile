.PHONY: test lint clean reflection-check format format-check regen-fixture

SHELL := /bin/bash

test:
	clojure -M:test:run-tests

# Regenerate test-resources/.../loan-doc-rules-annotations.edn (the
# annotation layer fixture used by core/api/smoke/source-sink tests).
regen-fixture:
	clojure -M:test -i dev/regen_fixture.clj -e nil

lint:
	clojure -M:lint --lint src test dev

format:
	cljfmt fix src test dev

format-check:
	cljfmt check src test dev

clean:
	rm -rf target .cpcache

reflection-check:
	./bin/ci/check-reflection.sh
