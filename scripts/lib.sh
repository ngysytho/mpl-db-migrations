#!/usr/bin/env bash
# Shared small helpers copied into each independent repository; never evaluate dotenv as shell code.
set +x
fail() { printf '%s\n' "$*" >&2; exit 1; }
require() { command -v "$1" >/dev/null 2>&1 || fail "Required tool missing: $1"; }
load_env() {
  local file=$1 line key value
  [[ -f "$file" ]] || fail "Missing environment file: $file"
  while IFS= read -r line || [[ -n "$line" ]]; do
    line=${line%$'\r'}
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" == *=* ]] || fail 'Invalid dotenv line; expected NAME=value'
    key=${line%%=*}; value=${line#*=}
    key="${key#"${key%%[![:space:]]*}"}"; key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"; value="${value%"${value##*[![:space:]]}"}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail 'Invalid dotenv key'
    if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then value=${value:1:${#value}-2}; fi
    if ! declare -p "$key" >/dev/null 2>&1; then export "$key=$value"; fi
  done < "$file"
}
select_java() {
  if [[ -z ${JAVA_HOME:-} && -x /usr/libexec/java_home ]]; then JAVA_HOME=$(/usr/libexec/java_home -v 21); export JAVA_HOME; fi
  JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}java
  command -v "$JAVA_BIN" >/dev/null || fail 'Java 21 is required'
}
expand_home() { case "$1" in '~/'*) printf '%s/%s' "$HOME" "${1#\~/}";; *) printf '%s' "$1";; esac; }
sha256_file() { if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }
# Token is supplied via stdin config, never curl's command-line arguments.
http_get() {
  local url=$1
  [[ ${HTTP_TOKEN:-} != *$'\n'* && ${HTTP_TOKEN:-} != *$'\r'* && ${HTTP_TOKEN:-} != *'"'* ]] || fail 'Invalid token encoding'
  printf 'header = "Authorization: Bearer %s"\n' "${HTTP_TOKEN:-}" |
    curl --config - --silent --show-error --fail --max-time 20 "$url"
}
jwt_claims() { jq -eR 'split(".")[1] | gsub("-";"+") | gsub("_";"/") | @base64d | fromjson'; }
managed_pid() {
  local file=$1 marker=$2 pid command
  [[ -f "$file" ]] || return 1
  read -r pid < "$file"; [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  command=$(ps -p "$pid" -o command= 2>/dev/null) || return 1
  [[ "$command" == *"$marker"* ]] || return 1
  printf '%s' "$pid"
}
stop_managed() {
  local file=$1 marker=$2 pid i
  if pid=$(managed_pid "$file" "$marker"); then
    kill -TERM "$pid"
    for ((i=0;i<30;i++)); do
      if ! managed_pid "$file" "$marker" >/dev/null; then rm -f "$file"; return; fi
      sleep 1
    done
    fail 'Managed process did not stop; inspect it before restarting'
  fi
  rm -f "$file"
}
wait_ready() {
  local url=$1 pidfile=$2 marker=$3 i
  for ((i=0;i<180;i++)); do
    curl -fsS --max-time 2 "$url" >/dev/null 2>&1 && return
    managed_pid "$pidfile" "$marker" >/dev/null || fail 'Managed process stopped; inspect its restricted log'
    sleep 2
  done
  fail 'Readiness timeout; inspect the restricted log'
}
