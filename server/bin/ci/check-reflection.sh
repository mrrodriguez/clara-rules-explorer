#!/bin/bash
set -e

# This runs the dev alias with *warn-on-reflection* set to true.
# We capture stderr to catch reflection warnings.
echo "Running reflection check on clara.server.graph.api..."

mkdir -p target
LOG_FILE="target/reflection.log"

# We use the :dev alias which now sets *warn-on-reflection* to true.
clojure -M:dev -e "(require 'clara.server.graph.api :reload)" 2>&1 | tee "$LOG_FILE"

# We only want to fail if there are reflection warnings in OUR code (clara/server/...)
if grep "Reflection warning" "$LOG_FILE" | grep -q "clara/server/"; then
  echo "Error: Reflection warnings detected in project code!"
  grep "Reflection warning" "$LOG_FILE" | grep "clara/server/"
  exit 1
fi

echo "Reflection check passed (no warnings in project code)."
