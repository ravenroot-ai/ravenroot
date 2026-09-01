#!/usr/bin/env sh
set -eu

# This is deliberately a narrow text contract rather than a YAML parser. Helm templates are
# not standalone YAML, and the properties below are literal deployment safety invariants: a writable
# persistent mount, an explicit store path, and no accidental multi-replica/shared-PV posture.
PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)

require_literal() {
  file=$1
  literal=$2
  if ! grep -F -- "$literal" "$file" >/dev/null; then
    echo "Missing deployment-store contract '$literal' in $file" >&2
    exit 1
  fi
}

# Every NEGATIVE check below calls this first because an absent binary can otherwise fail open.
# `rg` exited 127 inside an `if` condition, `set -eu` does not apply
# there, the branch was read as "no match" and the script printed "passed" having checked nothing. A
# missing OPERAND does exactly the same thing -- `grep` exits 2, the `if` reads false, and a
# forbidden-pattern check silently succeeds. Reproduced by renaming the Dockerfile:
#
#   grep: .../Dockerfile: No such file or directory
#   execution-store platform contracts passed.
#   EXIT=0
#
# A positive `require_literal` fails closed on a missing file all by itself (the `!` inverts a
# non-zero exit into an error), so it needs no guard. A negative check cannot, and the reason is NOT
# that the two outcomes share an exit status -- they do not. With /usr/bin/grep
# (BSD grep 2.6.0-FreeBSD), which is what `sh -c 'type grep'` resolves to and therefore what THIS
# script runs:
#
#   match 0 | no match, readable regular file 1 | missing 2 | unreadable 2 | directory 2
#
# What is identical is that an `if` CONDITION reads every non-zero alike, as false. So a negative
# check collapses "the pattern is absent" (1) into "the file could not be read" (2) and calls both a
# pass.
#
# Reading `$?` and comparing it with 1 would separate those two, and that is deliberately NOT what
# this does -- for a reason stronger than portability. Measured against the shell function named
# a recursively configured interactive-shell `grep` function, which
# RECURSES into directories: an empty directory returns 1 and a directory CONTAINING a match returns
# 0. A forbidden-pattern check keyed on the exit code would read that 0 as "the literal is present"
# and fail the gate for a path that contains nothing forbidden. So the discarded approach risks a
# FALSE RED just as readily as a silent pass. A guard that runs BEFORE grep is immune to both and
# needs to know nothing about which grep is in use -- the property worth having in a file whose whole
# history is subtleties about exit statuses.
#
# Both operators are needed and neither is redundant, measured above: `-r` alone admits a DIRECTORY
# (readable, and grep will not search it); `-f` alone admits an unreadable regular file.
require_readable() {
  file=$1
  if [ ! -f "$file" ] || [ ! -r "$file" ]; then
    echo "Cannot read $file as a regular file, so its deployment contract was never checked." >&2
    exit 1
  fi
}

