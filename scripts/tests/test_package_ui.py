import tempfile
import unittest
import zipfile
from pathlib import Path
from scripts.package_ui import package

class UiPackageTest(unittest.TestCase):
    def test_reproducible_archive_contains_compiled_bytes_and_no_backend(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = root / 'dist'
            dist.mkdir()
            (dist / 'index.html').write_text('<html>compiled UI</html>')
            (dist / 'assets').mkdir()
            (dist / 'assets/app.js').write_text('compiled')
            first, second = root / 'first.zip', root / 'second.zip'
            package(dist, first, 1770000000)
            package(dist, second, 1770000000)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(archive.read('ravenroot-ui/ui/assets/app.js'), b'compiled')
                self.assertIn('ravenroot-ui/server.mjs', archive.namelist())
                self.assertIn('ravenroot-ui/LICENSE', archive.namelist())
                self.assertFalse(any(name.endswith('.jar') for name in archive.namelist()))
            (dist / 'assets/linked').symlink_to(dist / 'index.html')
            with self.assertRaises(ValueError):
                package(dist, first, 1770000000)
