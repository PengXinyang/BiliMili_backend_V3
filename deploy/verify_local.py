"""Live acceptance checks against the isolated local Kubernetes deployment.
Requires running port forwards (start-local.ps1). No tokens/passwords in evidence.
"""
from pathlib import Path
import json, subprocess, sys, time, datetime, html, re
import requests, websocket
ROOT=Path(__file__).resolve().parents[1]
LOCAL=ROOT/'deploy/.local'; DEMO=ROOT/'deploy/demo'
S=requests.Session();S.trust_env=False
BASE='http://127.0.0.1:18080'
CREDS=json.loads((LOCAL/'credentials.json').read_text('utf-8'))
RESULT={'collectedAt':datetime.datetime.now().astimezone().isoformat(timespec='seconds'),'checks':[]}
def record(name,detail):
 RESULT['checks'].append({'name':name,'passed':True,'detail':detail}); print('PASS',name)
def k(*args,input=None):
 p=subprocess.run(['kubectl','--context','docker-desktop','-n','bilimili',*args],input=input,text=True,encoding='utf-8',capture_output=True,check=True)
 return p.stdout

def http(path,token=None,body=None,method=None):
 r=S.request(method or ('POST' if body is not None else 'GET'),BASE+path,json=body,headers={'Authorization':'Bearer '+token} if token else {},timeout=30)
 r.raise_for_status();j=r.json();assert j.get('code')==200,(path,j);return j.get('data')
def login(name):
 return http('/user/account/login',body={'username':name,'password':CREDS['demo_password']})
def setup_accounts():
 for name in ['demo_alice','demo_bob']:
  r=S.post(BASE+'/user/account/register',json={'username':name,'password':CREDS['demo_password'],'confirmedPassword':CREDS['demo_password']},timeout=30);r.raise_for_status();j=r.json()
  assert j['code']==200 or j.get('message')=='账号已存在',j
  print('Account ready:',name)
 # Update only these synthetic accounts; no real data is touched.
 sql="""SET NAMES utf8mb4;
UPDATE USERSERVICE.user SET nickname=CASE account WHEN 'demo_alice' THEN '演示用户 Alice' ELSE '演示用户 Bob' END,head_portrait='/media/demo/cover.svg',background='/media/demo/cover.svg' WHERE account IN ('demo_alice','demo_bob');
UPDATE VIDEOSERVICE.video SET sub_class_id='science' WHERE vid=1 AND title='Kubernetes 微服务部署演示';
"""
 k('exec','-i','deployment/mysql','--','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot',input=sql)
 users=[login(name) for name in ['demo_alice','demo_bob']]
 assert [u['user']['uid'] for u in users]==[1,2], 'Demo accounts must be UID 1 and 2 in the independent database'
 for u in users:
  r=S.put('http://127.0.0.1:19200/user/_doc/'+str(u['user']['uid'])+'?refresh=true',json={'uid':u['user']['uid'],'nickname':u['user']['nickname']},timeout=10);r.raise_for_status()
 return users

def recv_type(ws,kind,timeout=12):
 end=time.time()+timeout
 while time.time()<end:
  msg=json.loads(ws.recv())
  if msg['type']=='error': raise AssertionError(msg)
  if msg['type']==kind:return msg
 raise AssertionError('WebSocket timeout: '+kind)
def connect(token):
 ws=websocket.create_connection('ws://127.0.0.1:18080/message/im',origin='http://127.0.0.1:18080',timeout=15,http_proxy_host=None)
 ws.send(json.dumps({'code':100,'content':'Bearer '+token}));recv_type(ws,'connected');return ws

