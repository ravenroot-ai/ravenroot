#!/usr/bin/env python3
"""Admit and conditionally publish immutable Ravenroot GraphML release artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

NS = {"g": "http://graphml.graphdrawing.org/xmlns"}
MAX_CATALOG_BYTES = 2 * 1024 * 1024
SOURCE_COMMIT = re.compile(r"[0-9a-f]{40,64}")
HTTP_TIMEOUT_SECONDS = 60
HTTP_RESPONSE_BYTES = 64 * 1024
AWS_CLI_TIMEOUT_SECONDS = 90
AWS_CONNECT_TIMEOUT_SECONDS = 10
AWS_READ_TIMEOUT_SECONDS = 30
AWS_MAX_ATTEMPTS = "3"


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Reject redirects so an authorization header never crosses origins."""

    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


HTTP_OPENER = urllib.request.build_opener(NoRedirectHandler())


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_bounded(response, limit: int, deadline: float) -> bytes:
    """Read within both a byte limit and the request's absolute deadline."""
    try:
        raw_socket = response.fp.raw._sock
    except AttributeError:
        raw_socket = None
    chunks: list[bytes] = []
    length = 0
    read = getattr(response, "read1", response.read)
    while length <= limit:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise SystemExit("remote request exceeded the configured time limit")
        if raw_socket is not None:
            raw_socket.settimeout(remaining)
        try:
            chunk = read(min(64 * 1024, limit + 1 - length))
        except (TimeoutError, socket.timeout) as error:
            raise SystemExit("remote request exceeded the configured time limit") from error
        if time.monotonic() > deadline:
            raise SystemExit("remote request exceeded the configured time limit")
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)
        length += len(chunk)
    raise SystemExit("remote artifact exceeds the configured size limit")


def open_http(request, limit: int) -> tuple[int, bytes]:
    deadline = time.monotonic() + HTTP_TIMEOUT_SECONDS
    try:
        with HTTP_OPENER.open(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            return response.status, read_bounded(response, limit, deadline)
    except urllib.error.HTTPError:
        raise
    except (TimeoutError, socket.timeout, urllib.error.URLError) as error:
        raise SystemExit("remote request failed within the configured network boundary") from error


def admission(graph: bytes, url: str, token: str) -> dict:
    request = urllib.request.Request(
        f"{url.rstrip('/')}/v1/graphs/inspect?purpose=LOCAL_DEPLOYMENT",
        data=graph,
        method="POST",
        headers={"Content-Type": "application/graphml+xml", "Accept": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})},
    )
    _status, body = open_http(request, MAX_CATALOG_BYTES)
    value = json.loads(body)
    if (not value.get("valid") or value.get("violations") or value.get("findings")
            or any(not isinstance(value.get(name), int)
                   for name in ("nodes", "edges", "startNodes", "endNodes"))):
        raise SystemExit(f"Ravenroot admission refused the graph: {value.get('violations') or value.get('findings')}")
    return value


def metadata(graph: bytes) -> tuple[str, int, list[dict[str, str]]]:
    root = ET.fromstring(graph)
    keys = {item.get("id"): item.get("attr.name") for item in root.findall("g:key", NS)}
    graphs = root.findall("g:graph", NS)
    if len(graphs) != 1:
        raise SystemExit("GraphML must contain exactly one graph")
    graph_element = graphs[0]
    values = {keys.get(item.get("key")): (item.text or "").strip()
              for item in graph_element.findall("g:data", NS)}
    graph_id = values.get("ravenroot.authoring.graphId", "")
    try:
        version = int(values.get("ravenroot.authoring.releaseVersion", "0"))
    except ValueError as error:
        raise SystemExit("Authored release version is malformed") from error
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", graph_id) or graph_id == "submission" or version < 1:
        raise SystemExit("GraphML lacks an allowed authored graph identity and positive release version")
    dependencies: set[tuple[str, str]] = set()
    for owner in [graph_element, *graph_element.findall("g:node", NS)]:
        for item in owner.findall("g:data", NS):
            name, value = keys.get(item.get("key")), (item.text or "").strip()
            if name in {"behavior", "nodeType"} and value:
                dependencies.add((name, value))
    return graph_id, version, [{"kind": kind, "id": value} for kind, value in sorted(dependencies)]


