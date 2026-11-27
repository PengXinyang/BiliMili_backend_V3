"""Render an isolated local Kubernetes deployment. Secrets stay under deploy/.local."""
from pathlib import Path
import json, secrets, re, os
import yaml

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "deploy/.local"
OUT.mkdir(parents=True, exist_ok=True)
credentials = OUT / "credentials.json"
if credentials.exists():
    creds = json.loads(credentials.read_text(encoding="utf-8"))
else:
    creds = {"root_password": secrets.token_hex(20), "db_password": secrets.token_hex(20),
             "message_token": secrets.token_hex(32), "demo_password": "Demo" + secrets.token_hex(4)}
    credentials.write_text(json.dumps(creds, indent=2), encoding="utf-8")
(OUT / "demo-accounts.json").write_text(json.dumps({"accounts":["demo_alice","demo_bob"],"password":creds["demo_password"]},indent=2),encoding="utf-8")
NS = "bilimili"
IMAGE_TAG = os.environ.get("BILIMILI_IMAGE_TAG", "lab-20261127")
def obj(kind, name, **rest):
    return {"apiVersion": "v1", "kind": kind, "metadata": {"name": name, "namespace": NS}, **rest}
def config(name, data): return obj("ConfigMap", name, data=data)
def service(name, ports):
    return obj("Service", name, spec={"selector":{"app":name},"ports":[{"name":n,"port":p,"targetPort":p} for n,p in ports]})
def pvc(name,size):
    return obj("PersistentVolumeClaim",name,spec={"accessModes":["ReadWriteOnce"],"resources":{"requests":{"storage":size}}})
def env(name,value): return {"name":name,"value":str(value)}
def secret_env(name,key):
    return {"name":name,"valueFrom":{"secretKeyRef":{"name":"bilimili-secrets","key":key}}}
def volume(name,claim): return {"name":name,"persistentVolumeClaim":{"claimName":claim}}
def mount(name,path,**extra): return {"name":name,"mountPath":path,**extra}
def deploy(name,image,ports,environment=None,volumes=None,mounts=None,args=None,limits="512Mi",request="128Mi",probe=None):
    c={"name":name,"image":image,"imagePullPolicy":"IfNotPresent","ports":[{"containerPort":p} for p in ports],
       "resources":{"requests":{"cpu":"100m","memory":request},"limits":{"cpu":"2","memory":limits}}}
    if environment:c["env"]=environment
    if mounts:c["volumeMounts"]=mounts
    if args:c["args"]=args
    if probe:
        c["startupProbe"]={**probe,"failureThreshold":60,"periodSeconds":5}
        c["readinessProbe"]={**probe,"periodSeconds":5,"timeoutSeconds":3}
        c["livenessProbe"]={**probe,"periodSeconds":20,"failureThreshold":3,"timeoutSeconds":3}
    spec={"containers":[c]}
    if volumes:spec["volumes"]=volumes
    return {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":name,"namespace":NS},
        "spec":{"replicas":1,"strategy":{"type":"Recreate"},"selector":{"matchLabels":{"app":name}},
        "template":{"metadata":{"labels":{"app":name}},"spec":spec}}}

sql = ["SET NAMES utf8mb4;", "CREATE DATABASE IF NOT EXISTS MESSAGESERVICE CHARACTER SET utf8mb4;"]
for mod in ["user-service","video-service","article-service"]:
    text=(ROOT/mod/"src/main/java/com/bilimili/buaa13/database"/(mod+".sql")).read_text(encoding="utf-8")
    text=re.sub(r"DROP TABLE IF EXISTS[^;]+;","",text,flags=re.I)
    text=re.sub(r"CREATE DATABASE\s+(\w+)",r"CREATE DATABASE IF NOT EXISTS \1",text,flags=re.I)
    text=text.replace("CREATE TABLE ","CREATE TABLE IF NOT EXISTS ")
    text=text.replace("CHARSET=utf8 ", "CHARSET=utf8mb4 ")
    text=text.replace("INSERT INTO", "INSERT IGNORE INTO")
    sql.append(text)
