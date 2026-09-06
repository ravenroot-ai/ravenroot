#!/usr/bin/env python3
"""Validate the source navigation and generated GitHub Pages documentation site."""

from __future__ import annotations

import argparse
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit


NAVIGATION_URL = re.compile(
    r"^\s+url:\s*(?:\"([^\"]+)\"|'([^']+)'|([^\s#]+))\s*$"
)
RAVENROOT_TEMPLATE = re.compile(
    r"\{\{(?:payload|attributes\.[A-Za-z0-9_.-]+|properties\.[A-Za-z0-9_.-]+)\}\}"
)
RENDERED_CODE_LITERALS = {
    "/get-started/first-graph.html": {"{{payload}}"},
    "/reference/nodes-payload-limits.html": {"{{payload}}"},
    "/reference/core-nodes.html": {
        "{{payload}}", "{{attributes.name}}", "{{properties.name}}",
    },
    "/reference/node-contracts.html": {"{{payload}}"},
    "/reference/bundles/ai.html": {"{{payload}}"},
}


def document_url(path: Path, source_dir: Path) -> str:
    relative = path.relative_to(source_dir)
    if relative.name == "index.md":
        parent = relative.parent.as_posix()
        return "/" if parent == "." else f"/{parent}/"
    return f"/{relative.with_suffix('.html').as_posix()}"


def document_urls(source_dir: Path) -> list[str]:
    return sorted(document_url(path, source_dir) for path in source_dir.rglob("*.md"))


def navigation_urls(path: Path) -> list[str]:
    urls: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = NAVIGATION_URL.match(line)
        if match:
            urls.append(next(value for value in match.groups() if value is not None))
    return urls


def output_path(site_dir: Path, url: str) -> Path:
    if url == "/":
        return site_dir / "index.html"
    if url.endswith("/"):
        return site_dir / url.lstrip("/") / "index.html"
    return site_dir / url.lstrip("/")


