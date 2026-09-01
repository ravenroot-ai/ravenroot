#!/usr/bin/env python3
"""Verify the locally generated Ravenroot public API documentation artifacts."""

from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET
import sys
import zipfile
import re


ROOT = Path(__file__).resolve().parent.parent
MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0"


def read_product_version(root: Path) -> str:
    """Return the direct version declared by Ravenroot's authoritative parent POM."""
    pom = root / "ravenroot" / "pom.xml"
    project = ET.parse(pom).getroot()
    version = project.find(f"{{{MAVEN_NAMESPACE}}}version")
    if version is None or version.text is None or not version.text.strip():
        raise ValueError(f"{pom.relative_to(root)}: direct project <version> is missing")
    return version.text.strip()


PRODUCT_VERSION = read_product_version(ROOT)
API_ROOT = ROOT / "ravenroot" / "target" / "artifacts" / "api" / "apidocs"
JAVADOC_JAR = (
    ROOT
    / "ravenroot"
    / "ravenroot-application-api"
    / "target"
    / f"ravenroot-application-api-{PRODUCT_VERSION}-javadoc.jar"
)
PUBLIC_PREFIX = "ai.ravenroot.api"
EXCLUDED_PACKAGE_SEGMENTS = frozenset({"internal", "impl"})
PLACEHOLDER_TEXT = (
    "Describes this public Ravenroot API declaration.",
    "the operation result.",
    "Defines this contract for callers of the Ravenroot application API.",
    "Enforces this declaration's documented invariants before it becomes observable.",
    "Validates the state representation before it is exposed to an adapter.",
    "Describes the public contract exposed by this declaration.",
    "Describes public contract exposed to an integration.",
    "Applies the validation and normalization rules of the enclosing API value.",
)
PLACEHOLDER_PATTERNS = (
    r"the value produced when [^.]+ completes\.",
    r"the [^.]+ result produced by this operation\.",
    r"Performs the [^.]+ operation for the calling integration\.",
    r"Performs the [^.]+ operation at the public application boundary\.",
    r"the caller-supplied [A-Za-z][A-Za-z0-9 ]* used by this operation\.",
    r"Validates the state representation before [^.]+\.",
    r"Carries out the [^.]+ action at the application boundary\.",
    r"[A-Za-z][A-Za-z0-9 ]+ participating in this contract\.",
    r"the [^.]+ result established by [^.]+\.",
    r"whether the supplied current lease was released\.",
    r"whether the claimed item was acknowledged with its current fence\.",
)


