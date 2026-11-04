"""Render recorded measurements without converting missing or host-limited data to PASS."""
from pathlib import Path
import csv,json,re,math,zipfile
ROOT=Path(__file__).resolve().parents[3];OUT=Path(__file__).parent/'results';CHART=OUT/'charts';CHART.mkdir(exist_ok=True)
def read(name):return json.loads((OUT/name).read_text())
def fmt(x,n=2):
 try:return f'{float(x):,.{n}f}'
 except:return 'UNAVAILABLE'
def score(r):return r['primaryMetric']['score']
def metric(r,k):return r.get('secondaryMetrics',{}).get(k,{}).get('score','UNAVAILABLE')
def quantile(r,q):return r['primaryMetric'].get('scorePercentiles',{}).get(q,'UNAVAILABLE')
def all_records(prefix):
 out=[]
 paths=sorted(OUT.glob(prefix+'*.json'))
 if paths:
  for p in paths:
   data=json.loads(p.read_text())
   if isinstance(data,list):out.extend(data)
 else:
  with zipfile.ZipFile(OUT/'jmh-raw.zip') as archive:
   if json.loads(archive.read('environment.json')) != json.loads((OUT/'environment.json').read_text()):return []
   for name in sorted(archive.namelist()):
    if name.startswith(prefix):out.extend(json.loads(archive.read(name)))
 return out
records=all_records('generator-')+all_records('encoder-')+all_records('bitmap-')+all_records('live-')
env=read('environment.json');execution=read('execution.json');validation=read('validation.json')
issues=[]
for e in execution:
 for phase in ['before','after']:
  text=e[phase]['thermal']
  for key,value in re.findall(r'(CPU_Speed_Limit|CPU_Scheduler_Limit)\s*=\s*(\d+)',text):
   if int(value)<100:issues.append(f"{e['name']} {phase}: {key}={value}%")
# JMH can exit zero while recording failed forks; required case counts are checked separately.
expected={'generator':72,'encoder':8,'bitmap':24,'live':4}
counts={k:len(all_records(k+'-')) for k in expected}
raw=(OUT/('memory.log' if (OUT/'memory.log').exists() else 'memory.csv')).read_text().splitlines();head=next(x for x in raw if x.startswith('workers,tps,'));lines=[head]+[x for x in raw if re.match(r'^\d+,\d+,\d+,',x)]
(OUT/'memory.csv').write_text('\n'.join(lines)+'\n');memory=list(csv.DictReader(lines));assert len(memory)==168,len(memory)
assert all(r['insertedIds']==r['cardinality'] for r in memory)
# Persist a compact JMH CSV, keeping outcome counts and latency percentiles separate.
rows=[]
for r in records:
 rows.append({'benchmark':r['benchmark'],'mode':r['mode'],'threads':r['threads'],'params':json.dumps(r.get('params',{}),sort_keys=True),'score':score(r),'unit':r['primaryMetric']['scoreUnit'],'p50':quantile(r,'50.0') if r['mode']=='sample' else '', 'p95':quantile(r,'95.0') if r['mode']=='sample' else '', 'p99':quantile(r,'99.0') if r['mode']=='sample' else '', 'p999':quantile(r,'99.9') if r['mode']=='sample' else '', 'allocBytesOp':metric(r,'gc.alloc.rate.norm'),'gcCount':metric(r,'gc.count'),'gcMillis':metric(r,'gc.time'),'timeouts':metric(r,'timeouts')})
