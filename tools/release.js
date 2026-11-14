import {readFile,writeFile,mkdir,readdir,copyFile,realpath} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve,dirname,join} from 'node:path';
import {fileURLToPath} from 'node:url';
export const FILES=['index.html','styles.css','app.js','transport.js','online-player.js','local-player.js','gestures.js','audio-owner.js','presentation.js','account-access.js','offline-store.js','progress-journal.js','vendor/audio-adapter.js','vendor/playback-sync.js','manifest.webmanifest','assets/JetBrainsMono.ttf','assets/OFL.txt','assets/license.js','assets/icon.svg','assets/icon-192.png','assets/icon-512.png'];
export async function release(source,out){
  source=await realpath(source);out=await realpath(out);
  if(source===out||out.startsWith(source+'/'))throw new Error('Output must be a separate empty owned directory');
  if((await readdir(out)).length)throw new Error('Release output must be empty');
  const entries=[];for(const path of FILES){const bytes=await readFile(join(source,path));entries.push({path,sha256:createHash('sha256').update(bytes).digest('hex'),bytes:bytes.length});}
  const sw=await readFile(join(source,'sw.js'),'utf8');
  const version=createHash('sha256').update(JSON.stringify(entries)).update(sw).digest('hex');
  for(const {path}of entries){await mkdir(dirname(join(out,path)),{recursive:true});await copyFile(join(source,path),join(out,path));}
  await writeFile(join(out,'sw.js'),sw.replace('spofify-web-shell-v1','spofify-web-shell-'+version));
  const swBytes=await readFile(join(out,'sw.js'));entries.push({path:'sw.js',sha256:createHash('sha256').update(swBytes).digest('hex'),bytes:swBytes.length});
  await writeFile(join(out,'release.json'),JSON.stringify({version,basePath:'/app/',files:entries},null,2)+'\n');
  return {version,files:entries.length,bytes:entries.reduce((sum,file)=>sum+file.bytes,0)};
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const args=process.argv.slice(2);if(args.length!==2)throw new Error('Usage: node tools/release.js SOURCE EXISTING_EMPTY_OUTPUT');
  console.log(JSON.stringify(await release(...args)));
}
