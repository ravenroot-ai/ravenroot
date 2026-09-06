#!/usr/bin/env python3
"""Publish extension READMEs into the documentation site and detect drift.

The extension module README is the canonical bundle contract.  The files under
``docs/reference/bundles`` are deterministic publication views, not a second
copy for authors to maintain.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = ROOT / "ravenroot" / "ravenroot-extensions"
OUTPUT = ROOT / "docs" / "reference" / "bundles"
EXAMPLES = ROOT / "docs" / "reference" / "bundle-node-examples.md"
BASELINE = "f58cd7c7d98cd370c89199829d5436c6a7e8eb8b"


@dataclass(frozen=True)
class Bundle:
    module: str
    package_id: str
    behaviors: tuple[str, ...]

    @property
    def source(self) -> Path:
        return EXTENSIONS / f"ravenroot-{self.module}" / "README.md"

    @property
    def output(self) -> Path:
        return OUTPUT / f"{self.module}.md"

    @property
    def node_package_sources(self) -> tuple[Path, ...]:
        sources = EXTENSIONS / f"ravenroot-{self.module}" / "src" / "main" / "java"
        return tuple(sorted(sources.rglob("*NodePackage.java")))


BUNDLES = (
    Bundle("ai", "ai.ravenroot.extensions.ai", ("llm-prompt", "agent")),
    Bundle("amqp091", "ai.ravenroot.extensions.amqp091", ("amqp.publish", "amqp.consume")),
    Bundle("discord", "ai.ravenroot.extensions.discord", ("discord.interactions", "discord.send")),
    Bundle("filesystem", "ai.ravenroot.extensions.filesystem", ("filesystem.read", "filesystem.write")),
    Bundle("git-workspace", "ai.ravenroot.extensions.gitworkspace", ("git-workspace",)),
    Bundle("github", "ai.ravenroot.extensions.github", (
        "github-events-source", "project-transition", "github-app-review",
        "github-workflow-watch", "release-prepare",
    )),
    Bundle("jdbc", "ai.ravenroot.extensions.jdbc", ("jdbc.query", "jdbc.insert")),
    Bundle("kafka", "ai.ravenroot.extensions.kafka", ("kafka.produce", "kafka.consume")),
    Bundle("mail", "ai.ravenroot.extensions.mail", (
        "mail.send", "mail.imap.query", "mail.imap.consume", "mail.imap.move", "mail.imap.delete",
    )),
    Bundle("object-storage", "ai.ravenroot.extensions.storage", (
        "object.get", "object.put", "object.list", "object.delete",
    )),
    Bundle("ocr", "ai.ravenroot.extensions.ocr", ("ocr.extract",)),
    Bundle("openapi-client", "ai.ravenroot.extensions.openapi.client", ("openapi.call",)),
    Bundle("openapi-server", "ai.ravenroot.extensions.openapi.server", (
        "openapi.receive", "openapi.request-reply",
    )),
    Bundle("slack", "ai.ravenroot.extensions.slack", (
        "slack.events", "slack.commands", "slack.post-message",
    )),
    Bundle("spel", "ai.ravenroot.extensions.spel", ("spel.transform", "spel.decision")),
    Bundle("telegram", "ai.ravenroot.extensions.telegram", (
        "telegram.send", "telegram.answer.callback", "telegram.edit.message", "telegram.delete.message",
    )),
    Bundle("websocket", "ai.ravenroot.extensions.websocket", ("websocket.send", "websocket.receive")),
)

LINK = re.compile(r"(?<!!)\[([^]]+)]\(([^)]+)\)")
LIQUID_LITERAL = re.compile(r"\{\{(?:payload|attributes\.[A-Za-z0-9_.-]+|properties\.[A-Za-z0-9_.-]+)\}\}")


def discovered_modules() -> set[str]:
    modules: set[str] = set()
    for directory in EXTENSIONS.glob("ravenroot-*"):
        sources = directory / "src" / "main" / "java"
        if (directory / "pom.xml").is_file() and sources.is_dir() and any(sources.rglob("*NodePackage.java")):
            modules.add(directory.name.removeprefix("ravenroot-"))
    return modules


def _published_link(bundle: Bundle, target: str) -> str:
    parsed = urlsplit(target)
    if parsed.scheme or parsed.netloc or target.startswith(("#", "mailto:")):
        return target
    path_text, separator, fragment = target.partition("#")
    resolved = (bundle.source.parent / path_text).resolve()
    docs = ROOT / "docs"
    try:
        relative_docs = resolved.relative_to(docs)
    except ValueError:
        relative_repo = resolved.relative_to(ROOT).as_posix()
        published = f"https://github.com/ravenroot-ai/ravenroot/blob/{BASELINE}/{relative_repo}"
    else:
        published = os.path.relpath(docs / relative_docs, bundle.output.parent).replace(os.sep, "/")
    return f"{published}{separator}{fragment}"


def render(bundle: Bundle) -> str:
    source = bundle.source.read_text(encoding="utf-8")

    def rewrite(match: re.Match[str]) -> str:
        return f"[{match.group(1)}]({_published_link(bundle, match.group(2))})"

    source = LINK.sub(rewrite, source).rstrip() + "\n"
    source = LIQUID_LITERAL.sub(lambda match: "{% raw %}" + match.group(0) + "{% endraw %}", source)
    title, separator, body = source.partition("\n")
    if not title.startswith("# ") or not separator:
        raise ValueError(f"{bundle.source.relative_to(ROOT)} must start with a level-one title")
    node_list = ", ".join(f"`{behavior}`" for behavior in bundle.behaviors)
    canonical_url = (
        "https://github.com/ravenroot-ai/ravenroot/blob/"
        f"dev/ravenroot/ravenroot-extensions/ravenroot-{bundle.module}/README.md"
    )
    header = f"""{title}

