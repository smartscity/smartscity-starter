"""Steady-state finite-window model, not a materialized measurement or capacity guarantee."""
from collections import Counter,defaultdict
from functools import lru_cache
from pathlib import Path
import csv, math
OUT=Path(__file__).parent/'results'
@lru_cache(None)
def histogram(q,T,d):
 original=Counter(); buckets=defaultdict(lambda:[0,0]);g=q*d;full,rem=divmod(g,32768)
 for t in range(0,T,d):
  for j in range(full):
   original[32768]+=1;buckets[(100000+t+j)>>15][0]+=1
  if rem:
   original[rem]+=1;buckets[(100000+t+full)>>15][1]+=1
 encoded=Counter()
 for f,r in buckets.values():
  if rem and f+r:encoded[2*(f+r)]+=rem//2
  if f:encoded[2*f]+=16384-rem//2
 return original,encoded

def measure(hist,p,mult=1,arity=1):
 c=n=h=0
 for size,times in hist.items():
  size*=arity;occ=1 if p==1 else -math.expm1(size*math.log1p(-p));card=size*p;avg=card/occ
  cap=4
  while cap<avg and cap<4096:
   cap=min(4096,cap*2 if cap<64 else cap*3//2 if cap<1024 else cap*5//4)
   if cap>3840:cap=4096
  payload=8192 if avg>4096 else 2*cap
  c+=times*occ*mult;n+=times*card*mult;h+=times*occ*mult*(136+math.ceil(payload/8)*8)
 return c,n,h
rows=[]
for w,q in [(1,10),(1,100),(1,1000),(1,10000),(100,10),(100,1000),(100,10000)]:
 for d in [1,10]:
  for T in [1800,86400,604800,2592000]:
   orig,enc=histogram(q,T,d)
   for p in [1,.1,.01]:
    c,n,h=measure(orig,p,w/2 if w>1 else w,2 if w>1 else 1);ec,en,eh=measure(enc,p,w)
    assert abs(n-en)<max(1,n*1e-12)
    rows.append(dict(status='MODELED',workers=w,tps=q,period=d,seconds=T,p=p,generatedIds=w*q*T,expectedStoredIds=n,originalContainers=c,encodedContainers=ec,originalHeapEstimate=h,encodedHeapEstimate=eh,originalBytesPerId=h/n,encodedBytesPerId=eh/n,reductionPercent=100*(1-eh/h),runOptimize='NOT_MODELED'))
with (OUT/'modeled.csv').open('w')as f:
 w=csv.DictWriter(f,fieldnames=rows[0]);w.writeheader();w.writerows(rows)
print(f'MODELED {len(rows)} rows; no large ID sets materialized')
