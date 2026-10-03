#!/usr/bin/env bash
#
# §9.2 / §11 Days 13-14: drive the Stagecraft compatibility probe over the matrix.
#
# The probe itself is `./gradlew compatProbe` (see CompatProbe.kt); this script only runs it over a
# list of cells and reports a table. Credentials come from the environment, never the command line:
#
#   STAGECRAFT_USER=admin STAGECRAFT_TOKEN=... ./tools/compat-matrix.sh tools/cells.example.txt
#
# A cells file has one cell per line: `name <TAB> baseUrl <TAB> buildUrl` (buildUrl may be empty).
# Lines starting with `#` and blank lines are ignored.
#
# The matrix is 5 Jenkins rows x 5 configuration columns (password auth is n/a per the charter):
#
#   rows:     2.204.x LTS | 2.319.x LTS | 2.414.x LTS | 2.479.x LTS | newest LTS
#   columns:  csrf-off | csrf-on | https | behind-proxy | token
#
# Docker helpers for the two containers the Day-0 work already had:
#   2.541.3 -> :18080   2.479.3 -> :38080
# (See docs/compat-matrix.md for the full command and the 2.204.x decision.)

set -uo pipefail

CELLS="${1:-tools/cells.example.txt}"
if [[ ! -f "$CELLS" ]]; then
  echo "usage: STAGECRAFT_USER=... STAGECRAFT_TOKEN=... $0 <cells-file>" >&2
  exit 2
fi

if [[ -z "${STAGECRAFT_USER:-}" ]]; then
  echo "set STAGECRAFT_USER and STAGECRAFT_TOKEN (or STAGECRAFT_PASSWORD) first" >&2
  exit 2
fi

failures=0
total=0
while IFS=$'\t' read -r name base build; do
  [[ -z "${name// }" || "${name:0:1}" == "#" ]] && continue
  total=$((total + 1))
  echo "=== cell: ${name} (${base}) ==="
  args="--base=${base}"
  [[ -n "${build// }" ]] && args="${args} --build=${build}"
  if ./gradlew --quiet compatProbe --args="${args}"; then
    echo "--- ${name}: PASS"
  else
    echo "--- ${name}: FAIL"
    failures=$((failures + 1))
  fi
done < "$CELLS"

echo
echo "matrix: $((total - failures))/${total} cell(s) passed"
exit $((failures == 0 ? 0 : 1))
