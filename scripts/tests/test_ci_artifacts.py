import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from scripts import ci_artifacts

class ArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        (self.root/'current.jar').write_text('exact current-candidate artifact')
        self.env=patch.dict(os.environ,{'GITHUB_REPOSITORY':'ravenroot-ai/ravenroot','GITHUB_RUN_ID':'17','GITHUB_RUN_ATTEMPT':'1'})
        self.env.start()
    def tearDown(self): self.env.stop(); self.temp.cleanup()
    def test_exact_same_candidate_and_run_can_consume(self):
        ci_artifacts.seal(self.root); ci_artifacts.verify(self.root)
    def test_mutation_missing_file_or_unexpected_file_fails(self):
        for operation in ('mutate','delete','inject'):
            (self.root/'current.jar').write_text('original'); ci_artifacts.seal(self.root)
            if operation=='mutate': (self.root/'current.jar').write_text('stale')
            if operation=='delete': (self.root/'current.jar').unlink()
            if operation=='inject': (self.root/'extra.jar').write_text('stale')
            with self.assertRaises(ValueError): ci_artifacts.verify(self.root)
    def test_other_attempt_or_commit_or_absent_producer_is_refused(self):
        ci_artifacts.seal(self.root)
        for updates in ({'GITHUB_RUN_ID':'18'},{'GITHUB_RUN_ATTEMPT':'2'},{'GITHUB_REPOSITORY':''}):
            with patch.dict(os.environ,updates), self.assertRaises(ValueError): ci_artifacts.verify(self.root)
        with patch.object(ci_artifacts,'provenance',return_value={'commit':'another'}), self.assertRaises(ValueError): ci_artifacts.verify(self.root)
    def test_symlink_and_empty_artifacts_fail(self):
        (self.root/'link').symlink_to(self.root/'current.jar')
        with self.assertRaises(ValueError): ci_artifacts.seal(self.root)

if __name__ == '__main__': unittest.main()
