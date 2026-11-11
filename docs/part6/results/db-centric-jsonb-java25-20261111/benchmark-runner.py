import subprocess as s, pathlib as p, datetime, json, re, time
root=p.Path('/Users/pziobron/benchmark-results/part6/db-centric-jsonb-java25-20261111'); root.mkdir(parents=True,exist_ok=True)
def run(args,out=None,timeout=240):
 r=s.run(args,text=True,stdout=s.PIPE,stderr=s.STDOUT,timeout=timeout)
 if out: out.write_text(r.stdout)
 if r.returncode: raise RuntimeError(str(args)+'\n'+r.stdout[-4000:])
 return r.stdout
def k(*args,**kw): return run(['kubectl',*args],**kw)
def sql(query): return k('exec','db-centric-postgres-0','--','psql','-U','orders','-d','order_lifecycle','-At','-c',query)
run(['git','rev-parse','HEAD'],root/'revision.txt'); run(['git','diff'],root/'working-tree.patch')
for name,args in [('nodes',['kubectl','get','nodes','-o','yaml']),('docker',['docker','info']),('storage',['kubectl','get','storageclass','-o','yaml']),('power',['pmset','-g','assertions'])]:run(args,root/(name+'.txt'))
settings=p.Path('/Users/pziobron/Library/Group Containers/group.com.docker/settings-store.json')
if settings.exists():
 d=json.loads(settings.read_text()); (root/'docker-allocation.json').write_text(json.dumps({key:val for key,val in d.items() if key.lower() in ['cpus','memorymib','memory','cpucount']},indent=2))
