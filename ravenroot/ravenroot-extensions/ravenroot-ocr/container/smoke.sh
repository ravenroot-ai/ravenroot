#!/usr/bin/env sh
set -eu

IMAGE=${RAVENROOT_OCR_IMAGE:?set RAVENROOT_OCR_IMAGE to an immutable image digest}
case "$IMAGE" in *@sha256:*|sha256:*) ;; *) echo "OCR smoke requires an immutable image digest" >&2; exit 2 ;; esac

MODULE_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
CONTAINER="ravenroot-ocr-smoke-$$"
DATA_VOLUME="ravenroot-ocr-smoke-data-$$"
cleanup() {
  docker rm --force "$CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$DATA_VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

# Ravenroot's SQLite JDBC native library is extracted to the data mount and must be executable;
# this follows the standard image's durable-volume contract. The OCR-only mount remains noexec.
docker volume create "$DATA_VOLUME" >/dev/null
docker run --detach --name "$CONTAINER" --init \
  --read-only --user 10001:10001 --cap-drop ALL --security-opt no-new-privileges:true \
  --pids-limit 128 --memory 768m --cpus 1 \
  --tmpfs /tmp:size=32m,mode=1777 \
  --mount type=volume,source="$DATA_VOLUME",target=/opt/ravenroot/data \
  --tmpfs /opt/ravenroot/ocr-tmp:rw,noexec,nosuid,size=64m,mode=1777 \
  --env RAVENROOT_PORT=8080 --env RAVENROOT_REPLICAS=1 --env RAVENROOT_BIND_ADDRESS=127.0.0.1 \
  --env RAVENROOT_AUTH_MODE=disabled --env RAVENROOT_AUDIT_DIR=/opt/ravenroot/data/audit \
  --env RAVENROOT_EXECUTION_STORE_DIR=/opt/ravenroot/data/execution-store \
  "$IMAGE" >/dev/null

for _ in $(seq 1 30); do
  [ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$CONTAINER")" = healthy ] && break
  if [ "$(docker inspect --format '{{.State.Running}}' "$CONTAINER")" != true ]; then
    docker logs "$CONTAINER" >&2
    exit 1
  fi
  sleep 1
done
[ "$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER")" = healthy ]
test "$(docker exec "$CONTAINER" id -u)" = 10001
if docker exec "$CONTAINER" touch /opt/ravenroot/root-filesystem-must-remain-read-only; then exit 1; fi
if docker exec "$CONTAINER" touch /usr/share/tesseract-ocr/5/tessdata/must-remain-read-only; then exit 1; fi
docker exec "$CONTAINER" bash -c \
  'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /ready HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; head -1 <&3 | grep " 200 "'

# The test image is copied only into the declared OCR tmpfs. Tesseract and its immutable bundled
# language data then prove the native capability without opening another mount or network path.
docker exec --interactive "$CONTAINER" sh -c \
  'base64 -d >/opt/ravenroot/ocr-tmp/input.png' \
  <"$MODULE_DIR/src/test/resources/fixtures/ravenroot-text.png.base64"
docker exec "$CONTAINER" /usr/bin/tesseract /opt/ravenroot/ocr-tmp/input.png stdout \
  --tessdata-dir /usr/share/tesseract-ocr/5/tessdata -l eng --psm 3 | grep 'RAVENROOT 42'

docker stop --time 10 "$CONTAINER" >/dev/null
# The base JVM receives SIGTERM directly.  Its shutdown hook drains and exits, while HotSpot reports
# the terminating signal as 143 on this image; 137 would instead prove Docker had to SIGKILL it when
# the grace elapsed.  Accept only the normal zero exit or that observed graceful SIGTERM status.
case "$(docker inspect --format '{{.State.ExitCode}}' "$CONTAINER")" in 0|143) ;; *) exit 1 ;; esac
