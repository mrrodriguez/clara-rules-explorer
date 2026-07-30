.PHONY: test lint clean reflection-check format format-check

SHELL := /bin/bash

test:
	clojure -M:test:run-tests

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
