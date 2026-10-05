#!/usr/bin/env python3
"""Exercise the shipped compiled UI in a non-root/read-only container."""
import argparse
import json
import re
import subprocess
import time
import urllib.request

def docker(*args):
    return subprocess.check_output(['docker', *args], text=True).strip()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    args = parser.parse_args()
    image = json.loads(docker('image', 'inspect', args.image))[0]
    assert image['Config']['User'] == '10001:10001'
    assert image['Config']['Entrypoint'] == ['node', '/opt/ravenroot/server.mjs']
    container = docker('run', '-d', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
        '-p', '127.0.0.1::8080', '-e', 'RAVENROOT_UI_PREFIX=/workspace', args.image)
    try:
        port = docker('port', container, '8080/tcp').rsplit(':', 1)[1]
        base = 'http://127.0.0.1:' + port
        for attempt in range(60):
            try:
                with urllib.request.urlopen(base + '/health', timeout=1) as response:
                    assert json.load(response)['status'] == 'UP'
                break
            except OSError:
                if attempt == 59: raise
                time.sleep(0.25)
        with urllib.request.urlopen(base + '/workspace/') as response:
            html = response.read().decode()
            assert 'id="service-url" value="/workspace"' in html
            assert './assets/' in html
            assets = re.findall(r'(?:src|href)="(\./assets/[^"]+)"', html)
            assert assets
            for asset in assets:
                with urllib.request.urlopen(base + '/workspace/' + asset.removeprefix('./')) as asset_response:
                    assert asset_response.status == 200
                    assert asset_response.read(1)
            # Same compiled assets also work with the root deployment, without a prefix.
        docker('rm', '-f', container)
        container = docker('run', '-d', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
            '-p', '127.0.0.1::8080', args.image)
        port = docker('port', container, '8080/tcp').rsplit(':', 1)[1]
        base = 'http://127.0.0.1:' + port
        for attempt in range(60):
            try:
                with urllib.request.urlopen(base + '/') as response:
                    html = response.read().decode()
                break
            except OSError:
                if attempt == 59: raise
                time.sleep(0.25)
        assert 'id="service-url" value=""' in html
        for asset in assets:
            with urllib.request.urlopen(base + '/' + asset.removeprefix('./')) as response:
                assert response.status == 200
        assert docker('exec', container, 'node', '-e',
            "console.log(process.getuid()); const fs=require('fs'); console.log(fs.existsSync('/opt/ravenroot/ravenroot.jar'))") == '10001\nfalse'
        print('Compiled UI served successfully as non-root on read-only filesystem.')
    finally:
        docker('rm', '-f', container)
