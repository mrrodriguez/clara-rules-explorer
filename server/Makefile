.PHONY: test lint clean reflection-check format format-check regen-fixture demo-setup demo-run hierarchy-run

SHELL := /bin/bash

test:
	clojure -M:test:run-tests

# Serialize the demo session (loan-app rules) to demo-data/session.bin.
demo-setup:
	clojure -M:demo-setup

# Start the demo explorer server (loan-app rules session) on port 9001.
demo-run:
	clojure -M:demo-run -s demo-data/session.bin \
	  --annotations '{:enrichment :auto-detect}' \
	  -l test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn

# Start the loan-hierarchy-rules explorer server (keyword hierarchy, tuple
# fact types) on port 9201 — used by the hierarchy e2e project and for
# exploring hierarchy-specific features.  Override the port with PORT=NNNN.
hierarchy-run:
	clojure -M:hierarchy-run $(PORT)

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
