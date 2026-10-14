'use strict';
const $=id=>document.getElementById(id),C=RunCore,artRoot='../vendor/Huati/Content/Art/';
let state=null,mapData=null,busy=false,toastTimer,tab='run',scheduleEdited=false,previousInputs=false,bootArt;
const titles={run:'模拟跑步',auto:'定时计划',records:'跑步记录',map:'校园地图',account:'账号与设置'};
const states={created:'模拟进行中',paused:'已暂停',create_pending:'正在创建',create_unknown:'创建待核实',checking:'正在校验',finish_pending:'正在提交',record_updated:'上传轨迹中',finish_unknown:'提交待核实',finished:'已完成',discarded:'已舍弃'};
const pending=r=>!!r?.status&&C.unresolved(r);
const format=ms=>ms?C.date(ms):'—';
const minuteText=ms=>{const value=Math.max(0,Math.ceil(ms));return `${String(Math.floor(value/60000)).padStart(2,'0')}:${String(Math.floor(value/1000)%60).padStart(2,'0')}.${String(value%1000).padStart(3,'0')}`;};
function toast(message){$('toast').textContent=message;$('toast').classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').classList.remove('visible'),5000);}
function showTab(name){tab=name;document.querySelectorAll('.page').forEach(el=>el.classList.toggle('active',el.id==='page-'+name));document.querySelectorAll('[data-tab]').forEach(el=>el.classList.toggle('selected',el.dataset.tab===name));$('page-title').textContent=titles[name];requestAnimationFrame(()=>maps.forEach(map=>map.draw()));}
document.querySelectorAll('[data-tab]').forEach(el=>el.addEventListener('click',()=>showTab(el.dataset.tab)));
$('account-jump').onclick=()=>showTab('account');$('map-jump').onclick=()=>showTab('map');
async function invoke(action,input={}){return window.huati.invoke(action,input);}
async function perform(action,input={},message){
  if(busy)return;busy=true;updateButtons();
  try{const value=await invoke(action,input);if(value?.server_time_ms)render(value);if(message)toast(message);return value;}
  catch(error){toast(error.message);}finally{busy=false;updateButtons();}
}
function bind(id,action,input=()=>({}),message){$(id).onclick=()=>{try{return perform(action,input(),message);}catch(error){toast(error.message);}};}
function confirm(title,text,label='确认'){
  return new Promise(resolve=>{
    const dialog=$('confirm-dialog');$('confirm-title').textContent=title;$('confirm-text').textContent=text;$('confirm-ok').textContent=label;
    const done=value=>{dialog.close();$('confirm-ok').onclick=null;$('confirm-cancel').onclick=null;dialog.oncancel=null;resolve(value);};
    $('confirm-ok').onclick=()=>done(true);$('confirm-cancel').onclick=()=>done(false);dialog.oncancel=event=>{event.preventDefault();done(false);};dialog.showModal();
  });
}
function parameters(){return {metres:Math.round(Number($('metres').value)*1000),duration_ms:C.manualDuration($('duration').value,$('duration-seconds').value,$('duration-milliseconds').value),steps:Number($('steps').value),roads:$('roads').checked};}
bind('points','points');bind('preview','preview',parameters);
$('start').onclick=async()=>{try{const input=parameters();C.parameters(input.metres,input.duration_ms,input.steps);if(await confirm('创建本次模拟记录',`目标 ${$('metres').value} km · ${minuteText(input.duration_ms)}\n这会在服务器创建一条记录。计满时长后自动校验并提交，即使判为无效也会提交。`,'创建并开始')){const value=await perform('start',input);if(value)showTab('run');}}catch(error){toast(error.message);}};
bind('pause','pause');bind('resume','resume');
$('discard').onclick=async()=>{if(await confirm('舍弃本次记录','舍弃后停止本地计时与提交。已经在服务器创建的占位记录仍然保留。','舍弃本次'))await perform('discard');};
$('schedule-time').addEventListener('input',()=>{scheduleEdited=true;});
$('plan').onclick=async()=>{
  const [hour,minute]=$('schedule-time').value.split(':').map(Number);
  if(!C.validTime(hour,minute)){toast('时间需在北京时间 06:00—21:00，最晚 21:00');return;}
  const value=await perform('plan',{hour,minute,roads:$('auto-roads').checked},'已保存计划。请保留程序在托盘运行。');if(value){scheduleEdited=false;render(value);}
};
bind('disable','disable');bind('probe','probe',()=>({}),'已安排自检，一分钟后查看结果。');bind('hide','hide');bind('quit','quit');
bind('refresh-records','refresh-records');bind('catalog-import','catalog-import');
$('autostart').onchange=async()=>{const value=await perform('autostart',{enabled:$('autostart').checked});if(!value)$('autostart').checked=!!state?.desktop?.autostart;};
bind('sms','sms',()=>({phone:$('phone').value.trim()}));
$('login').onclick=async()=>{const value=await perform('login',{phone:$('phone').value.trim(),secret:$('secret').value,byCode:$('login-mode').value==='code'});if(value){$('secret').value='';toast('登录成功');}};
$('login-mode').onchange=()=>{$('sms').hidden=$('login-mode').value!=='code';$('secret').placeholder=$('login-mode').value==='code'?'请输入短信验证码':'请输入账号密码';};
bind('import-session','import-session');bind('export-session','export-session');
$('logout').onclick=async()=>{if(await confirm('退出当前账号','将关闭自动计划并清除桌面版保存的账号与本地记录。','退出账号'))await perform('logout');};
function text(id,value){$(id).textContent=value;}
let lastHistory='',lastResults='';
function render(value){
  state=value;const r=value.run||{},active=!!r.status,preview=pending(r)?r:value.route||value.preview;
  text('bj-clock',C.date(Date.now()).slice(11)+' · 北京时间');text('signed-label',value.signed_in?value.account?.name||'已登录':'尚未登录');
  text('notice-text',value.message||'欢迎回来。先登录账号，再准备你今天的路线。');
  text('side-status',value.enabled?'自动运行已开启':pending(r)?states[r.status]||'任务处理中':'后台待命');
  if(!scheduleEdited)$('schedule-time').value=`${String(value.hour).padStart(2,'0')}:${String(value.minute).padStart(2,'0')}`;
  if(!previousInputs){$('roads').checked=value.preferences?.roads!==false;$('auto-roads').checked=value.preferences?.roads!==false;previousInputs=true;}
  $('autostart').checked=!!value.desktop?.autostart;
  text('awake-state',value.desktop?.awake?'已阻止自动睡眠':'后台待命');
  text('probe-result',value.desktop?.probe_result||'自检只检查后台计时，不会创建跑步记录。');
  text('next-time',value.enabled?`${String(value.hour).padStart(2,'0')}:${String(value.minute).padStart(2,'0')}`:'尚未开启');
  text('next-date',value.next_target_ms?format(value.next_target_ms)+' · 北京时间':'保存计划后显示下一次准备与开始时间。');
  text('prep-time',value.preparation_at_ms?format(value.preparation_at_ms)+' 开始准备':'校内起点 · 刷新三点');
  text('prep-message',value.preparation||'起点与随机三点将在提前两分钟时自动准备。');
  text('run-status',states[r.status]||'尚未开始');text('remaining',active?minuteText(r.remaining_ms||0):'00:00');const hint=document.createElement('small');hint.textContent=r.status==='paused'?'计时已暂停':'剩余时间';$('remaining').append(hint);
  $('run-progress').style.width=active?`${Math.min(100,Math.max(0,100-(r.remaining_ms||0)/(r.duration_ms||1)*100))}%`:'0%';
  text('run-detail',active?`${r.record_id?'编号 '+r.record_id+' · ':''}${(r.distance/1000).toFixed(2)} km · ${minuteText(r.duration_ms)} · ${r.start_ms?format(r.start_ms):''}`:'你的下一段路线正在等待开始。');
  text('run-error',r.error||(r.accepted_invalid?'服务器判为无效：'+(r.invalid_reason||'请到小程序查看原因'):'')||'');
  $('route-distance').innerHTML='';const distance=document.createTextNode(preview?.distance?(preview.distance/1000).toFixed(2)+' ':'— '),unit=document.createElement('em');unit.textContent='km';$('route-distance').append(distance,unit);
  text('route-points',`${(r.points||value.preview?.points||[]).length} / 3`);
  text('origin-text',value.origin||'校内随机选择');
  text('account-detail',value.signed_in?`${value.account?.name||'已登录'} · 账号 ${value.account?.id||''}\n会话已加密保存在当前 Windows 用户下。`:'使用绑定的手机号登录，或导入手机导出的会话文件。');
  const running=r.status==='created';if(bootArt)$('run-mascot').src=mascot(running?'running':'normal');
  const historyJSON=JSON.stringify(value.history||[]);
  if(lastHistory!==historyJSON){lastHistory=historyJSON;renderHistory(value.history||[]);}
  const resultsJSON=JSON.stringify(value.results);
  if(lastResults!==resultsJSON){lastResults=resultsJSON;renderResults(value.results);}
  text('catalog-text',`已知 ${value.catalog?.points?.length||0} 个点位 · ${value.catalog?.complete?'导入表标记为完整':'持续收集，并非服务器全部点位'}。地图点位仅作示意。`);
  updateButtons();maps.forEach(map=>map.draw());
}
function updateButtons(){
  if(!state)return;const r=state.run||{},unresolved=pending(r),signed=state.signed_in;
  for(const id of ['points','preview','start'])$(id).disabled=busy||!signed||unresolved;
  $('preview').disabled||=!state.preview;
  $('pause').disabled=busy||r.status!=='created';$('resume').disabled=busy||r.status!=='paused';
  $('discard').disabled=busy||!['created','paused','check_failed','create_unknown','finish_unknown'].includes(r.status);
  for(const id of ['plan','refresh-records','export-session'])$(id).disabled=busy||!signed;
  $('logout').disabled=busy||!signed||unresolved;$('disable').disabled=busy||!state.enabled;
  for(const id of ['login','sms','import-session','catalog-import','probe'])$(id).disabled=busy;
}
function renderHistory(list){
  const body=$('history');body.replaceChildren();$('history-empty').hidden=!!list.length;
  for(const item of list){const tr=document.createElement('tr');const values=[format(item.start_ms),item.record_id||'—',(item.distance/1000).toFixed(2)+' km',minuteText(item.duration_ms),item.status==='discarded'?'已舍弃':item.accepted_invalid?'已提交 · 无效':'已提交'];
    values.forEach(value=>{const td=document.createElement('td');td.textContent=String(value);tr.append(td);});body.append(tr);}
}
function renderResults(results){
  const box=$('server-results');box.replaceChildren();if(!results)return;
  const heading=document.createElement('b');heading.textContent='服务器成绩 · '+format(results.checked_at);box.append(heading);
  const aliases={valid_count:'有效次数',count:'总次数',total_count:'总次数',total_mileage:'总里程',mileage:'里程',valid_mileage:'有效里程',running_count:'跑步次数',valid:'有效次数',invalid:'无效次数',status:'状态'};
  const stats=results.statistics,dl=document.createElement('dl');
  if(stats&&typeof stats==='object')for(const [key,label]of Object.entries(aliases))if(stats[key]!==undefined&&typeof stats[key]!=='object'){const group=document.createElement('div'),dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=String(stats[key]);group.append(dt,dd);dl.append(group);}box.append(dl);
  const details=document.createElement('details'),summary=document.createElement('summary'),pre=document.createElement('pre');summary.textContent='查看服务器返回的成绩详情';pre.textContent=JSON.stringify(results,null,2);details.append(summary,pre);box.append(details);
}
function mascot(mode='normal'){return artRoot+`mascots/${String(bootArt.mascot).padStart(2,'0')}_${String(bootArt.variant).padStart(2,'0')}_${mode}.png`;}
class CampusMap{
  constructor(id){this.canvas=$(id);this.ctx=this.canvas.getContext('2d');this.zoom=1;this.dx=0;this.dy=0;this.drag=null;
    this.canvas.addEventListener('wheel',event=>{event.preventDefault();const rect=this.canvas.getBoundingClientRect();this.scale(Math.exp(-event.deltaY*.001),event.clientX-rect.left,event.clientY-rect.top);},{passive:false});
    this.canvas.addEventListener('pointerdown',event=>{this.drag={x:event.clientX,y:event.clientY};this.canvas.setPointerCapture(event.pointerId);});
    this.canvas.addEventListener('pointermove',event=>{if(!this.drag)return;this.dx+=event.clientX-this.drag.x;this.dy+=event.clientY-this.drag.y;this.drag={x:event.clientX,y:event.clientY};this.draw();});
    this.canvas.addEventListener('pointerup',()=>this.drag=null);this.canvas.addEventListener('pointercancel',()=>this.drag=null);
    new ResizeObserver(()=>this.draw()).observe(this.canvas);
  }
  scale(multiplier,x=this.width/2,y=this.height/2){const next=Math.min(8,Math.max(.75,this.zoom*multiplier)),ratio=next/this.zoom;this.dx=(this.dx-x+this.width/2)*ratio+x-this.width/2;this.dy=(this.dy-y+this.height/2)*ratio+y-this.height/2;this.zoom=next;this.draw();}
  reset(){this.zoom=1;this.dx=0;this.dy=0;this.draw();}
  draw(){
    if(!mapData||!this.canvas.clientWidth||!this.canvas.clientHeight)return;
    const width=this.width=this.canvas.clientWidth,height=this.height=this.canvas.clientHeight,dpr=devicePixelRatio||1;
    this.canvas.width=Math.round(width*dpr);this.canvas.height=Math.round(height*dpr);const g=this.ctx;g.setTransform(dpr,0,0,dpr,0,0);g.fillStyle='#f1f7fb';g.fillRect(0,0,width,height);
    const boundary=mapData.boundary,ys=boundary.map(p=>p[0]),xs=boundary.map(p=>p[1]),cx=(Math.max(...xs)+Math.min(...xs))/2,cy=(Math.max(...ys)+Math.min(...ys))/2,cos=Math.cos(cy*Math.PI/180);
    const scale=Math.min((width-34)/((Math.max(...xs)-Math.min(...xs))*cos),(height-45)/(Math.max(...ys)-Math.min(...ys)))*this.zoom;
    const project=p=>[width/2+(p[1]-cx)*cos*scale+this.dx,height/2-(p[0]-cy)*scale+this.dy];
    const path=points=>{g.beginPath();points.forEach((p,i)=>{const [x,y]=project(p);i?g.lineTo(x,y):g.moveTo(x,y);});};
    path(boundary);g.closePath();g.fillStyle='#f7fbfd';g.fill();g.strokeStyle='#c2dbe9';g.lineWidth=1.3;g.stroke();
    for(const area of mapData.areas){g.beginPath();for(const ring of area.rings){ring.forEach((p,i)=>{const[x,y]=project(p);i?g.lineTo(x,y):g.moveTo(x,y);});g.closePath();}g.fillStyle=area.kind==='water'?'#d5eaf5':area.kind==='green'?'#e0ede7':area.kind==='building'?'#dce7ee':'#e7f0ec';g.fill('evenodd');g.strokeStyle=area.kind==='building'?'#d1dfe8':'#dbe9e9';g.lineWidth=.5;g.stroke();}
    for(const road of mapData.roads){path(road.points);g.strokeStyle='#d8e4ec';g.lineWidth=Math.max(1,road.width*1.4*this.zoom);g.lineJoin='round';g.lineCap='round';g.stroke();path(road.points);g.strokeStyle='#fff';g.lineWidth=Math.max(.7,road.width*.85*this.zoom);g.stroke();}
    g.font=`${Math.min(13,8.5+this.zoom)}px 'Microsoft YaHei UI'`;g.textAlign='center';
    for(const label of mapData.labels){if(label.priority<50&&this.zoom<2.2)continue;const [x,y]=project(label.point);if(x<15||x>width-15||y<12||y>height-12)continue;g.strokeStyle='#f7fbfd';g.lineWidth=3;g.strokeText(label.name,x,y);g.fillStyle='#8ba4b5';g.fillText(label.name,x,y);}
    const r=state?.run||{},route=pending(r)?r:state?.route||r;
    if(route?.route_samples?.length){path(route.route_samples.map(p=>{const w=C.wgs(p);return[w.lat,w.lng];}));g.strokeStyle='#ffffffd0';g.lineWidth=6;g.stroke();g.strokeStyle='#5a97bf';g.lineWidth=3;g.stroke();}
    const marker=(p,name,color,size)=>{const w=C.wgs(p),[x,y]=project([w.lat,w.lng]);g.beginPath();g.arc(x,y,size,0,Math.PI*2);g.fillStyle=color;g.fill();g.strokeStyle='#fff';g.lineWidth=2;g.stroke();if(this.zoom>1.2&&name){g.font="10px 'Microsoft YaHei UI'";g.strokeStyle='#fff';g.lineWidth=3;g.strokeText(name,x,y-12);g.fillStyle='#7393a9';g.fillText(name,x,y-12);}};
    if(this.canvas.id==='campus-map'&&$('all-points').checked)for(const p of state?.catalog?.points||[])marker(p,p.point_name,'#9ab7cc',4);
    const points=pending(r)?r.points:state?.preview?.points||r.points||[];
    for(const [index,p]of(points||[]).entries())marker(p,String(index+1)+' '+(p.point_name||''),'#d99d8b',5.5);
    g.fillStyle='#9eb3c1';g.font="9px 'Microsoft YaHei UI'";g.textAlign='right';g.fillText('N ↑',width-16,22);
  }
}
const maps=[new CampusMap('route-map'),new CampusMap('campus-map')];
$('zoom-in').onclick=()=>maps[1].scale(1.3);$('zoom-out').onclick=()=>maps[1].scale(1/1.3);$('map-reset').onclick=()=>maps[1].reset();$('all-points').onchange=()=>maps[1].draw();
window.huati.onStatus(value=>render(value));
async function bootstrap(){
  try{
    const result=await invoke('bootstrap');mapData=result.map;bootArt=result.art;
    const tips=['跑前热身，让每一步都更轻松。','循序渐进，找到适合自己的节奏。','运动前后记得补水，照顾好身体。','跑完缓步走一走，给心率一点恢复时间。','穿上舒适的跑鞋，享受校园的风。','不必追赶别人，稳定的节奏属于你。'];text('tip',tips[Math.floor(Math.random()*tips.length)]);
    for(const el of document.querySelectorAll('.auto-mascot,.account-mascot,.empty-mascot'))el.src=mascot();
    render(result.status);setTimeout(()=>$('splash').classList.add('hidden'),3000);
  }catch(error){$('splash').classList.add('hidden');toast('启动读取失败：'+error.message);}
}
window.desktopUITest=async()=>{
  const result={pages:[],errors:[],images:[],navigation:true,map_zoom:true,splash_illustration_removed:!document.getElementById('splash-art')};
  $('duration').value='15';$('duration-seconds').value='12';$('duration-milliseconds').value='345';
  result.manual_duration_ms=parameters().duration_ms;result.manual_duration_label=minuteText(result.manual_duration_ms);
  $('duration-seconds').value='60';try{parameters();result.invalid_duration_rejected=false;}catch{result.invalid_duration_rejected=true;}$('duration-seconds').value='12';
  const original=state;render({...original,signed_in:true,preview:null,run:null});result.missing_origin_start_enabled=!$('start').disabled;render(original);
  for(const name of Object.keys(titles)){showTab(name);await new Promise(resolve=>requestAnimationFrame(resolve));const el=$('page-'+name);result.pages.push({name,visible:el.getBoundingClientRect().height>0,title:$('page-title').textContent});if(!el.getBoundingClientRect().height)result.navigation=false;}
  showTab('map');const prior=maps[1].zoom;maps[1].scale(1.3);result.map_zoom=maps[1].zoom>prior;maps[1].reset();
  result.images=Array.from(document.images).filter(el=>el.src).map(el=>({name:el.src.split('/').pop(),loaded:el.complete&&el.naturalWidth>0}));
  result.signed_in=state?.signed_in;result.scheduled=state?.enabled;result.ipc_isolated=typeof window.require==='undefined'&&typeof window.process==='undefined';
  const confirmed=confirm('创建本次模拟记录','这是界面自检，不会创建服务器记录。','确认');$('confirm-cancel').click();result.confirm_canceled=await confirmed===false;
  showTab('run');return result;
};
bootstrap();
