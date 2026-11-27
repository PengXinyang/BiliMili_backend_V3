"""Exercise the deployed original Vue frontends and capture genuine browser results.

Requires deploy-frontends.ps1, prepare_frontend_demo.py and local Chrome/Playwright.
Uses isolated demo accounts only. Evidence never includes passwords or tokens.
"""
from pathlib import Path
import datetime
import json
import shutil
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'deploy/.tools/python'))
from playwright.sync_api import sync_playwright, expect

OUT = ROOT.parent / '部署验证材料'
IMAGES = OUT / '截图'
IMAGES.mkdir(parents=True, exist_ok=True)
PASSWORD = json.loads((ROOT / 'deploy/.local/credentials.json').read_text('utf-8'))['demo_password']
CLIENT = 'http://127.0.0.1:8787'
ADMIN = 'http://127.0.0.1:8788'
RESULT = {'collectedAt': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(timespec='seconds'),
          'client': CLIENT, 'admin': ADMIN, 'checks': [], 'screenshots': []}


def record(name, **detail):
    RESULT['checks'].append({'name': name, 'passed': True, 'detail': detail})
    print('PASS', name, flush=True)


def shot(page, name):
    page.screenshot(path=str(IMAGES / name), full_page=False)
    RESULT['screenshots'].append(name)


def login(page, account):
    page.goto(CLIENT, wait_until='networkidle')
    page.locator('.login-botton').click()
    page.get_by_placeholder('请输入账号').fill(account)
    page.get_by_placeholder('请输入密码').fill(PASSWORD)
    page.locator('.login-boxes .commit').click()
    page.wait_for_function("!!localStorage.getItem('teri_token')")
    page.wait_for_timeout(350)


def api(page, path):
    token = page.evaluate("localStorage.getItem('teri_token')")
    response = page.request.get(CLIENT + '/api' + path, headers={'Authorization': 'Bearer ' + token})
    assert response.ok, (path, response.status)
    body = response.json()
    assert body['code'] == 200, (path, body.get('message'))
    return body['data']


def connected(page):
    expect(page.get_by_test_id('connection-status')).to_contain_text('实时连接已建立', timeout=20000)
    expect(page.get_by_placeholder('输入消息，Enter 发送，Shift + Enter 换行')).to_be_visible()


def send(page, message):
    editor = page.get_by_placeholder('输入消息，Enter 发送，Shift + Enter 换行')
    editor.fill(message)
    page.get_by_test_id('send-message').click()
    expect(editor).to_have_value('', timeout=15000)
    row = page.locator('.message-row').filter(has=page.get_by_text(message, exact=True))
    expect(row).to_be_visible()
    return int(row.get_attribute('data-message-id'))