class GeneratedPage(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.primary_navigation = False
        self.primary_links: list[str] = []
        self.current_links: list[str] = []
        self.hrefs: list[str] = []
        self.anchors: set[str] = set()
        self.has_mobile_navigation = False
        self.has_main_content = False
        self.has_skip_link = False
        self.has_color_scheme = False
        self.code_depth = 0
        self.code_text: list[str] = []

    @staticmethod
    def _attributes(attrs: list[tuple[str, str | None]]) -> dict[str, str]:
        return {name: value or "" for name, value in attrs}

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = self._attributes(attrs)
        classes = attributes.get("class", "").split()

        if identifier := attributes.get("id"):
            self.anchors.add(identifier)
        if tag == "a" and (name := attributes.get("name")):
            self.anchors.add(name)

        if tag == "nav" and attributes.get("aria-label") == "Primary":
            self.primary_navigation = True
        elif tag == "details" and "mobile-nav" in classes:
            self.has_mobile_navigation = True
        elif tag == "main" and attributes.get("id") == "main-content":
            self.has_main_content = True
        elif (
            tag == "meta"
            and attributes.get("name") == "color-scheme"
            and attributes.get("content") == "light dark"
        ):
            self.has_color_scheme = True

        if tag == "a":
            href = attributes.get("href")
            if href is not None:
                self.hrefs.append(href)
            if self.primary_navigation and href is not None:
                self.primary_links.append(href)
                if attributes.get("aria-current") == "page":
                    self.current_links.append(href)
            if "skip-link" in classes and href == "#main-content":
                self.has_skip_link = True
        if tag == "code":
            self.code_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag == "nav" and self.primary_navigation:
            self.primary_navigation = False
        if tag == "code" and self.code_depth:
            self.code_depth -= 1

    def handle_data(self, data: str) -> None:
        if self.code_depth:
            self.code_text.append(data)


def unprotected_template_literals(text: str) -> list[str]:
    protected = {
        match.start(1)
        for match in re.finditer(r"\{% raw %\}(\{\{[^}\n]+\}\})\{% endraw %\}", text)
    }
    return [match.group(0) for match in RAVENROOT_TEMPLATE.finditer(text)
            if match.start() not in protected]


def parse_generated_page(path: Path) -> GeneratedPage:
    page = GeneratedPage()
    page.feed(path.read_text(encoding="utf-8"))
    return page


def generated_page_url(path: Path, site_dir: Path) -> str:
    relative = path.relative_to(site_dir)
    if relative.name == "index.html":
        parent = relative.parent.as_posix()
        return "/" if parent == "." else f"/{parent}/"
    return f"/{relative.as_posix()}"


def internal_target(source_url: str, href: str) -> tuple[str, str] | None:
    """Resolve one local href to its rendered URL path and decoded fragment.

    Schemed and protocol-relative URLs leave the generated site, so they are not
    local targets for this checker. Queries do not select a generated file and
    are deliberately ignored after normal URL resolution.
    """
    parsed = urlsplit(href)
    if parsed.scheme or parsed.netloc:
        return None
    resolved = urlsplit(urljoin(f"https://docs.invalid{source_url}", href))
    return unquote(resolved.path) or source_url, unquote(resolved.fragment)


def validate_internal_links(site_dir: Path) -> list[str]:
    """Check every rendered local anchor href points at a file and, if present, an anchor."""
    pages = sorted(site_dir.rglob("*.html"))
    parsed_pages = {generated_page_url(path, site_dir): parse_generated_page(path) for path in pages}
    errors: list[str] = []

    for source_url, page in parsed_pages.items():
        for href in page.hrefs:
            target = internal_target(source_url, href)
            if target is None:
                continue
            target_url, fragment = target
            target_path = output_path(site_dir, target_url)
            if not target_path.is_file():
                errors.append(
                    f"Internal link target is missing on {source_url}: {href!r} resolves to {target_url}"
                )
                continue
            if fragment:
                target_page = parsed_pages.get(target_url)
                if target_page is None or fragment not in target_page.anchors:
                    errors.append(
                        f"Internal link anchor is missing on {source_url}: {href!r} resolves to "
                        f"{target_url}#{fragment}"
                    )

    return errors


def validate_source(source_dir: Path) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    documents = document_urls(source_dir)
    navigation = navigation_urls(source_dir / "_data" / "navigation.yml")

    duplicate_urls = sorted({url for url in navigation if navigation.count(url) > 1})
    if duplicate_urls:
        errors.append(f"Duplicate navigation URLs: {', '.join(duplicate_urls)}")

    missing = sorted(set(documents) - set(navigation))
    extra = sorted(set(navigation) - set(documents))
    if missing:
        errors.append(f"Documentation pages missing from navigation: {', '.join(missing)}")
    if extra:
        errors.append(f"Navigation URLs without a documentation page: {', '.join(extra)}")

    cname = (source_dir / "CNAME").read_text(encoding="utf-8").strip()
    if cname != "docs.ravenroot.ai":
        errors.append("docs/CNAME must contain exactly docs.ravenroot.ai")

    for path in sorted(source_dir.rglob("*.md")):
        unprotected = unprotected_template_literals(path.read_text(encoding="utf-8"))
        if unprotected:
            errors.append(
                f"Ravenroot template literals are not protected from Liquid in {path}: "
                + ", ".join(sorted(set(unprotected)))
            )

    return navigation, errors


def validate_generated_site(site_dir: Path, navigation: list[str]) -> list[str]:
    errors: list[str] = []
    expected_links = set(navigation)

    for url in navigation:
        path = output_path(site_dir, url)
        if not path.is_file():
            errors.append(f"Generated page is missing for {url}: {path}")
            continue

        page = parse_generated_page(path)
        if set(page.primary_links) != expected_links:
            errors.append(f"Primary navigation is incomplete on {url}")
        if page.current_links != [url]:
            errors.append(f"Active navigation state is incorrect on {url}: {page.current_links}")
        if not page.has_mobile_navigation:
            errors.append(f"Mobile navigation is missing on {url}")
        if not page.has_main_content:
            errors.append(f"Main content landmark is missing on {url}")
        if not page.has_skip_link:
            errors.append(f"Skip link is missing on {url}")
        if not page.has_color_scheme:
            errors.append(f"Light and dark color-scheme metadata is missing on {url}")
        rendered_code = "".join(page.code_text)
        for literal in sorted(RENDERED_CODE_LITERALS.get(url, set())):
            if literal not in rendered_code:
                errors.append(f"Rendered code on {url} is missing Ravenroot literal {literal}")

    stylesheet = site_dir / "assets" / "css" / "site.css"
    if not stylesheet.is_file():
        errors.append("Generated documentation stylesheet is missing")
    else:
        css = stylesheet.read_text(encoding="utf-8")
        for contract in (
            "prefers-color-scheme: dark",
            ".sidebar",
            ".mobile-nav",
            ":focus-visible",
        ):
            if contract not in css:
                errors.append(f"Generated stylesheet is missing {contract!r}")
        if not re.search(r"color-scheme:\s*light dark", css):
            errors.append("Generated stylesheet does not declare the light and dark color scheme")

    errors.extend(validate_internal_links(site_dir))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=Path("docs"))
    parser.add_argument("--site-dir", type=Path, default=Path("_site"))
    args = parser.parse_args()

    navigation, errors = validate_source(args.source_dir)
    errors.extend(validate_generated_site(args.site_dir, navigation))
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(
        f"Documentation site check passed ({len(navigation)} pages and navigation links)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