compose="$PROJECT_DIR/compose.yaml"
# Compose is an operational deployment descriptor, so its explanatory comments must
# remain English. The former Italian corpus is represented as SHA-256 fingerprints rather than clear
# text: each fingerprint names one exact removed line without reintroducing that prohibited prose.
# This deliberately exact guard avoids heuristic language detection, which could mistake identifiers,
# product names, or data for prose.
legacy_compose_comment_fingerprints='
079e87f0af237d70090fc18e1372e51f7da00ea45102074b67f1618a1f6fe41c
5ed2dad698bdb4597660b882f9cc3fd2db785629efad56368ef1bd799a937189
3cbedd288d4ab8166c927eb55d54a72d26e1665082f9b5339491ec50a3d29e3d
1534f6eca34c83d16d9eb88286b211726d0e84a4272541c604306df3ffca93bf
e5d2d42c8c3f823f80871106c852bb23782c612671b23fbd821be9bc35cd19de
6c189b12c267b5c0cd40f457fb4be12cefcdcc8093e6e78240c9b0b2c5e3c6ad
7bb235f8800c455cc07a2254207e41604d1eb4407dbed5cd9b26830567d7cffc
f44915980a331ba24ec7f62aebbf757b30bce3d6edef363e2b4b33cc20d9e796
a3292f4d6ae79619d8820ecb9249df3fc01ade81456794ea3dab0587017149d0
03b12b13ade579bd9a5cbc6ee97a2225ece18750e6d2178723ada727a2c94e83
8908fefc59691e56bd1a7c1cff4eaf4a0e95eb3c47b347f08c3b0661f5d46433
2e355c64f2038d3a7c9c222af9bb128e84cfe55409428ffa79edfae6edf4e1eb
272889ef84384b1819aa596aed8bc6b5bc79726d87887b62c3d72bee9310fbf5
1c9d87ce77fcce90401e7152a5f4a90857fafa6516dabaa511832536e8163dd3
bf25b1f92e78f8659ebd5a1a79d8e0a45194b1186f5331c457d86f14f75b843f
ef20f61db6f588bf16965644303a294a5c1f07bf8c92c31c7cb1387b1742a98a
98e1ac741d08ea218ce5d0a88b6898c87320e70caabfd012d5c8320ed29d9e89
d4df3cb24826074b3ffc32649c47c09027b652e8fcc9492f8391bb7b8c61e634
440ef3df71ffa7bd539cbbf08418c00b875f639d470772725304c9747f5b6db5
099be9181249786fe94c929090749089a3ea34c986daa860ec2ee0bdf0199221
7a23515bcdcd46e3e93158aea7bd3767f801a8714a2ccca002447340566aad7b
cfe2b75ec3be9400de6d41f624db91ae20ccb042ce91d0a34fa11f08e59786fc
d3dec8ac25bae3966e57fede8a57fd4dbd8eea939d206d8923b9b853cf25d8e6
fdce9e8b0c40d8c8d85c204b0c8938fd7474444afcc2b48aaf2967c39d832220
00afaebacf11ae00ac9104dd73d8c9cde80da097dfcd3b1de53d293bef40b92c
a04ef225eca23a356567840a23dbd2d713532748e1eac682813be818053e3c38
a5f9b147155e527ec759c8d387076d74ccb654c98dd2f0bfa385bcbde743da78
889620285a5f21ca93d42b4371e8ad628f9e66f40d58501e727506a58589b725
a339ab38a5a038170f922737c1f1696e08a16ad48231059bf702373188a879b8
18a7f31596a72fbc2fb8669701f8e19fdd725ccbf12b3b130c0c964a25503683
32565269e8884b6a3b672263299380a4e0ca933b975ba65d530433cc6c5c3f72
87f8d09dac3dc28b718804dbfd7987c54529cc38e6657d33b1f42e53be145d46
685046242cda87c2df8cb3e88778f4109daefa139b46b51a7a9695b5714489b7
2077aaf92700bebc3c3a2761cc9237270787d480acad6e9fe3b08b80097720a5
ca47d55818c0f831c8a51dd4c8dc376b242e25afe492926452d9f4232e9a9f8e
0f740e2f5721d74f499f9c9e89c65538b668404a3fb461fb531db8041d061f18
9108bf3069d1a54b9fb438728697968ce23f03b51f95aa42c3a8c9afc0843c5b
d0ad336ae8b2c38567f758ad8fa00c123c01ddae8ba8e7f84820ca6c5f9516d6
e0c7537b39cb2aaf444f3332832b556af5e8283257fb8f591dc9c2223ec6d52b
0b4e0b518af8c572b6a4fa0ed4598ee5f7fa9a82c9d0a1789c3893b7d3e1bebb
bd584035d7bbd647c8a9b8cd4aa2d21a80207c122e318be7c31668a6b7c2ca25
6e72571ab2ca34231bb815c58709124abfe92169f1e14f4671494898fd198027
918e4509c933fdbb4462206348d92c85e1ec46e3e74ba114bf465888b1f44e95
a48e4201011b864898287bcb03ca0fd408a5f8eaa5dcf15e2cd7e0d7bbc46e72
0aa55aefadea65f96ab6d5a4bc4ef1d3af5d9f8a968dd0cba1c20205c13c7c38
d86f3f1eae92d8169ec1b4f5e9e1a3676717b49ac442a27bc86163e8075ccfb3
24c426f63cf4d0b2f103e5dfc4963bff2b927fd716a986d92a21f5d0c1d450e1
bef6c3643065b2a4c208eb0f38d25769fa2d149809347e24486dfc5dfa354f30
2e458cf07eab98dfaa18707d63b6b24923ca284335f18b5a801f3614d899170c
ddd76e030689a89f25d0a86e7186ceced22e6a63ca1095fb7fdc5e1ab2c4d894
2336d4f8e879d1d9cf40532c20c4c016d57d1d62945eddb63d20e400dba95b1e
30acf01194a095c4dd48fe2bc825be31a59b4e661d623534c334a88d290b91b1
7616677407db69fb5b7aaad5d4a5cbf44b3954dd1b43d3a8389d546b16a77656
277329cf1b8af6c91dc27b420d3fcbcdee5c9c97de1cc735e1736a636326b1d9
8762066a94d17d2b83200ae2447ce59f1034ea580c82d767896e958659aa1c11
5e5f3bfa3f222a655e4f5583223f136a8399971295e58f9ff95283c7e3e9d47b
ea924bde9011541a1ba0bda3b1bfa4f7e60dcf94050506687127b8686c4bbad1
6b6bc491d7b17218590a0a19e040c6909e43ada9a8c9e5770267bc860f0d4726
297c7f3874d805ec0d320e5e6a6357471a9de192f4eaf389d65aefb2ba8c1a82
93733688993a2ba7d21086a30a280c66e0d719f210d7e6f6ef6bd0ceef3efff0
a6b0a4c4eedd15ce7c3c7d86ef6eeba545f140439709494842876b315ca5dbb3
622fc1386ac397fd23acdbcfd7ef964d18e5b875c015c0b7ea6b55fec5d02b86
8661338a124807d245d48a399ee7b49c5d4e009c07b42b42a345fd90971fed24
7e7cb86cc7913cc2eddd0fecc33594ff245b6dba855a5757a41dd885ba4c5da7
'