with (OUT/'jmh.csv').open('w')as f:w=csv.DictWriter(f,fieldnames=rows[0]);w.writeheader();w.writerows(rows)
status={'recordCounts':counts,'expected':expected,'hostIssues':issues,'sourcesUnchanged':env.get('sourcesUnchanged',False),'jarUnchanged':env.get('jarUnchanged',False),'status':'NOT_COMPARABLE' if issues else 'EXPLORATORY','actualRetainedHeap':'UNAVAILABLE','processRSS':'UNAVAILABLE','cpuUtilization':'UNAVAILABLE'}
(OUT/'summary.json').write_text(json.dumps(status,indent=2)+'\n')
s=f'''# Smartscity ID benchmark

## Generator names and Roaring storage encoding — 2026-11-04

Status: **{status['status']}** for performance comparison; measured memory is a deterministic dataset comparison, with JVM heap explicitly estimated. JMH records: {counts}; expected: {expected}. Source unchanged: {env.get('sourcesUnchanged')}; JAR unchanged: {env.get('jarUnchanged')}. The SDK's standard protocol, ring capacity and default Boot behavior remain unchanged. No universal speedup is claimed.

The machine reported CPU limiting during later groups. One-fork, short local results below are exploratory point estimates, not a production baseline or statistically established regression/improvement. Do not rank implementations using host-limited groups. The 16/32-thread groups oversubscribe this 12-logical-core machine; independent buffered workers add producer threads as well. Even lower thread counts include GC/JMH/producer threads. Tail percentiles use SampleTime sampling and the short run does not establish a production P99.9 SLO.

Environment: {env['cpu']}; {env['physical']} physical/{env['logical']} logical CPUs; {int(env['ram'])/2**30:.0f} GiB RAM; {env['os']}. Temurin Java 21.0.12.1+1; G1; -Xms256m -Xmx2g. RoaringBitmap 1.6.29, JMH 1.37, JOL 0.17. One fork, 1 x 1 s warmup, 2 x 1 s measurement, GC profiler. This intentionally deviates from the recommended 3 forks, 5 x 5 s warmup and 10 x 5 s measurement. Invocation setup is outside the timed live-pipeline method but may contribute profiler allocation/GC. CPU utilization, process RSS and exact retained heap are UNAVAILABLE.

Measured source base commit: `{env['git']}` with the task's working changes. Frozen benchmark JAR SHA-256: `{env['jarSha256']}`. [Environment and source fingerprints](../../test/smartscity-id-benchmark/roaring/results/environment.json) bind these measurements to source contents, not just the base commit.

Validation: {validation['tests']} reported tests, {validation['tests']-validation['skipped']} executed, {validation['skipped']} conditional skips, {validation['failures']} failures, {validation['errors']} errors. Java 21 reactor verification and production artifact boundaries passed. External database/fault skips are not claimed as executed. The encoder tests check every bit, v1 golden vectors, boundary/random round trips and concurrent lifecycle behavior; the storage driver checks cardinality, membership and deletion before reporting results.

## Table A — Generator performance

Throughput is attempted calls; failure counts are retained separately. Avg/P99 come from a separate SampleTime run, not throughput reciprocal. Alloc B/op comes from the throughput GC profiler. Outcomes from different modes must not be merged. Both shared and independent instances use safe distinct Worker fixtures; no database registration is timed.

| Generator | Ownership | Threads | Throughput M attempts/s | Sample mean ns | P99 ns | Alloc B/op | Throughput timeouts (count) |
|---|---|---:|---:|---:|---:|---:|---:|
'''
gens=all_records('generator-');thr=[r for r in gens if r['mode']=='thrpt'];lat={(r['params']['kind'],r['params']['ownership'],r['threads']):r for r in gens if r['mode']=='sample'}
for r in sorted(thr,key=lambda r:(r['params']['ownership'],r['threads'],r['params']['kind'])):
 key=(r['params']['kind'],r['params']['ownership'],r['threads']);l=lat.get(key);s+=f"| {key[0]} | {key[1]} | {key[2]} | {fmt(score(r)*1000)} | {fmt(score(l)) if l else 'NOT_RUN'} | {fmt(quantile(l,'99.0')) if l else 'NOT_RUN'} | {fmt(metric(r,'gc.alloc.rate.norm'),4)} | {fmt(metric(r,'timeouts'),0)} |\n"
s+='\n## Table B — Encoder performance\n\nChanging arrays are prepared before measurement. Values are consumed; no generation is included. Full throughput, round-trip cost and allocation results are in jmh.csv. Encode versus identity is the relevant incremental CPU comparison, but host variation and one fork prevent a precise overhead guarantee.\n\n| Encoder | Encode ns/op | Decode ns/op | Alloc B/op |\n|---|---:|---:|---:|\n'
enc={r['benchmark'].rsplit('.',1)[1]:r for r in all_records('encoder-') if r['mode']=='avgt'}
for label,k,d in [('IdentityIdEncoder','identity','identity'),('RoaringIdEncoder','encode','decode')]:
 r=enc.get(k);s+=f"| {label} | {fmt(score(r)) if r else 'NOT_RUN'} | {fmt(score(enc[d])) if d in enc else 'NOT_RUN'} | {fmt(metric(r,'gc.alloc.rate.norm'),5) if r else 'NOT_RUN'} |\n"
s+='\n## Table C — Bitmap memory (measured, estimated JVM heap)\n\nIdentical logical IDs and insertion selection, seed 711. All four configurations are measured. The table shows uniform 100% and 1% cases; 10%, burst cases, all container types and timing/GC columns are in [memory.csv](../../test/smartscity-id-benchmark/roaring/results/memory.csv). Windows vary to keep the experiment bounded; compare within a row, not across differently sized workloads. The steady allocation fixture uses the standard formula, not actual distributed scheduling.\n\n| Workers | TPS/worker | Ratio | Window s | Stored IDs | runOptimize | Original heap B | Encoded heap B | Reduction % | Original containers | Encoded containers |\n|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|\n'
lookup={(r['workers'],r['tps'],r['period'],r['p'],r['runOptimize'],r['encoding']):r for r in memory}
for r in memory:
 if r['encoding']!='standard' or r['period']!='1' or r['p']=='0.10':continue
 k=(r['workers'],r['tps'],r['period'],r['p'],r['runOptimize'],'roaring');e=lookup[k];h=float(r['estimatedHeapBytes']);eh=float(e['estimatedHeapBytes']);s+=f"| {r['workers']} | {r['tps']} | {r['p']} | {r['seconds']} | {r['insertedIds']} | {r['runOptimize']} | {h:,.0f} | {eh:,.0f} | {100*(1-eh/h):.2f} | {r['containers']} | {e['containers']} |\n"
