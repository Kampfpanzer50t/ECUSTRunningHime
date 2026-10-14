'use strict';
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..'),vendor=path.join(__dirname,'vendor');
// Explicit allowlist: no capture files, phone exports or account state enter the package.
const copy=(source,target)=>{fs.mkdirSync(path.dirname(target),{recursive:true});fs.cpSync(source,target,{recursive:true});};
copy(path.join(root,'ios/companion/runner.cjs'),path.join(vendor,'companion/runner.cjs'));
copy(path.join(root,'ios/Huati/Core/run-core.js'),path.join(vendor,'Huati/Core/run-core.js'));
for(const name of ['campus-map.json','campus-roads.json','Art'])copy(path.join(root,'ios/Huati/Content',name),path.join(vendor,'Huati/Content',name));
copy(path.join(root,'ios/Huati/Core/run-core.js'),path.join(__dirname,'ui/run-core.js'));
console.log('桌面资源已准备，仅包含代码、美术资源和公开校园地图。');
