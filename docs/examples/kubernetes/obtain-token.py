#!/usr/bin/env python3
"""Obtain a personal Keycloak device-flow access token without exposing it in terminal output."""
import argparse
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def post(url, values):
    request = urllib.request.Request(url, data=urllib.parse.urlencode(values).encode(),
        headers={'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try:
            return {'error': json.load(error).get('error', 'http_error')}
        except (ValueError, TypeError):
            return {'error': 'http_error'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--issuer', required=True)
    parser.add_argument('--client', default='ravenroot-workstation')
    parser.add_argument('--output', type=Path, default=Path('ravenroot-access-token.txt'))
    args = parser.parse_args()
    issuer = args.issuer.rstrip('/')
    parsed = urllib.parse.urlparse(issuer)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise SystemExit('Use a canonical HTTPS issuer without credentials, query or fragment.')
    if args.output.exists():
        raise SystemExit('Output already exists; select a new permission-restricted file.')
    endpoint = issuer + '/protocol/openid-connect'
    device = post(endpoint + '/auth/device', {'client_id': args.client, 'scope': 'openid'})
    if 'device_code' not in device:
        raise SystemExit('Device authorization refused: ' + str(device.get('error', 'invalid_response')))
    print('Open in your browser:', device['verification_uri'])
    print('Enter this temporary device user code:', device['user_code'])
    interval = max(1, int(device.get('interval', 5)))
    deadline = time.monotonic() + int(device['expires_in'])
    while time.monotonic() < deadline:
        time.sleep(interval)
        if time.monotonic() >= deadline:
            break
        result = post(endpoint + '/token', {
            'grant_type': 'urn:ietf:params:oauth:grant-type:device_code',
            'client_id': args.client, 'device_code': device['device_code']})
        if 'access_token' in result:
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as stream:
                stream.write(result['access_token'])
            print('Saved personal access token to:', args.output)
            print('Declared lifetime (seconds):', result.get('expires_in', 'unknown'))
            print('Paste into Service token, then remove the file. Never share the token.')
            return
        error = result.get('error', 'invalid_response')
        if error == 'authorization_pending':
            continue
        if error == 'slow_down':
            interval += 5
            continue
        raise SystemExit('Login stopped: ' + str(error))
    raise SystemExit('Device code expired; restart authorization.')


if __name__ == '__main__':
    main()
