"""Exercise both launchers with fake Maven, temporary .env files and no services."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parent
BASH = shutil.which('bash')
if os.name == 'nt':
    git_bash = Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Git/bin/bash.exe'
    BASH = str(git_bash) if git_bash.exists() else None
POWERSHELL = shutil.which('powershell') or shutil.which('pwsh')
KEYS = ['DB_PASSWORD', 'SMTP_PASSWORD', 'JWT_SECRET', 'OTP_SECRET', 'SAMPLE2', 'EMPTY', 'LITERAL', 'LAST_VALUE']


class LauncherChecks:
    launcher = None

    def execute(self, content, exit_code=0):
        with tempfile.TemporaryDirectory(prefix='utetaste-launcher-') as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            shutil.copyfile(SOURCE / self.launcher, root / 'scripts' / self.launcher)
            (root / '.env').write_bytes(content)
            before = hashlib.sha256(content).hexdigest()
            probe = root / 'probe.py'
            probe.write_text(
                "import json,os,sys\nfrom pathlib import Path\n"
                f"keys={KEYS!r}\n"
                "Path('probe.json').write_text(json.dumps({'values':{k:os.environ.get(k) for k in keys},"
                "'args':sys.argv[1:]},ensure_ascii=False),encoding='utf-8')\n"
                "sys.exit(int(os.environ['PROBE_EXIT']))\n", encoding='utf-8')
            (root / 'mvnw.cmd').write_text(
                '@echo off\n"%PROBE_PYTHON%" "%PROBE_SCRIPT%" %*\nexit /b %errorlevel%\n',
                encoding='ascii')
            (root / 'mvnw').write_text(
                '#!/usr/bin/env bash\nexec "$PROBE_PYTHON" "$PROBE_SCRIPT" "$@"\n',
                encoding='ascii')
            (root / 'mvnw').chmod(0o755)
            env = {**os.environ, 'PROBE_PYTHON': sys.executable.replace('\\', '/'),
                   'PROBE_SCRIPT': str(probe).replace('\\', '/'), 'PROBE_EXIT': str(exit_code)}
            for key in KEYS:
                env.pop(key, None)
            if self.launcher.endswith('.ps1'):
                command = [POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass',
                           '-File', str(root / 'scripts' / self.launcher)]
            else:
                command = [BASH, str(root / 'scripts' / self.launcher).replace('\\', '/')]
            result = subprocess.run(command, cwd=root, env=env, capture_output=True,
                                    text=True, encoding='utf-8', errors='replace', timeout=30)
            observed = json.loads((root / 'probe.json').read_text(encoding='utf-8')) if (root / 'probe.json').exists() else None
            self.assertEqual(hashlib.sha256((root / '.env').read_bytes()).hexdigest(), before)
            self.assertFalse((root / 'must-not-exist').exists(), '.env must never execute shell code')
            return result, observed

    def test_windows_env_quotes_comments_and_literal_values(self):
        fixture = (
            '# invented fixture values only\r\n'
            'DB_PASSWORD="fixture database password" # local comment\r\n'
            "SMTP_PASSWORD='fixture#mail password'\r\n"
            'JWT_SECRET=fixture-jwt-value # local comment\r\n'
            'OTP_SECRET=fixture-otp#literal\r\n'
            'export SAMPLE2 = fixture-value\r\n'
            'EMPTY=\r\n'
            'LITERAL=$(touch must-not-exist)\r\n'
        ).encode('utf-8')
        result, observed = self.execute(fixture)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(observed)
        self.assertEqual(observed['args'], ['spring-boot:run'])
        self.assertEqual(observed['values'], {
            'DB_PASSWORD':'fixture database password', 'SMTP_PASSWORD':'fixture#mail password',
            'JWT_SECRET':'fixture-jwt-value', 'OTP_SECRET':'fixture-otp#literal',
            'SAMPLE2':'fixture-value', 'EMPTY':'', 'LITERAL':'$(touch must-not-exist)', 'LAST_VALUE':None})

    def test_utf8_bom_unicode_and_final_line_without_newline(self):
        result, observed = self.execute(b'\xef\xbb\xbf' + 'DB_PASSWORD="mật khẩu thử"\r\nLAST_VALUE=final-fixture'.encode('utf-8'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(observed['values']['DB_PASSWORD'], 'mật khẩu thử')
        self.assertEqual(observed['values']['LAST_VALUE'], 'final-fixture')

    def test_rejects_invalid_line_without_echoing_its_value(self):
        result, observed = self.execute(b'DB_PASSWORD=fixture\nINVALID LINE fixture-sensitive-marker\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(observed)
        self.assertNotIn('fixture-sensitive-marker', result.stdout + result.stderr)

    def test_rejects_unclosed_quote_without_starting_maven(self):
        result, observed = self.execute(b'DB_PASSWORD="fixture-unclosed\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(observed)
        self.assertNotIn('fixture-unclosed', result.stdout + result.stderr)

    def test_propagates_maven_failure_exit_code(self):
        result, observed = self.execute(b'DB_PASSWORD=fixture\n', exit_code=42)
        self.assertIsNotNone(observed)
        self.assertEqual(result.returncode, 42)


@unittest.skipUnless(os.name == 'nt' and POWERSHELL, 'The PowerShell launcher requires Windows')
class PowerShellLauncherTests(LauncherChecks, unittest.TestCase):
    launcher = 'start.ps1'


@unittest.skipUnless(BASH, 'Bash is not installed')
class BashLauncherTests(LauncherChecks, unittest.TestCase):
    launcher = 'start.sh'


if __name__ == '__main__':
    unittest.main()