legacy_compose_fingerprint_count=64
legacy_compose_fingerprint_set_digest=9c8d9e06bee8acea069188a4a3da8418beb88538ce60866334769e5d45d65176

validate_legacy_compose_fingerprint_corpus() {
  fingerprints=$(printf '%s\n' "$legacy_compose_comment_fingerprints" | awk 'NF')
  count=$(printf '%s\n' "$fingerprints" | wc -l | tr -d ' ')
  if [ "$count" -ne "$legacy_compose_fingerprint_count" ]; then
    echo "Compose legacy-comment fingerprint corpus must contain exactly $legacy_compose_fingerprint_count entries; found $count." >&2
    exit 1
  fi

  unique_count=$(printf '%s\n' "$fingerprints" | LC_ALL=C sort -u | wc -l | tr -d ' ')
  if [ "$unique_count" -ne "$legacy_compose_fingerprint_count" ]; then
    echo "Compose legacy-comment fingerprint corpus must contain $legacy_compose_fingerprint_count unique entries; found $unique_count." >&2
    exit 1
  fi

  set_digest=$(printf '%s\n' "$fingerprints" | LC_ALL=C sort | shasum -a 256 | awk '{print $1}')
  if [ "$set_digest" != "$legacy_compose_fingerprint_set_digest" ]; then
    echo "Compose legacy-comment fingerprint corpus integrity check failed." >&2
    exit 1
  fi
}