s+='\nJOL graph traversal fell back to estimated 64-bit HotSpot layouts (compressed references, 8-byte alignment). getSizeInBytes in Roaring 1.6.29 casts serialized size to int; serializedBytes is the long representation. Neither equals JVM retained heap. Peak pool sums are not exact simultaneous peaks or Bitmap-only memory. runOptimize timing and before/after heap are recorded, with exclusive mutation; peak observations include other process objects. No memory advantage implies lower GC latency or faster recovery without application measurements.\n'
s+='\n## Table D — Storage and end-to-end performance\n\nThe replay pipeline uses identical source arrays and excludes allocation; the live pipeline separately includes actual BufferedIdGenerator calls on identical fixed-clock prefixes. This separates workload-shape comparison from live generation. Add/replay P99 values normalize whole 10,000-ID batches and are not per-request tail latency. Contains percentiles are individual existing-key lookups; absence lookup is not measured. All Bitmap mutation is single-writer.\n\n| TPS | Encoding | Add ops/s | Contains ops/s | Replay ops/s | Replay amortized P99 ns | Replay alloc B/ID |\n|---:|---|---:|---:|---:|---:|---:|\n'
bm=all_records('bitmap-');idx={(r['params']['tps'],r['params']['encoding'],r['benchmark'].rsplit('.',1)[1],r['mode']):r for r in bm}
for q in ['10','10000']:
 for e in ['standard','roaring']:
  vals=[idx.get((q,e,m,'thrpt')) for m in ['add','contains','replayEndToEnd']];l=idx.get((q,e,'replayEndToEnd','sample'));s+=f'| {q} | {e} | '+' | '.join(fmt(score(r)*1e9,0) if r else 'NOT_RUN' for r in vals)+f" | {fmt(quantile(l,'99.0')) if l else 'NOT_RUN'} | {fmt(metric(vals[2],'gc.alloc.rate.norm')) if vals[2] else 'NOT_RUN'} |\n"
s+='\n| Live pipeline | Throughput ops/s | Sample mean ns/ID | Amortized P99 ns/ID |\n|---|---:|---:|---:|\n'
live=all_records('live-')
for e in ['standard','roaring']:
 t=next((r for r in live if r['mode']=='thrpt' and r['params']['encoding']==e),None);l=next((r for r in live if r['mode']=='sample' and r['params']['encoding']==e),None);s+=f"| {e} | {fmt(score(t)*1e9,0) if t else 'NOT_RUN'} | {fmt(score(l)) if l else 'NOT_RUN'} | {fmt(quantile(l,'99.0')) if l else 'NOT_RUN'} |\n"
s+='''
## Retention model (not measured)

[modeled.csv](../../test/smartscity-id-benchmark/roaring/results/modeled.csv) covers all 7 worker/rate pairs, 100/10/1% insertion, uniform and 10-second bursts, and 30 minutes/1 day/7 days/30 days. Source IDs use standard steady timestamps; workers 0..99 are paired for original containers. For each candidate container with n original values, expected occupancy is `1-(1-p)^n` and expected cardinality is `n*p`. Original high keys are `(t,w>>>1)`; encoded keys are `(w,t>>>15,s>>>1)`. The model counts those bucket populations before applying selection. Startup-cache transients, worker churn and correlated business failures are not included.

Heap uses 96 B amortized ART/directory overhead plus a 24 B container and aligned 16 B array header. Array growth is approximated at conditional mean cardinality using the library's capacity steps; bitmap payload is 8192 B above 4096 entries. This is not an exact expectation near conversion thresholds. Modeled runOptimize is NOT_MODELED; do not extrapolate its measured benefit indiscriminately. Full models include bytes/ID and reduction; billion-ID cases were not materialized.

## Interpretation and deployment

Use the identity/standard path for database keys and general identifiers. The optional permutation has no additional allocation resource and does not alter default buffered generation. Prefer encoding existing business IDs in Bitmap storage. It can strongly reduce sparse low-TPS container overhead over long enough windows, but high-TPS short windows may spread sequence pairs into many encoded containers and consume more memory; the table preserves those negative reductions. runOptimize often makes standard consecutive runs extremely compact. CPU cost, insertion locality and collection size must be weighed together; no universal winner is established.

Keep business-time retention windows, explicit encoding-version metadata and coordinated snapshot migration. Do not silently reinterpret existing Bitmap keys, mix encoding versions, or allocate a second unrelated ID for one business entity. Numeric time ordering changes; uniqueness still depends on safe worker registration. Raft state machines must apply deterministic same-version encoding across replicas; Raft itself is not an in-memory database. See [design](README.md#generator-implementations-and-storage-encoding), [usage](USER_GUIDE.md#roaring-oriented-storage) and [direct commands](../../test/smartscity-id-benchmark/USER_GUIDE.md#generator-encoder-and-bitmap-comparison).

## Charts

All plotted performance numbers are raw exploratory observations, including host-limited groups; charts are not a validated ranking. Memory plots show measured estimated heap; modeled values are kept in a separate CSV.

'''
for name in ['generator-throughput','generator-p99','encoding-cost','heap','containers','memory-reduction','end-to-end']:
 s+=f'![{name}](../../test/smartscity-id-benchmark/roaring/results/charts/{name}.png)\n\n'
