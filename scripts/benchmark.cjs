// Dependency-free HTTP baseline driver for the isolated start-benchmark.ps1 environment.
// Closed-loop concurrency: one outstanding request per worker, no automatic retries.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const os = require('node:os');
const crypto = require('node:crypto');
const {execFile} = require('node:child_process');
const {promisify} = require('node:util');
const {performance, monitorEventLoopDelay} = require('node:perf_hooks');
const assert = require('node:assert/strict');
const exec = promisify(execFile);
const args = Object.fromEntries(process.argv.slice(2).map(x => { const i=x.indexOf('='); return [x.slice(0,i),x.slice(i+1)]; }));
if (!args['--state']) throw new Error('Usage: node scripts/benchmark.cjs --state=data/benchmark-.../state.json [--prepare=true] [--seconds=15] [--levels=1,8,32,64]');
const state = JSON.parse(fs.readFileSync(args['--state'],'utf8').replace(/^\uFEFF/,''));
const base = new URL(state.baseUrl);
if (base.hostname !== '127.0.0.1' || state.databaseName !== 'easymall_bench') throw new Error('Only the isolated loopback benchmark is allowed.');
const dir = state.runDirectory;
const mysql = path.join(state.mysqlBin,'mysql.exe');
const agent = new http.Agent({keepAlive:true,maxSockets:160,maxFreeSockets:160});
const seconds = Number(args['--seconds'] || 15);
const warmSeconds = Number(args['--warmup'] || 3);
const levels = (args['--levels'] || '1,8,32,64').split(',').map(Number);
if (![seconds,warmSeconds,...levels].every(Number.isFinite) || seconds<1 || warmSeconds<0 || levels.some(c=>c<1 || c>128 || !Number.isInteger(c))) throw new Error('Invalid load parameters.');
const datasetFile = path.join(dir,'dataset.json');
let data;
function request(method, url, token, body, key) {
  return new Promise((resolve,reject) => {
    const payload=body ? JSON.stringify(body) : null;
    const headers={};
    if(token) headers.Authorization='Bearer '+token;
    if(key) headers['Idempotency-Key']=key;
    if(payload) {headers['Content-Type']='application/json';headers['Content-Length']=Buffer.byteLength(payload);}
    const req=http.request(new URL(url,base),{method,headers,agent},res=>{
      const chunks=[];
      res.on('data',chunk=>chunks.push(chunk));
      res.on('error',reject);
      res.on('end',()=>{try {resolve({status:res.statusCode,body:JSON.parse(Buffer.concat(chunks).toString())});} catch(e) {reject(e);} });
    });
    req.setTimeout(15000,()=>req.destroy(new Error('request timeout')));
    req.on('error',reject);req.end(payload);
  });
}
async function api(method,url,token,body,key) {
  const r=await request(method,url,token,body,key);
  if(r.status!==200 || r.body.code!=='OK') throw new Error(`${method} ${url}: HTTP ${r.status}, ${r.body.code}`);
  return r.body.data;
}
async function sql(query) {
  const result=await exec(mysql,[`--defaults-file=${state.mysqlClientFile}`,'--batch','--raw','--skip-column-names','--default-character-set=utf8mb4',`--database=${state.databaseName}`,'--execute',query],{maxBuffer:16*1024*1024,windowsHide:true});
  return result.stdout.trim();
}
const sqlRows=async query => {const s=await sql(query); return s ? s.split(/\r?\n/).map(line=>line.split('\t')) : [];};
async function prepare() {
  if(fs.existsSync(datasetFile)) throw new Error('Dataset exists; use a new isolated environment or run without --prepare.');
  const admin=await api('POST','/api/auth/login',null,{username:'admin',password:'DemoAdmin123!'});
  data={runId:crypto.randomUUID(),adminToken:admin.token,users:[],products:[]};
  const category=await api('POST','/api/admin/categories',data.adminToken,{name:'Benchmark-'+data.runId});
  data.categoryId=category.id;
  for(let i=0;i<65;i++) {
    const p=await api('POST','/api/admin/products',data.adminToken,{categoryId:category.id,name:'Bench product '+i,description:'Isolated benchmark inventory',price:12.3,active:true});
    await api('POST',`/api/admin/products/${p.id}/stock`,data.adminToken,{delta:1000000},crypto.randomUUID());
    data.products.push(p.id);
  }
  for(let i=0;i<64;i++) {
    const username='bench_'+i,password='BenchmarkBuyer123!';
    await api('POST','/api/auth/register',null,{username,password});
    const login=await api('POST','/api/auth/login',null,{username,password});
    const address=await api('POST','/api/addresses',login.token,{recipient:'Benchmark buyer',phone:'13800138000',detail:'Isolated benchmark address',isDefault:true});
    data.users.push({id:login.user.id,username,password,token:login.token,addressId:address.id});
  }
  // Ten thousand catalog-only rows make list/search work representative of nontrivial data size.
  // These rows start with zero stock and are never ordered; all purchasable stock uses API ledger writes.
  const digits='(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)';
  await sql(`INSERT INTO product(category_id,name,description,price,stock,active) SELECT ${category.id},CONCAT('catalog-',a.n+10*b.n+100*c.n+1000*d.n),'Read-only benchmark row',10.00,0,TRUE FROM ${digits} a CROSS JOIN ${digits} b CROSS JOIN ${digits} c CROSS JOIN ${digits} d`);
  fs.writeFileSync(datasetFile,JSON.stringify(data,null,2));
  console.log('Prepared 64 buyers, 65 stocked products, and 10000 catalog-only products. Credentials remain in ignored data/.');
}
const statusNames=['Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Threads_running','Questions'];
async function dbSnapshot() {
  const status=Object.fromEntries((await sqlRows(`SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN (${statusNames.map(s=>`'${s}'`).join(',')})`)).map(([k,v])=>[k,Number(v)]));
  const digests=Object.fromEntries((await sqlRows("SELECT DIGEST,COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_EXAMINED,DIGEST_TEXT FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='easymall_bench' AND DIGEST IS NOT NULL")).map(([id,count,time,rows,text])=>[id,{count:Number(count),time:Number(time),rows:Number(rows),text}]));
  return {status,digests};
}
async function metric(name) {
  const r=await request('GET','/actuator/metrics/'+name,data.adminToken);
  if(r.status!==200) return null;
  return Object.fromEntries(r.body.measurements.map(m=>[m.statistic,m.value]));
}
async function metricsSnapshot() {
  const names=['hikaricp.connections.acquire','hikaricp.connections.usage','jvm.gc.pause'];
  return Object.fromEntries(await Promise.all(names.map(async n=>[n,await metric(n)])));
}
async function sample() {
  const names=['hikaricp.connections.active','hikaricp.connections.pending','process.cpu.usage','system.cpu.usage'];
  const values=Object.fromEntries(await Promise.all(names.map(async n=>[n,await metric(n)])));
  const locks=await sqlRows("SELECT l.OBJECT_NAME,COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA='easymall_bench' GROUP BY l.OBJECT_NAME");
  // data_lock_waits has one row per requesting/blocking relationship, not one per thread.
  return {at:new Date().toISOString(),metrics:values,lockWaitEdges:Object.fromEntries(locks.map(([k,v])=>[k,Number(v)]))};
}
const scenarios={
  product_public:i=>({method:'GET',url:`/api/products/${data.products[0]}`}),
  product_authenticated:i=>({method:'GET',url:`/api/products/${data.products[0]}`,token:data.users[i%64].token}),
  catalog_search:i=>({method:'GET',url:`/api/products?keyword=catalog&categoryId=${data.categoryId}&page=1&size=20`}),
  order_distributed:i=>orderRequest(i%64,data.products[i%64+1]),
  order_hotspot:i=>orderRequest(i%64,data.products[0]),
  order_same_user:i=>orderRequest(0,data.products[i%64+1]),
  login:i=>({method:'POST',url:'/api/auth/login',body:{username:data.users[i%64].username,password:data.users[i%64].password}})
};
function orderRequest(user,product) {const u=data.users[user];return {method:'POST',url:'/api/orders',token:u.token,key:crypto.randomUUID(),body:{addressId:u.addressId,items:[{productId:product,quantity:1}],clearCart:false}};}
function validate(name,response,spec) {
  assert.equal(response.status,200);assert.equal(response.body.code,'OK');
  const d=response.body.data;
  if(name.startsWith('order_')) {assert.equal(d.status,'PENDING');assert.equal(Number(d.total_amount),12.3);assert.equal(d.items[0].product_id,spec.body.items[0].productId);}
  else if(name.startsWith('product_')) {assert.equal(d.id,data.products[0]);assert.equal(d.active,true);}
  else if(name==='catalog_search') {assert.equal(d.total,10000);assert.equal(d.items.length,20);}
  else if(name==='login') assert.ok(d.token);
}
async function load(name,concurrency,duration,phaseId) {
  const latencies=[],statuses={},failures=[];let ok=0,fail=0,sequence=0;
  const start=performance.now(),deadline=start+duration*1000;
  await Promise.all(Array.from({length:concurrency},(_,i)=>(async()=>{
    while(performance.now()<deadline) {
      const spec=scenarios[name](i);
      if(name.startsWith('order_')) spec.key=`${phaseId}-${sequence++}`;
      const begin=performance.now();
      try {const response=await request(spec.method,spec.url,spec.token,spec.body,spec.key);statuses[response.status]=(statuses[response.status]||0)+1;validate(name,response,spec);ok++;}
      catch(e) {fail++;if(failures.length<5) failures.push(e.message);}
      latencies.push(performance.now()-begin);
    }
  })()));
  const elapsed=(performance.now()-start)/1000;
  latencies.sort((a,b)=>a-b);
  const percentile=p=>latencies[Math.min(latencies.length-1,Math.ceil(latencies.length*p)-1)]||0;
  return {ok,fail,statuses,failureExamples:failures,requests:ok+fail,elapsedSeconds:elapsed,throughput:ok/elapsed,latencyMs:{mean:latencies.reduce((a,b)=>a+b,0)/Math.max(latencies.length,1),p50:percentile(.5),p95:percentile(.95),p99:percentile(.99),max:latencies.at(-1)||0}};
}
function dbDelta(before,after) {
  const status=Object.fromEntries(statusNames.map(n=>[n,(after.status[n]||0)-(before.status[n]||0)]));
  const topSql=Object.entries(after.digests).map(([id,v])=>{const b=before.digests[id]||{count:0,time:0,rows:0};return {sql:v.text,count:v.count-b.count,totalMs:(v.time-b.time)/1e9,rowsExamined:v.rows-b.rows};}).filter(x=>x.count>0).sort((a,b)=>b.totalMs-a.totalMs).slice(0,12);
  return {status,topSql};
}
async function verify(prefix,expected) {
  const rows=await sqlRows(`SELECT COUNT(*),COALESCE(SUM(total_amount),0) FROM mall_order WHERE request_key LIKE '${prefix}-%'`);
  assert.equal(Number(rows[0][0]),expected);assert.ok(Math.abs(Number(rows[0][1])-expected*12.3)<.001);
  const ids=data.products.join(',');
  const mismatch=Number(await sql(`SELECT COUNT(*) FROM product p LEFT JOIN (SELECT product_id,SUM(delta) delta FROM stock_log GROUP BY product_id) s ON s.product_id=p.id WHERE p.id IN (${ids}) AND (p.stock<0 OR p.stock<>COALESCE(s.delta,0))`));
  assert.equal(mismatch,0);
  const reservationMismatch=Number(await sql(`SELECT COUNT(*) FROM (SELECT o.id FROM mall_order o LEFT JOIN stock_log l ON l.order_id=o.id AND l.reason='RESERVE' WHERE o.request_key LIKE '${prefix}-%' GROUP BY o.id HAVING COUNT(l.id)<>1 OR SUM(l.delta)<>-1) x`));
  assert.equal(reservationMismatch,0);
  return {orders:expected,stockLedgerMismatches:mismatch,reservationMismatches:reservationMismatch};
}
async function main() {
  if(args['--prepare']==='true') await prepare(); else data=JSON.parse(fs.readFileSync(datasetFile,'utf8'));
  if(args['--prepare-only']==='true') return;
  const selected=(args['--scenarios'] || 'product_public,product_authenticated,catalog_search,order_distributed,order_hotspot,order_same_user').split(',');
  if(selected.some(n=>!scenarios[n])) throw new Error('Unknown scenario.');
  const report={startedAt:new Date().toISOString(),model:'closed-loop, keep-alive HTTP/1.1, no retries',seconds,warmSeconds,levels,
    environment:{...state,mysqlClientFile:undefined,runDirectory:undefined,mysqlBin:undefined,cpu:os.cpus()[0].model,logicalCpus:os.cpus().length,totalMemoryBytes:os.totalmem(),freeMemoryBytes:os.freemem(),node:process.version,java:(await exec('java',['-version'])).stderr,mysql:await sql('SELECT VERSION()'),sameHost:true},results:[]};
  const output=path.join(dir,'results-'+Date.now()+'.json');
  let index=0;
  for(const name of selected) for(const concurrency of levels) {
    const prefix='b'+crypto.randomUUID().replaceAll('-','').slice(0,20);
    console.log(`START ${++index}/${selected.length*levels.length} ${name} c=${concurrency}`);
    const warm=await load(name,concurrency,warmSeconds,prefix+'-w');
    if(warm.fail) throw new Error('Warmup failed: '+JSON.stringify(warm.failureExamples));
    const before=await dbSnapshot(),metricsBefore=await metricsSnapshot();
    const delay=monitorEventLoopDelay({resolution:20});delay.enable();
    const cpuStart=process.cpuUsage(),clockStart=performance.now();
    let stopped=false;const samples=[],monitorErrors=[];
    const monitor=(async()=>{while(!stopped) {try{samples.push(await sample());}catch(e){monitorErrors.push(e.message);} if(!stopped) await new Promise(r=>setTimeout(r,1000));}})();
    const measured=await load(name,concurrency,seconds,prefix+'-m');
    const cpu=process.cpuUsage(cpuStart),cpuElapsed=performance.now()-clockStart;
    delay.disable();stopped=true;await monitor;
    const after=await dbSnapshot(),metricsAfter=await metricsSnapshot();
    let consistency=null;
    if(name.startsWith('order_')) {
      try { consistency=await verify(prefix,warm.ok+measured.ok); }
      catch(e) { consistency={failed:true,message:e.message}; }
    }
    const result={name,concurrency,...measured,warmupRequests:warm.ok,db:dbDelta(before,after),metricsBefore,metricsAfter,samples,monitorErrors,driver:{cpuPercentOfOneCore:(cpu.user+cpu.system)/1000/cpuElapsed*100,eventLoopP99Ms:delay.percentile(99)/1e6,eventLoopMaxMs:delay.max/1e6},consistency};
    report.results.push(result);report.updatedAt=new Date().toISOString();fs.writeFileSync(output,JSON.stringify(report,null,2));
    console.log(`RESULT ${name} c=${concurrency} ok/s=${measured.throughput.toFixed(1)} p95=${measured.latencyMs.p95.toFixed(1)}ms errors=${measured.fail} rowLockWaits=${result.db.status.Innodb_row_lock_waits} rowLockMs=${result.db.status.Innodb_row_lock_time}`);
    if(measured.fail || consistency?.failed || monitorErrors.length) {process.exitCode=1;console.log('Stopping after failure; partial report: '+output);return;}
  }
  report.finishedAt=new Date().toISOString();fs.writeFileSync(output,JSON.stringify(report,null,2));console.log('REPORT '+output);
}
main().catch(e=>{console.error(e.stack);process.exitCode=1;}).finally(()=>agent.destroy());
