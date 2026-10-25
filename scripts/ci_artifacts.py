#!/usr/bin/env python3
"""Seal/verify same-run artifacts before consuming current-candidate Maven/UI outputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def provenance():
    return {k: os.environ.get(k, '') for k in ('GITHUB_REPOSITORY', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')} | {
        'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        'tree': subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip(),
    }


def hashes(root):
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('symlinks are not accepted in verified artifacts')
        if path.is_file() and path != root / 'provenance.json':
            result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not result:
        raise ValueError('empty artifact')
    return result


def seal(root):
    record = {'schema': 1, 'producer': provenance(), 'files': hashes(root)}
    (root / 'provenance.json').write_text(json.dumps(record, indent=2) + '\n')


def verify(root):
    record = json.loads((root / 'provenance.json').read_text())
    if record.get('schema') != 1 or record.get('producer') != provenance() or record.get('files') != hashes(root):
        raise ValueError('artifact provenance/content does not match this exact candidate and run')
    for key in ('GITHUB_REPOSITORY', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        if not record['producer'].get(key):
            raise ValueError('trusted Actions producer identity is required')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('operation', choices=['seal', 'verify'])
    p.add_argument('root', type=Path)
    args = p.parse_args()
    (seal if args.operation == 'seal' else verify)(args.root)