forbid_compose_legacy_comment_candidate() {
  candidate=$1
  fingerprint=$(printf '%s' "$candidate" | shasum -a 256 | awk '{print $1}')
  if printf '%s\n' "$legacy_compose_comment_fingerprints" | grep -qx -- "$fingerprint"; then
    echo "compose.yaml must not restore a removed non-English operational comment." >&2
    exit 1
  fi
}

forbid_compose_legacy_comment() {
  remainder=$1
  # A historical fingerprint covers the exact comment content after its marker. For each marker,
  # check the raw suffix and every prefix split across leading horizontal whitespace. This removes
  # only a possible marker separator, one character at a time, and therefore preserves the two
  # content-significant leading spaces in corpus entries 8 and 9. Repeating across every '#' also
  # covers inline comments.
  while :; do
    case "$remainder" in
      *\#*)
        suffix=${remainder#*\#}
        candidate=$suffix
        while :; do
          forbid_compose_legacy_comment_candidate "$candidate"
          case "$candidate" in
            ' '*|'	'*) candidate=${candidate#?} ;;
            *) break ;;
          esac
        done
        remainder=$suffix
        ;;
      *) return 0 ;;
    esac
  done
}

validate_legacy_compose_fingerprint_corpus
require_readable "$compose"
while IFS= read -r line; do
  case "$line" in
    *\#*) forbid_compose_legacy_comment "$line" ;;
  esac
done <"$compose"

require_literal "$compose" 'RAVENROOT_EXECUTION_STORE_DIR: /opt/ravenroot/data/execution-store'
require_literal "$compose" 'RAVENROOT_ARTIFACT_STORE_DIR: /opt/ravenroot/data/artifact-store'
require_literal "$compose" 'ravenroot-audit-data:/opt/ravenroot/data'
require_literal "$compose" 'ravenroot-audit-data: {}'
require_literal "$compose" 'read_only: true'

kubernetes="$PROJECT_DIR/deploy/kubernetes/ravenroot.yaml"
require_literal "$kubernetes" 'replicas: 1'
require_literal "$kubernetes" 'strategy:'
require_literal "$kubernetes" 'type: Recreate'
require_literal "$kubernetes" 'RAVENROOT_EXECUTION_STORE_DIR'
require_literal "$kubernetes" 'RAVENROOT_ARTIFACT_STORE_DIR'
require_literal "$kubernetes" 'value: /opt/ravenroot/data/artifact-store'
require_literal "$kubernetes" 'mountPath: /opt/ravenroot/data'
require_literal "$kubernetes" 'claimName: ravenroot-data'
require_literal "$kubernetes" 'ReadWriteOnce'
require_literal "$kubernetes" 'fsGroup: 10001'
require_literal "$kubernetes" 'readOnlyRootFilesystem: true'

helm_values="$PROJECT_DIR/deploy/helm/ravenroot/values.yaml"
helm_template="$PROJECT_DIR/deploy/helm/ravenroot/templates/deployment.yaml"
helm_pvc="$PROJECT_DIR/deploy/helm/ravenroot/templates/pvc.yaml"
require_literal "$helm_values" 'replicaCount: 1'
require_literal "$helm_values" 'ReadWriteOnce'
require_literal "$helm_values" 'fsGroup: 10001'
require_literal "$helm_template" 'RAVENROOT_EXECUTION_STORE_DIR'
require_literal "$helm_template" 'RAVENROOT_ARTIFACT_STORE_DIR'
require_literal "$helm_template" 'value: /opt/ravenroot/data/artifact-store'
require_literal "$helm_template" 'mountPath: /opt/ravenroot/data'
require_literal "$helm_template" 'strategy:'
require_literal "$helm_template" 'type: Recreate'
require_literal "$helm_pvc" 'kind: PersistentVolumeClaim'
require_literal "$helm_pvc" 'storageClassName:'

