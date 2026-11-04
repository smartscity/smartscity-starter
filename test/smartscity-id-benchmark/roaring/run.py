"""Optional bounded orchestration; direct Maven/JMH commands are documented in USER_GUIDE."""
from pathlib import Path
import subprocess,json,hashlib,time,os,signal,platform
ROOT=Path(__file__).resolve().parents[3];OUT=Path(__file__).parent/'results';OUT.mkdir(exist_ok=True)
JAVA=os.environ.get('JAVA_HOME','/Users/liyunlong/.sdkman/candidates/java/21.0.12+1.1-tem')+'/bin/java'
JAR=ROOT/'test/smartscity-id-benchmark/target/benchmarks.jar'
def cmd(args):
 try:return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT,timeout=10).strip()
 except Exception as e:return str(e)
def host():return {'time':time.time(),'thermal':cmd(['pmset','-g','therm']),'power':cmd(['pmset','-g','batt']),'load':os.getloadavg()}
def fingerprint():
 paths=list((ROOT/'module/smartscity-id/src').rglob('*.java'))+list((ROOT/'test/smartscity-id-benchmark/src').rglob('*.java'))+[ROOT/'build/smartscity-parent/pom.xml',ROOT/'test/smartscity-id-benchmark/pom.xml']
 return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
env={'git':cmd(['git','rev-parse','HEAD']),'java':cmd([JAVA,'-version']),'cpu':cmd(['sysctl','-n','machdep.cpu.brand_string']),'logical':cmd(['sysctl','-n','hw.logicalcpu']),'physical':cmd(['sysctl','-n','hw.physicalcpu']),'ram':cmd(['sysctl','-n','hw.memsize']),'os':platform.platform(),'roaring':'1.6.29','jmh':'1.37','jol':'0.17','flags':['-Xms256m','-Xmx2g','-XX:+UseG1GC'],'forks':1,'warmup':'1 x 1s','measurement':'2 x 1s','jarSha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'sources':fingerprint(),'hostStart':host()}
(OUT/'environment.json').write_text(json.dumps(env,indent=2)+'\n')
results=[];start=time.monotonic()
def execute(name,args,limit=180):
 before=host();argv=[JAVA,'-Xms256m','-Xmx2g','-XX:+UseG1GC']+args
 with (OUT/(name+'.log')).open('w')as log:
  p=subprocess.Popen(argv,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  try:rc=p.wait(timeout=limit)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGTERM);p.wait(timeout=10);rc=124
 results.append({'name':name,'exitCode':rc,'command':argv,'before':before,'after':host()});(OUT/'execution.json').write_text(json.dumps(results,indent=2)+'\n');print(name,rc,flush=True)
 if rc!=0:print('FAIL retained; continuing independent cases',flush=True)
def jmh(name,pattern,mode,threads,params=[]):
 execute(name,['-jar',str(JAR),pattern,'-bm',mode,'-t',str(threads),'-f','1','-wi','1','-w','1s','-i','2','-r','1s','-prof','gc','-rf','json','-rff',str(OUT/(name+'.json')),'-jvmArgs','-Xms256m -Xmx2g -XX:+UseG1GC']+params,300)
# One invocation contains six parameter combinations; JSON preserves each one independently.
for threads in [1,2,4,8,16,32]:
 for mode in ['thrpt','sample']:jmh(f'generator-{threads}-{mode}','.*roaring.GeneratorBenchmark.generate',mode,threads)
for mode in ['thrpt','avgt']:jmh('encoder-'+mode,'.*roaring.EncoderBenchmark.*',mode,1)
for mode in ['thrpt','sample']:jmh('bitmap-'+mode,'.*roaring.BitmapBenchmark.*',mode,1)
for mode in ['thrpt','sample']:jmh('live-'+mode,'.*roaring.LivePipelineBenchmark.*',mode,1)
execute('memory',['-cp',str(JAR),'com.smartscity.id.benchmark.roaring.MemoryComparison'],300)
after=fingerprint();env['sourcesUnchanged']=after==env['sources'];env['jarUnchanged']=hashlib.sha256(JAR.read_bytes()).hexdigest()==env['jarSha256'];env['hostEnd']=host();env['elapsedSeconds']=time.monotonic()-start
(OUT/'environment.json').write_text(json.dumps(env,indent=2)+'\n')