<!-- Generated by scripts/publish_bundle_reference.py; do not edit this file directly. -->

> **Canonical source and applicability.** This publication view is generated from the
> [`ravenroot-{bundle.module}` module README]({canonical_url}) in this documentation revision, whose
> product contract was audited at the documented development baseline. The optional package identity
> is `{bundle.package_id}` and it contributes {node_list}.
> The package targets `ravenroot.node-sdk/2`; fields absent from a node's descriptor do not apply.
> Installing a bundle does not enable it; the operator must also allow its manifest identity and
> recreate or restart the service as described in the [bundle lifecycle](../../operator-guide/plugin-bundles.md).
> Download its complete admission-ready GraphML from the [first-party bundle examples](../bundle-node-examples.md).

"""
    return header + body


def errors() -> list[str]:
    problems: list[str] = []
    examples = EXAMPLES.read_text(encoding="utf-8") if EXAMPLES.is_file() else ""
    declared = {bundle.module for bundle in BUNDLES}
    discovered = discovered_modules()
    for module in sorted(discovered - declared):
        problems.append(f"first-party node-package module is not published: {module}")
    for module in sorted(declared - discovered):
        problems.append(f"published bundle has no first-party node-package module: {module}")

    expected_outputs = {bundle.output for bundle in BUNDLES}
    if OUTPUT.is_dir():
        for stale in sorted(OUTPUT.glob("*.md")):
            if stale.name != "index.md" and stale not in expected_outputs:
                problems.append(f"stale generated bundle page: {stale.relative_to(ROOT)}")

    for bundle in BUNDLES:
        if not bundle.source.is_file():
            problems.append(f"canonical README is missing: {bundle.source.relative_to(ROOT)}")
            continue
        source = bundle.source.read_text(encoding="utf-8")
        if bundle.package_id not in source:
            problems.append(f"{bundle.source.relative_to(ROOT)} does not name package {bundle.package_id}")
        for behavior in bundle.behaviors:
            if behavior not in source:
                problems.append(f"{bundle.source.relative_to(ROOT)} does not name node {behavior}")
            example_link = f"../examples/nodes/{behavior}.graphml"
            example_file = ROOT / "docs" / "examples" / "nodes" / f"{behavior}.graphml"
            if example_link not in examples:
                problems.append(f"bundle example index is missing complete graph link: {behavior}")
            elif not example_file.is_file():
                problems.append(f"bundle example graph is missing: {behavior}")
            elif example_file.read_text(encoding="utf-8").count(
                    f'<data key="behavior">{behavior}</data>') != 1:
                problems.append(f"bundle example graph does not select exactly one {behavior} node")
        if len(bundle.node_package_sources) != 1:
            problems.append(
                f"{bundle.module} must have exactly one NodePackage source; "
                f"found {len(bundle.node_package_sources)}"
            )
        elif "NodeSdk.CONTRACT" not in bundle.node_package_sources[0].read_text(encoding="utf-8"):
            problems.append(
                f"{bundle.node_package_sources[0].relative_to(ROOT)} does not declare NodeSdk.CONTRACT"
            )
        if not bundle.output.is_file():
            problems.append(f"generated page is missing: {bundle.output.relative_to(ROOT)}")
        elif bundle.output.read_text(encoding="utf-8") != render(bundle):
            problems.append(
                f"generated page has drifted: {bundle.output.relative_to(ROOT)} "
                "(run scripts/publish_bundle_reference.py)"
            )
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report drift without writing files")
    args = parser.parse_args()

    if not args.check:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        for bundle in BUNDLES:
            bundle.output.write_text(render(bundle), encoding="utf-8")

    problems = errors()
    if problems:
        for problem in problems:
            print(f"ERROR: {problem}", file=sys.stderr)
        return 1
    print(f"Bundle reference is current ({len(BUNDLES)} packages, "
          f"{sum(len(bundle.behaviors) for bundle in BUNDLES)} node types).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
