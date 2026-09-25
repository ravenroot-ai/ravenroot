# MinIO acceptance fixtures

The object-storage acceptance test consumes the two public, immutable image indexes recorded in
[`minio-fixtures.properties`](minio-fixtures.properties). The `project.repository` namespace is
owned by the Ravenroot GitHub organization; neither Java nor CI pulls the archival upstream
repositories directly.

## Recorded provenance

| Role | Upstream payload | Source revision | Packaging revision | Original digest | Project-owned digest | License |
|---|---|---|---|---|---|---|
| Server | MinIO `RELEASE.2025-05-24T17-08-30Z` | `ecde75f9112f8410cb6cacb4b76193f1475b587e` | Bitnami `2025.5.24-debian-12-r5` | `sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c` | `sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c` | AGPL-3.0-only |
| Client | `mc RELEASE.2025-05-21T01-59-54Z` | `f71ad84bcf0fd4369691952af5d925347837dcec` | Bitnami `2025.5.21-debian-12-r4` | `sha256:00dcc4e58ada0df45bb7d9ee435af98295f96c27c3c68292ce78ec700a87b511` | `sha256:00dcc4e58ada0df45bb7d9ee435af98295f96c27c3c68292ce78ec700a87b511` | AGPL-3.0-only |

The Bitnami images embed the corresponding AGPL source offer and full license text under each
application's `/opt/bitnami/<application>/licenses/` directory. The server reports the recorded
source commit from `minio --version`; the client reports its commit from `mc --version`. The
project-owned digests equal the originals because publication copies each complete multi-platform
index and its referenced manifests without rebuilding or rewriting it.

## Verified rotation

1. Select immutable upstream multi-platform indexes. Never start from a mutable tag.
2. On both `linux/amd64` and `linux/arm64`, pull the digest, run the binary's `--version`, and verify
   the release tag and source commit. Inspect the embedded license/source-offer files and the OCI
   packaging labels.
3. Update every provenance field and both digests in `minio-fixtures.properties`. Run
   `python3 scripts/fixtures/minio/verify.py validate` and
   `python3 -m unittest scripts.tests.test_minio_fixture`.
4. Have a repository maintainer dispatch **Publish MinIO acceptance fixtures** for the reviewed
   commit. The workflow copies both complete indexes using `packages: write`, then verifies that
   the expected project-owned digest and every declared architecture are readable. It never accepts
   registry credentials from workflow inputs.
5. For a newly created package, set `ravenroot-minio-fixtures` visibility to public and grant this
   repository administrative package ownership. Ordinary CI deliberately performs anonymous pulls
   with only `contents: read`; public readability is therefore part of the acceptance contract.
6. Remove both project-owned images from a test host, run
   `python3 scripts/fixtures/minio/verify.py preflight --platform linux/amd64 --pull`, then execute
   `mvn -f ravenroot/pom.xml -pl ravenroot-extensions/ravenroot-object-storage -am verify`.
   Dispatch the full CI workflow on the exact candidate commit only after this clean-cache proof.

Do not delete an older digest while a reachable Ravenroot commit still references it. Rotation adds
new immutable content first, verifies it, changes the manifest, and retires old content only after
the repository history no longer depends on its availability.
