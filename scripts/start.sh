#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then python3 scripts/init-env.py; fi
env_line_number=0
invalid_env() {
  printf 'Invalid .env format at line %d.\n' "$env_line_number" >&2
  exit 1
}
while IFS= read -r env_entry || [[ -n "$env_entry" ]]; do
  ((env_line_number+=1))
  env_entry="${env_entry%$'\r'}"
  if ((env_line_number == 1)); then env_entry="${env_entry#$'\xef\xbb\xbf'}"; fi
  [[ "$env_entry" =~ ^[[:space:]]*(#|$) ]] && continue
  [[ "$env_entry" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=[[:space:]]*(.*)$ ]] || invalid_env
  env_name="${BASH_REMATCH[2]}"
  env_value="${BASH_REMATCH[3]}"
  env_value="${env_value%"${env_value##*[![:space:]]}"}"
  env_quote="${env_value:0:1}"
  if [[ "$env_quote" == "'" || "$env_quote" == '"' ]]; then
    env_rest="${env_value:1}"
    [[ "$env_rest" == *"$env_quote"* ]] || invalid_env
    env_value="${env_rest%%"$env_quote"*}"
    env_suffix="${env_rest#*"$env_quote"}"
    env_suffix="${env_suffix#"${env_suffix%%[![:space:]]*}"}"
    [[ -z "$env_suffix" || "$env_suffix" == \#* ]] || invalid_env
  else
    env_value="${env_value%%[[:space:]]#*}"
    env_value="${env_value%"${env_value##*[![:space:]]}"}"
  fi
  export "$env_name=$env_value"
done < .env
exec ./mvnw spring-boot:run
