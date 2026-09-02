#!/usr/bin/env python3
"""Validate the source navigation and generated GitHub Pages documentation site."""

from __future__ import annotations

import argparse
import re
import sys
from html.parser import HTMLParser
from pathlib import Path


NAVIGATION_URL = re.compile(
    r"^\s+url:\s*(?:\"([^\"]+)\"|'([^']+)'|([^\s#]+))\s*$"
)


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
        self.has_mobile_navigation = False
        self.has_main_content = False
        self.has_skip_link = False
        self.has_color_scheme = False

    @staticmethod
    def _attributes(attrs: list[tuple[str, str | None]]) -> dict[str, str]:
        return {name: value or "" for name, value in attrs}

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = self._attributes(attrs)
        classes = attributes.get("class", "").split()

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
            href = attributes.get("href", "")
            if self.primary_navigation:
                self.primary_links.append(href)
                if attributes.get("aria-current") == "page":
                    self.current_links.append(href)
            if "skip-link" in classes and href == "#main-content":
                self.has_skip_link = True

    def handle_endtag(self, tag: str) -> None:
        if tag == "nav" and self.primary_navigation:
            self.primary_navigation = False


def parse_generated_page(path: Path) -> GeneratedPage:
    page = GeneratedPage()
    page.feed(path.read_text(encoding="utf-8"))
    return page


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