# This used to be `rg -q`. ripgrep is not a declared dependency of this repository, and the
# rest of this script uses `grep -F` throughout -- `rg` was the one exception. When `rg` is absent
# the command exits 127; `set -eu` does not apply inside an `if` condition (POSIX shell behaviour,
# not a `set -e` gap), so the branch below was silently skipped and this script printed "passed" and
# exited 0 without ever having looked at the files. `grep -F` with multiple file operands has the
# same "match in any file" -q semantics as `rg -q` did, and grep is already an unconditional,
# unguarded dependency of every `require_literal` call above -- if it were ever missing, this script
# would already have failed loudly before reaching this line.
#
# That reasoning covers the binary, not the operands, and this check has four of them. Three
# carry earlier require_literal assertions that fail closed on a missing file; values.schema.json
# carries none, so a rename or a deletion of it would have left this branch skipped and the script
# green -- the same silent pass described above, one argument over. Guarded explicitly rather than
# left to that reflection.
helm_schema="$PROJECT_DIR/deploy/helm/ravenroot/values.schema.json"
require_readable "$helm_values"
require_readable "$helm_template"
require_readable "$helm_pvc"
require_readable "$helm_schema"
if grep -q -F -- 'existingClaim' "$helm_values" "$helm_template" "$helm_pvc" "$helm_schema"; then
  echo 'Helm must not permit an unverified existing PVC for the local SQLite store.' >&2
  exit 1
fi

# RAVENROOT_ARTIFACT_PROVENANCE=unverified installs a verifier that accepts
# every artifact and checks NOTHING. compose.yaml sets it deliberately and says so -- it declares
# itself "a genuine local-only development mode" -- and that is the one file in this repository that
# may name it. The release image and both production descriptors must leave it unset, so the server's
# fail-closed default is what a real deployment actually starts with.
#
# Why here and NOT in ravenroot-distribution's ReleaseArtifactBoundaryGate (ADR 0017): that gate
# reads the built jar's class graph at `package`,
# and this posture is not in the jar. It is an ENV line in an image and two YAML descriptors, none of
# which that gate can see or is built to read. Same intent, wrong instrument. This script already
# reads exactly these files, and already runs on every PR as platform-config-check.
#
# The forbidden literal is the variable NAME, not the value `unverified`. A text contract cannot bind
# a name to its value across two YAML lines (`name:` / `value:`), and a Helm chart that templated the
# value -- `RAVENROOT_ARTIFACT_PROVENANCE: {{ .Values.provenance }}` -- would slip past a check that
# looked for `unverified`. Forbidding the name costs a deployment nothing: pinning `refusing`
# explicitly buys exactly what leaving it unset already gives.
#
# The first revision of this check defended its bare `grep` in an `if` by arguing that grep is an
# unconditional dependency of every require_literal above, so its absence would fail loudly first.
# That argument is true of the BINARY and says nothing about the OPERAND, which is the half that
# matters here -- and it held for three of these four files only by reflection, because each has an
# earlier positive assertion that fails closed when the file is missing. The Dockerfile has no
# positive assertion anywhere in this script, so it was the one operand nothing covered, and it is
# precisely the file the "release image" half of this invariant rests on. Hence require_readable,
# which does not depend on any other check having run. The RED/GREEN control proving these branches
# actually fire -- including the missing-file case -- is in
# test_execution_store_platform_regression.sh, which platform-config-check runs immediately after
# this script.
forbid_provenance_opt_in() {
  file=$1
  require_readable "$file"
  if grep -q -F -- 'RAVENROOT_ARTIFACT_PROVENANCE' "$file"; then
    echo "SEC-12: $file must not set RAVENROOT_ARTIFACT_PROVENANCE; the unverified opt-in belongs to compose.yaml's local-only development mode and must never ship in a release image or a production descriptor." >&2
    exit 1
  fi
}

forbid_provenance_opt_in "$PROJECT_DIR/Dockerfile"
forbid_provenance_opt_in "$kubernetes"
forbid_provenance_opt_in "$helm_values"
forbid_provenance_opt_in "$helm_template"

echo 'execution-store platform contracts passed.'
