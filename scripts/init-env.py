"""Create local configuration without overwriting an existing .env."""
from pathlib import Path
import secrets
root=Path(__file__).resolve().parent.parent
out=root/'.env'
if out.exists():
    print('.env already exists; keeping your current configuration.')
else:
    text=(root/'.env.example').read_text(encoding='utf-8')
    text=text.replace('CHANGE_ME_DATABASE_PASSWORD',secrets.token_urlsafe(24))
    text=text.replace('CHANGE_ME_USE_A_RANDOM_SECRET_AT_LEAST_32_BYTES',secrets.token_urlsafe(48))
    text=text.replace('CHANGE_ME_RANDOM_SECRET_AT_LEAST_32_BYTES',secrets.token_urlsafe(48))
    text=text.replace('CHANGE_ME_DEMO_PASSWORD_AT_LEAST_10_CHARACTERS',secrets.token_urlsafe(24))
    text=text.replace('CHANGE_ME_AT_LEAST_10_CHARACTERS',secrets.token_urlsafe(24))
    out.write_text(text,encoding='utf-8')
    try: out.chmod(0o600)
    except OSError: pass
    print('Created .env with independent random secrets. Read your local DEMO_PASSWORD/ADMIN_PASSWORD privately; never commit .env.')
