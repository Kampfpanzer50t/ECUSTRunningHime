'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const crypto=require('node:crypto');
const {Executor,Store,cleanSession,encrypt,makeServer}=require('../companion/runner.cjs');
const {RunCore:C}=require('../Huati/Core/run-core.js');
const time=value=>Date.parse(value+'+08:00');
const config={host:'run.ecust.edu.cn',student_id:'fixture-account',headers:{'X-Session-ID':'fixture-session'}};
const token='fixture_token_123456789012345678901234567890123456789';
const points=C.referencePoints.map((p,i)=>({...p,point_name:'fixture-'+i}));
class MemoryStore{
  constructor(state){this.value=state?structuredClone(state):null;this.writes=[];}
  load(){return this.value?structuredClone(this.value):null;}
  save(value){this.value=structuredClone(value);this.writes.push(structuredClone(value));}
}
function fixture(at='2026-10-13T06:13:00'){
  let now=time(at);const calls=[],failures=new Set(),store=new MemoryStore();
  const api={
    async value(endpoint,body){
      calls.push({endpoint,body});if(failures.has(endpoint))throw new Error('fixture connection failure');
      if(endpoint==='/xcxapi/getUserInfo/')return {id:'fixture-account',min_step_frequency:30,max_step_frequency:400};
      if(endpoint==='/xcxapi/createLine/')return {record_id:77};
      if(endpoint==='/xcxapi/checkRecord/')return {status:0,warning:'fixture invalid'};
      return {};
    },
    async request(endpoint){
      calls.push({endpoint});if(failures.has('points'))throw new Error('fixture points failure');
      return {code:1,data:points,student_id:'fixture-account'};
    }
  };
  const options={clock:()=>now,monotonic:()=>now,apiFactory:()=>api,random:()=>0.123456789};
  const executor=new Executor(store,options);
  const plan=(extra={})=>executor.command({id:crypto.randomUUID(),action:'plan',session:config,hour:6,minute:15,roads:true,...extra});
  return {executor,store,calls,failures,options,plan,now:()=>now,set:value=>{now=typeof value==='number'?value:time(value);}};
}
test('prepares exactly two minutes early, waits for target and saves random milliseconds',async()=>{
  const f=fixture('2026-10-13T06:12:59.999');f.plan();await f.executor.tick();assert.equal(f.calls.length,0);
  f.set('2026-10-13T06:13:00');await f.executor.tick();assert(f.calls.some(c=>c.endpoint.includes('randrunInfo')));assert(!f.calls.some(c=>c.endpoint.includes('createLine')));
  f.set('2026-10-13T06:14:59.999');await f.executor.tick();assert.equal(f.executor.state.run,null);
  f.set('2026-10-13T06:15:00');await f.executor.tick();const run=f.executor.state.run;
  assert.equal(run.start_ms,f.now());assert.equal(run.status,'created');assert(run.distance>=2100);
  assert(run.duration_ms>720000&&run.duration_ms<960000);assert.notEqual(run.duration_ms%1000,0);
  for(const point of points)assert(Math.min(...run.route_samples.map(p=>C.distance(p,point)))<0.1);
  await f.executor.tick();assert.equal(f.calls.filter(c=>c.endpoint.includes('createLine')).length,1);
});
test('06:00 schedule prepares origin at 05:58, refreshes after opening and starts',async()=>{
  const f=fixture('2026-10-13T05:58:00');f.plan({hour:6,minute:0});await f.executor.tick();
  assert(f.executor.state.schedule.prepared_origin);assert.equal(f.calls.length,0);
  f.set('2026-10-13T06:00:00');await f.executor.tick();assert.equal(f.executor.state.run.status,'created');
  assert(f.executor.state.schedule.prepared_refresh_ms>=time('2026-10-13T06:00:00'));
});
test('preparation retries after failure and never uses stale points',async()=>{
  const f=fixture();f.plan();f.failures.add('points');await f.executor.tick();assert.equal(f.executor.state.run,null);
  assert.equal(f.executor.state.schedule.retry_ms,f.now()+30000);f.failures.clear();
  f.set('2026-10-13T06:13:29');await f.executor.tick();assert.equal(f.calls.filter(c=>c.endpoint.includes('randrunInfo')).length,1);
  f.set('2026-10-13T06:20:00');await f.executor.tick();assert.equal(f.executor.state.run.status,'created');
  assert.equal(f.executor.state.run.start_ms,f.now());assert.equal(f.calls.filter(c=>c.endpoint.includes('randrunInfo')).length,2);
});
test('counts the whole duration, submits invalid verdict and uploads every path batch',async()=>{
  const f=fixture('2026-10-13T06:15:00');f.plan();await f.executor.tick();const end=f.now()+f.executor.state.run.duration_ms;
  f.set(end-1);await f.executor.tick();assert(!f.calls.some(c=>c.endpoint.includes('checkRecord')));
  f.set(end);await f.executor.tick();assert.equal(f.executor.state.run.status,'finished');assert(f.executor.state.run.accepted_invalid);
  assert.equal(f.calls.filter(c=>c.endpoint.includes('updateRecordNew')).length,1);
  assert(f.calls.filter(c=>c.endpoint.includes('uploadPathPoint')).length>1);
  const submit=f.calls.find(c=>c.endpoint.includes('updateRecordNew')).body.a;
  const [iv,ciphertext]=submit.split(':'),decipher=crypto.createDecipheriv('aes-128-cbc',Buffer.from('A7fK92LmXyQw8vRp'),Buffer.from(iv,'base64'));
  const payload=JSON.parse(Buffer.concat([decipher.update(Buffer.from(ciphertext,'base64')),decipher.final()]).toString());
  assert.equal(payload.record_id,77);assert.equal(payload.running_time,Math.floor(f.executor.state.run.duration_ms/1000));
  assert.equal(f.executor.state.run.uploaded_points,C.path(f.executor.state.run,end).length);
});
test('unknown creation is durable and never blindly retried, even after restart',async()=>{
  const f=fixture('2026-10-13T06:15:00');f.plan();f.failures.add('/xcxapi/createLine/');await f.executor.tick();
  assert.equal(f.executor.state.run.status,'create_unknown');f.failures.clear();
  const again=new Executor(f.store,f.options);f.set('2026-10-13T07:00:00');await again.tick();
  assert.equal(f.calls.filter(c=>c.endpoint.includes('createLine')).length,1);
});
test('unknown update and path upload are never blindly retried',async()=>{
  for(const endpoint of ['/xcxapi/updateRecordNew/','/xcxapi/uploadPathPointV3/']){
    const f=fixture('2026-10-13T06:15:00');f.plan();await f.executor.tick();f.failures.add(endpoint);f.set(f.now()+f.executor.state.run.duration_ms);await f.executor.tick();
    assert.equal(f.executor.state.run.status,'finish_unknown');const calls=f.calls.length;f.failures.clear();
    await new Executor(f.store,f.options).tick();assert.equal(f.calls.length,calls);
  }
});
test('restart restores interrupted read-only check but preserves uncertain mutations',()=>{
  for(const [input,output] of [['checking','created'],['create_pending','create_unknown'],['finish_pending','finish_unknown'],['record_updated','finish_unknown']]){
    const store=new MemoryStore({schedule:{enabled:false},run:{status:input,auto_submit:true},receipts:{}});
    const ex=new Executor(store,{clock:()=>10000});assert.equal(ex.state.run.status,output);assert.equal(ex.state.run.auto_submit,output==='created');
  }
});
test('pause and resume preserve exact duration, and release cannot abandon an active record',async()=>{
  const f=fixture('2026-10-13T06:15:00');f.plan();await f.executor.tick();f.set(f.now()+100000);
  const duration=f.executor.state.run.duration_ms;
  f.executor.command({id:crypto.randomUUID(),action:'pause'});const remaining=C.remaining(f.executor.state.run,f.now());
  f.set(f.now()+200000);await f.executor.tick();assert.equal(C.remaining(f.executor.state.run,f.now()),remaining);
  assert.throws(()=>f.executor.command({id:crypto.randomUUID(),action:'release'}));
  f.executor.command({id:crypto.randomUUID(),action:'resume'});assert.equal(f.executor.state.run.duration_ms,duration);
  f.executor.command({id:crypto.randomUUID(),action:'discard'});assert.equal(f.executor.state.run.status,'discarded');
  f.executor.command({id:crypto.randomUUID(),action:'release'});assert.equal(f.executor.state.schedule.enabled,false);
});
test('same command id can be retried without re-applying a pause',async()=>{
  const f=fixture('2026-10-13T06:15:00');f.plan();await f.executor.tick();const command={id:crypto.randomUUID(),action:'pause'};
  f.executor.command(command);f.executor.command(command);assert.equal(f.executor.state.run.pauses.length,1);
  assert.equal(f.executor.command(command).confirmed_id,command.id);
  assert.throws(()=>f.executor.command({...command,action:'discard'}));
});
test('disabling future plans leaves the current run eligible for submission',async()=>{
  const f=fixture('2026-10-13T06:15:00');f.plan();await f.executor.tick();f.executor.command({id:crypto.randomUUID(),action:'disable'});
  f.set(f.now()+f.executor.state.run.duration_ms);await f.executor.tick();assert.equal(f.executor.state.run.status,'finished');
});
test('a later schedule today can create another record, while old slot stays reserved',async()=>{
  const f=fixture('2026-10-13T06:15:00');f.plan();await f.executor.tick();f.executor.command({id:crypto.randomUUID(),action:'discard'});
  f.plan({hour:7,minute:0});f.set('2026-10-13T06:58:00');await f.executor.tick();f.set('2026-10-13T07:00:00');await f.executor.tick();
  assert.equal(f.calls.filter(c=>c.endpoint.includes('createLine')).length,2);
  assert.deepEqual(f.executor.state.schedule.attempted_times,['06:15','07:00']);
});
test('21:00 schedule tolerates milliseconds of timer delay',async()=>{
  const f=fixture('2026-10-13T20:58:00');f.plan({hour:21,minute:0});await f.executor.tick();f.set('2026-10-13T21:00:00.002');await f.executor.tick();
  assert.equal(f.executor.state.run.status,'created');
});
test('status response and stored receipts never contain account session headers',()=>{
  const f=fixture();f.plan();const text=JSON.stringify(f.executor.snapshot());assert(!text.includes('fixture-session'));assert(!text.includes('fixture-account'));
  assert(!JSON.stringify(f.executor.state.receipts).includes('fixture-session'));
});
test('session validation prevents header injection and protocol crypto stays compatible',()=>{
  assert.throws(()=>cleanSession({...config,host:'wrong.example'}));assert.throws(()=>cleanSession({...config,headers:{'X-Session-ID':'x\r\nfoo'}}));
  assert.equal(encrypt({fixture:1},Buffer.alloc(16)),'AAAAAAAAAAAAAAAAAAAAAA==:BGu00rSpvgFKuj16y9KktQ==');
});
test('encrypted state authenticates contents and refuses missing original keys',()=>{
  const folder=fs.mkdtempSync(path.join(os.tmpdir(),'huati-executor-test-'));
  try{
    const store=new Store(folder,true);store.save({fixture:'private-value'});assert.equal(store.load().fixture,'private-value');
    const file=path.join(folder,'state.bin'),bytes=fs.readFileSync(file);assert(!bytes.includes(Buffer.from('private-value')));
    bytes[bytes.length-1]^=1;fs.writeFileSync(file,bytes);assert.throws(()=>store.load());
    fs.unlinkSync(path.join(folder,'key.bin'));assert.throws(()=>new Store(folder,true));
  }finally{
    assert.equal(path.dirname(fs.realpathSync(folder)).toLowerCase(),fs.realpathSync(os.tmpdir()).toLowerCase());
    assert(path.basename(folder).startsWith('huati-executor-test-'));
    fs.rmSync(folder,{recursive:true,force:true});
  }
});
test('HTTP endpoint requires authentication and denies browser cross-origin commands',async()=>{
  const f=fixture(),server=makeServer(f.executor,token);await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const root='http://127.0.0.1:'+server.address().port;
    assert.equal((await fetch(root+'/v1/status')).status,401);
    assert.equal((await fetch(root+'/v1/status',{headers:{Authorization:'Bearer '+token,Origin:'https://example.com'}})).status,401);
    const headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
    assert.equal((await fetch(root+'/v1/status',{headers})).status,200);
    const response=await fetch(root+'/v1/command',{method:'POST',headers,body:JSON.stringify({id:crypto.randomUUID(),action:'plan',session:config,hour:6,minute:15})});
    assert.equal(response.status,200);assert((await response.json()).enabled);
    assert.equal((await fetch(root+'/invalid',{headers})).status,404);
  }finally{await new Promise(resolve=>server.close(resolve));}
});
test('rejected commands cannot modify either in-memory or durable state',()=>{
  const f=fixture(),before=JSON.stringify(f.executor.state);
  assert.throws(()=>f.plan({hour:21,minute:30}));assert.equal(JSON.stringify(f.executor.state),before);assert.equal(JSON.stringify(f.store.value),before);
});
test('a repeated command must preserve its full payload',()=>{
  const f=fixture(),id=crypto.randomUUID(),command={id,action:'plan',session:config,hour:6,minute:15,roads:true};
  f.executor.command(command);
  assert.throws(()=>f.executor.command({...command,hour:7}));
  assert.equal(f.executor.state.schedule.hour,6);
  f.executor.command({roads:true,minute:15,hour:6,session:config,action:'plan',id});
  assert.equal(f.executor.state.schedule.hour,6);
});
test('failed durable command save rolls back ownership state before rejecting',()=>{
  const f=fixture(),before=JSON.stringify(f.executor.state);
  f.store.save=()=>{throw new Error('fixture disk failure');};
  assert.throws(()=>f.plan());assert.equal(JSON.stringify(f.executor.state),before);
});