s+='## Host issues and evidence\n\n'+('\n'.join('- '+x for x in issues) if issues else 'No recorded boundary limit; this does not exclude every transient.')+'\n\n[Execution commands and observations](../../test/smartscity-id-benchmark/roaring/results/execution.json), [JMH compact CSV](../../test/smartscity-id-benchmark/roaring/results/jmh.csv), [lossless raw JMH archive](../../test/smartscity-id-benchmark/roaring/results/jmh-raw.zip), [validation counts](../../test/smartscity-id-benchmark/roaring/results/validation.json). Earlier Baidu comparison and sampling work used different frozen snapshots; its numbers are not a baseline for this feature.\n'
(ROOT/'docs/id/benchmark.md').write_text(s)
# Standard chart renderer uses a pinned external plotting library, never a production dependency.
series=[]
def add(name,label,x,y,unit,title,xlabel):
 series.append('\t'.join(map(str,[name,label,x,y,unit,title,xlabel])))
for mode,name,unit in [('thrpt','generator-throughput','M attempts/s'),('sample','generator-p99','P99 ns (log scale)')]:
 for kind in ['sequence','buffered','roaring']:
  for r in sorted([r for r in gens if r['mode']==mode and r['params']=={'kind':kind,'ownership':'shared'}],key=lambda r:r['threads']):
   add(name,kind,r['threads'],score(r)*1000 if mode=='thrpt' else quantile(r,'99.0'),unit,'Exploratory JMH: later groups host-limited','Shared consumer threads (16/32 oversubscribed)')
for k,r in enc.items():add('encoding-cost','cost',k,score(r),'ns/op','Encoder cost: host-limited exploratory run','Operation')
selected=[r for r in memory if r['encoding']=='standard' and r['period']=='1' and r['p']=='0.01' and r['runOptimize']=='false']
for r in selected:
 e=lookup[(r['workers'],r['tps'],'1','0.01','false','roaring')];label=r['workers']+'w x '+r['tps']
 for name,col,unit in [('heap','estimatedHeapBytes','Estimated heap B (log scale)'),('containers','containers','Container count (log scale)')]:
  for strategy,row in [('standard',r),('encoded',e)]:add(name,strategy,label,row[col],unit,'Measured 1% insertion: uniform, no runOptimize, varying windows','Workers x TPS/worker')
 add('memory-reduction','reduction',label,100*(1-float(e['estimatedHeapBytes'])/float(r['estimatedHeapBytes'])),'Heap reduction % (negative = worse)','Measured estimated heap: 1% insertion','Workers x TPS/worker')
for encoding in ['standard','roaring']:
 for q in ['10','10000']:
  r=idx.get((q,encoding,'replayEndToEnd','thrpt'))
  if r:add('end-to-end',encoding,q,score(r)*1000,'M replay add+contains ops/s','Identical replay, excluding allocation; host-limited','Source workload TPS shape')
(OUT/'chart-data.tsv').write_text('\n'.join(series)+'\n')
import subprocess,os
java=Path(os.environ.get('JAVA_HOME','/Users/liyunlong/.sdkman/candidates/java/21.0.12+1.1-tem'))/'bin'
jar=Path(os.environ.get('JFREECHART_JAR','/tmp/roaring-audit/lib/jfreechart.jar'));classes=Path('/tmp/smartscity-roaring-charts');classes.mkdir(exist_ok=True)
subprocess.run([str(java/'javac'),'-cp',str(jar),'-d',str(classes),str(Path(__file__).parent/'ChartRenderer.java')],check=True)
subprocess.run([str(java/'java'),'-Djava.awt.headless=true','-cp',str(classes)+os.pathsep+str(jar),'ChartRenderer',str(OUT/'chart-data.tsv'),str(CHART)],check=True)
print(json.dumps(status))
