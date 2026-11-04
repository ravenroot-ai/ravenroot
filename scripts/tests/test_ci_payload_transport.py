"""Complete CI data travels as inert bounded files, never execve environment payloads."""
from __future__ import annotations
import copy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from scripts import ci_required as ci

ROOT = Path(__file__).resolve().parents[2]


def envelope(plan, tier='full'):
    value = {job: {'result': 'success' if tier == 'full' or job == ci.CLASSIFICATION_JOB else 'skipped',
                   'outputs': {}} for job in [ci.CLASSIFICATION_JOB, *ci.GATED_JOBS]}
    value[ci.CLASSIFICATION_JOB]['outputs'] = {'plan': json.dumps(plan, separators=(',', ':')), 'tier': tier}
    return value


class NeedsFileTest(unittest.TestCase):
    def test_complete_payload_and_plan_survive_without_projection(self):
        self.assertEqual(ci.MAX_NEEDS_BYTES, 8388608)
        value = envelope({'candidate': 'a' * 40, 'nested': {'opaque': ['one', 2, None]}})
        value['full-ui-e2e']['outputs']['opaque'] = {'all': ['retained', True]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'needs.json'; path.write_text(json.dumps(value))
            payload, plan = ci.load_needs_file(path)
            self.assertEqual(payload, value)
            self.assertEqual(plan, json.loads(value[ci.CLASSIFICATION_JOB]['outputs']['plan']))

    def test_bad_files_never_fall_back_to_environment(self):
        good = envelope({'candidate': 'a' * 40})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'needs.json'
            with self.assertRaises(ValueError): ci.load_needs_file(None)
            with self.assertRaises(OSError): ci.load_needs_file(path)
            for raw in ['{bad', '[]', '{}', '{"a":{},"a":{}}', '{"a":null}',
                        json.dumps({ci.CLASSIFICATION_JOB: {'outputs': {'plan': '[]'}}}),
                        json.dumps({ci.CLASSIFICATION_JOB: {'outputs': {'plan': '{"x":1,"x":2}'}}}),
                        json.dumps({ci.CLASSIFICATION_JOB: {'outputs': {'plan': '{"x":NaN}'}}}),
                        json.dumps({ci.CLASSIFICATION_JOB: {'result': True, 'outputs': {'plan': '{}'}}})]:
                with self.subTest(raw=raw):
                    path.write_text(raw)
                    with self.assertRaises((ValueError, TypeError)): ci.load_needs_file(path)
            raw = json.dumps(good).encode(); path.write_bytes(raw + b' ' * (ci.MAX_NEEDS_BYTES + 1 - len(raw)))
            with self.assertRaisesRegex(ValueError, '8 MiB'): ci.load_needs_file(path)
            path.write_bytes(b'\xff')
            with self.assertRaises(UnicodeError): ci.load_needs_file(path)
            link = Path(directory) / 'link'; link.symlink_to(path)
            with self.assertRaises(OSError): ci.load_needs_file(link)
            with self.assertRaises(ValueError): ci.load_needs_file(Path(directory))

    def test_missing_file_refuses_even_with_valid_legacy_environment(self):
        from unittest.mock import patch
        with patch.dict(os.environ, {'NEEDS_JSON': json.dumps(envelope({'x': 1})), 'IMPACT_PLAN': '{"x":1}'}):
            self.assertEqual(ci.main(['--needs-file', '/missing-ci-input']), 1)
            self.assertEqual(ci.main([]), 1)

    def test_fast_and_print_contexts_keep_their_existing_small_interfaces(self):
        import contextlib
        import io
        from unittest.mock import patch
        fast = {job: {'result': 'success'} for job in ci.FAST_JOBS}
        with patch.dict(os.environ, {'NEEDS_JSON': json.dumps(fast)}, clear=True), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(ci.main(['--fast']), 0)
        output = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), contextlib.redirect_stdout(output):
            self.assertEqual(ci.main(['--print-contexts', 'promotion']), 0)
        self.assertEqual(output.getvalue().splitlines(), ci.required_contexts('promotion'))

    def test_workflow_requires_typed_file_transport_for_every_large_consumer(self):
        text = ci.WORKFLOW.read_text()
        self.assertEqual(ci.verify_workflow(text), [])
        for job in [ci.GATE_JOB, 'full-preflight', 'full-regression']:
            block = ci.job_blocks(text)[job]
            mutations = [block.replace('${{ toJSON(needs) }}', '${{ needs.release-classification.outputs.plan }}'),
                         block.replace('umask 077', 'umask 022'),
                         block.replace('set -o noclobber', ''),
                         block.replace(str(ci.MAX_NEEDS_BYTES), str(ci.MAX_NEEDS_BYTES + 1)),
                         block.replace('        run: |', '        env:\n          RESULTS: ${{ toJSON(needs) }}\n        run: |', 1)]
            if job == ci.GATE_JOB:
                mutations.append(block.replace(' --needs-file "$RUNNER_TEMP/ci-required-needs.json"', ''))
            else:
                mutations.append(block.replace('.value.result == "success"', '.value.result != "failure"'))
            for changed in mutations:
                with self.subTest(job=job, changed=changed[-120:]):
                    self.assertNotEqual(changed, block)
                    self.assertTrue(ci.verify_workflow(text.replace(block, changed, 1)))