def verify(persistence=False):
 a,b=setup_accounts();ta,tb=a['token'],b['token']
 record('两个真实账号登录',{'uids':[a['user']['uid'],b['user']['uid']]})
 for path in ['/user/personal/info','/message/api/unread']:
  r=S.get(BASE+path,timeout=10);assert r.status_code==401,(path,r.status_code)
 r=S.get(BASE+'/user/personal/info',headers={'Authorization':'Bearer invalid'},timeout=10);assert r.status_code==403,r.status_code
 r=S.get(BASE+'/user/internal/message/session',timeout=10);assert r.status_code in [403,404],r.status_code
 record('鉴权与内部接口隔离',{'anonymous':401,'invalidToken':403,'internalEndpoint':r.status_code})
 v=http('/video/getone?vid=1',ta);assert v['video']['vid']==1 and v['user']['uid']==1 and v['category'] is not None,v
 record('视频与作者跨服务查询',{'title':v['video']['title'],'author':v['user']['nickname'],'category':v['category']})
 v0=http('/video/getone?vid=1');assert v0['user']['uid']==1,v0
 record('公开内容匿名访问',{'vid':v0['video']['vid'],'authorUid':v0['user']['uid']})
 article=http('/article/column/markdown?aid=1',ta);assert 'Kubernetes' in article['content'] and article['user']['uid']==1,article
 record('文章 PVC 正文与作者查询',{'title':article['title'],'author':article['user']['nickname']})
 media=S.get(BASE+'/media/demo/sample.mp4',headers={'Range':'bytes=0-1023'},timeout=15);assert media.status_code==206 and len(media.content)==1024
 record('媒体文件范围请求',{'status':206,'contentRange':media.headers.get('Content-Range')})
 result=S.post('http://127.0.0.1:19200/video/_search',json={'query':{'match':{'title':'Kubernetes'}}},timeout=10).json()
 assert result['hits']['total']['value']>=1,result
 record('Elasticsearch 真实索引检索',{'hits':result['hits']['total']['value'],'title':result['hits']['hits'][0]['_source']['title']})
 wsA=connect(ta);wsB=connect(tb)
 try:
  http('/message/api/chats/1/read',tb,method='POST')
  text='Kubernetes 实机私聊验证 '+datetime.datetime.now().strftime('%H:%M:%S')
  wsA.send(json.dumps({'code':101,'acceptId':2,'content':text},ensure_ascii=False))
  event=recv_type(wsB,'message')
  # Ignore unread/read notifications preceding the new message.
  while 'detail' not in event['data']: event=recv_type(wsB,'message')
  sent=event['data']['detail'];assert sent['content']==text and sent['postId']==1
  unread=http('/message/api/unread',tb);assert unread['message']>=1,unread
  record('WebSocket 双用户实时投递',{'messageId':sent['id'],'from':1,'to':2,'content':text,'unread':unread['message']})
  http('/message/api/chats/1/read',tb,method='POST');assert http('/message/api/unread',tb)['message']==0
  history=http('/message/api/chats/1/messages',tb)['messageList'];assert any(m['id']==sent['id'] and m['read'] for m in history)
  record('私聊历史与已读状态',{'messageId':sent['id'],'read':True,'unread':0})
  withdraw=http('/message/api/messages',ta,{'acceptId':2,'content':'撤回功能验证'})
  http('/message/api/messages/'+str(withdraw['id'])+'/withdraw',ta,method='POST')
  history=http('/message/api/chats/1/messages',tb)['messageList'];assert any(m['id']==withdraw['id'] and m['withdraw']==1 and m['content']=='' for m in history)
  record('发送者撤回消息',{'messageId':withdraw['id'],'withdraw':1})
 finally: wsA.close();wsB.close()
 if persistence:
  before=json.loads(k('get','pods','-l','app=message-service','-o','json'))['items'][0]['metadata']['uid']
  k('rollout','restart','deployment/message-service');k('rollout','status','deployment/message-service','--timeout=120s')
  after=json.loads(k('get','pods','-l','app=message-service','-o','json'))['items'][0]['metadata']['uid'];assert before!=after
  history=http('/message/api/chats/1/messages',tb)['messageList'];assert any(m['id']==sent['id'] and m['content']==text for m in history)
  record('消息服务重建后历史保留',{'oldPodUid':before,'newPodUid':after,'preservedMessageId':sent['id']})
 (LOCAL/'verification.json').write_text(json.dumps(RESULT,ensure_ascii=False,indent=2),'utf-8')
 print('All live checks passed:',len(RESULT['checks']))

def snapshot():
 data={'collectedAt':datetime.datetime.now().astimezone().isoformat(timespec='seconds')}
 for key,resource in [('nodes','nodes'),('pods','pods'),('pvcs','pvc')]: data[key]=json.loads(k('get',resource,'-o','json'))
 assert all(p['status'].get('containerStatuses') and all(c['ready'] for c in p['status']['containerStatuses']) for p in data['pods']['items'])
 data['nacos']=S.get('http://127.0.0.1:18848/nacos/v1/ns/service/list',params={'pageNo':1,'pageSize':100},timeout=10).json()
 data['elasticsearch']=S.get('http://127.0.0.1:19200/_cluster/health',timeout=10).json()
 data['indexCounts']={n:S.get('http://127.0.0.1:19200/'+n+'/_count',timeout=10).json()['count'] for n in ['user','video']}
 mysql=k('exec','deployment/mysql','--','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT COUNT(*) FROM USERSERVICE.user; SELECT COUNT(*) FROM MESSAGESERVICE.chat_detailed;"').strip()
 redis=k('exec','deployment/redis','--','redis-cli','ping').strip();assert redis=='PONG'
 data['dependencies']='MySQL 查询成功；Redis PONG'
 data['databaseCounts']=mysql
 (DEMO/'cluster.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
 parts=['采集时间：'+data['collectedAt']]
 for args in [('cluster-info',),('get','nodes'),('get','pods','-o','wide'),('get','pvc')]:
  parts.append('> kubectl --context docker-desktop -n bilimili '+' '.join(args)+'\n'+re.sub(r'\x1b\[[0-9;]*m','',k(*args)).strip())
 raw='\n\n'.join(parts)+'\n'
 out=ROOT.parent/'部署验证材料';out.mkdir(exist_ok=True)
 (out/'kubectl实际输出.log').write_text(raw,'utf-8')
 (DEMO/'commands.html').write_text('<!doctype html><meta charset="utf-8"><title>Kubernetes 实际命令输出</title><style>body{padding:28px;background:#f4f7fa;color:#172c45;font-family:Microsoft YaHei}h1{font-size:25px}pre{background:white;padding:22px;border:1px solid #dce5ef;border-radius:10px;font:13px/1.9 Consolas,monospace;white-space:pre-wrap}p{font-size:13px;color:#64768c}</style><h1>Kubernetes 本地部署 · 实际命令输出</h1><p>以下文本由 kubectl 直接采集。本页仅负责展示，原始输出随截图保存在部署验证材料目录。</p><pre>'+html.escape(raw)+'</pre>','utf-8')
 print('Snapshot saved')
if __name__=='__main__':
 if '--snapshot' in sys.argv:snapshot()
 else:verify('--persistence' in sys.argv)
