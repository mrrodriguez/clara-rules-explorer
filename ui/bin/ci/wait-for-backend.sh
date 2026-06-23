#!/bin/bash

URL=${1:-"http://localhost:9001/v1/rulebase-summary"}
TIMEOUT=${2:-60}
LOG_FILE=${3:-"backend.log"}

echo "Waiting for backend at $URL (timeout: ${TIMEOUT}s)..."

timeout "${TIMEOUT}s" bash -c "until curl -s $URL > /dev/null; do sleep 2; done"

if [ $? -ne 0 ]; then
  echo "Error: Backend failed to start or respond within ${TIMEOUT}s"
  if [ -f "$LOG_FILE" ]; then
    echo "--- Backend Logs ---"
    cat "$LOG_FILE"
  else
    echo "Log file $LOG_FILE not found."
  fi
  exit 1
fi

echo "Backend is up and running."