@unittest.skipUnless(sys.platform.startswith('linux'), 'execve environment limit regression runs on Linux')
class LinuxPayloadTransportTest(unittest.TestCase):
    def test_real_producer_and_verifier_with_release_shaped_and_larger_complete_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); repo = root / 'repo'; repo.mkdir()
            (repo / 'scripts').mkdir()
            for name in ['ci_required.py', 'impact_plan.py']:
                shutil.copyfile(ROOT / 'scripts' / name, repo / 'scripts' / name)
            shutil.copytree(ROOT / '.github/workflows', repo / '.github/workflows')
            (repo / 'ravenroot').mkdir()
            (repo / 'ravenroot/pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>ai.ravenroot</groupId><artifactId>ravenroot-parent</artifactId><version>1</version></project>')
            env = {'PATH': os.environ['PATH'], 'PYTHONDONTWRITEBYTECODE': '1'}
            def git(*args):
                return subprocess.check_output(['git', '-c', 'user.name=CI Fixture', '-c',
                    'user.email=ci@example.invalid', *args], cwd=repo, env=env, text=True).strip()
            git('init', '-q')
            paths = [repo / ('docs/transport/' + 'fixture-public-path-' * 10 + str(i) + '.md') for i in range(900)]
            for path in paths: path.parent.mkdir(parents=True, exist_ok=True); path.write_text('before\n')
            git('add', '.'); git('commit', '-qm', 'base'); base = git('rev-parse', 'HEAD')
            for count in [592, 900]:
                for path in paths[:count]: path.write_text('after\n')
                git('add', '.'); git('commit', '-qm', 'candidate'); head = git('rev-parse', 'HEAD')
                produced = root / 'plan.json'
                subprocess.run([sys.executable, 'scripts/impact_plan.py', '--base', base, '--candidate', head,
                    '--event', 'workflow_dispatch', '--lane', 'dev', '--force-complete', '--output', str(produced)],
                    cwd=repo, env=env, check=True, capture_output=True)
                plan = json.loads(produced.read_text()); self.assertEqual(len(plan['changed']), count)
                value = envelope(plan)
                value[ci.CLASSIFICATION_JOB]['outputs']['inert'] = '$(' + 'touch INJECT_A) `touch INJECT_B` "quote" \'quote\'\nRAVENROOT_NEEDS_JSON\ntouch INJECT_C\n${HOME}'
                stage = root / 'stage.sh'
                stage.write_text(ci.needs_file_script(ci.GATE_JOB).replace('${{ toJSON(needs) }}', json.dumps(value, indent=2)))
                needs_path = root / 'ci-required-needs.json'
                if needs_path.exists(): needs_path.unlink()
                subprocess.run(['/usr/bin/bash', str(stage)], cwd=repo, env={**env, 'RUNNER_TEMP': str(root)}, check=True)
                self.assertEqual(json.loads(needs_path.read_text()), value)
                for name in ['INJECT_A', 'INJECT_B', 'INJECT_C']: self.assertFalse((repo / name).exists())
                result_env = {**env, 'CI_TIER': 'full', 'CI_CANCELLED': 'false', 'EVENT_NAME': 'workflow_dispatch',
                              'IMPACT_LANE': 'dev', 'IMPACT_BASE': base}
                def consume(data=value, overrides=None):
                    needs_path.write_text(json.dumps(data))
                    return subprocess.run([sys.executable, 'scripts/ci_required.py', '--needs-file', str(needs_path)],
                        cwd=repo, env={**result_env, **(overrides or {})}, capture_output=True, text=True)
                result = consume(); self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('ci-required: tier full satisfied', result.stdout)
                for state in ['failure', 'skipped', 'cancelled']:
                    bad = copy.deepcopy(value); bad['full-backend-tests']['result'] = state
                    self.assertEqual(consume(bad).returncode, 1)
                bad = copy.deepcopy(value); del bad['full-backend-tests']
                self.assertEqual(consume(bad).returncode, 1)
                self.assertEqual(consume(overrides={'CI_CANCELLED': 'true'}).returncode, 1)
                for key, changed in [('tree', '0' * 40), ('candidate', '0' * 40), ('event', 'push'), ('lane', 'release/0.8')]:
                    bad = copy.deepcopy(value); wrong = copy.deepcopy(plan); wrong[key] = changed
                    bad[ci.CLASSIFICATION_JOB]['outputs']['plan'] = json.dumps(wrong)
                    self.assertEqual(consume(bad).returncode, 1)
                if count == 900:
                    for name, raw in [('NEEDS_JSON', json.dumps(value)), ('IMPACT_PLAN', json.dumps(plan))]:
                        self.assertGreater(len(raw.encode()), 32 * os.sysconf('SC_PAGESIZE'))
                        with self.assertRaises(OSError) as raised:
                            subprocess.run(['/usr/bin/bash', '-c', 'exit 0'], env={**env, name: raw})
                        self.assertEqual(raised.exception.errno, 7)
                # Promotion uses the same full object and exact source, but no functional rerun.
                promotion = copy.deepcopy(plan); promotion['event'] = 'pull_request'
                promoted = envelope(promotion, 'promotion')
                evidence = {'workflow_runs': [{'head_sha': head, 'path': '.github/workflows/ci.yml',
                    'event': 'workflow_dispatch', 'head_branch': 'dev', 'conclusion': 'success',
                    'head_repository': {'full_name': 'ravenroot-ai/ravenroot'},
                    'updated_at': datetime.now(timezone.utc).isoformat()}]}
                evidence_file = root / 'evidence.json'; evidence_file.write_text(json.dumps(evidence))
                promotion_env = {'CI_TIER': 'promotion', 'EVENT_NAME': 'pull_request', 'BASE_REF': 'main',
                    'HEAD_REF': 'dev', 'HEAD_REPOSITORY': 'ravenroot-ai/ravenroot', 'REPOSITORY': 'ravenroot-ai/ravenroot',
                    'PROMOTED_SHA': head, 'PROMOTION_EVIDENCE': str(evidence_file)}
                self.assertEqual(consume(promoted, promotion_env).returncode, 0)
                for key, changed in [('head_sha', '0' * 40), ('conclusion', 'cancelled'),
                                     ('updated_at', (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()),
                                     ('head_repository', {'full_name': 'foreign/repository'})]:
                    bad = copy.deepcopy(evidence); bad['workflow_runs'][0][key] = changed
                    evidence_file.write_text(json.dumps(bad))
                    self.assertEqual(consume(promoted, promotion_env).returncode, 1)
                evidence_file.write_text(json.dumps(evidence))
