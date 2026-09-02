import tempfile
import unittest
from pathlib import Path

from scripts.check_docs_site import (
    GeneratedPage,
    document_url,
    navigation_urls,
    output_path,
)


class CheckDocsSiteTest(unittest.TestCase):
    def test_document_url_preserves_existing_pages_paths(self):
        source = Path("docs")
        self.assertEqual("/", document_url(source / "index.md", source))
        self.assertEqual(
            "/get-started/",
            document_url(source / "get-started" / "index.md", source),
        )
        self.assertEqual(
            "/get-started/install-start.html",
            document_url(source / "get-started" / "install-start.md", source),
        )

    def test_navigation_urls_accepts_quoted_and_plain_values(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "navigation.yml"
            path.write_text(
                """- title: Start
  items:
    - title: Home
      url: /
    - title: Guide
      url: "/guide/"
""",
                encoding="utf-8",
            )
            self.assertEqual(["/", "/guide/"], navigation_urls(path))

    def test_output_path_maps_pretty_and_html_urls(self):
        site = Path("_site")
        self.assertEqual(site / "index.html", output_path(site, "/"))
        self.assertEqual(site / "guide" / "index.html", output_path(site, "/guide/"))
        self.assertEqual(site / "guide.html", output_path(site, "/guide.html"))

    def test_generated_page_recognizes_accessibility_and_navigation_contracts(self):
        page = GeneratedPage()
        page.feed(
            """<!doctype html><html><head>
            <meta name="color-scheme" content="light dark"></head><body>
            <a class="skip-link" href="#main-content">Skip</a>
            <nav aria-label="Primary"><a href="/" aria-current="page">Home</a></nav>
            <main id="main-content"></main>
            <details class="mobile-nav"></details>
            </body></html>"""
        )
        self.assertEqual(["/"], page.primary_links)
        self.assertEqual(["/"], page.current_links)
        self.assertTrue(page.has_color_scheme)
        self.assertTrue(page.has_main_content)
        self.assertTrue(page.has_mobile_navigation)
        self.assertTrue(page.has_skip_link)


if __name__ == "__main__":
    unittest.main()
