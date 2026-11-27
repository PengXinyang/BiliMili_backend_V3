"""Publish Nacos configs and idempotent search-index seed data. Run before app startup."""
from pathlib import Path
import requests
ROOT=Path(__file__).resolve().parents[1]
s=requests.Session();s.trust_env=False
for p in (ROOT/'deploy/nacos').glob('*.yaml'):
 r=s.post('http://127.0.0.1:18848/nacos/v1/cs/configs',data={'dataId':p.name,'group':'DEFAULT_GROUP','type':'yaml','content':p.read_text('utf-8')},timeout=15)
 r.raise_for_status();assert r.text=='true',r.text
 print('Published',p.name)
for name,props in {'user':{'uid':{'type':'integer'},'nickname':{'type':'text'}},'video':{'vid':{'type':'integer'},'uid':{'type':'integer'},'title':{'type':'text'},'tags':{'type':'text'},'status':{'type':'integer'},'mainClassId':{'type':'keyword'},'subClassId':{'type':'keyword'}},'search_word':{'content':{'type':'text'}}}.items():
 if s.head('http://127.0.0.1:19200/'+name,timeout=15).status_code==404:
  r=s.put('http://127.0.0.1:19200/'+name,json={'settings':{'number_of_shards':1,'number_of_replicas':0},'mappings':{'properties':props}},timeout=15);r.raise_for_status()
 print('Index ready:',name)
r=s.put('http://127.0.0.1:19200/video/_doc/1?refresh=true',json={'vid':1,'uid':1,'title':'Kubernetes 微服务部署演示','tags':'Kubernetes','status':1,'mainClassId':'knowledge','subClassId':'science'},timeout=15);r.raise_for_status()
