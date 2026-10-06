#!/usr/bin/env python3
"""Package the independently installable UI deterministically from compiled bytes."""
import argparse
import datetime
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def package(dist: Path, output: Path, epoch: int) -> None:
    if not (dist / 'index.html').is_file():
        raise ValueError('Build the frontend before packaging')
    stamp = datetime.datetime.fromtimestamp(max(epoch, 315532800), datetime.timezone.utc)
    output.parent.mkdir(parents=True, exist_ok=True)
    files = [(path, 'ravenroot-ui/ui/' + path.relative_to(dist).as_posix())
             for path in sorted(dist.rglob('*')) if path.is_file()]
    files += [(ROOT / 'ui-server/server.mjs', 'ravenroot-ui/server.mjs'),
              (ROOT / 'ui-server/README.md', 'ravenroot-ui/README.md'),
              (ROOT / 'LICENSE', 'ravenroot-ui/LICENSE')]
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for path, name in files:
            if path.is_symlink():
                raise ValueError('UI package cannot contain symbolic links')
            entry = zipfile.ZipInfo(name, stamp.timetuple()[:6])
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, path.read_bytes())

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dist', type=Path, default=ROOT / 'ravenroot/ravenroot-ui/dist')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--epoch', type=int, default=int(os.environ.get('SOURCE_DATE_EPOCH', '315532800')))
    args = parser.parse_args()
    package(args.dist, args.output, args.epoch)
