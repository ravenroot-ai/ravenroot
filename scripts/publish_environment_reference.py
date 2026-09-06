#!/usr/bin/env python3
"""Publish the production Java environment-variable inventory and detect drift."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "ravenroot"
OUTPUT = ROOT / "docs" / "reference" / "environment-variables.md"
VARIABLE = re.compile(r'"(RAVENROOT_[A-Z0-9_]+)"')


@dataclass(frozen=True)
class Group:
    title: str
    link: str
    applicability: str


GROUPS = {
    "agent": Group("Agent authority", "configuration.md#agent-authority-and-budgets",
                   "typed startup policy; unset uses the finite table defaults"),
    "assistant": Group("Authoring assistant", "../operator-guide/authoring-assistant.md#setting-reference",
                       "startup setting; the runbook gives each type and default"),
    "bundle": Group("Bundle profile", "bundles/",
                    "optional package only; its bundle page defines the strict profile"),
    "credential": Group("Credentials and egress", "../operator-guide/credentials-egress.md",
                        "operator secret/profile or bounded outbound policy"),
    "embed": Group("Embedded viewer", "embed-extension-contracts.md",
                   "startup setting; disabled unless explicitly enabled"),
    "graph": Group("Graph execution", "configuration.md#graph-execution-resource-limits",
                   "positive bounded startup limit; blank uses the documented default"),
    "identity": Group("Identity and HTTP boundary", "configuration.md#identity-and-browser-controls",
                      "startup identity, listener, origin, proxy, or request boundary"),
    "observability": Group("Observability", "configuration.md#observability",
                           "startup setting; telemetry is disabled by default"),
    "persistence": Group("Persistence and recovery", "../operator-guide/persistence-lifecycle.md",
                         "startup store/path setting; the runbook states durability"),
    "plugin": Group("Package activation", "../operator-guide/plugin-bundles.md",
                    "startup package selection; unset loads no optional packages"),
    "program": Group("Programs and artifacts", "configuration.md#programmable-artifacts",
                     "startup runtime, supervisor, artifact, or resource limit"),
    "rate": Group("HTTP rate and representation limits", "configuration.md#http-rate-and-representation-limits",
                  "positive integer startup limit; blank uses the table default"),
    "runtime": Group("Server lifecycle", "configuration.md#server-process-and-readiness",
                     "startup process, deployment, readiness, or UI setting"),
    "tool": Group("Tool and approval policy", "configuration.md#tool-and-approval-policy",
                  "startup allowlist or durable approval policy"),
}

BUNDLE_PREFIXES = (
    "RAVENROOT_AMQP091_", "RAVENROOT_DISCORD_", "RAVENROOT_FILESYSTEM_",
    "RAVENROOT_GITHUB_", "RAVENROOT_GIT_WORKSPACE_", "RAVENROOT_IMAP_",
    "RAVENROOT_JDBC_", "RAVENROOT_KAFKA_", "RAVENROOT_LLM_", "RAVENROOT_MAIL_",
    "RAVENROOT_MCP_", "RAVENROOT_OBJECT_STORAGE_", "RAVENROOT_OCR_",
    "RAVENROOT_OPENAPI_", "RAVENROOT_SLACK_", "RAVENROOT_TELEGRAM_",
    "RAVENROOT_WEBSOCKET_",
)


def variables() -> dict[str, tuple[Path, ...]]:
    found: dict[str, set[Path]] = {}
    for source in sorted(SOURCE.rglob("src/main/java/**/*.java")):
        for name in VARIABLE.findall(source.read_text(encoding="utf-8")):
            found.setdefault(name, set()).add(source)
    return {name: tuple(sorted(paths)) for name, paths in sorted(found.items())}


def undocumented_variables() -> list[str]:
    references: list[str] = []
    for path in sorted((ROOT / "docs").rglob("*.md")):
        if path != OUTPUT:
            references.append(path.read_text(encoding="utf-8"))
    extensions = ROOT / "ravenroot" / "ravenroot-extensions"
    for path in sorted(extensions.glob("ravenroot-*/README.md")):
        references.append(path.read_text(encoding="utf-8"))
    documented = "\n".join(references)
    return [name for name in variables() if name not in documented]


def group(name: str) -> str:
    if name.startswith("RAVENROOT_ASSISTANT_"):
        return "assistant"
    if name.startswith(BUNDLE_PREFIXES):
        return "bundle"
    if name.startswith("RAVENROOT_AGENT_"):
        return "agent"
    if name.startswith("RAVENROOT_GRAPH"):
        return "graph"
    if name.startswith("RAVENROOT_EMBED_") or name == "RAVENROOT_REPLICAS":
        return "embed"
    if name.startswith("RAVENROOT_RATELIMIT_") or name in {
        "RAVENROOT_SSE_QUEUE_CAPACITY", "RAVENROOT_TRUSTED_PROXY_ADDRESSES",
        "RAVENROOT_TRUSTED_PROXY_HOPS",
    }:
        return "rate"
    if name.startswith("RAVENROOT_OTEL_"):
        return "observability"
    if name.startswith("RAVENROOT_TOOL_") or name == "RAVENROOT_ALLOWED_TOOLS":
        return "tool"
    if name.startswith(("RAVENROOT_GRAAL_", "RAVENROOT_PROGRAM_", "RAVENROOT_ARTIFACT_")):
        return "program"
    if name in {"RAVENROOT_EXECUTION_STORE_DIR", "RAVENROOT_EXECUTION_STORE_ENABLED",
                "RAVENROOT_AUDIT_DIR", "RAVENROOT_CREDENTIAL_DIR"}:
        return "persistence"
    if name.startswith(("RAVENROOT_ENABLED_PLUGINS", "RAVENROOT_NODE_PACKAGE_SERVICES_")) \
            or name in {"RAVENROOT_NODE_PACKAGES", "RAVENROOT_PLUGINS_INSTALL_DIR"}:
        return "plugin"
    if name.startswith("RAVENROOT_CREDENTIAL_") or name.startswith("RAVENROOT_HTTP_") \
            or name in {"RAVENROOT_EGRESS_RESERVED_EXCEPTIONS", "RAVENROOT_TOKEN"}:
        return "credential"
    if name.startswith(("RAVENROOT_AUTH_", "RAVENROOT_BROWSER_", "RAVENROOT_TRUSTED_TLS_")) \
            or name in {"RAVENROOT_BIND_ADDRESS", "RAVENROOT_CONTAINER_LOOPBACK_ONLY",
                        "RAVENROOT_LOCAL_HOST_BIND_ADDRESS", "RAVENROOT_PUBLIC_ORIGIN",
                        "RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS", "RAVENROOT_UI_CONNECT_ORIGINS"}:
        return "identity"
    if name in {"RAVENROOT_ENGINE", "RAVENROOT_MAX_ACTIVE_DEPLOYMENTS", "RAVENROOT_PORT",
                "RAVENROOT_READY_DRAIN_GRACE_MS", "RAVENROOT_READY_STORE_CHECK_TIMEOUT_MS",
                "RAVENROOT_SERVER_STOP_DELAY_SECONDS", "RAVENROOT_UI_DIR",
                "RAVENROOT_UNKNOWN_BEHAVIOR"}:
        return "runtime"
    raise ValueError(f"unclassified production environment variable: {name}")


def render() -> str:
    inventory = variables()
    rows: dict[str, list[str]] = {key: [] for key in GROUPS}
    for name in inventory:
        rows[group(name)].append(name)
    body = [
        "# Production environment-variable inventory", "",
        "This generated inventory covers every literal `RAVENROOT_*` environment name read by",
        "production Java at the documented development baseline. Prefixes ending in `_` are dynamic",
        "families completed by a profile, tenant, credential, or package key as described by the linked",
        "contract. Repository shell tooling has its own environment table in the",
        "[command-line tools manual](command-line-tools.md).", "",
        "<!-- Generated by scripts/publish_environment_reference.py; do not edit directly. -->", "",
        "Every value is read at process startup unless its linked runbook explicitly describes a durable",
        "API or file. Changing an environment value therefore requires a restart or container recreation.",
        "Absent privileged configuration denies the capability; malformed trusted configuration refuses",
        "startup. The semantic pages linked below define exact types, defaults, precedence, persistence,",
        "conditional applicability, and verification.", "",
    ]
    for key, definition in GROUPS.items():
        names = rows[key]
        if not names:
            continue
        body.extend((f"## {definition.title}", "", f"Detailed contract: [{definition.title}]({definition.link}).", "",
                     "| Variable or family | Applicability and default boundary |", "|---|---|"))
        body.extend(f"| `{name}` | {definition.applicability} |" for name in names)
        body.append("")
    body.extend(("## Validation", "",
                 "`python3 scripts/publish_environment_reference.py --check` re-extracts literal names",
                 "from production Java, refuses any unclassified name, and compares this page byte-for-byte.",
                 "Dynamic suffixes and settings assembled outside Java literals remain covered by their",
                 "maintained parser or command-help checks rather than being invented here.", ""))
    return "\n".join(body)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report drift without writing")
    args = parser.parse_args()
    expected = render()
    missing = undocumented_variables()
    if missing:
        print("ERROR: production environment names lack a semantic reference: "
              + ", ".join(missing), file=sys.stderr)
        return 1
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text(encoding="utf-8") != expected:
            print("ERROR: production environment reference has drifted; run "
                  "scripts/publish_environment_reference.py", file=sys.stderr)
            return 1
    else:
        OUTPUT.write_text(expected, encoding="utf-8")
    print(f"Production environment reference is current ({len(variables())} literal names).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
