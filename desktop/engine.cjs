'use strict';
const crypto=require('node:crypto');
const {Executor,API,cleanSession,encrypt}=require('./vendor/companion/runner.cjs');
const {RunCore:C}=require('./vendor/Huati/Core/run-core.js');
const check=(ok,message)=>{if(!ok)throw new Error(message);};
class DesktopEngine extends Executor{
  constructor(store,options={}){
    super(store,options);this.loginRequest=options.loginRequest||loginRequest;
    this.state.preferences||={roads:true};this.state.history||=[];
    this.state.catalog||={complete:false,source:'公开参考与历次获取的点位',points:C.referencePoints};
    this.save();
  }
  session(){check(this.state.config,'请先登录或导入会话');return this.state.config;}
  idle(){check(!C.unresolved(this.state.run),'请先完成、舍弃或核实当前记录');}
  snapshot(){
    const out=super.snapshot(),s=this.state;
    return {...out,version:'2.1.1',signed_in:!!s.config,account:s.user?{name:String(s.user.name||s.user.student_name||'已登录账号'),id:String(s.config?.student_id||'')}:null,
      preferences:s.preferences,preview:s.preview||null,route:s.route||null,catalog:s.catalog,history:s.history,results:s.results||null,
      origin:s.origin?.source||'',next_target_ms:s.schedule.enabled?this.nextTarget():0,
      preparation_at_ms:s.schedule.enabled?this.nextTarget()-120000:0};
  }
  nextTarget(){const s=this.state.schedule,now=this.clock(),at=C.target(s,now);return C.dailyEligible(s,now)?at:C.next(s,now);}
  archive(){
    const r=this.state.run;if(!r||!['finished','discarded'].includes(r.status))return;
    const key=String(r.record_id||r.start_ms),list=this.state.history;
    if(list.some(item=>item.key===key&&item.status===r.status))return;
    list.unshift({key,record_id:r.record_id,status:r.status,start_ms:r.start_ms,end_ms:r.end_ms||r.discarded_at,distance:r.distance,duration_ms:r.duration_ms,accepted_invalid:r.accepted_invalid,invalid_reason:r.invalid_reason||''});
    this.state.history=list.slice(0,100);this.save();
  }
  async acceptSession(input){
    const candidate=cleanSession({...input,student_id:input?.student_id||'pending-verification'}),api=this.apiFactory(candidate);
    const user=await api.value('/xcxapi/getUserInfo/');check(user?.id,'未取得账号信息');
    check(!input.student_id||String(input.student_id)===String(user.id),'会话与账号不一致');
    check(!C.unresolved(this.state.run)||this.state.run.student_id===String(user.id),'还有另一账号的未处理记录');
    if(this.state.config?.student_id!==String(user.id)){
      this.idle();this.state.schedule={enabled:false,hour:6,minute:15};this.state.preview=null;this.state.route=null;this.state.run=null;
      this.state.history=[];this.state.results=null;this.state.catalog={complete:false,source:'公开参考与历次获取的点位',points:C.referencePoints};
    }
    this.state.config={...candidate,student_id:String(user.id)};this.state.user=user;
    this.state.message='登录成功，会话已加密保存在这台电脑。';this.save();return this.snapshot();
  }
  async login(input){
    const phone=String(input.phone||'');check(/^1[3-9][0-9]{9}$/.test(phone),'请输入绑定的 11 位手机号');
    const secret=String(input.secret||'');check(input.byCode?/^[0-9]{4,8}$/.test(secret):secret.length>0&&secret.length<=256,'请输入有效验证码或密码');
    const data=await this.loginRequest(input.byCode?'/xcxapi/login/':'/xcxapi/userLogin/',{iphone:phone,[input.byCode?'code':'password']:secret});
    check(typeof data.session_keys==='string'&&data.session_keys,'服务器未返回会话，请使用文件导入');
    return this.acceptSession({host:'run.ecust.edu.cn',headers:{'X-Session-ID':data.session_keys},...(data.id?{student_id:String(data.id)}:{})});
  }
  async sms(phone){
    check(/^1[3-9][0-9]{9}$/.test(phone),'请输入绑定的 11 位手机号');
    check(this.clock()>=(this.state.sms_next_ms||0),'请等待 60 秒后再获取验证码');
    this.state.sms_next_ms=this.clock()+60000;this.save();await this.loginRequest('/xcxapi/sendSMS/',{iphone:phone});
    this.state.message='验证码已发送，请在下方输入。';this.save();return this.snapshot();
  }
  async loadPoints(){
    this.idle();const api=this.apiFactory(this.session());
    const origin=C.origin(null,this.map.boundary,Math.floor(this.random()*4294967296),this.graph);origin.source='电脑校内随机模拟起点';
    const user=await api.value('/xcxapi/getUserInfo/');check(String(user.id)===this.session().student_id,'账号发生变化，请重新登录');
    await api.value('/xcxapi/RunningData/');
    const result=await api.request(`/xcxtapi/activity/randrunInfo?lat=${origin.lat}&lng=${origin.lng}`);
    C.points(result.data);check(String(result.student_id)===this.session().student_id,'三点所属账号不一致');
    const start=C.validOrigin({lat:Number(result.lat),lng:Number(result.lng)},this.map.boundary)?result:origin;
    this.state.preview={points:result.data,lat:Number(start.lat),lng:Number(start.lng),student_id:String(user.id),captured_at:this.clock()};
    C.coordinate(this.state.preview.lat,this.state.preview.lng);this.state.origin=origin;this.state.route=null;
    this.state.catalog=C.catalogMerge(this.state.catalog,result.data,this.map.boundary,this.clock());
    this.state.message='今日随机三点已刷新，可以预览本次路线。';this.save();return this.snapshot();
  }
  preview(input){
    this.idle();const p=this.state.preview,age=this.clock()-(p?.captured_at||0);
    check(p&&age>=0&&age<=1800000,'请先刷新三点，点位预览有效期为 30 分钟');
    C.parameters(input.metres,input.duration_ms,input.steps);check(input.metres>=2100,'总里程不能低于 2.1 公里');
    const roads=!!input.roads;
    this.state.route=roads?C.road(p,input.metres,false,this.graph,this.map.boundary,crypto.randomInt(1,0x100000000)):C.basic(p,input.metres,false);
    this.state.preferences.roads=roads;this.state.message='路线已生成，经过本次三个点位。';this.save();return this.snapshot();
  }
  async startManual(input){
    C.parameters(input.metres,input.duration_ms,input.steps);
    const age=this.clock()-(this.state.preview?.captured_at||0);
    if(!C.validOrigin(this.state.preview,this.map.boundary)||age<0||age>1800000)await this.loadPoints();
    this.preview(input);const p=this.state.preview,route=this.state.route,api=this.apiFactory(this.session()),user=await api.value('/xcxapi/getUserInfo/');
    check(String(user.id)===p.student_id,'账号已变化，请刷新三点');
    const seconds=input.duration_ms/1000,pace=seconds/(route.distance/1000),frequency=input.steps/(seconds/60);
    check(route.distance>=Math.max(2100,Number(user.shortest_distance??2000))&&pace>=Number(user.min_pace??180)&&pace<=Number(user.max_pace??1000),'里程或配速不在服务器允许范围内');
    check(frequency>=Number(user.min_step_frequency??30)&&frequency<=Number(user.max_step_frequency??400),'步频不在服务器允许范围内');
    this.archive();this.state.run={...route,status:'create_pending',student_id:p.student_id,points:p.points,lat:p.lat,lng:p.lng,start_ms:this.clock(),uptime_start:this.monotonic(),process_id:this.process,duration_ms:input.duration_ms,steps:input.steps,pauses:[],auto_submit:true};this.save();
    try{
      const data=await api.value('/xcxapi/createLine/',{student_id:user.id,pass_point:p.points});check(Number(data.record_id)>0,'创建响应缺少编号');
      this.state.run.record_id=Number(data.record_id);this.state.run.status='created';this.state.message='模拟已开始，计满时长后自动校验并提交。';
    }catch(error){this.state.run.status='create_unknown';this.state.run.auto_submit=false;this.state.run.error='创建结果待核实，请到官方小程序查询；不会重复创建。';}
    this.state.preview=null;this.state.route=null;this.save();return this.snapshot();
  }
  plan(input){
    const result=this.command({id:crypto.randomUUID(),action:'plan',hour:input.hour,minute:input.minute,roads:!!input.roads,session:this.session()});
    this.state.preferences.roads=!!input.roads;this.save();return result;
  }
  action(action){const value=this.command({id:crypto.randomUUID(),action});this.archive();return value;}
  async tick(){
    await super.tick();const s=this.state.schedule;
    if(s.prepared_origin)s.prepared_origin.source='电脑校内随机模拟起点';
    if(s.prepared_preview){this.state.catalog=C.catalogMerge(this.state.catalog,s.prepared_preview.points,this.map.boundary,this.clock());this.save();}
    this.archive();
  }
  async finish(){
    const r=this.state.run,end=this.clock();check(r?.status==='created'&&C.remaining(r,end)===0,'当前记录尚未计满时长');
    const seconds=Math.floor((end-r.start_ms)/1000);check(seconds>0&&seconds<=86400,'记录时长异常，请到官方小程序核实');
    if(r.process_id===this.process)check(Math.abs(this.monotonic()-r.uptime_start-(end-r.start_ms))<=60000,'电脑时间发生变化');
    const track=C.path(r,end),api=this.apiFactory(this.session());
    const payload={record_id:r.record_id,pace:seconds/60/(r.distance/1000),running_time:seconds,mileage:r.distance,start_time:C.date(r.start_ms),end_time:C.date(end),pass_point:3,step_count:r.steps},body={a:encrypt(payload)};
    r.status='checking';r.auto_submit=false;this.save();
    try{
      const user=await api.value('/xcxapi/getUserInfo/');check(String(user.id)===r.student_id,'账号已变化');
      const verdict=await api.value('/xcxapi/checkRecord/',body);check([0,1].includes(Number(verdict.status)),'校验状态未知');
      r.accepted_invalid=Number(verdict.status)===0;r.invalid_reason=String(verdict.warning||verdict.message||'');
    }catch(error){
      // These reads are safe to retry. Never repeat a write after an ambiguous response.
      r.status='created';r.auto_submit=true;r.check_retry_ms=this.clock()+30000;
      r.error='校验暂未成功，30 秒后继续尝试；会话失效时请重新登录。';this.save();return;
    }
    r.status='finish_pending';r.finish_payload=payload;this.save();
    try{
      await api.value('/xcxapi/updateRecordNew/',body);r.status='record_updated';this.save();
      for(let i=0;i<track.length;i+=50){await api.value('/xcxapi/uploadPathPointV3/',{img_url:encrypt({record_id:r.record_id,path_point:track.slice(i,i+50),path_image:''})});r.uploaded_points=Math.min(i+50,track.length);this.save();}
      r.status='finished';r.end_ms=end;delete r.error;this.state.message=r.accepted_invalid?'已提交，服务器判为无效。':'已提交，请以官方小程序成绩为准。';this.save();this.archive();
    }catch(error){r.status='finish_unknown';r.auto_submit=false;r.error='提交或轨迹上传结果待核实，请查看官方小程序；不会重复提交。';this.save();}
  }
  async refreshRecords(){
    const api=this.apiFactory(this.session()),statistics=await api.value('/xcxapi/recordListcont/');
    const results={statistics,checked_at:this.clock()},id=this.state.run?.record_id;
    if(id)results.record=await api.value('/xcxapi/recordInfo/?id='+Number(id));
    this.state.results=results;this.state.message='已查询服务器记录。';this.save();return this.snapshot();
  }
  importCatalog(input){this.state.catalog=C.catalogImport(input,this.map.boundary,this.clock());this.save();return this.snapshot();}
  logout(){this.idle();this.state.config=null;this.state.user=null;this.state.schedule.enabled=false;this.state.preview=null;this.state.route=null;this.state.run=null;this.state.history=[];this.state.results=null;this.state.catalog={complete:false,points:C.referencePoints};this.state.message='已退出登录并清除本机账号数据。';this.save();return this.snapshot();}
}
async function loginRequest(endpoint,body){
  check(['/xcxapi/sendSMS/','/xcxapi/login/','/xcxapi/userLogin/'].includes(endpoint),'登录接口无效');
  const response=await fetch('https://run.ecust.edu.cn'+endpoint,{method:'POST',redirect:'error',signal:AbortSignal.timeout(40000),headers:{'content-type':'application/json','X-Session-ID':'1111'},body:JSON.stringify(body)});
  check(response.ok,'网络请求未成功');let size=0,parts=[];
  for await(const chunk of response.body){size+=chunk.length;check(size<=2097152,'响应过大');parts.push(chunk);}
  const value=JSON.parse(Buffer.concat(parts).toString('utf8'));check(value.code===1&&value.data!==undefined,'登录未成功，请检查手机号与验证码或密码');return value.data;
}
module.exports={DesktopEngine,loginRequest};
