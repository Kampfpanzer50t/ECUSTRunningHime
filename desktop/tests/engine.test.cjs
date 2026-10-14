'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {DesktopEngine}=require('../engine.cjs');
const {RunCore:C}=require('../vendor/Huati/Core/run-core.js');
const config={host:'run.ecust.edu.cn',headers:{'X-Session-ID':'desktop-fixture'},student_id:'fixture-student'};
const points=C.referencePoints.map((p,i)=>({...p,point_name:'fixture-'+i}));
class MemoryStore{constructor(state){this.value=state?structuredClone(state):null;}load(){return this.value?structuredClone(this.value):null;}save(value){this.value=structuredClone(value);}}
const time=value=>Date.parse(value+'+08:00');
function fixture(at='2026-10-14T06:13:00'){
  let now=time(at);const calls=[],failures=new Set(),store=new MemoryStore();
  const api={async value(endpoint,body){calls.push({endpoint,body});if(failures.has(endpoint))throw Error('fixture network error');
    if(endpoint==='/xcxapi/getUserInfo/')return{id:'fixture-student',name:'fixture',min_step_frequency:30,max_step_frequency:400};
    if(endpoint==='/xcxapi/createLine/')return{record_id:42};if(endpoint==='/xcxapi/checkRecord/')return{status:0,warning:'fixture-invalid'};return{};},
    async request(endpoint){calls.push({endpoint});if(failures.has('points'))throw Error('fixture points error');return{data:points,student_id:'fixture-student'};}};
  const options={clock:()=>now,monotonic:()=>now,apiFactory:()=>api,random:()=>.123456789,loginRequest:async()=>({session_keys:'desktop-fixture',id:'fixture-student'})};
  const engine=new DesktopEngine(store,options);
  return{engine,store,options,calls,failures,set:value=>{now=typeof value==='number'?value:time(value);},now:()=>now,login:()=>engine.acceptSession(config)};
}
const manual={metres:2200,duration_ms:900000,steps:2600,roads:true};
test('fresh desktop is signed out and does not contact accounts',async()=>{const f=fixture();await f.engine.tick();assert.equal(f.calls.length,0);assert.equal(f.engine.snapshot().signed_in,false);assert.equal(f.engine.snapshot().enabled,false);});
test('manual start automatically recovers absent, outside and expired origins with exact duration',async()=>{
  for(const kind of ['absent','outside','expired']){
    const f=fixture();await f.login();
    if(kind!=='absent')f.engine.state.preview={points,student_id:config.student_id,lat:kind==='outside'?31.2:points[0].lat,lng:points[0].lng,captured_at:kind==='expired'?f.now()-1800001:f.now()};
    const input={...manual,metres:4000,duration_ms:C.manualDuration('15','12','345')};
    await f.engine.startManual(input);const r=f.engine.state.run;
    assert(C.validOrigin(r,f.engine.map.boundary));assert.equal(r.duration_ms,912345);assert.equal(r.status,'created');
    assert.equal(f.calls.filter(v=>v.endpoint.includes('randrunInfo')).length,1);
    f.set(r.start_ms+912344);await f.engine.tick();assert.equal(r.status,'created');f.set(r.start_ms+912345);await f.engine.tick();assert.equal(f.engine.state.run.status,'finished');
    assert.equal(f.calls.filter(v=>v.endpoint.includes('createLine')).length,1);
  }
});
test('session validation redacts credentials and rejects mismatched accounts',async()=>{
  const f=fixture();await f.login();assert.equal(f.engine.snapshot().signed_in,true);assert(!JSON.stringify(f.engine.snapshot()).includes('desktop-fixture'));
  await assert.rejects(f.engine.acceptSession({...config,student_id:'different'}),/不一致/);
  await assert.rejects(f.engine.acceptSession({...config,headers:{'X-Session-ID':'bad\r\nheader'}}),/无效/);
});
test('manual route and pause/resume retain all three checkpoints and complete after active duration',async()=>{
  const f=fixture();await f.login();await f.engine.loadPoints();const preview=f.engine.preview(manual).route;
  assert(preview.distance>=2100);assert(Math.abs(preview.distance-manual.metres)<=500);
  for(const p of points)assert(Math.min(...preview.route_samples.map(s=>C.distance(s,p)))<.1);
  await f.engine.startManual(manual);f.set(f.now()+300000);f.engine.action('pause');f.set(f.now()+600000);await f.engine.tick();assert.equal(f.engine.state.run.status,'paused');
  f.engine.action('resume');assert.equal(C.remaining(f.engine.state.run,f.now()),600000);f.set(f.now()+599999);await f.engine.tick();assert.equal(f.engine.state.run.status,'created');
  f.set(f.now()+1);await f.engine.tick();assert.equal(f.engine.state.run.status,'finished');assert.equal(f.engine.state.run.accepted_invalid,true);assert.equal(f.engine.state.history.length,1);
  assert.equal(f.calls.filter(v=>v.endpoint.includes('updateRecordNew')).length,1);assert(f.calls.filter(v=>v.endpoint.includes('uploadPathPointV3')).length>1);
});
test('scheduled desktop prepares two minutes early and fully counts random milliseconds',async()=>{
  const f=fixture('2026-10-14T06:12:59.999');await f.login();f.engine.plan({hour:6,minute:15,roads:true});const initial=f.calls.length;
  await f.engine.tick();assert.equal(f.calls.length,initial);f.set('2026-10-14T06:13:00');await f.engine.tick();assert(f.calls.some(v=>v.endpoint.includes('randrunInfo')));assert.equal(f.engine.state.run,null);
  f.set('2026-10-14T06:15:00');await f.engine.tick();const run=f.engine.state.run;assert.equal(run.status,'created');assert(run.duration_ms>720000&&run.duration_ms<960000);assert(run.duration_ms%1000>0);assert(run.distance>=2100);
  f.set(f.now()+run.duration_ms-1);await f.engine.tick();assert.equal(f.engine.state.run.status,'created');f.set(f.now()+1);await f.engine.tick();assert.equal(f.engine.state.run.status,'finished');
});
test('06:00 plan prepares origin at 05:58 and waits for opening before points',async()=>{
  const f=fixture('2026-10-14T05:58:00');await f.login();f.calls.length=0;f.engine.plan({hour:6,minute:0,roads:true});await f.engine.tick();assert(f.engine.state.schedule.prepared_origin);assert.equal(f.calls.length,0);
  f.set('2026-10-14T06:00:00');await f.engine.tick();assert.equal(f.engine.state.run.status,'created');assert(f.engine.state.schedule.prepared_refresh_ms>=time('2026-10-14T06:00:00'));
});
test('preparation and read-only finish checks retry without skipping task',async()=>{
  const f=fixture();await f.login();f.engine.plan({hour:6,minute:15,roads:true});f.failures.add('points');await f.engine.tick();assert.equal(f.engine.state.schedule.retry_ms,f.now()+30000);
  f.failures.clear();f.set('2026-10-14T06:15:00');await f.engine.tick();f.set(f.now()+f.engine.state.run.duration_ms);f.failures.add('/xcxapi/checkRecord/');await f.engine.tick();
  assert.equal(f.engine.state.run.status,'created');assert(f.engine.state.run.auto_submit);f.failures.clear();f.set(f.now()+29999);await f.engine.tick();assert.equal(f.engine.state.run.status,'created');
  f.set(f.now()+1);await f.engine.tick();assert.equal(f.engine.state.run.status,'finished');assert.equal(f.calls.filter(v=>v.endpoint.includes('updateRecordNew')).length,1);
});
test('changing to later time permits another same-day run, same slot cannot duplicate',async()=>{
  const f=fixture('2026-10-14T06:15:00');await f.login();f.engine.plan({hour:6,minute:15,roads:false});await f.engine.tick();f.set(f.now()+f.engine.state.run.duration_ms);await f.engine.tick();await f.engine.tick();
  assert.equal(f.calls.filter(v=>v.endpoint.includes('createLine')).length,1);f.engine.plan({hour:7,minute:0,roads:true});f.set('2026-10-14T06:58:00');await f.engine.tick();f.set('2026-10-14T07:00:00');await f.engine.tick();
  assert.equal(f.calls.filter(v=>v.endpoint.includes('createLine')).length,2);
});
test('unknown create and submit responses are durable and never replayed on restart',async()=>{
  for(const endpoint of ['/xcxapi/createLine/','/xcxapi/updateRecordNew/','/xcxapi/uploadPathPointV3/']){
    const f=fixture('2026-10-14T06:15:00');await f.login();f.engine.plan({hour:6,minute:15,roads:true});f.failures.add(endpoint);await f.engine.tick();
    if(endpoint!=='/xcxapi/createLine/'){f.set(f.now()+f.engine.state.run.duration_ms);await f.engine.tick();}
    assert.equal(f.engine.state.run.status,endpoint.includes('createLine')?'create_unknown':'finish_unknown');const previous=f.calls.filter(v=>v.endpoint===endpoint).length;
    const again=new DesktopEngine(f.store,f.options);f.set(f.now()+3600000);await again.tick();assert.equal(f.calls.filter(v=>v.endpoint===endpoint).length,previous);
  }
});
test('active record resumes after process restart with remaining timer intact',async()=>{
  const f=fixture();await f.login();await f.engine.loadPoints();await f.engine.startManual(manual);f.set(f.now()+300000);
  const again=new DesktopEngine(f.store,f.options);assert.equal(again.snapshot().run.remaining_ms,600000);f.set(f.now()+600000);await again.tick();assert.equal(again.state.run.status,'finished');
});
test('discard stops submission and disables do not terminate an existing run',async()=>{
  const f=fixture('2026-10-14T06:15:00');await f.login();f.engine.plan({hour:6,minute:15,roads:true});await f.engine.tick();f.engine.action('disable');assert.equal(f.engine.state.run.status,'created');
  f.engine.action('discard');f.set(f.now()+3600000);await f.engine.tick();assert(!f.calls.some(v=>v.endpoint.includes('updateRecordNew')));assert.equal(f.engine.state.history[0].status,'discarded');
});
test('input limits and failed stale-point refresh cannot create server state',async()=>{
  const f=fixture();await f.login();assert.throws(()=>f.engine.plan({hour:21,minute:1}),/时间/);await f.engine.loadPoints();assert.throws(()=>f.engine.preview({...manual,metres:2000}),/2.1/);
  f.set(f.now()+1800001);assert.throws(()=>f.engine.preview(manual),/刷新三点/);f.failures.add('points');await assert.rejects(f.engine.startManual(manual),/fixture points/);assert(!f.calls.some(v=>v.endpoint.includes('createLine')));
});
test('SMS cooldown persists and no password is stored',async()=>{
  const f=fixture();await f.engine.sms('13800000000');await assert.rejects(f.engine.sms('13800000000'),/60 秒/);await f.engine.login({phone:'13800000000',secret:'fixture-password',byCode:false});
  assert(!JSON.stringify(f.store.value).includes('fixture-password'));
});
