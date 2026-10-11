#!/usr/bin/env bash
set -euo pipefail
command -v node >/dev/null || { echo 'Install Node.js 22.12+ first.' >&2; exit 1; }
exec node "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/dev.mjs" start history "$@"