def http_get(url: str, token: str, limit: int = MAX_CATALOG_BYTES) -> bytes:
    request = urllib.request.Request(url, headers={
        "Accept": "application/json, application/graphml+xml;q=0.9",
        **({"Authorization": f"Bearer {token}"} if token else {}),
    })
    _status, body = open_http(request, limit)
    return body


def http_create(base: str, path: str, data: bytes, content_type: str, token: str) -> None:
    url = f"{base.rstrip('/')}/{path}"
    request = urllib.request.Request(url, data=data, method="PUT",
        headers={"Content-Type": content_type, "If-None-Match": "*",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    try:
        status, _body = open_http(request, HTTP_RESPONSE_BYTES)
        if status not in {200, 201, 204}:
            raise SystemExit(f"artifact upload returned {status}")
    except urllib.error.HTTPError as error:
        if error.code not in {409, 412}:
            error.close()
            raise
        error.close()
        existing = http_get(url, token, max(len(data), 1))
        if existing != data:
            raise SystemExit(f"immutable artifact exists with different bytes: {path}") from error


def aws_run(command: list[str]) -> subprocess.CompletedProcess:
    bounded_command = [command[0], "--cli-connect-timeout", str(AWS_CONNECT_TIMEOUT_SECONDS),
                       "--cli-read-timeout", str(AWS_READ_TIMEOUT_SECONDS), *command[1:]]
    environment = {**os.environ, "AWS_MAX_ATTEMPTS": AWS_MAX_ATTEMPTS,
                   "AWS_RETRY_MODE": "standard"}
    try:
        return subprocess.run(bounded_command, stdout=subprocess.DEVNULL,
                              stderr=subprocess.PIPE, text=True, env=environment,
                              timeout=AWS_CLI_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as error:
        raise SystemExit("immutable artifact storage request exceeded the configured time limit") from error


def s3_create(bucket: str, path: str, data: bytes, content_type: str) -> None:
    with tempfile.TemporaryDirectory(prefix="ravenroot-publish-") as directory:
        source = pathlib.Path(directory, "source")
        existing = pathlib.Path(directory, "existing")
        source.write_bytes(data)
        command = ["aws", "s3api", "put-object", "--bucket", bucket, "--key", path,
                   "--content-type", content_type, "--if-none-match", "*", "--body", str(source)]
        result = aws_run(command)
        if result.returncode == 0:
            return
        fetched = aws_run(["aws", "s3api", "get-object", "--bucket", bucket,
                           "--key", path, str(existing)])
        if fetched.returncode != 0 or not existing.exists() or existing.read_bytes() != data:
            raise SystemExit(f"immutable artifact upload failed or existing bytes differ: {path}")


def previous_entries(url: str | None, token: str) -> list[dict]:
    if not url:
        return []
    catalog = json.loads(http_get(url, token))
    if catalog.get("contract") != "ravenroot-graph-catalog-v1" or not isinstance(catalog.get("artifacts"), list):
        raise SystemExit("previous catalog has an unsupported contract")
    seen: set[tuple[str, str, int]] = set()
    entries: list[dict] = []
    for item in catalog["artifacts"]:
        if not isinstance(item, dict):
            raise SystemExit("previous catalog contains a malformed entry")
        try:
            identity = (item["tenantId"], item["graphId"], item["releaseVersion"])
            hashes = (item["graphMlSha256"], item["manifestSha256"])
            source_commit = item["sourceCommit"]
        except KeyError as error:
            raise SystemExit("previous catalog entry lacks release evidence") from error
        if (not all(isinstance(value, str) and value for value in identity[:2])
                or not isinstance(identity[2], int) or identity[2] < 1
                or not all(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) for value in hashes)
                or not isinstance(source_commit, str) or not SOURCE_COMMIT.fullmatch(source_commit)):
            raise SystemExit("previous catalog entry contains invalid release evidence")
        if identity in seen:
            raise SystemExit("previous catalog contains duplicate release identities")
        seen.add(identity)
        entries.append(item)
    return entries


def append_release(entries: list[dict], release: dict) -> list[dict]:
    related = [item for item in entries if item["tenantId"] == release["tenantId"]
               and item["graphId"] == release["graphId"]]
    same = [item for item in related if item["releaseVersion"] == release["releaseVersion"]]
    if same:
        if same[0] != release:
            raise SystemExit("release version is already bound to different immutable evidence")
        return entries
    if related and release["releaseVersion"] <= max(item["releaseVersion"] for item in related):
        raise SystemExit("release version must increase monotonically")
    return [*entries, release]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("graphml", type=pathlib.Path)
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--admission-url", required=True)
    parser.add_argument("--catalog-path", required=True)
    parser.add_argument("--previous-catalog-url")
    parser.add_argument("--http-base")
    parser.add_argument("--s3-bucket")
    args = parser.parse_args()
    source_commit = args.source_commit.lower()
    if not SOURCE_COMMIT.fullmatch(source_commit):
        raise SystemExit("--source-commit must be a full hexadecimal Git commit ID")
    if bool(args.http_base) == bool(args.s3_bucket):
        raise SystemExit("select exactly one of --http-base or --s3-bucket")
    graph = args.graphml.read_bytes()
    accepted = admission(graph, args.admission_url, os.getenv("RAVENROOT_ADMISSION_TOKEN", ""))
    graph_id, version, dependencies = metadata(graph)
    graph_sha = digest(graph)
    tenant_segment = digest(args.tenant.encode("utf-8"))[:32]
    prefix = f"graphs/tenants/{tenant_segment}/{graph_id}/{version}/{graph_sha}"
    graph_path = f"{prefix}.graphml"
    compatibility = {
        "accepted": True, "contract": "ravenroot-local-deployment-admission-v1",
        "purpose": "LOCAL_DEPLOYMENT", "nodes": accepted["nodes"], "edges": accepted["edges"],
        "startNodes": accepted["startNodes"], "endNodes": accepted["endNodes"],
        "violations": [], "findings": [],
    }
    manifest = {
        "contract": "ravenroot-graph-artifact-v1", "tenantId": args.tenant,
        "graphId": graph_id, "releaseVersion": version, "sourceCommit": source_commit,
        "graphMlSha256": graph_sha, "compatibility": compatibility, "dependencies": dependencies,
    }
    manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    manifest_sha = digest(manifest_bytes)
    manifest_path = f"{prefix}.{manifest_sha}.manifest.json"
    release = {
        "tenantId": args.tenant, "graphId": graph_id, "releaseVersion": version,
        "sourceCommit": source_commit, "graphMlSha256": graph_sha, "graphMlPath": graph_path,
        "manifestSha256": manifest_sha, "manifestPath": manifest_path,
    }
    upload_token = os.getenv("RAVENROOT_ARTIFACT_UPLOAD_TOKEN", "")
    entries = previous_entries(args.previous_catalog_url, upload_token)
    catalog = {"contract": "ravenroot-graph-catalog-v1",
               "artifacts": append_release(entries, release)}
    if len(catalog["artifacts"]) > 200:
        raise SystemExit("catalog exceeds the supported release count; begin a new retained catalog series")
    catalog_bytes = json.dumps(catalog, sort_keys=True, separators=(",", ":")).encode()
    if len(catalog_bytes) > MAX_CATALOG_BYTES:
        raise SystemExit("catalog exceeds the supported size limit")
    uploader = (lambda path, data, kind: http_create(args.http_base, path, data, kind,
                upload_token)) if args.http_base else (
                lambda path, data, kind: s3_create(args.s3_bucket, path, data, kind))
    uploader(graph_path, graph, "application/graphml+xml")
    uploader(manifest_path, manifest_bytes, "application/json")
    uploader(args.catalog_path, catalog_bytes, "application/json")
    print(json.dumps({"catalogPath": args.catalog_path, "graphId": graph_id,
                      "releaseVersion": version, "sourceCommit": source_commit,
                      "sha256": graph_sha}, sort_keys=True))


if __name__ == "__main__":
    main()