for db, user in [("USERSERVICE","user_app"),("VIDEOSERVICE","video_app"),("ARTICLESERVICE","article_app"),("MESSAGESERVICE","message_app")]:
    sql.append(f"CREATE USER IF NOT EXISTS '{user}'@'%' IDENTIFIED BY '{creds['db_password']}'; GRANT ALL ON {db}.* TO '{user}'@'%';")
sql.append("""USE VIDEOSERVICE;
INSERT IGNORE INTO video(vid,uid,title,duration,main_class_id,sub_class_id,tags,description,cover_url,video_url,status,upload_date)
VALUES(1,1,'Kubernetes 微服务部署演示',5,'knowledge','science','Kubernetes','本地实验生成的视频素材','/media/demo/cover.svg','/media/demo/sample.mp4',1,NOW());
INSERT IGNORE INTO video_status(vid) VALUES(1);
USE ARTICLESERVICE;
INSERT IGNORE INTO article(aid,uid,title,cover_url,vid,content_url,status)
VALUES(1,1,'BiliMili 微服务部署实验','/media/demo/cover.svg','1','/media/demo/article.md',1);
INSERT IGNORE INTO article_status(aid) VALUES(1);
""")
# SQL containing generated passwords is a Secret, not a public ConfigMap.
infra=[{"apiVersion":"v1","kind":"Namespace","metadata":{"name":NS}},
       obj("Secret","bilimili-secrets",type="Opaque",stringData={"root-password":creds["root_password"],"db-password":creds["db_password"],"message-token":creds["message_token"]}),
       obj("Secret","mysql-init",type="Opaque",stringData={"01-init.sql":"\n".join(sql)})]
for name,size in [("mysql-data","2Gi"),("redis-data","512Mi"),("es-data","2Gi"),("media-data","1Gi"),("nacos-data","512Mi")]:infra.append(pvc(name,size))
infra += [
service("mysql",[("mysql",3306)]),
deploy("mysql","mysql:8.0",[3306],[secret_env("MYSQL_ROOT_PASSWORD","root-password")],
       [volume("data","mysql-data"),{"name":"init","secret":{"secretName":"mysql-init"}}],
       [mount("data","/var/lib/mysql"),mount("init","/docker-entrypoint-initdb.d",readOnly=True)],
       ["--character-set-server=utf8mb4","--collation-server=utf8mb4_unicode_ci","--max-connections=150"],limits="1Gi",request="256Mi",
       probe={"exec":{"command":["sh","-c",'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -h 127.0.0.1 -uroot --silent']}}),
service("redis",[("redis",6379)]),
deploy("redis","redis:7.4-alpine",[6379],volumes=[volume("data","redis-data")],mounts=[mount("data","/data")],
       args=["redis-server","--appendonly","yes"],limits="256Mi",probe={"exec":{"command":["redis-cli","ping"]}}),
service("nacos",[("http",8848),("grpc",9848),("grpc-server",9849)]),
deploy("nacos","nacos/nacos-server:v2.2.3",[8848,9848,9849],
       [env("MODE","standalone"),env("PREFER_HOST_MODE","hostname"),env("NACOS_AUTH_ENABLE","false"),
        env("JVM_XMS","256m"),env("JVM_XMX","512m"),env("JVM_XMN","128m")],volumes=[volume("data","nacos-data")],mounts=[mount("data","/home/nacos/data")],limits="1Gi",
       probe={"httpGet":{"path":"/nacos/v1/console/health/readiness","port":8848}}),
service("elasticsearch",[("http",9200)]),
deploy("elasticsearch","elasticsearch:7.17.16",[9200],
       [env("discovery.type","single-node"),env("xpack.security.enabled","false"),env("ES_JAVA_OPTS","-Xms512m -Xmx512m")],
       [volume("data","es-data")],[mount("data","/usr/share/elasticsearch/data")],limits="1536Mi",request="512Mi",
       probe={"httpGet":{"path":"/_cluster/health","port":9200}})
]
# Elasticsearch needs a writable volume owned by UID 1000.
infra[-1]["spec"]["template"]["spec"]["securityContext"]={"fsGroup":1000}

