'use strict';
// Personal executor. No account is contacted unless a plan is provisioned by the iPhone.
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const http=require('node:http');
const {execFileSync}=require('node:child_process');
const {RunCore:C}=require('../Huati/Core/run-core.js');
const resource=name=>JSON.parse(fs.readFileSync(path.join(__dirname,'../Huati/Content',name),'utf8'));
const HOST='run.ecust.edu.cn';
const assert=(ok,message)=>{if(!ok)throw new Error(message);};
const unresolved=r=>!!r&&C.unresolved(r);
const copy=v=>JSON.parse(JSON.stringify(v));
const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
function cleanSession(input){
  assert(input&&input.host===HOST,'会话主机不正确');
  const headers={};
  for(const [name,value] of Object.entries(input.headers||{})){
    const key=name.toLowerCase();if(!['x-session-id','authorization','user-agent','referer'].includes(key))continue;
    assert(typeof value==='string'&&value.length<=8192&&!/[\r\n]/.test(value),'会话头无效');
    if(value)headers[key]=value;
  }
  assert(headers['x-session-id']&&headers['x-session-id']!=='1111','会话缺少登录信息');
  assert(input.student_id&&String(input.student_id).length<=80,'会话缺少账号编号');
  return {host:HOST,headers,student_id:String(input.student_id)};
}
function encrypt(value,iv=crypto.randomBytes(16)){
  const cipher=crypto.createCipheriv('aes-128-cbc',Buffer.from('A7fK92LmXyQw8vRp'),iv);
  return iv.toString('base64')+':'+Buffer.concat([cipher.update(JSON.stringify(value),'utf8'),cipher.final()]).toString('base64');
}
class API{
  constructor(config){this.config=cleanSession(config);}
  async request(endpoint,body){
    assert(/^\/xcx(?:t)?api\//.test(endpoint)&&!endpoint.includes('#'),'接口路径无效');
    const url=new URL('https://'+HOST+endpoint);assert(url.hostname===HOST,'接口主机无效');
    const response=await fetch(url,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(40000),headers:{...this.config.headers,'content-type':'application/json','cache-control':'no-store'},body:body===undefined?undefined:JSON.stringify(body)});
    assert(response.ok,'服务器网络请求失败');
    const parts=[];let size=0;
    for await(const chunk of response.body){size+=chunk.length;assert(size<=2097152,'响应过大');parts.push(chunk);}
    const value=JSON.parse(Buffer.concat(parts).toString('utf8'));
    assert(value.code===1&&value.data!==undefined,value.code===-2?'会话已过期，请重新登录同步':'服务器未接受请求');
    return value;
  }
  async value(endpoint,body){return (await this.request(endpoint,body)).data;}
}
function protectedKey(file,create){
  const windows=process.platform==='win32';
  const transform=(bytes,protect)=>{
    if(!windows)return bytes;
    const operation=protect?'Protect':'Unprotect';
    const script=`Add-Type -AssemblyName System.Security; $dataBytes=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); [Convert]::ToBase64String([Security.Cryptography.ProtectedData]::${operation}($dataBytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))`;
    return Buffer.from(execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',script],{input:bytes.toString('base64'),windowsHide:true,encoding:'utf8'}).trim(),'base64');
  };
  if(!fs.existsSync(file)){
    assert(create,'缺少存储密钥，已停止以保护原记录');
    fs.writeFileSync(file,transform(crypto.randomBytes(32),true),{mode:0o600,flag:'wx'});
  }
  const key=transform(fs.readFileSync(file),false);assert(key.length===32,'存储密钥无效');return key;
}
class Store{
  constructor(folder,create=false){
    fs.mkdirSync(folder,{recursive:true,mode:0o700});this.folder=folder;
    const keyFile=path.join(folder,'key.bin');
    assert(!(fs.existsSync(path.join(folder,'state.bin'))&&!fs.existsSync(keyFile)),'原数据的密钥缺失，请恢复备份');
    this.key=protectedKey(keyFile,create);
  }
  load(){
    const file=path.join(this.folder,'state.bin');if(!fs.existsSync(file))return null;
    const data=fs.readFileSync(file);assert(data.length>=28,'存储数据损坏');
    const cipher=crypto.createDecipheriv('aes-256-gcm',this.key,data.subarray(0,12));
    cipher.setAAD(Buffer.from('huati-companion-v1'));cipher.setAuthTag(data.subarray(12,28));
    return JSON.parse(Buffer.concat([cipher.update(data.subarray(28)),cipher.final()]).toString('utf8'));
  }
  save(state){
    const iv=crypto.randomBytes(12),cipher=crypto.createCipheriv('aes-256-gcm',this.key,iv);
    cipher.setAAD(Buffer.from('huati-companion-v1'));
    const encrypted=Buffer.concat([cipher.update(JSON.stringify(state),'utf8'),cipher.final()]);
    const temp=path.join(this.folder,'state.tmp');fs.writeFileSync(temp,Buffer.concat([iv,cipher.getAuthTag(),encrypted]),{mode:0o600});
    fs.renameSync(temp,path.join(this.folder,'state.bin'));
  }
}
class Executor{
  constructor(store,options={}){
    this.store=store;this.state=store.load()||{schedule:{enabled:false,hour:6,minute:15},run:null,receipts:{}};
    this.clock=options.clock||Date.now;this.apiFactory=options.apiFactory||(config=>new API(config));
    this.random=options.random||Math.random;this.map=options.map||resource('campus-map.json');this.graph=options.graph||resource('campus-roads.json');
    this.monotonic=options.monotonic||(()=>performance.now());this.process=crypto.randomUUID();this.queue=Promise.resolve();
    const r=this.state.run;
    if(r){
      if(r.status==='checking'){r.status='created';r.auto_submit=true;r.check_retry_ms=this.clock()+30000;}
      if(r.status==='create_pending'){r.status='create_unknown';r.auto_submit=false;}
      if(['finish_pending','record_updated'].includes(r.status)){r.status='finish_unknown';r.auto_submit=false;}
      if(['create_unknown','finish_unknown'].includes(r.status))r.error='操作被中断，需到官方小程序核实；不会重复发送。';
    }
    this.save();
  }
  save(){this.store.save(this.state);}
  exclusive(work){const result=this.queue.then(work);this.queue=result.catch(()=>{});return result;}
  snapshot(){
    const r=this.state.run;
    // Never expose session headers or internal command payloads in status responses.
    const keys=['status','record_id','start_ms','duration_ms','distance','steps','points','route_samples','accepted_invalid','invalid_reason','error','pauses'];
    const run=r?Object.fromEntries(keys.filter(k=>r[k]!==undefined).map(k=>[k,r[k]])):{};
    if(r)run.remaining_ms=C.remaining(r,this.clock());
    return {version:'2.0.5',server_time_ms:this.clock(),enabled:!!this.state.schedule.enabled,hour:this.state.schedule.hour,minute:this.state.schedule.minute,preparation:this.state.schedule.prep_message||'',message:this.state.message||'',run,unresolved:unresolved(r)};
  }
  command(command){
    const previous=copy(this.state);
    try{return this.applyCommand(command);}catch(error){this.state=previous;throw error;}
  }
  applyCommand(command){
    assert(command&&typeof command.id==='string'&&/^[a-zA-Z0-9-]{16,80}$/.test(command.id),'命令编号无效');
    assert(['plan','disable','pause','resume','discard','release'].includes(command.action),'命令无效');
    const digest=crypto.createHash('sha256').update(JSON.stringify(canonical(command))).digest('hex');
    const old=this.state.receipts[command.id];
    if(old){assert(old.action===command.action&&old.digest===digest,'命令编号已被其他操作使用');return {...this.snapshot(),confirmed_id:command.id};}
    const now=this.clock(),s=this.state.schedule,r=this.state.run;
    switch(command.action){
    case 'plan':{
      assert(C.validTime(command.hour,command.minute),'时间需在北京时间 06:00–21:00');
      const config=cleanSession(command.session);
      assert(!unresolved(r)||r.student_id===config.student_id,'请先处理电脑上的原账号记录');
      this.state.config=config;this.state.roads=!!command.roads;
      const changed=s.hour!==command.hour||s.minute!==command.minute;
      const next={...s,hour:command.hour,minute:command.minute,enabled:true,enabled_at_ms:now,student_id:config.student_id};
      if(changed||s.student_id!==config.student_id){for(const key of Object.keys(next))if(key.startsWith('prep')||key==='retry_ms')delete next[key];}
      this.state.schedule=next;this.state.message='电脑已接管计划：提前两分钟准备，到点开始。';break;
    }
    case 'disable':s.enabled=false;this.state.message='已停止后续计划；当前记录继续处理。';break;
    case 'pause':this.state.run=C.pause(r,now);this.state.message='电脑记录已暂停。';break;
    case 'resume':this.state.run=C.resume(r,now);this.state.message='电脑记录已继续。';break;
    case 'discard':this.state.run=C.discard(r,now);this.state.message='已舍弃电脑记录；服务器占位仍保留。';break;
    case 'release':assert(!unresolved(r),'请先完成或舍弃电脑当前记录');s.enabled=false;delete this.state.config;this.state.message='已结束代执行，可使用手机本机功能。';break;
    }
    this.state.receipts[command.id]={action:command.action,at:now,digest};
    const ids=Object.keys(this.state.receipts);for(const id of ids.slice(0,Math.max(0,ids.length-200)))delete this.state.receipts[id];
    this.save();return {...this.snapshot(),confirmed_id:command.id};
  }
  async tick(){
    const now=this.clock(),s=this.state.schedule,r=this.state.run;
    if(r?.status==='created'&&r.auto_submit&&now>=(r.check_retry_ms||0)&&C.remaining(r,now)===0){await this.finish();return;}
    if(!C.dailyDue(s,now,unresolved(r)))return;
    const at=C.target(s,now);
    if(unresolved(r)){
      this.state.schedule={...C.reserve(s,at,now),prep_message:'还有未处理的记录，本次时间未创建新记录。'};this.save();return;
    }
    if(s.prep_target_ms!==at){
      for(const key of Object.keys(s))if(key.startsWith('prep')||key==='retry_ms')delete s[key];
      s.prep_target_ms=at;
    }
    if(!C.ready(s,now,at)){
      try{
        if(!s.prepared_origin||!C.validOrigin(s.prepared_origin,this.map.boundary))s.prepared_origin=C.origin(null,this.map.boundary,Math.floor(this.random()*4294967296),this.graph);
        s.prep_message='准备校内模拟起点，并刷新今日随机三点。';this.save();
        if(this.clock()<C.opening(at)){s.retry_ms=C.opening(at);s.prep_message='05:58 已准备起点，等待 06:00 接口开放后刷新三点。';this.save();return;}
        const api=this.apiFactory(this.state.config),user=await api.value('/xcxapi/getUserInfo/');
        assert(String(user.id)===this.state.config.student_id,'账号不一致');
        await api.value('/xcxapi/RunningData/');s.prepared_refresh_ms=this.clock();
        const p=s.prepared_origin,response=await api.request(`/xcxtapi/activity/randrunInfo?lat=${p.lat}&lng=${p.lng}`);
        C.points(response.data);assert(String(response.student_id)===String(user.id),'三点账号不一致');
        const start=C.validOrigin({lat:Number(response.lat),lng:Number(response.lng)},this.map.boundary)?response:p;
        s.prepared_preview={points:response.data,lat:Number(start.lat),lng:Number(start.lng),student_id:String(user.id),captured_at:this.clock()};
        s.prep_status='ready';s.retry_ms=0;s.prep_message='今日三点已刷新，等待计划时间。';this.save();
      }catch(error){s.prep_status='waiting_points';s.retry_ms=this.clock()+30000;s.prep_message='准备失败，30 秒后继续重试；请确认联网和会话有效。';this.save();return;}
    }
    if(this.clock()<at||!C.dailyEligible(s,this.clock())||!C.ready(s,this.clock(),at))return;
    const duration=C.randomDuration(this.random());
    this.state.schedule=C.reserve(s,at,this.clock());this.state.schedule.last_duration_ms=duration;this.save();
    try{await this.create(duration);}catch(error){this.state.message='创建未完成；查看当前记录或重新设定后续时间。';this.save();}
  }
  async create(duration){
    const p=this.state.schedule.prepared_preview,api=this.apiFactory(this.state.config);
    const route=this.state.roads?C.road(p,2200,true,this.graph,this.map.boundary,crypto.randomInt(1,0x100000000)):C.basic(p,2200,true);
    const user=await api.value('/xcxapi/getUserInfo/');assert(String(user.id)===p.student_id,'账号已变化');
    const frequency=2600/(duration/60000);assert(frequency>=Number(user.min_step_frequency??30)&&frequency<=Number(user.max_step_frequency??400),'步频不在服务器范围');
    this.state.run={...route,status:'create_pending',student_id:p.student_id,points:p.points,lat:p.lat,lng:p.lng,start_ms:this.clock(),uptime_start:this.monotonic(),process_id:this.process,duration_ms:duration,steps:2600,pauses:[],auto_submit:true};this.save();
    try{
      const result=await api.value('/xcxapi/createLine/',{student_id:user.id,pass_point:p.points});assert(Number(result.record_id)>0,'创建响应缺少编号');
      this.state.run.record_id=Number(result.record_id);this.state.run.status='created';
      this.state.message='已开始，计满完整随机时长后校验并直接提交。';
    }catch(error){this.state.run.status='create_unknown';this.state.run.auto_submit=false;this.state.run.error='创建结果待核实；不会重复创建。';}
    this.state.schedule.prep_status='consumed';delete this.state.schedule.prepared_preview;this.save();
  }
  async finish(){
    const r=this.state.run,end=this.clock(),seconds=Math.floor((end-r.start_ms)/1000),api=this.apiFactory(this.state.config);
    assert(seconds>0&&seconds<=86400,'记录时长异常');
    if(r.process_id===this.process)assert(Math.abs(this.monotonic()-r.uptime_start-(end-r.start_ms))<=60000,'电脑时间发生变化');
    const track=C.path(r,end),payload={record_id:r.record_id,pace:seconds/60/(r.distance/1000),running_time:seconds,mileage:r.distance,start_time:C.date(r.start_ms),end_time:C.date(end),pass_point:3,step_count:r.steps};
    const body={a:encrypt(payload)};r.status='checking';r.auto_submit=false;this.save();
    try{
      const user=await api.value('/xcxapi/getUserInfo/');assert(String(user.id)===r.student_id,'账号已变化');
      const detail=await api.value('/xcxapi/checkRecord/',body);assert([0,1].includes(Number(detail.status)),'校验状态未知');
      r.accepted_invalid=Number(detail.status)===0;r.invalid_reason=detail.warning||detail.message||'';
    }catch(error){r.status='created';this.state.run=C.pause(r,end);this.state.run.error='校验失败，已暂停。请检查会话与网络后继续。';this.save();return;}
    r.status='finish_pending';r.finish_payload=payload;this.save();
    try{
      await api.value('/xcxapi/updateRecordNew/',body);r.status='record_updated';this.save();
      for(let i=0;i<track.length;i+=50){
        await api.value('/xcxapi/uploadPathPointV3/',{img_url:encrypt({record_id:r.record_id,path_point:track.slice(i,i+50),path_image:''})});
        r.uploaded_points=Math.min(i+50,track.length);this.save();
      }
      r.status='finished';r.end_ms=end;delete r.error;
      this.state.message=r.accepted_invalid?'已提交，服务器判为无效。':'已提交，请以官方小程序成绩为准。';this.save();
    }catch(error){r.status='finish_unknown';r.error='提交或轨迹上传结果待核实；不会重复提交。';this.save();}
  }
}
function authorize(request,token){
  const supplied=Buffer.from(request.headers.authorization||''),expected=Buffer.from('Bearer '+token);
  return supplied.length===expected.length&&crypto.timingSafeEqual(supplied,expected);
}
async function readBody(request){
  assert(request.headers['content-type']?.split(';')[0]==='application/json','需要 JSON 请求');
  const parts=[];let size=0;for await(const part of request){size+=part.length;assert(size<=262144,'请求过大');parts.push(part);}
  return JSON.parse(Buffer.concat(parts).toString('utf8'));
}
function makeServer(executor,token){
  return http.createServer({requestTimeout:15000,headersTimeout:10000},async(request,response)=>{
    let commandID;
    const send=(code,value)=>{response.writeHead(code,{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});response.end(JSON.stringify(value));};
    if(!authorize(request,token)||request.headers.origin){send(401,{error:'认证失败'});return;}
    try{
      if(request.method==='GET'&&request.url==='/v1/status'){send(200,await executor.exclusive(()=>executor.snapshot()));return;}
      if(request.method==='POST'&&request.url==='/v1/command'){
        const body=await readBody(request);commandID=body.id;send(200,await executor.exclusive(()=>executor.command(body)));return;
      }
      send(404,{error:'接口不存在'});
    }catch(error){send(400,{error:error.message,result:'rejected',command_id:commandID});}
  });
}
async function main(){
  const args=process.argv.slice(2),mode=args[0]||'serve';
  const option=(key,fallback)=>{const i=args.indexOf(key);return i>=0?args[i+1]:fallback;};
  const folder=path.resolve(option('--data',path.join(__dirname,'private')));
  const store=new Store(folder,mode==='init');let state=store.load();
  if(mode==='init'){
    assert(!fs.existsSync(path.join(folder,'executor.lock')),'请先关闭代执行助手再初始化');
    const endpoint=new URL(option('--endpoint',''));assert(endpoint.protocol==='https:'&&!endpoint.username&&!endpoint.password&&!endpoint.search&&!endpoint.hash&&endpoint.pathname==='/','请提供自己的 HTTPS 根地址');
    state=state||{schedule:{enabled:false,hour:6,minute:15},run:null,receipts:{}};
    state.token=state.token||crypto.randomBytes(32).toString('base64url');store.save(state);
    fs.writeFileSync(path.join(folder,'huati-companion-pair.json'),JSON.stringify({format:'huati-companion-pair',version:1,endpoint:endpoint.origin,token:state.token},null,2),{mode:0o600});
    process.stdout.write('已生成私有配对文件：'+path.join(folder,'huati-companion-pair.json')+'\n');return;
  }
  assert(mode==='serve'&&state?.token,'请先执行 init 生成配对文件');
  const port=Number(option('--port','8765'));assert(Number.isInteger(port)&&port>=1024&&port<=65535,'端口无效');
  const lock=path.join(folder,'executor.lock');const lockHandle=fs.openSync(lock,'wx',0o600);
  fs.writeFileSync(lockHandle,String(process.pid));
  process.once('exit',()=>{fs.closeSync(lockHandle);fs.unlinkSync(lock);});
  const executor=new Executor(store),server=makeServer(executor,state.token);server.listen(port,'127.0.0.1');
  server.on('error',()=>{process.stderr.write('本机端口不可用，请关闭重复实例。\n');process.exitCode=1;stop();});
  process.stdout.write('电脑代执行已启动，仅监听本机 '+port+'。请保持电脑联网且不睡眠。\n');
  let stopping=false,timer;
  const loop=async()=>{
    if(stopping)return;
    try{await executor.exclusive(()=>executor.tick());}catch(error){
      if(executor.state.run?.status==='created')executor.state.run=C.pause(executor.state.run,Date.now());
      executor.state.message='任务停止：时长或状态异常，请查看记录。';executor.save();
    }
    const wake=C.wake(executor.state.schedule,executor.state.run,Date.now());
    if(!stopping)timer=setTimeout(loop,Math.min(1000,Math.max(1,wake?Math.ceil(wake-Date.now()):1000)));
  };
  const stop=()=>{stopping=true;clearTimeout(timer);server.close();};process.on('SIGINT',stop);process.on('SIGTERM',stop);await loop();
}
module.exports={API,Store,Executor,cleanSession,encrypt,makeServer};
if(require.main===module)main().catch(()=>{process.stderr.write('启动失败：检查 Node.js 版本、HTTPS 地址及数据目录；原数据未重置。\n');process.exitCode=1;});