def until(page, predicate, timeout=10):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return
        page.wait_for_timeout(150)
    raise AssertionError('Expected live state was not reached')


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(executable_path='C:/Program Files/Google/Chrome/Application/chrome.exe', headless=True)
    errors = []
    failures = []
    contexts = []

    def new_page():
        context = browser.new_context(viewport={'width': 1440, 'height': 900}, locale='zh-CN', timezone_id='Asia/Hong_Kong')
        contexts.append(context)
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.on('response', lambda response: failures.append({'status': response.status, 'url': response.url})
                if response.status >= 400 and '/api/' in response.url else None)
        return page

    try:
        alice, bob, admin = new_page(), new_page(), new_page()
        login(alice, 'demo_alice')
        login(bob, 'demo_bob')
        record('原客户端双账号登录', uids=[api(alice, '/user/personal/info')['uid'], api(bob, '/user/personal/info')['uid']])

        alice.goto(CLIENT + '/video/1', wait_until='networkidle')
        alice.wait_for_function("document.querySelector('video')?.readyState >= 2")
        alice.evaluate("document.querySelector('video').currentTime = 1")
        alice.wait_for_timeout(500)
        shot(alice, '06_原客户端视频播放.png')
        record('原客户端视频查询与媒体加载', vid=1, duration=alice.locator('video').evaluate('(v)=>v.duration'))
        alice.goto(CLIENT + '/article/1', wait_until='networkidle')
        expect(alice.get_by_role('heading', name='BiliMili 微服务部署实验', level=1, exact=True)).to_be_visible()
        shot(alice, '07_原客户端文章阅读.png')
        record('原客户端文章正文查询', aid=1)

        alice.goto(CLIENT + '/message?peer=2', wait_until='networkidle')
        connected(alice)
        expect(alice.get_by_test_id('send-message')).to_be_disabled()
        record('私聊认证连接与空消息限制')
        stamp = datetime.datetime.now().strftime('%H:%M:%S')
        text_a = f'视频和文章都能打开了。Bob，能收到私聊吗？（{stamp}）'
        text_b = '收到了！我这边也能看到聊天记录，刷新后消息还在。'
        first = send(alice, text_a)
        until(bob, lambda: api(bob, '/message/api/unread')['message'] >= 1)
        unread = api(bob, '/message/api/unread')['message']
        record('发送确认与离开会话后的未读数', messageId=first, unread=unread)

        bob.goto(CLIENT + '/message?peer=1', wait_until='networkidle')
        connected(bob)
        expect(bob.locator(f'[data-message-id="{first}"] .bubble')).to_have_text(text_a)
        until(bob, lambda: api(bob, '/message/api/unread')['message'] == 0)
        history = api(bob, '/message/api/chats/1/messages')['messageList']
        assert any(m['id'] == first and m['read'] for m in history)
        record('打开会话显示消息并标记已读', messageId=first, unread=0)
        second = send(bob, text_b)
        expect(alice.locator(f'[data-message-id="{second}"] .bubble')).to_have_text(text_b)
        record('双用户 WebSocket 实时回复', aliceMessageId=first, bobMessageId=second)

        temporary = send(alice, '这条消息用于检查撤回功能。')
        alice.locator(f'[data-message-id="{temporary}"] .withdraw').click()
        expect(bob.locator(f'[data-message-id="{temporary}"] .bubble')).to_have_text('这条消息已撤回')
        history = api(bob, '/message/api/chats/1/messages')['messageList']
        assert any(m['id'] == temporary and m['withdraw'] == 1 and m['content'] == '' for m in history)
        record('原客户端撤回与双方同步', messageId=temporary, storedContentEmpty=True)

        alice.reload(wait_until='networkidle')
        connected(alice)
        expect(alice.locator(f'[data-message-id="{first}"] .bubble')).to_have_text(text_a)
        expect(alice.locator(f'[data-message-id="{second}"] .bubble')).to_have_text(text_b)
        record('浏览器刷新后恢复登录与历史记录', preservedIds=[first, second])

        # Close only this test browser's connection; production reconnect logic must recover.
        alice.evaluate("document.querySelector('#app').__vue_app__.config.globalProperties.$store.state.ws.close()")
        until(alice, lambda: '实时连接已建立' not in alice.get_by_test_id('connection-status').inner_text(), timeout=3)
        connected(alice)
        expect(alice.locator(f'[data-message-id="{second}"] .bubble')).to_have_text(text_b)
        record('WebSocket 断线后自动重连与历史恢复')
        alice.wait_for_timeout(700)
        shot(alice, '08_原客户端Alice私聊.png')
        shot(bob, '09_原客户端Bob私聊.png')

        admin.set_viewport_size({'width': 1440, 'height': 650})
        admin.goto(ADMIN, wait_until='networkidle')
        admin.locator('input[type=text]').fill('demo_admin')
        admin.locator('input[type=password]').fill(PASSWORD)
        admin.locator('.login-btn').click()
        admin.wait_for_url('**/home')
        expect(admin.get_by_text('演示管理员', exact=False).first).to_be_visible()
        admin.wait_for_load_state('networkidle')
        shot(admin, '10_原管理端首页.png')
        record('独立管理员账号登录与真实统计')

        for kind, title, filename in [('video', 'Kubernetes 微服务部署演示', '11_原管理端视频审核.png'),
                                      ('article', 'BiliMili 微服务部署实验', '12_原管理端文章审核.png')]:
            admin.goto(ADMIN + '/review/' + kind + '/form', wait_until='networkidle')
            admin.get_by_text('已过审', exact=True).click()
            expect(admin.locator('tbody').get_by_text(title, exact=True)).to_be_visible()
            shot(admin, filename)
            admin.goto(ADMIN + '/review/' + kind + '/detail/1', wait_until='networkidle')
            expect(admin.get_by_text(title, exact=True).first).to_be_visible()
            record('管理端' + ('视频' if kind == 'video' else '文章') + '审核列表与详情', contentId=1)

        assert not errors, errors
        assert not failures, failures
        record('浏览器页面与接口错误检查', uncaughtErrors=0, failedApiRequests=0)
        commands = ROOT / 'deploy/demo/commands.html'
        if commands.exists():
            admin.goto(commands.as_uri(), wait_until='load')
            admin.screenshot(path=str(IMAGES / '05_kubectl命令输出.png'), full_page=True)
        cluster = ROOT / 'deploy/demo/cluster.json'
        if cluster.exists():
            shutil.copyfile(cluster, OUT / '集群状态快照.json')
        RESULT['passed'] = True
    except Exception as error:
        RESULT['passed'] = False
        RESULT['failure'] = str(error)
        raise
    finally:
        RESULT['browserErrors'] = errors
        RESULT['failedApiRequests'] = failures
        (OUT / '原前端验收记录.json').write_text(json.dumps(RESULT, ensure_ascii=False, indent=2), 'utf-8')
        browser.close()

print('Frontend checks passed:', len(RESULT['checks']))
