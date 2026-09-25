# MinIO acceptance fixtures

The object-storage acceptance test consumes the two repository-linked, immutable image indexes recorded in
[`minio-fixtures.properties`](minio-fixtures.properties). The `project.repository` namespace is
owned by the Ravenroot GitHub organization and linked to this source repository through OCI source
metadata. Neither Java nor CI pulls the archival upstream repositories directly. GitHub Actions
reads the package with its built-in token and only `packages: read`; no private credential is needed.

## Recorded provenance

| Role | Upstream payload | Source revision | Packaging revision | Original digest | Project-owned digest | License |
|---|---|---|---|---|---|---|
| Server | MinIO `RELEASE.2025-05-24T17-08-30Z` | `ecde75f9112f8410cb6cacb4b76193f1475b587e` | Bitnami `2025.5.24-debian-12-r5` | `sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c` | `PENDING` | AGPL-3.0-only |
| Client | `mc RELEASE.2025-05-21T01-59-54Z` | `f71ad84bcf0fd4369691952af5d925347837dcec` | Bitnami `2025.5.21-debian-12-r4` | `sha256:00dcc4e58ada0df45bb7d9ee435af98295f96c27c3c68292ce78ec700a87b511` | `PENDING` | AGPL-3.0-only |

The Bitnami images embed the corresponding AGPL source offer and full license text under each
application's `/opt/bitnami/<application>/licenses/` directory. The server reports the recorded
source commit from `minio --version`; the client reports its commit from `mc --version`. The
project-owned images add only deterministic OCI provenance metadata to the exact digest-pinned base.
That wrapper metadata links the GHCR package to this repository, so the project digest intentionally
differs from the original upstream digest.

## Verified rotation

1. Select immutable upstream multi-platform indexes. Never start from a mutable tag.
2. On both `linux/amd64` and `linux/arm64`, pull the digest, run the binary's `--version`, and verify
   the release tag and source commit. Inspect the embedded license/source-offer files and the OCI
   packaging labels.
3. Update every provenance field, set both project digests to `PENDING`, and run
   `python3 scripts/fixtures/minio/verify.py validate --allow-pending-project-digests` and
   `python3 -m unittest scripts.tests.test_minio_fixture`.
4. Have a repository maintainer dispatch **Publish MinIO acceptance fixtures** for the reviewed
   commit. A digest-pinned BuildKit creates both deterministic multi-platform wrappers, whose config
   includes `org.opencontainers.image.source=https://github.com/ravenroot-ai/ravenroot`, and publishes
   them using only the workflow's `packages: write` token. It reports the resulting index digests and
   never accepts registry credentials from workflow inputs.
5. Record the two reported project digests, run the ordinary fail-closed manifest validation, and
   repeat the publication workflow. The second build must reproduce those exact digests. Do not
   merge a manifest containing `PENDING`.
6. On a clean test host, authenticate without printing the token, remove both project-owned images,
   and run the preflight followed by the acceptance reactor:

   ```sh
   export RAVENROOT_FIXTURE_REGISTRY_USER=YOUR_GITHUB_LOGIN
   read -r -s RAVENROOT_FIXTURE_REGISTRY_TOKEN
   export RAVENROOT_FIXTURE_REGISTRY_TOKEN
   python3 scripts/fixtures/minio/verify.py preflight --platform linux/amd64 --pull
   mvn -f ravenroot/pom.xml -pl ravenroot-extensions/ravenroot-object-storage -am verify
   unset RAVENROOT_FIXTURE_REGISTRY_TOKEN
   ```

   Dispatch the full CI workflow on the exact candidate commit only after this clean-cache proof.
   A public package can be read without the two environment variables; private packages remain
   supported because Actions access is inherited from the repository link.

Do not delete an older digest while a reachable Ravenroot commit still references it. Rotation adds
new immutable content first, verifies it, changes the manifest, and retires old content only after
the repository history no longer depends on its availability.
