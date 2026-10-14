'use strict';
const {contextBridge,ipcRenderer}=require('electron');
const actions=new Set(['status','bootstrap','sms','login','import-session','export-session','points','preview','start','plan','disable','pause','resume','discard','refresh-records','catalog-import','logout','autostart','probe','hide','quit']);
contextBridge.exposeInMainWorld('huati',{
  invoke:async(action,input)=>{
    if(!actions.has(action))throw new Error('操作无效');
    const result=await ipcRenderer.invoke('huati:invoke',action,input);
    if(!result.ok)throw new Error(result.error);return result.value;
  },
  onStatus:callback=>{const listener=(_event,value)=>callback(value);ipcRenderer.on('huati:status',listener);return()=>ipcRenderer.removeListener('huati:status',listener);}
});
