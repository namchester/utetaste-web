#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then python3 scripts/init-env.py; fi
while IFS='=' read -r name value; do
  [[ -z "$name" || "$name" == \#* ]] && continue
  export "$name=$value"
done < .env
exec ./mvnw spring-boot:run
