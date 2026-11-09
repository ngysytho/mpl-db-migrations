#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source "$ROOT/scripts/lib.sh"
if [[ ${1:-} == --env-file ]]; then load_env "${2:?Missing env file}"; shift 2; fi
[[ $# -ge 1 ]] || fail 'Usage: migrate.sh [--env-file FILE] validate|status|updateSQL|update|verify|verify-history|rollbackSQL|rollback [COUNT]'
select_java
JAR="$ROOT/target/mpl-db-migrations-1.0.0-runner.jar"
[[ -f "$JAR" ]] || fail 'Build migration artifact first: ./mvnw -B -ntp package'
exec "$JAVA_BIN" -jar "$JAR" "$@"
