// Retained foreground supervision. The invoking tool must await this process
// through its final receipt; do not background it and return from its shell.
import {spawn} from 'node:child_process';
import {readFile,writeFile,realpath,stat} from 'node:fs/promises';
import {join,resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const [runArg,releaseArg]=process.argv.slice(2);
if(!runArg||!releaseArg)throw new Error('Usage: node tools/browser-gate.js RUN_ROOT RELEASE_ROOT');
const run=await realpath(runArg),release=await realpath(releaseArg),source=resolve(dirname(fileURLToPath(import.meta.url)),'..');
if(!run.startsWith('/var/home/lsm/.local/state/spofify-agent-runs/')||!release.startsWith(run+'/'))throw new Error('Private owned SSD run required');
const stdout=[],stderr=[];let outputBytes=0,ready=false,reason='deadline',closing=false;
const child=spawn(process.execPath,[join(source,'tools/fixture.js'),'--source',release,'--root',join(run,'fixture'),'--listen','http://127.0.0.1:5173'],{stdio:['pipe','pipe','pipe'],env:process.env});
let pending='';
child.stdout.on('data',chunk=>{
  outputBytes+=chunk.length;if(outputBytes>65536){reason='log-budget';void shutdown();return;}
  stdout.push(chunk);pending+=chunk.toString();
  for(let at;(at=pending.indexOf('\n'))>=0;){const line=pending.slice(0,at);pending=pending.slice(at+1);try{const value=JSON.parse(line);if(!ready&&value.origin==='http://127.0.0.1:5173'){ready=true;void writeFile(join(run,'ready.json'),JSON.stringify(value)+'\n',{flag:'wx',mode:0o600});}}catch{/* bounded fixture text */}}
});
child.stderr.on('data',chunk=>{outputBytes+=chunk.length;if(outputBytes<=65536)stderr.push(chunk);else{reason='log-budget';void shutdown();}});
async function shutdown(){if(closing)return;closing=true;child.stdin.end();setTimeout(()=>{if(child.exitCode===null)child.kill('SIGTERM');},2500).unref();}
const poll=setInterval(async()=>{try{await stat(join(run,'stop'));reason='requested';await shutdown();}catch(error){if(error.code!=='ENOENT'){reason=error.code||'state-error';await shutdown();}}},250);
const deadline=setTimeout(()=>void shutdown(),180000);
const terminal=await new Promise(resolveExit=>{child.once('error',error=>resolveExit({error:error.message,code:null}));child.once('exit',(code,signal)=>resolveExit({code,signal}));});
clearInterval(poll);clearTimeout(deadline);
await writeFile(join(run,'fixture.stdout'),Buffer.concat(stdout),{mode:0o600});await writeFile(join(run,'fixture.stderr'),Buffer.concat(stderr),{mode:0o600});
const receipt={...terminal,reason,ready,outputBytes,producerExited:true};await writeFile(join(run,'supervisor.json'),JSON.stringify(receipt)+'\n');
console.log(JSON.stringify(receipt));if(terminal.code!==0)process.exitCode=1;
