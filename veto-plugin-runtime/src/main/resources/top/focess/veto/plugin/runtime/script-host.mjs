import vm from 'node:vm';
import {Console} from 'node:console';
import {readFile} from 'node:fs/promises';
import {createInterface} from 'node:readline';
import {PassThrough, Writable} from 'node:stream';
import {dirname} from 'node:path';
const plugins = new Map();
const limit = 65536;
async function load(key, path) {
  if (plugins.has(key)) return plugins.get(key);
  const input = new PassThrough();
  const pending = new Map();
  let output = '', sequence = 0;
  const stdout = new Writable({write(chunk, encoding, done) {
    output += chunk.toString();
    if (Buffer.byteLength(output) > limit) { done(new Error('Frame limit')); return; }
    for (;;) {
      const end = output.indexOf('\n'); if (end < 0) break;
      const line = output.slice(0, end); output = output.slice(end + 1);
      try {
        const message = JSON.parse(line); const waiter = pending.get(message.id);
        if (!waiter || message.jsonrpc !== '2.0') throw new Error('Invalid response');
        pending.delete(message.id);
        if (message.error || !Object.hasOwn(message, 'result')) waiter.reject(new Error('Plugin failure'));
        else waiter.resolve(message.result);
      } catch (error) { for (const waiter of pending.values()) waiter.reject(error); pending.clear(); }
    }
    done();
  }});
  const timers = new Set();
  const schedule = (fn, ms, ...args) => { const timer = setTimeout(() => { timers.delete(timer); fn(...args); }, ms); timers.add(timer); return timer; };
  const interval = (fn, ms, ...args) => { const timer = setInterval(fn, ms, ...args); timers.add(timer); return timer; };
  const dispose = () => { input.end(); stdout.destroy(); for (const timer of timers) {clearTimeout(timer); clearInterval(timer);} for (const waiter of pending.values()) waiter.reject(new Error('Closed')); pending.clear(); };
  const context = vm.createContext({Buffer, URL, URLSearchParams, TextEncoder, TextDecoder,
    setTimeout:schedule, setInterval:interval, clearTimeout, clearInterval, queueMicrotask,
    console:new Console(process.stderr, process.stderr),
    process:{stdin:input, stdout, stderr:process.stderr, env:{}, argv:[process.execPath,path],
      platform:process.platform, arch:process.arch, versions:process.versions,
      cwd:()=>dirname(path), nextTick:process.nextTick.bind(process),
      exit:()=>{dispose(); throw new Error('Plugin exited');}}});
  const imports = new Map();
  const module = new vm.SourceTextModule(await readFile(path,'utf8'), {context,identifier:path});
  await module.link(async name => {
    if (!name.startsWith('node:')) throw new Error('Only bundled code and Node builtins are supported');
    if (imports.has(name)) return imports.get(name);
    const value = await import(name); const names = Object.keys(value);
    const linked = new vm.SyntheticModule(names, function(){for(const n of names)this.setExport(n,value[n]);},{context});
    imports.set(name,linked); return linked;
  });
  let failed;
  module.evaluate().catch(error=>{failed=error;dispose();});
  const request = (method,params) => new Promise((resolve,reject)=>{
    if (failed) {reject(failed); return;}
    const id=++sequence; pending.set(id,{resolve,reject});
    input.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');
  });
  try {const hello=await request('initialize',{protocolVersion:1}); if(hello.protocolVersion!==1)throw new Error('Protocol mismatch');}
  catch(error){dispose();throw error;}
  const plugin={request,dispose}; plugins.set(key,plugin); return plugin;
}
for await (const line of createInterface({input:process.stdin})) {
  let request;
  try {
    if(Buffer.byteLength(line)>limit)throw new Error('Frame limit');
    request=JSON.parse(line); const p=request.params; let result;
    if(request.method==='invoke') {const plugin=await load(p.plugin,p.entryPoint); result=await plugin.request('invoke',{handler:p.handler,arguments:p.arguments});}
    else if(request.method==='unload') {plugins.get(p.plugin)?.dispose();plugins.delete(p.plugin);result=true;}
    else throw new Error('Unsupported request');
    const response=JSON.stringify({jsonrpc:'2.0',id:request.id,result});
    if(Buffer.byteLength(response)>limit)throw new Error('Frame limit');
    process.stdout.write(response+'\n');
  } catch(error) {process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:request?.id,error:{code:-32603,message:'Plugin call failed'}})+'\n');}
}
for(const plugin of plugins.values())plugin.dispose();