for n,size in [(1,10000),(2,10000),(3,10000),(1,100000)]:
 d=root/f'{size}-cycle-{n}'; 
 if d.exists():
  d.rename(d.with_name(d.name+'-setup-attempt-'+str(int(time.time()))))
 d.mkdir(exist_ok=True); print('START',d.name,flush=True)
 k('delete','job','integration-test','flink-job-submitter','--ignore-not-found=true')
 run(['helm','uninstall','order-processor','--ignore-not-found'])
 k('delete','deployment','db-centric','flink-taskmanager','flink-jobmanager','--ignore-not-found=true')
 k('delete','-f','db-centric/k8s/postgres.yaml','--ignore-not-found=true')
 k('delete','-f','kafka-streams/k8s/kafka/','--ignore-not-found=true')
 for app in ['db-centric','order-processor','order-lifecycle-flink','db-centric-postgres','kafka-broker']:
  if k('get','pods','-l','app='+app,'-o','name').strip(): k('wait','--for=delete','pod','-l','app='+app,'--timeout=120s')
 k('delete','pvc','data-db-centric-postgres-0','kafka-data-kafka-broker-0','--ignore-not-found=true')
 k('apply','-f','kafka-streams/k8s/kafka/')
 k('rollout','status','statefulset/kafka-broker','--timeout=180s')
 k('wait','--for=condition=complete','job/kafka-topic-creator','--timeout=180s')
 offsets=k('exec','kafka-broker-0','--','/opt/kafka/bin/kafka-get-offsets.sh','--bootstrap-server','localhost:29092','--topic','org.example.order.*',out=d/'empty-offsets.txt')
 assert len(offsets.strip().splitlines())==12 and all(line.endswith(':0') for line in offsets.strip().splitlines()),offsets
 k('apply','-f','db-centric/k8s/postgres.yaml'); k('rollout','status','statefulset/db-centric-postgres','--timeout=180s')
 k('apply','-f','db-centric/k8s/processor.yaml')
 k('set','env','deployment/db-centric','STATE_STORAGE=jsonb','KAFKA_GROUP_ID=order-lifecycle-db-centric-jsonb','LOGGING_LEVEL_ORG_EXAMPLE_ORDER_LIFECYCLE_SERVICE=WARN','LOGGING_LEVEL_ORG_EXAMPLE_ORDER_LIFECYCLE_DB_SERVICE=WARN','LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_KAFKA_LISTENER=INFO')
 k('rollout','status','deployment/db-centric','--timeout=180s')
 def members():return k('exec','kafka-broker-0','--','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','localhost:29092','--describe','--group','order-lifecycle-db-centric-jsonb','--members')
 for attempt in range(40):
  m=members()
  if len([l for l in m.splitlines() if l.startswith('order-lifecycle-db-centric-jsonb')])==4:break
  time.sleep(2)
 else:raise RuntimeError(m)
 (d/'members-before.txt').write_text(m)
 assert sql('SELECT count(*) FROM lifecycle_documents;').strip()=='0'
 for pod in json.loads(k('get','pods','-l','app=db-centric','-o','json'))['items']:
  name=pod['metadata']['name']; logs=k('logs',name,'-c','processor',out=d/(name+'-before.log')); assert 'stateStorage=jsonb' in logs
 (d/'before-stats.txt').write_text(sql('SELECT pg_database_size(current_database()); SELECT wal_records,wal_fpi,wal_bytes FROM pg_stat_wal;'))
 (d/'durability.txt').write_text(sql('SHOW fsync; SHOW synchronous_commit;'))
 assert (d/'durability.txt').read_text().strip()=='on\non'
 pods=json.loads(k('get','pods','-l','app=db-centric','-o','json'))['items']
 for pod in pods:
  name=pod['metadata']['name']; v=k('exec',name,'-c','processor','--','java','-version',out=d/(name+'-java.txt')); assert '25.' in v
 def capture(stage,expected):
  counts=sql("SELECT count(*),count(completed_at),count(published_at),count(*) FILTER (WHERE completed_at IS NOT NULL AND published_at IS NULL),count(*) FILTER (WHERE (state #>> '{OrderState,filledQuantity}')::bigint=2),sum((SELECT count(*) FROM jsonb_each(state #> '{OrderState,childOrders}'))),sum((SELECT count(*) FROM jsonb_each(state #> '{OrderState,childOrders}') c,LATERAL jsonb_array_elements(c.value -> 'fills') f)) FROM lifecycle_documents;")
  (d/(stage+'-counts.txt')).write_text(counts); assert counts.strip()==f'{expected}|{expected}|{expected}|0|{expected}|{expected*2}|{expected*2}',counts
  empty=sql('SELECT (SELECT count(*) FROM orders),(SELECT count(*) FROM fills),(SELECT count(*) FROM hierarchies);')
  (d/(stage+'-relational-counts.txt')).write_text(empty); assert empty.strip()=='0|0|0'
  lag=k('exec','kafka-broker-0','--','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','localhost:29092','--describe','--group','order-lifecycle-db-centric-jsonb',out=d/(stage+'-lag.txt'))
  rows=[l.split() for l in lag.splitlines() if l.startswith('order-lifecycle-db-centric-jsonb')];assert len(rows)==6 and all(r[5]=='0' for r in rows),lag
  (d/(stage+'-members.txt')).write_text(members())
  (d/(stage+'-stats.txt')).write_text(sql('SELECT pg_database_size(current_database()); SELECT wal_records,wal_fpi,wal_bytes FROM pg_stat_wal; SELECT wait_event_type,wait_event,count(*) FROM pg_stat_activity GROUP BY 1,2;'))
  ps=json.loads(k('get','pods','-l','app=db-centric','-o','json'))['items']; assert len(ps)==4
  for pod in ps:
   name=pod['metadata']['name']; assert all(x['restartCount']==0 for x in pod['status']['containerStatuses'])
   logs=k('logs',name,'-c','processor',out=d/(stage+'-'+name+'.log'))
   before=(d/(name+'-before.log')).read_text()
   for marker in ['partitions assigned:','partitions revoked:']:
    assert [l for l in before.splitlines() if marker in l]==[l for l in logs.splitlines() if marker in l],(name,marker)
   assert not re.search(r'\b(ERROR|WARN)\b', '\n'.join(l for l in logs.splitlines() if 'Flyway upgrade recommended' not in l)),logs[-3000:]
 for stage,iterations,total in [('warmup',1000,1000),('measurement',size,size+1000)]:
  if stage=='measurement': k('delete','job','integration-test')
  k('apply','-f',f'kafka-streams/k8s/tests/integration-test-job-{iterations}.yaml')
  k('wait','--for=condition=Initialized','pod','-l','job-name=integration-test','--timeout=120s')
  # Periodically inspect active listeners; fail immediately on errors.
  deadline=time.time()+ (3900 if size==100000 else 360)
  while True:
   job=json.loads(k('get','job','integration-test','-o','json'))
   if job['status'].get('failed'):raise RuntimeError(k('logs','job/integration-test','-c','test-runner'))
   if job['status'].get('succeeded'):break
   logs=k('logs','-l','app=db-centric','-c','processor','--tail=100')
   if re.search(r'\b(ERROR|WARN)\b', '\n'.join(l for l in logs.splitlines() if 'Flyway upgrade recommended' not in l)):raise RuntimeError(logs)
   if time.time()>deadline:raise RuntimeError('job timeout')
   with (d/(stage+'-wait-samples.txt')).open('a') as f: f.write(datetime.datetime.now().isoformat()+'\n'+sql('SELECT wait_event_type,wait_event,count(*) FROM pg_stat_activity GROUP BY 1,2;'))
   time.sleep(5)
  log=k('logs','job/integration-test','-c','test-runner',out=d/(stage+'.log'))
  assert 'BUILD SUCCESSFUL' in log and f'messages={iterations*5} ' in log and f'hierarchyKeys={iterations} ' in log and 'processors=4 ' in log,log[-2000:]
  capture(stage,total)
  print(d.name,stage,re.findall(r'SCENARIO_RESULT[^\n]+',log)[-1],flush=True)
 for label,args in [('pods',['get','pods','-o','yaml']),('workloads',['get','deploy,statefulset,job','-o','yaml']),('pvcs',['get','pvc','-o','yaml']),('events',['get','events','--sort-by=.metadata.creationTimestamp']),('broker',['logs','kafka-broker-0']),('postgres',['logs','db-centric-postgres-0'])]:k(*args,out=d/(label+'.txt'))
 (d/'ACCEPTED').write_text(datetime.datetime.now().isoformat())
 print('ACCEPTED',d.name,flush=True)
print('ALL COMPLETE',flush=True)
