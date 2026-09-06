import tempfile
import unittest
from pathlib import Path

from scripts.check_docs_site import (
    GeneratedPage,
    document_url,
    generated_page_url,
    internal_target,
    navigation_urls,
    output_path,
    unprotected_template_literals,
    validate_internal_links,
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

    def test_liquid_guard_detects_a_mutated_unprotected_ravenroot_template(self):
        self.assertEqual(["{{payload}}"], unprotected_template_literals("`{{payload}}`"))
        self.assertEqual(
            [],
            unprotected_template_literals("`{% raw %}{{payload}}{% endraw %}`"),
        )

    def test_generated_page_collects_exact_rendered_code_literals(self):
        page = GeneratedPage()
        page.feed("<pre><code>Hello, {{payload}} and {{attributes.name}}</code></pre>")
        self.assertEqual("Hello, {{payload}} and {{attributes.name}}", "".join(page.code_text))

    def test_generated_page_collects_content_links_and_anchor_targets(self):
        page = GeneratedPage()
        page.feed('<section id="details"><a href="child.html#next">Next</a><a name="legacy"></a></section>')
        self.assertEqual(["child.html#next"], page.hrefs)
        self.assertEqual({"details", "legacy"}, page.anchors)

    def test_internal_target_resolves_relative_root_query_and_percent_encoded_fragment(self):
        self.assertEqual(("/reference/page.html", "section one"),
                         internal_target("/guide/", "../reference/page.html?view=full#section%20one"))
        self.assertEqual(("/guide/", "local"), internal_target("/guide/", "#local"))
        self.assertIsNone(internal_target("/guide/", "https://example.test/page#external"))
        self.assertIsNone(internal_target("/guide/", "//cdn.example.test/site.css"))
        self.assertIsNone(internal_target("/guide/", "mailto:docs@example.test"))

    def test_generated_page_url_maps_index_and_html_pages(self):
        site = Path("_site")
        self.assertEqual("/", generated_page_url(site / "index.html", site))
        self.assertEqual("/guide/", generated_page_url(site / "guide" / "index.html", site))
        self.assertEqual("/reference/page.html", generated_page_url(site / "reference" / "page.html", site))

    def test_internal_link_validation_accepts_relative_root_query_and_fragments(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            site = Path(temporary_directory)
            (site / "guide").mkdir()
            (site / "reference").mkdir()
            (site / "index.html").write_text('<main id="top anchor"></main>', encoding="utf-8")
            (site / "reference" / "page.html").write_text('<h1 id="section one">Reference</h1>', encoding="utf-8")
            (site / "guide" / "index.html").write_text(
                '''<a href="../?view=summary#top%20anchor">Home</a>
                <a href="../reference/page.html?source=guide#section%20one">Reference</a>
                <a href="https://example.test/remote#ignored">External</a>
                <a href="mailto:docs@example.test">Mail</a>''',
                encoding="utf-8",
            )
            self.assertEqual([], validate_internal_links(site))

    def test_internal_link_validation_reports_missing_target_and_anchor(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            site = Path(temporary_directory)
            (site / "index.html").write_text(
                '<a href="missing.html">Missing page</a><a href="#missing-anchor">Missing anchor</a>',
                encoding="utf-8",
            )
            errors = validate_internal_links(site)
            self.assertEqual(2, len(errors))
            self.assertTrue(any("target is missing" in error and "missing.html" in error for error in errors))
            self.assertTrue(any("anchor is missing" in error and "missing-anchor" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
