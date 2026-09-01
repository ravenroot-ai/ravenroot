#!/bin/sh
set -eu
LC_ALL=C
export LC_ALL

base_uri=${1:?usage: probe.sh BASE_URI}
token_file=
cleanup() {
  if [ -n "${token_file}" ]; then
    rm -f "${token_file}"
  fi
}
trap cleanup 0
trap 'exit 129' 1
trap 'exit 130' 2
trap 'exit 143' 15

# Shell variables cannot preserve NUL bytes. Materialize stdin privately and validate its raw bytes
# before reading the token into a variable. The only accepted record is one non-empty line whose
# content bytes are ASCII 0x21..0x7e and whose final byte is its single LF terminator.
umask 077
token_file=$(mktemp "${TMPDIR:-/tmp}/ravenroot-openapi-probe.XXXXXX")
cat > "${token_file}"
if ! od -An -v -t u1 "${token_file}" | awk '
  BEGIN { bytes = 0; ended = 0; valid = 1 }
  {
    for (field = 1; field <= NF; field++) {
      octet = $field + 0
      bytes++
      if (ended) valid = 0
      else if (octet == 10) ended = 1
      else if (octet < 33 || octet > 126) valid = 0
    }
  }
  END { exit !(valid && ended && bytes >= 2) }
'; then
  echo 'probe token must be one non-empty newline-terminated line of ASCII 0x21..0x7e' >&2
  exit 2
fi
IFS= read -r token < "${token_file}"
rm -f "${token_file}"
token_file=

# A caller may still have the legacy variable exported. Never pass it, or any other ambient value,
# to curl: the bearer header travels only over curl's standard input.
unset RAVENROOT_OPENAPI_PROBE_TOKEN
clean_curl() {
  env -i PATH="${PATH:-/usr/bin:/bin}" curl "$@"
}

clean_curl -fsS "${base_uri}/ready" >/dev/null
status=$(printf '%s\n' \
  "Authorization: Bearer ${token}" \
  'Content-Type: application/json; charset=utf-8' \
  'X-Trace: container-probe' \
  'Idempotency-Key: openapi-container-probe' | clean_curl -sS -o /dev/null -w '%{http_code}' \
  -H @- \
  --data '{"amount":2}' \
  "${base_uri}/managed/openapi/api/orders/42?verbose=true")
test "${status}" = 202
