#!/usr/bin/env bash
# One-command on-sale stampede: ./burst.sh <BASE_URL> [--requests N] [--concurrency N] [--admin-key KEY] ...
# Uses a local JDK 21+ if present, otherwise runs the same script inside a JDK container.
set -euo pipefail
cd "$(dirname "$0")"
if [ $# -lt 1 ]; then
  echo "usage: ./burst.sh <BASE_URL> [--requests 20000] [--hot-seats 5] [--per-hot-seat 500] [--concurrency 1000] [--admin-key KEY]" >&2
  exit 2
fi
if command -v java >/dev/null 2>&1 && [ "$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p')" -ge 21 ]; then
  exec java burst/Burst.java "$@"
fi
echo "(no JDK 21+ found, running in eclipse-temurin:21-jdk container)" >&2
exec docker run --rm --network host -e ADMIN_KEY -v "$PWD/burst:/burst:ro" eclipse-temurin:21-jdk java /burst/Burst.java "$@"
