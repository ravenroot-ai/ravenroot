import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('oidc_device_example', ROOT / 'docs/examples/kubernetes/obtain-token.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)

class DeviceExampleTest(unittest.TestCase):
    def test_slow_down_interval_and_secure_exclusive_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'access-token'
            arguments = ['obtain-token.py', '--issuer', 'https://identity.example.test/realms/ravenroot', '--output', str(output)]
            clock = [0]
            waits = []
            def sleep(interval):
                waits.append(interval)
                clock[0] += interval
            values = [
                {'device_code': 'private-device', 'verification_uri': 'https://identity.example.test/device',
                 'user_code': 'temporary', 'interval': 2, 'expires_in': 120},
                {'error': 'authorization_pending'}, {'error': 'slow_down'},
                {'access_token': 'private-access-token', 'expires_in': 300}]
            with mock.patch('sys.argv', arguments), mock.patch.object(helper, 'post', side_effect=values), \
                 mock.patch.object(helper.time, 'monotonic', side_effect=lambda: clock[0]), \
                 mock.patch.object(helper.time, 'sleep', side_effect=sleep), mock.patch('builtins.print') as printed:
                helper.main()
            self.assertEqual(waits, [2, 2, 7])
            self.assertEqual(output.read_text(), 'private-access-token')
            self.assertEqual(os.stat(output).st_mode & 0o777, 0o600)
            self.assertNotIn('private-access-token', str(printed.call_args_list))
            self.assertNotIn('private-device', str(printed.call_args_list))
            with mock.patch('sys.argv', arguments), mock.patch.object(helper, 'post') as posted:
                with self.assertRaises(SystemExit): helper.main()
                posted.assert_not_called()

    def test_insecure_issuer_is_refused_before_contact(self):
        with mock.patch('sys.argv', ['obtain-token.py', '--issuer', 'http://identity.example.test']), \
             mock.patch.object(helper, 'post') as posted:
            with self.assertRaises(SystemExit): helper.main()
            posted.assert_not_called()