nacos_configs={}
for name,port,db,user in [("user-service",8081,"USERSERVICE","user_app"),("video-service",8082,"VIDEOSERVICE","video_app"),("article-service",8083,"ARTICLESERVICE","article_app")]:
    nacos_configs[name+"-k8s.yaml"]={
        "server.port":port,"spring.main.allow-circular-references":True,
        "spring.datasource.url":f"jdbc:mysql://mysql:3306/{db}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username":user,"spring.datasource.password":"${DB_PASSWORD}",
        "spring.datasource.account":"local-admin","spring.datasource.driver-class-name":"com.mysql.cj.jdbc.Driver",
        "spring.datasource.initial-size":1,"spring.datasource.min-idle":1,"spring.datasource.max-active":10,
        "spring.redis.host":"redis","spring.redis.port":6379,"spring.redis.timeout":3000,
        "spring.redis.jedis.pool.max-idle":8,"spring.redis.jedis.pool.max-wait":3000,
        "elasticsearch.host":"elasticsearch","elasticsearch.port":9200,"elasticsearch.username":"","elasticsearch.password":"",
        "oss.bucket":"bilimili-local","oss.bucketUrl":"/media/","storage.local-root":"/data/media","directory.chunk":"/data/chunks/",
        "spring.autoconfigure.exclude":"org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "mybatis-plus.configuration.map-underscore-to-camel-case":True,
        "feign.client.config.default.connectTimeout":3000,"feign.client.config.default.readTimeout":10000,
        "spring.cloud.sentinel.enabled":False,"spring.jackson.time-zone":"Asia/Shanghai"
    }
nacos_configs["gateway-service-k8s.yaml"]={"server.port":8080}
cfgdir=ROOT/"deploy/nacos";cfgdir.mkdir(exist_ok=True)
for name,data in nacos_configs.items():(cfgdir/name).write_text(yaml.safe_dump(data,allow_unicode=True,sort_keys=False),encoding="utf-8")
# Apps use Nacos for registration; only the standalone message module uses Service DNS.
apps=[]
for module,name,port in [("gateway_service","gateway-service",8080),("user-service","user-service",8081),("video-service","video-service",8082),("article-service","article-service",8083),("message-service","message-service",8084)]:
    e=[env("SPRING_PROFILES_ACTIVE","k8s,local-storage" if name in ["user-service","video-service","article-service"] else "k8s"),
       env("JAVA_TOOL_OPTIONS","-Xms96m -Xmx384m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"),
       env("NACOS_SERVER_ADDR","nacos:8848"),
       env("MESSAGE_ALLOWED_ORIGINS","http://localhost:18080,http://127.0.0.1:18080,http://localhost:8787,http://127.0.0.1:8787,http://localhost:8788,http://127.0.0.1:8788"),
       env("GATEWAY_ALLOWED_ORIGINS","http://localhost:18080,http://127.0.0.1:18080,http://localhost:8787,http://127.0.0.1:8787,http://localhost:8788,http://127.0.0.1:8788"),env("NACOS_NAMESPACE",""),
       {"name":"POD_IP","valueFrom":{"fieldRef":{"fieldPath":"status.podIP"}}}]
    mounts=[];volumes=[]
    if name in ["user-service","video-service","article-service"]:
        e.append(secret_env("DB_PASSWORD","db-password"))
        volumes=[volume("media","media-data"),{"name":"chunks","emptyDir":{}}]
        mounts=[mount("media","/data/media"),mount("chunks","/data/chunks")]
    if name in ["user-service","message-service"]:e.append(secret_env("MESSAGE_INTERNAL_TOKEN","message-token"))
    if name=="message-service":
        e += [env("MESSAGE_DB_USER","message_app"),secret_env("MESSAGE_DB_PASSWORD","db-password")]
    p={"httpGet":{"path":"/actuator/health/liveness","port":port}} if name in ["gateway-service","message-service"] else {"tcpSocket":{"port":port}}
    apps += [service(name,[("http",port)]),deploy(name,"bilimili/"+name+":"+IMAGE_TAG,[port],e,volumes,mounts,limits="768Mi",probe=p)]
(OUT/"infrastructure.json").write_text(json.dumps({"apiVersion":"v1","kind":"List","items":infra},ensure_ascii=False,indent=2),encoding="utf-8")
(ROOT/"deploy/k8s/applications.yaml").write_text(yaml.safe_dump_all(apps,allow_unicode=True,sort_keys=False),encoding="utf-8")
# No generated passwords are printed.
print("Rendered infrastructure, application workloads and Nacos configs.")