class PageCollector(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.links: list[str] = []
        self.anchors: set[str] = set()

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        for name in ("href", "src"):
            if attributes.get(name):
                self.links.append(attributes[name] or "")
        if attributes.get("id"):
            self.anchors.add(attributes["id"] or "")
        if tag == "a" and attributes.get("name"):
            self.anchors.add(attributes["name"] or "")


def fail(message: str) -> None:
    print(f"API documentation verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_page(page: Path) -> PageCollector:
    collector = PageCollector()
    collector.feed(page.read_text(encoding="utf-8"))
    return collector


def local_target(api_root: Path, page: Path, link: str) -> tuple[Path, str] | None:
    parsed = urlsplit(link)
    if parsed.scheme or parsed.netloc:
        return None
    target = page.resolve() if parsed.path == "" else (page.parent / unquote(parsed.path)).resolve()
    try:
        target.relative_to(api_root.resolve())
    except ValueError:
        fail(f"{page.relative_to(api_root)} links outside the generated API tree: {link}")
    return target, unquote(parsed.fragment)


def verify_html_links(api_root: Path) -> None:
    pages = sorted(api_root.rglob("*.html"))
    if not pages:
        fail(f"no HTML pages generated below {api_root}")
    collectors = {page: read_page(page) for page in pages}
    for page, collector in collectors.items():
        text = page.read_text(encoding="utf-8")
        if any(placeholder in text for placeholder in PLACEHOLDER_TEXT):
            fail(f"{page.relative_to(api_root)} contains generated placeholder documentation")
        if any(re.search(pattern, text) for pattern in PLACEHOLDER_PATTERNS):
            fail(f"{page.relative_to(api_root)} contains generated boilerplate documentation")
        if re.search(r'id="param-[^"]+">[^<]+</span></code>\s*-\s*the\s+[A-Za-z][A-Za-z0-9]*\s+value\.', text):
            fail(f"{page.relative_to(api_root)} contains identifier-only parameter documentation")
        for link in collector.links:
            resolved = local_target(api_root, page, link)
            if resolved is None:
                continue
            target, fragment = resolved
            if not target.exists():
                fail(f"{page.relative_to(api_root)} has a missing local target {target.relative_to(api_root)}")
            if fragment:
                if target.suffix.lower() != ".html":
                    fail(f"{page.relative_to(api_root)} links to a fragment in a non-HTML target: {link}")
                target_collector = collectors.get(target)
                if target_collector is None:
                    target_collector = read_page(target)
                if fragment not in target_collector.anchors:
                    fail(f"{page.relative_to(api_root)} has a missing fragment target {link}")


def package_is_supported(package_name: str) -> bool:
    parts = package_name.split(".")
    return package_name.startswith(PUBLIC_PREFIX + ".") and not any(
        part in EXCLUDED_PACKAGE_SEGMENTS for part in parts
    )


def verify_element_list(lines: list[str], source: str) -> None:
    packages = [line.strip() for line in lines if line.strip() and not line.startswith("module:")]
    if not packages:
        fail(f"{source} contains no documented packages")
    outside = [package for package in packages if not package_is_supported(package)]
    if outside:
        fail(f"unsupported packages leaked into {source}: " + ", ".join(outside))


def verify_packages(api_root: Path) -> None:
    element_list = api_root / "element-list"
    if not element_list.is_file():
        fail("missing aggregate element-list, so the generated package boundary cannot be checked")
    verify_element_list(element_list.read_text(encoding="utf-8").splitlines(), "aggregate element-list")


def verify_javadoc_jar(javadoc_jar: Path) -> None:
    if not javadoc_jar.is_file() or javadoc_jar.stat().st_size == 0:
        fail(f"missing or empty Javadoc JAR: {javadoc_jar}")
    with zipfile.ZipFile(javadoc_jar) as archive:
        names = archive.namelist()
        package_paths = [name for name in names if name.startswith("ai/ravenroot/api/")]
        if not package_paths:
            fail("Javadoc JAR contains no public SDK paths")
        excluded = [
            name
            for name in package_paths
            if any(segment in EXCLUDED_PACKAGE_SEGMENTS for segment in Path(name).parts)
        ]
        if excluded:
            fail("excluded packages leaked into Javadoc JAR: " + ", ".join(excluded))
        if "element-list" not in names:
            fail("Javadoc JAR is missing element-list")
        verify_element_list(
            archive.read("element-list").decode("utf-8").splitlines(), "Javadoc JAR element-list"
        )


def verify_void_stage_return_contracts(source: Path) -> None:
    """Reject boolean-result wording on asynchronous operations that return no value."""
    text = source.read_text(encoding="utf-8")
    pattern = re.compile(
        r"/\*\*(?P<doc>.*?)\*/\s*(?:default\s+)?CompletionStage<Void>\s+\w+\s*\(",
        re.DOTALL,
    )
    for match in pattern.finditer(text):
        return_tags = re.findall(r"@return\s+([^\n*]+)", match.group("doc"), re.IGNORECASE)
        if return_tags and re.match(r"whether\b", return_tags[-1].strip(), re.IGNORECASE):
            try:
                display_name = source.relative_to(ROOT)
            except ValueError:
                display_name = source
            fail(f"{display_name} gives boolean-result wording to CompletionStage<Void>")


def main() -> None:
    index = API_ROOT / "index.html"
    if not index.is_file():
        fail(f"missing API HTML entry point: {index.relative_to(ROOT)}")
    verify_html_links(API_ROOT)
    verify_packages(API_ROOT)
    verify_javadoc_jar(JAVADOC_JAR)
    verify_void_stage_return_contracts(
        ROOT / "ravenroot" / "ravenroot-application-api" / "src" / "main" / "java"
        / "ai" / "ravenroot" / "api" / "persistence" / "ExecutionStore.java"
    )
    print(f"API documentation verified: {index.relative_to(ROOT)} and {JAVADOC_JAR.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
