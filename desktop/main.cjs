'use strict';
const {app,BrowserWindow,ipcMain,dialog,Tray,Menu,nativeImage,powerSaveBlocker,powerMonitor}=require('electron');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {Store}=require('./vendor/companion/runner.cjs');
const {DesktopEngine}=require('./engine.cjs');
const {RunCore:C}=require('./vendor/Huati/Core/run-core.js');
const smoke=process.argv.includes('--smoke-test');
const smokeFolder=path.resolve(process.argv.find(arg=>arg.startsWith('--smoke-dir='))?.slice(12)||path.join(app.getPath('temp'),'HuatiDesktop-Smoke'));
if(smoke)app.setPath('userData',path.join(smokeFolder,'data'));
let window,tray,engine,quitting=false,timer,blocker,probing=0,lastMessage='',smokePageLoaded=false;
const root=path.join(__dirname,'vendor/Huati/Content');
const icon=path.join(root,'Art/icons/launcher_head.png');
function awake(){
  const needed=engine.state.schedule.enabled||(engine.state.run?.status==='created'&&engine.state.run.auto_submit)||probing>Date.now();
  if(needed&&blocker===undefined)blocker=powerSaveBlocker.start('prevent-app-suspension');
  if(!needed&&blocker!==undefined){powerSaveBlocker.stop(blocker);blocker=undefined;}
}
function status(){return {...engine.snapshot(),desktop:{autostart:app.getLoginItemSettings().openAtLogin,awake:blocker!==undefined,probe_until_ms:probing,probe_result:engine.state.probe_result||'',locked_background:true}};}
function notify(){if(window&&!window.isDestroyed())window.webContents.send('huati:status',status());}
async function loop(){
  if(quitting)return;
  try{await engine.exclusive(()=>engine.tick());}catch(error){
    if(engine.state.run?.status==='created')engine.state.run=C.pause(engine.state.run,Date.now());
    engine.state.message='计时或状态异常，当前任务已停止，请查看记录。';engine.save();
  }
  if(probing&&Date.now()>=probing){probing=0;engine.state.probe_result='后台计时自检通过：主进程已在一分钟后执行。';engine.save();}
  awake();notify();
  if(tray){tray.setToolTip('华理跑步姬 · '+(engine.state.schedule.enabled?'自动运行已开启':'自动运行未开启'));}
  if(engine.state.message!==lastMessage){lastMessage=engine.state.message;if(tray&&window&&!window.isVisible()&&engine.state.run?.status==='finished')tray.displayBalloon({title:'华理跑步姬',content:lastMessage,icon});}
  const wake=C.wake(engine.state.schedule,engine.state.run,Date.now());
  if(!quitting)timer=setTimeout(loop,Math.min(1000,Math.max(1,wake?Math.ceil(wake-Date.now()):1000)));
}
function show(){if(!window||window.isDestroyed())createWindow();window.show();window.restore();window.focus();}
function createWindow(){
  window=new BrowserWindow({width:1240,height:850,minWidth:920,minHeight:660,title:'华理跑步姬',backgroundColor:'#edf6fd',icon,autoHideMenuBar:true,
    webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true,backgroundThrottling:false}});
  window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  window.webContents.on('will-navigate',(event,url)=>{if(url!==window.webContents.getURL())event.preventDefault();});
  window.webContents.session.setPermissionRequestHandler((_webContents,_permission,callback)=>callback(false));
  window.on('close',event=>{if(!quitting){event.preventDefault();window.hide();}});
  window.loadFile(path.join(__dirname,'ui/index.html'));
  if(smoke){window.webContents.once('did-finish-load',()=>{smokePageLoaded=true;});}
}
async function readJSONFile(title){
  const result=await dialog.showOpenDialog(window,{title,properties:['openFile'],filters:[{name:'JSON 文件',extensions:['json']}]});
  if(result.canceled)return null;const file=result.filePaths[0];
  if(fs.statSync(file).size>262144)throw new Error('文件不能超过 256 KB');return JSON.parse(fs.readFileSync(file,'utf8'));
}
async function dispatch(action,input={}){
  if(smoke&&['sms','login','import-session','start','plan','points','refresh-records','logout','export-session'].includes(action))throw new Error('界面自检不连接真实账号');
  switch(action){
    case 'status':return status();
    case 'bootstrap':return {status:status(),map:JSON.parse(fs.readFileSync(path.join(root,'campus-map.json'),'utf8')),art:{mascot:crypto.randomInt(1,7),variant:crypto.randomInt(1,4)}};
    case 'sms':return engine.sms(String(input.phone||''));
    case 'login':return engine.login(input);
    case 'import-session':{const value=await readJSONFile('导入登录会话');return value?engine.acceptSession(value):status();}
    case 'export-session':{
      engine.session();const result=await dialog.showSaveDialog(window,{title:'导出会话（仅供自己使用）',defaultPath:'huati-desktop-session.json',filters:[{name:'JSON 文件',extensions:['json']}]});
      if(!result.canceled)fs.writeFileSync(result.filePath,JSON.stringify({format:'huati-mobile-session',version:1,...engine.session()},null,2),'utf8');return status();
    }
    case 'points':return engine.loadPoints();
    case 'preview':return engine.preview(input);
    case 'start':return engine.startManual(input);
    case 'plan':return engine.plan(input);
    case 'disable':case 'pause':case 'resume':case 'discard':return engine.action(action);
    case 'refresh-records':return engine.refreshRecords();
    case 'catalog-import':{const value=await readJSONFile('导入校园点位表');return value?engine.importCatalog(value):status();}
    case 'logout':return engine.logout();
    case 'autostart':{
      const executable=process.env.PORTABLE_EXECUTABLE_FILE||process.execPath;
      if(!app.isPackaged)throw new Error('请在打包后的电脑版中设置开机启动');
      app.setLoginItemSettings({openAtLogin:!!input.enabled,path:executable,args:['--tray']});return status();
    }
    case 'probe':probing=Date.now()+60000;engine.state.probe_result='已安排一分钟后台计时自检，可以最小化或锁屏。';engine.save();awake();return status();
    case 'hide':window.hide();return status();
    case 'quit':{
      if(!window.isVisible())show();
      const result=await dialog.showMessageBox(window,{type:'question',title:'退出华理跑步姬',message:'退出会停止本机定时任务。',detail:'窗口右上角关闭按钮可以保留托盘后台运行。',buttons:['留在后台','完全退出'],defaultId:0,cancelId:0});
      if(result.response===1){quitting=true;app.quit();}return status();
    }
    default:throw new Error('未知操作');
  }
}
if(!app.requestSingleInstanceLock()){app.quit();}else{
  app.on('second-instance',()=>{if(engine)show();});
  app.whenReady().then(async()=>{
    const store=new Store(path.join(app.getPath('userData'),'vault'),true);engine=new DesktopEngine(store);
    tray=new Tray(nativeImage.createFromPath(icon).resize({width:32,height:32}));
    tray.setContextMenu(Menu.buildFromTemplate([{label:'打开华理跑步姬',click:show},{label:'退出（停止定时任务）',click:()=>dispatch('quit').catch(()=>{})}]));tray.on('double-click',show);
    ipcMain.handle('huati:invoke',async(event,action,input)=>{
      if(!window||event.sender!==window.webContents||event.senderFrame!==window.webContents.mainFrame)throw new Error('页面无效');
      if(typeof action!=='string'||input!==undefined&&(!input||typeof input!=='object'||Array.isArray(input))||JSON.stringify(input||{}).length>262144)throw new Error('请求无效');
      try{
        const value=await engine.exclusive(()=>dispatch(action,input));awake();notify();return {ok:true,value};
      }catch(error){return {ok:false,error:String(error.message||'操作未完成')};}
    });
    createWindow();if(process.argv.includes('--tray'))window.hide();
    powerMonitor.on('resume',()=>{engine.state.message='电脑已恢复，正在检查未完成任务与定时计划。';engine.save();notify();});
    await loop();
    if(smoke){
      setTimeout(async()=>{
        try{
          const output=smokeFolder;fs.mkdirSync(output,{recursive:true});
          const result=await window.webContents.executeJavaScript('window.desktopUITest()');
          await new Promise(resolve=>setTimeout(resolve,400));
          fs.writeFileSync(path.join(output,'ui-result.json'),JSON.stringify({page_loaded:smokePageLoaded,version:app.getVersion(),...result},null,2));
          fs.writeFileSync(path.join(output,'desktop.png'),(await window.webContents.capturePage()).toPNG());
        }catch(error){fs.mkdirSync(smokeFolder,{recursive:true});fs.writeFileSync(path.join(smokeFolder,'ui-error.txt'),error.message);}finally{quitting=true;app.quit();}
      },5500);
    }
  }).catch(async error=>{await dialog.showMessageBox({type:'error',title:'启动失败',message:'无法读取桌面版数据。原文件未重置。',detail:String(error.message)});quitting=true;app.quit();});
}
app.on('before-quit',()=>{quitting=true;clearTimeout(timer);if(blocker!==undefined)powerSaveBlocker.stop(blocker);});
app.on('window-all-closed',()=>{});
