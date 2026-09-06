#!/usr/bin/env python3
"""Check operational manuals against maintained command and configuration sources."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMMAND_DOC = ROOT / "docs" / "reference" / "command-line-tools.md"
ASSISTANT_DOC = ROOT / "docs" / "operator-guide" / "authoring-assistant.md"
CORE_DOC = ROOT / "docs" / "reference" / "core-nodes.md"
CLI_SOURCE = ROOT / "ravenroot" / "ravenroot-cli" / "src" / "main" / "java" / "ai" / "ravenroot" / "cli"
ASSISTANT_SOURCE = ROOT / "ravenroot" / "ravenroot-server" / "src" / "main" / "java" / "ai" / "ravenroot" / "server" / "assistant"
CORE_FACTORIES = ROOT / "ravenroot" / "ravenroot-core" / "src" / "main" / "java" / "ai" / "ravenroot" / "core" / "runtime" / "builtin" / "StandardBehaviorFactories.java"

CORE_FACTORY_IDS = {
    "LogNodeBehaviorFactory": "log",
    "DelayNodeBehaviorFactory": "delay",
    "HumanTaskNodeBehaviorFactory": "human-task",
    "TemplateNodeBehaviorFactory": "template",
    "JsonParseNodeBehaviorFactory": "json-parse",
    "CelTransformNodeBehaviorFactory": "cel-transform",
    "CelDecisionNodeBehaviorFactory": "cel-decision",
    "JsonPathNodeBehaviorFactory": "json-path",
    "HttpRequestNodeBehaviorFactory": "http-request",
    "ProgramNodeBehaviorFactory": "program",
    "BoundaryGuardNodeBehaviorFactory": "boundary-guard",
}


def help_text(script: str) -> str:
    result = subprocess.run(
        [str(ROOT / script), "help"], cwd=ROOT, capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"{script} help exited {result.returncode}: {result.stderr.strip()}")
    return result.stdout


def script_contract_tokens() -> dict[str, set[str]]:
    contracts: dict[str, set[str]] = {}
    for script in ("plugin.sh", "service.sh", "dev.sh", "ravenroot/scripts/server.sh"):
        help_output = help_text(script)
        tokens = set(re.findall(r"(?<![-\w.])(--[a-z][a-z0-9-]*|RAVENROOT_[A-Z0-9_]+)", help_output))
        tokens.update(re.findall(r"^\s{2,}(-[a-z]{1,2})(?:,|\s)", help_output, re.MULTILINE))
        if script == "plugin.sh":
            tokens.update(re.findall(r"^  ([a-z][a-z-]+)(?=[ ,<])", help_output, re.MULTILINE))
        elif script == "service.sh":
            for group in re.findall(
                r"^  ([a-z][a-z0-9-]*(?:, [a-z][a-z0-9-]*)*)\s{2,}",
                help_output,
                re.MULTILINE,
            ):
                tokens.update(part.strip() for part in group.split(","))
        elif script == "dev.sh":
            tokens.update(re.findall(r"^  ([a-z][a-z-]+)\s", help_output, re.MULTILINE))
        else:
            tokens.update(re.findall(r"^  ([a-z][a-z-]+)(?:, [a-z][a-z-]+)*\s", help_output, re.MULTILINE))
        contracts[script] = tokens
    return contracts


def cli_tokens() -> set[str]:
    ravenroot_cli = (CLI_SOURCE / "RavenrootCli.java").read_text(encoding="utf-8")
    tokens = set(re.findall(r'case "([a-z][a-z-]+)" ->', ravenroot_cli))
    tokens.update((
        "backup", "verify", "restore", "embed-registration", "list", "add", "register",
        "start", "stop", "restart", "undeploy", "show", "provision", "revoke",
    ))
    for name in ("GlobalOptions.java", "CredentialAddArgs.java"):
        source = (CLI_SOURCE / name).read_text(encoding="utf-8")
        tokens.update(re.findall(r'case "(--[a-z][a-z0-9-]*)" ->', source))
    embed_source = (CLI_SOURCE / "EmbedRegistrationCommand.java").read_text(encoding="utf-8")
    embed_usage = embed_source[embed_source.index("private int usage()") :]
    tokens.update(re.findall(
        r'--(?:[a-z][a-z0-9-]*[a-z0-9]|[a-z])(?![a-z0-9-])', embed_usage,
    ))
    tokens.add("--help")
    return tokens


def assistant_variables() -> set[str]:
    variables: set[str] = set()
    for source in ASSISTANT_SOURCE.rglob("*.java"):
        variables.update(re.findall(r'"(RAVENROOT_ASSISTANT_[A-Z0-9_]+)"', source.read_text(encoding="utf-8")))
    return variables


def core_nodes() -> set[str]:
    source = CORE_FACTORIES.read_text(encoding="utf-8")
    factories = set(re.findall(r"new ([A-Za-z0-9]+NodeBehaviorFactory)\s*\(", source))
    unknown = factories - CORE_FACTORY_IDS.keys()
    if unknown:
        raise RuntimeError(f"unmapped standard behavior factories: {', '.join(sorted(unknown))}")
    return {CORE_FACTORY_IDS[factory] for factory in factories}


def missing_tokens(text: str, tokens: set[str]) -> list[str]:
    return sorted(
        token for token in tokens
        if not re.search(
            rf"`[^`\n]*(?<![\w.-]){re.escape(token)}(?![\w.-])[^`\n]*`",
            text,
        )
    )


def errors() -> list[str]:
    problems: list[str] = []
    command_doc = COMMAND_DOC.read_text(encoding="utf-8")
    assistant_doc = ASSISTANT_DOC.read_text(encoding="utf-8")
    core_doc = CORE_DOC.read_text(encoding="utf-8")
    for script, tokens in script_contract_tokens().items():
        missing = missing_tokens(command_doc, tokens)
        if missing:
            problems.append(f"{script} help tokens missing from command manual: {', '.join(missing)}")
    for fixed_script in ("ravenroot/scripts/build-release.sh", "ravenroot/scripts/run-local.sh"):
        if f"`./{fixed_script}`" not in command_doc:
            problems.append(f"fixed script missing from command manual: {fixed_script}")
    missing_cli = missing_tokens(command_doc, cli_tokens())
    if missing_cli:
        problems.append(f"application CLI tokens missing from command manual: {', '.join(missing_cli)}")
    missing_assistant = missing_tokens(assistant_doc, assistant_variables())
    if missing_assistant:
        problems.append(f"assistant variables missing from operator manual: {', '.join(missing_assistant)}")
    missing_core = missing_tokens(core_doc, core_nodes())
    if missing_core:
        problems.append(f"standard core nodes missing from core reference: {', '.join(missing_core)}")
    return problems


def main() -> int:
    problems = errors()
    if problems:
        for problem in problems:
            print(f"ERROR: {problem}", file=sys.stderr)
        return 1
    print("Operational documentation matches maintained script help, CLI verbs, core nodes, and assistant variables.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
