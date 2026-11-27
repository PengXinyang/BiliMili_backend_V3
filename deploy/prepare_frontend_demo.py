"""Create the local experiment's admin account through real registration and login."""
from pathlib import Path
import json
import subprocess
import requests

ROOT = Path(__file__).resolve().parents[1]
credentials = json.loads((ROOT / 'deploy/.local/credentials.json').read_text('utf-8'))
session = requests.Session()
session.trust_env = False
base = 'http://127.0.0.1:18080'
account = 'demo_admin'
password = credentials['demo_password']
session.post(base + '/user/account/register', json={
    'username': account, 'password': password, 'confirmedPassword': password,
}, timeout=20).raise_for_status()
response = session.post(base + '/user/account/login', json={
    'username': account, 'password': password,
}, timeout=20)
response.raise_for_status()
data = response.json()
assert data['code'] == 200, 'Demo admin registration/login failed'
uid = int(data['data']['user']['uid'])
assert uid not in (1, 2), 'Do not change the ordinary demo users'
sql = f"SET NAMES utf8mb4; UPDATE USERSERVICE.user SET role=1,nickname='演示管理员' WHERE uid={uid} AND account='demo_admin';"
result = subprocess.run([
    'kubectl', '--context', 'docker-desktop', '-n', 'bilimili', 'exec', '-i', 'deployment/mysql', '--',
    'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot',
], input=sql, text=True, encoding='utf-8', capture_output=True)
assert result.returncode == 0, 'Local admin role setup failed'
response = session.post(base + '/user/admin/account/login', json={
    'username': account, 'password': password,
}, timeout=20)
assert response.status_code == 200 and response.json()['code'] == 200, 'Admin login failed'
(ROOT / 'deploy/.local/frontend-accounts.json').write_text(json.dumps({
    'username': account, 'uid': uid, 'password': password,
}, ensure_ascii=False, indent=2), 'utf-8')
print(f'Local admin ready: {account} (UID {uid}); password saved only in deploy/.local/frontend-accounts.json')
