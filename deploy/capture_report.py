"""Capture actual browser screens; requires a running, verified local deployment."""
from pathlib import Path
import sys,json,shutil
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'deploy/.tools/python'))
from playwright.sync_api import sync_playwright
OUT=ROOT.parent/'部署验证材料';IMAGES=OUT/'截图';IMAGES.mkdir(parents=True,exist_ok=True)
cred=json.loads((ROOT/'deploy/.local/credentials.json').read_text('utf-8'))
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='C:/Program Files/Google/Chrome/Application/chrome.exe',headless=True)
 page=browser.new_page(viewport={'width':1440,'height':1080},device_scale_factor=1,locale='zh-CN',timezone_id='Asia/Hong_Kong')
 errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
 page.goto('http://127.0.0.1:18080/media/demo/index.html',wait_until='networkidle')
 page.wait_for_function("document.querySelector('#ready').textContent==='9 / 9'")
 page.screenshot(path=str(IMAGES/'01_集群部署.png'),full_page=True)
 page.click('[data-tab="business"]');page.fill('#password',cred['demo_password']);page.click('#login')
 page.wait_for_function("document.querySelector('#requests').textContent.includes('200')")
 page.wait_for_function("document.querySelector('video').readyState>=2")
 page.evaluate("document.querySelector('video').currentTime=1")
 page.wait_for_timeout(400)
 page.screenshot(path=str(IMAGES/'02_登录与内容查询.png'),full_page=True)
 page.click('[data-tab="message"]')
 for who in ['alice','bob']:
  page.fill(f'#{who} [data-field="password"]',cred['demo_password']);page.click(f'#{who} [data-action="connect"]')
  page.wait_for_function("who=>document.querySelector('#'+who+' [data-field=status]').textContent.includes('已认证并连接')",arg=who)
 page.fill('#alice [data-field="content"]','你好 Bob，BiliMili 已部署到本地 Kubernetes，收到请回复。');page.click('#alice [data-action="send"]')
 page.wait_for_function("document.querySelector('#bob [data-field=log]').textContent.includes('收到请回复')")
 page.fill('#bob [data-field="content"]','已收到！这条回复经过网关和 message-service，并已保存到 MySQL。');page.click('#bob [data-action="send"]')
 page.wait_for_function("document.querySelector('#alice [data-field=log]').textContent.includes('已收到')")
 page.click('#bob [data-action="read"]');page.wait_for_function("document.querySelector('#bob [data-field=detail]').textContent.includes('已读状态')")
 page.screenshot(path=str(IMAGES/'03_双用户实时私聊.png'),full_page=True)
 page.goto('http://127.0.0.1:18080/media/demo/evidence.html',wait_until='networkidle');page.wait_for_selector('tbody tr')
 page.screenshot(path=str(IMAGES/'04_业务与持久化验收.png'),full_page=True)
 page.goto('http://127.0.0.1:18080/media/demo/commands.html',wait_until='networkidle')
 page.screenshot(path=str(IMAGES/'05_kubectl命令输出.png'),full_page=True)
 if errors: raise AssertionError(errors)
 browser.close()
for src,dest in [(ROOT/'deploy/.local/verification.json','业务验收记录.json'),(ROOT/'deploy/demo/cluster.json','集群状态快照.json')]:shutil.copyfile(src,OUT/dest)
print('Screenshots saved:',[f.name for f in IMAGES.glob('*.png')])
