// Plugin-owned task Inspector. No feature state or styles are imported from the host.
const words = {
 en: {title:'Background tasks',loading:'Loading background tasks…',empty:'No background tasks for this session.',unavailable:'Background task status is temporarily unavailable.',offline:'Disconnected — showing the last known task state.',stale:'Task status is awaiting an update.',failed:'The action could not be completed. Refreshing task status.',retry:'Refresh',more:'Load more',output:'Output',live:'Live output buffer; refreshing restarts reading from the current buffer.',noOutput:'No output yet.',command:'Full command',cwd:'Full directory',running:'Running',exit:'Exited',stop:'Stop',remove:'Remove',stopping:'Stopping…',removing:'Removing…'},
 zh: {title:'后台任务',loading:'正在加载后台任务…',empty:'此会话暂无后台任务。',unavailable:'后台任务状态暂时不可用。',offline:'连接已断开 — 当前显示上次已知任务状态。',stale:'正在等待任务状态更新。',failed:'操作未能完成，正在刷新任务状态。',retry:'刷新',more:'加载更多',output:'输出',live:'实时输出缓冲区；刷新后从当前缓冲区重新读取。',noOutput:'暂无输出。',command:'完整命令',cwd:'完整目录',running:'运行中',exit:'已退出',stop:'停止',remove:'移除',stopping:'正在停止…',removing:'正在移除…'}
};
const css = `.veto-tasks{padding:12px;min-height:100%;font-size:12px;color:rgb(var(--paper));overflow-wrap:anywhere}.veto-tasks ul{list-style:none;padding:0;margin:0;display:grid;gap:8px}.veto-tasks li{border:1px solid rgb(var(--rule));border-radius:6px;padding:8px;display:grid;gap:5px;min-width:0}.veto-tasks .row{display:flex;flex-wrap:wrap;align-items:center;gap:8px}.veto-tasks .dim{color:rgb(var(--dim))}.veto-tasks .mono{font-family:"IBM Plex Mono",monospace;font-size:11px}.veto-tasks .uptime{margin-left:auto}.veto-tasks .badge{border:1px solid rgb(var(--rule));border-radius:4px;padding:2px 6px}.veto-tasks .alive{color:rgb(var(--pass));border-color:rgb(var(--pass)/.4)}.veto-tasks pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:160px;overflow:auto;padding:6px 8px;margin:0;border:1px solid rgb(var(--rule));border-radius:6px;background:rgb(var(--codebg));font-size:11px;line-height:1.6}.veto-tasks summary{cursor:pointer;padding:4px 0}.veto-tasks button{border:1px solid rgb(var(--rule));border-radius:6px;padding:3px 9px;background:transparent;color:inherit;font:inherit;cursor:pointer}.veto-tasks button:disabled{opacity:.5;cursor:default}.veto-tasks button:focus-visible{outline:2px solid rgb(var(--paper));outline-offset:2px}.veto-tasks .stop,.veto-tasks [role=alert]{color:rgb(var(--verdict))}.veto-tasks .notice{margin:0 0 8px;line-height:1.5}.veto-tasks .actions{justify-content:space-between}`;
function uptime(value) {const seconds=Math.max(0,Math.floor(Number(value)||0));const h=Math.floor(seconds/3600),m=Math.floor(seconds%3600/60),s=seconds%60;return h?`${h}h ${m}m`:m?`${m}m ${s}s`:`${s}s`;}
export function activate(host) {
 const {createElement:h,useState,useRef,useEffect}=host.React;
 function TextPage({context,task,field}) {
  const t=context.locale.startsWith('zh')?words.zh:words.en;
  const [page,setPage]=useState(null),[error,setError]=useState(false),[loading,setLoading]=useState(false);
  const active=useRef(null),disposed=useRef(false);
  const read=async(offset=0)=>{
   if(!context.connected)return;active.current?.abort();const controller=new AbortController();active.current=controller;setLoading(true);setError(false);
   try{const data=await context.invoke(field?'detail':'output',{taskId:task.taskId,taskInstanceId:task.taskInstanceId,...(field?{field}:{}),offset,limit:8192},controller.signal);
    if(disposed.current||controller.signal.aborted)return;if(typeof data?.text!=='string')throw new Error('Invalid text page');
    setPage(previous=>({...data,text:offset?(previous?.text??'')+data.text:data.text}));
   }catch(failure){if(!disposed.current&&!controller.signal.aborted&&failure?.name!=='AbortError')setError(true);}
   finally{if(!disposed.current&&!controller.signal.aborted)setLoading(false);}
  };
  useEffect(()=>{disposed.current=false;if(!context.connected)setLoading(false);else void read();return()=>{disposed.current=true;active.current?.abort();};},[context.connected]);
  return h('div',null,!field&&h('p',{className:'dim'},t.live),error&&h('p',{role:'alert'},t.unavailable),loading&&h('p',{role:'status'},t.loading),page&&h('pre',null,page.text||t.noOutput),h('div',{className:'row'},h('button',{type:'button',disabled:loading||!context.connected,onClick:()=>void read()},t.retry),page&&typeof page.nextOffset==='number'&&page.nextOffset<page.total&&h('button',{type:'button',disabled:loading||!context.connected,onClick:()=>void read(page.nextOffset)},t.more)));
 }
 function Disclosure({context,task,field,revision}) {
  const [open,setOpen]=useState(false),t=context.locale.startsWith('zh')?words.zh:words.en;
  return h('details',{onToggle:event=>setOpen(event.currentTarget.open)},h('summary',null,field?t[field]:t.output),open&&h(TextPage,{key:revision,context,task,field}));
 }
 function Tasks({context,onCount}) {
  const t=context.locale.startsWith('zh')?words.zh:words.en;
  const [items,setItems]=useState(null),[stale,setStale]=useState(true),[error,setError]=useState(false),[actionError,setActionError]=useState(false),[busy,setBusy]=useState(null),[total,setTotal]=useState(null),[next,setNext]=useState(null),[revision,setRevision]=useState(0);
  const refresh=useRef(()=>{}),action=useRef(null),lock=useRef(false);
  useEffect(()=>{onCount?.(!context.connected||stale||error?null:total);},[onCount,context.connected,stale,error,total]);
  useEffect(()=>{
   let disposed=false,request=null,version=0;
   const load=async(offset=0)=>{
    if(disposed||!context.connected)return;
    const current=++version;request?.abort();request=new AbortController();setStale(true);
    try{const result=await context.invoke('list',{offset,limit:20},request.signal);if(disposed||current!==version)return;
     if(!Array.isArray(result?.items)||result.items.some(item=>typeof item.taskId!=='string'||typeof item.taskInstanceId!=='string'))throw new Error('Invalid task list');
     setItems(previous=>offset?[...(previous??[]),...result.items]:result.items);setTotal(result.total);setNext(typeof result.nextOffset==='number'&&result.nextOffset<result.total?result.nextOffset:null);if(!offset)setRevision(value=>value+1);setError(false);setStale(false);
    }catch(failure){if(!disposed&&current===version&&failure?.name!=='AbortError')setError(true);}
   };
   refresh.current=load;
   const unsubscribe=context.subscribe('tasks',load);
   if(context.connected)void load();else setStale(true);

   return()=>{disposed=true;++version;request?.abort();action.current?.abort();lock.current=false;setBusy(null);unsubscribe();};
  },[context.session,context.agent,context.connected,context.invoke,context.subscribe]);
  const control=async task=>{
   if(lock.current||!context.connected||stale||error)return;
   lock.current=true;setBusy(task.taskInstanceId);setActionError(false);
   const controller=new AbortController();action.current=controller;
   try{await context.invoke('stopOrRemove',{taskId:task.taskId,taskInstanceId:task.taskInstanceId},controller.signal);}
   catch(failure){if(!controller.signal.aborted&&failure?.name!=='AbortError')setActionError(true);}
   finally{if(!controller.signal.aborted){lock.current=false;setBusy(null);void refresh.current();}}
  };
  return h('div',{className:'veto-tasks'},h('style',null,css),
   !context.connected&&h('p',{className:'notice dim',role:'status'},t.offline),
   context.connected&&stale&&items!==null&&!error&&h('p',{className:'notice dim',role:'status'},t.stale),
   error&&h('p',{className:'notice',role:'alert'},t.unavailable,' ',h('button',{type:'button',disabled:!context.connected,onClick:()=>void refresh.current()},t.retry)),
   actionError&&h('p',{className:'notice',role:'alert'},t.failed),
   items===null&&!error&&context.connected&&h('p',{role:'status'},t.loading),
   items?.length===0&&!stale&&!error&&h('p',{className:'dim'},t.empty),
   items!==null&&h('button',{type:'button',disabled:stale||!context.connected,onClick:()=>void refresh.current()},t.retry),
   items&&h('ul',null,items.map(task=>h('li',{key:task.taskInstanceId},
    h('div',{className:'row'},h('span',{className:`badge mono ${task.alive?'alive':'dim'}`},task.alive?t.running:`${t.exit} ${task.exitCode??'?'}`),h('span',{className:'mono dim'},task.taskId),h('span',{className:'mono dim uptime'},uptime(task.uptimeSeconds))),
    h('div',{className:'mono'},task.command),h('div',{className:'mono dim'},task.cwd),
    task.recentOutput&&h('pre',{className:'mono'},task.recentOutput),
    h(Disclosure,{context,task,revision}),
    task.commandTruncated&&h(Disclosure,{context,task,revision,field:'command'}),
    task.cwdTruncated&&h(Disclosure,{context,task,revision,field:'cwd'}),
    h('div',{className:'row actions'},h('span',{className:'mono dim'},`pid ${task.pid}`),h('button',{type:'button',className:task.alive?'stop':'dim',disabled:!!busy||!context.connected||stale||error,onClick:()=>void control(task)},busy===task.taskInstanceId?(task.alive?t.stopping:t.removing):(task.alive?t.stop:t.remove)))
   ))),
   next!==null&&h('button',{type:'button',disabled:stale||!context.connected,onClick:()=>void refresh.current(next)},t.more)
  );
 }
 host.registerInspector('tasks',{en:words.en.title,'zh-CN':words.zh.title},props=>h(Tasks,{...props,key:JSON.stringify([props.context.session,props.context.agent])}));
}
