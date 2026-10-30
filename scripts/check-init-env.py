"""Test the initializer only in temporary directories; never access the workspace .env."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('scripts/init-env.py')
example = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('.env.example')
sys.argv = [sys.argv[0]]

class InitEnvTests(unittest.TestCase):
    def workspace(self, root):
        (root / 'scripts').mkdir()
        shutil.copyfile(source, root / 'scripts/init-env.py')
        shutil.copyfile(example, root / '.env.example')

    def run_initializer(self, root):
        subprocess.run([sys.executable, str(root / 'scripts/init-env.py')],
                       check=True, capture_output=True, text=True)

    def test_generates_distinct_secrets_without_placeholders(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.workspace(root)
            self.run_initializer(root)
            values = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines()
                          if line and not line.startswith('#'))
            keys = ['DB_PASSWORD', 'JWT_SECRET', 'OTP_SECRET', 'ADMIN_PASSWORD', 'DEMO_PASSWORD']
            self.assertEqual(len({values[key] for key in keys}), len(keys))
            for key in keys:
                self.assertNotIn('CHANGE_ME', values[key])
                self.assertGreaterEqual(len(values[key]), 32)

    def test_keeps_existing_file_byte_for_byte(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.workspace(root)
            content = b'EXISTING_VALUE=keep-test-only\r\n'
            (root / '.env').write_bytes(content)
            self.run_initializer(root)
            self.assertEqual((root / '.env').read_bytes(), content)

    def test_example_disables_external_services_without_credentials(self):
        values = dict(line.split('=', 1) for line in example.read_text(encoding='utf-8').splitlines()
                      if line and not line.startswith('#'))
        self.assertEqual(values['MAIL_ENABLED'], 'false')
        for key in ['SMTP_USERNAME', 'SMTP_PASSWORD', 'MAIL_FROM',
                    'CLOUDINARY_CLOUD_NAME', 'CLOUDINARY_API_KEY', 'CLOUDINARY_API_SECRET']:
            self.assertEqual(values[key], '')

if __name__ == '__main__':
    unittest.main()
