import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Divider, LinearProgress, Link, List, ListItemButton, ListItemText, MenuItem, Paper, Stack, Tab, Tabs, TextField, Typography, useMediaQuery } from '@mui/material';
import { action, active, ApiError, fields, formats, getSnapshot, status, type Profile, type Settings, type Snapshot } from './api';

import AssetsPage from './AssetsPage';
import DatasetPage from './DatasetPage';
import Tooltip from './Hint';

const metricLabels:Record<string,string>={completed_step:'训练步数',epoch:'轮数',loss:'训练 Loss',eval_loss:'验证 Loss'};
function statusColor(value:string):'success'|'error'|'warning'|'primary'|'default' {
 if(value==='completed')return 'success';
 if(value==='failed')return 'error';
 if(value==='interrupted'||value==='paused'||value==='pausing'||value==='pause_pending')return 'warning';
 if(active.has(value))return 'primary';
 return 'default';
}

function Parameters({settings}:{settings:Settings}) {
 return <Box component="dl" sx={{m:0,display:'grid',gridTemplateColumns:{xs:'1fr',sm:'repeat(2,minmax(0,1fr))'},gap:2}}>
  {fields.map(({key,label},index)=><Box key={key} sx={{minWidth:0,gridColumn:index<4?'1 / -1':undefined}}>
   <Typography component="dt" variant="caption" color="text.secondary">{label}</Typography>
   <Typography component="dd" variant="body2" sx={{m:0,overflowWrap:'anywhere'}}>{key==='prompt.format'?formats[settings[key] as keyof typeof formats] || settings[key]:key==='early.stopping.patience'?(settings[key]==='0'?'关闭（固定轮数）':`${settings[key]||'—'} 轮`):settings[key] || '—'}</Typography>
  </Box>)}
 </Box>;
}
function FormSection({title,children,expanded,onToggle,preview}:{title:string;children:ReactNode;expanded?:boolean;onToggle?:()=>void;preview?:string}) {
 return <Paper variant="outlined" sx={{overflow:'hidden'}}><Stack direction="row" sx={{px:1.25,py:0.75,alignItems:'center',gap:1,bgcolor:'action.hover'}}><Typography variant="subtitle2" sx={{fontWeight:650}}>{title}</Typography>{preview&&!expanded&&<Typography variant="body2" color="text.secondary" noWrap sx={{flex:1,minWidth:0}}>{preview}</Typography>}{onToggle&&<Button onClick={onToggle} sx={{ml:'auto',flexShrink:0}}>{expanded?'收起':'展开'}</Button>}</Stack>{expanded!==false&&<Box sx={{p:1.5,borderTop:1,borderColor:'divider'}}>{children}</Box>}</Paper>;
}
function managedDataset(settings:Settings,datasets:Snapshot['datasets']) {
 const path=(settings['training.data'] || '').replaceAll('\\','/');
 return datasets?.find(dataset=>!dataset.archived && path.includes(`/training-datasets/${dataset.id}/exports/`))?.id || '';
}
function defaultDataset(settings:Settings,datasets:Snapshot['datasets']) {
 return datasets?.find(dataset=>!dataset.archived && dataset.format===settings['prompt.format'])?.id || '';
}
function CreateTask({defaults,snapshot,onSaved,onClose,profile}:{profile?:Profile;defaults:Settings;snapshot:Snapshot;onSaved:()=>Promise<void>;onClose:()=>void}) {
 const [name,setName]=useState(profile?.name || ''); const [values,setValues]=useState<Settings>(()=>({...defaults,...(profile?{}:{'output.directory':''})}));
 const [template,setTemplate]=useState(snapshot.templates[0]?.id || '');
 const [errors,setErrors]=useState<Record<string,string>>({}); const [error,setError]=useState(''); const [pending,setPending]=useState(false);
 const [dataset,setDataset]=useState(()=>profile?managedDataset(profile.settings,snapshot.datasets):defaultDataset(defaults,snapshot.datasets));
 const [advanced,setAdvanced]=useState(false); const [dirty,setDirty]=useState(false); const [discard,setDiscard]=useState(false);
 const form=useRef<HTMLFormElement>(null);
 useEffect(()=>{const warn=(e:BeforeUnloadEvent)=>{if(dirty){e.preventDefault();e.returnValue='';}};window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn);},[dirty]);
 const close=()=>{if(!pending){if(dirty)setDiscard(true);else onClose();}};
 const focus=(key:string)=>requestAnimationFrame(()=>{form.current?.querySelector<HTMLInputElement>(`[name="${key}"]`)?.focus();});
 async function save(event:FormEvent) {
  event.preventDefault(); if(pending)return;
  const issues:Record<string,string>={};
  if(!name.trim())issues.name='请输入任务名称';
  if(!dataset)issues.dataset='请选择数据集';
  for(const f of fields){if(['training.data','validation.data'].includes(f.key))continue;const value=values[f.key];if(!value?.trim())issues[f.key]='请填写此项';else if(f.min!==undefined && (!Number.isFinite(Number(value)) || Number(value)<f.min || Number(value)>f.max! || (!f.step && !Number.isInteger(Number(value)))))issues[f.key]='请输入允许范围内的数值';}
  setErrors(issues);const first=Object.keys(issues)[0];if(first){if(fields.find(f=>f.key===first)?.min!==undefined)setAdvanced(true);focus(first);return;}
  setPending(true);setError('');
  try {const selected=snapshot.datasets?.find(d=>d.id===dataset);const settings=selected?{...values,'prompt.format':selected.format}:values;await action(snapshot,profile?`profiles/${profile.id}/update`:'profiles/create',{name,settings,template,dataset,datasetRevision:selected?.revision});setDirty(false);await onSaved();onClose();}
  catch(e){if(e instanceof ApiError && e.field){setErrors({[e.field]:e.message});setAdvanced(true);focus(e.field);}setError(e instanceof Error?e.message:'保存失败');}
  finally {setPending(false);}
 }
 const dynamic=Number(values['early.stopping.patience']||0)>0;
 const effectiveBatch=Number(values['batch.size'])*Number(values['accumulation.steps']);
 const input=(f:typeof fields[number])=>{const label=f.key==='epochs'&&dynamic?'最大训练轮数（Epoch）':f.label;return <Box key={f.key}><Typography variant="caption" color="text.secondary" sx={{display:'block',mb:0.5,fontWeight:600}}>{label}</Typography><TextField name={f.key} fullWidth size="small" type="number" value={values[f.key] || ''} disabled={pending} error={!!errors[f.key]} helperText={errors[f.key]} slotProps={{htmlInput:{min:f.min,max:f.max,step:f.step || 1,maxLength:1024,'aria-label':label}}} onChange={e=>{setDirty(true);setValues({...values,[f.key]:e.target.value});setErrors({...errors,[f.key]:''});}}/></Box>;};
 const models=snapshot.assets.filter(asset=>asset.kind==='model'&&asset.trainable);
 const modelPath=values['base.model'] || '';
 const registered=models.some(asset=>asset.path.replaceAll('\\','/')===modelPath.replaceAll('\\','/'));
 return <><Dialog open onClose={close} fullWidth maxWidth="md" aria-labelledby="create-title">
  <Box component="form" ref={form} onSubmit={save} noValidate>
   <DialogTitle id="create-title">{profile?'编辑任务':'创建任务'}</DialogTitle>
   <DialogContent><Stack spacing={1.5} sx={{pt:1}}>
    {error && <Alert severity="error">{error}</Alert>}
    <FormSection title="任务信息"><Stack spacing={1.25}>
    {!profile && <TextField select fullWidth size="small" value={template} disabled={pending} onChange={e=>{const next=snapshot.templates.find(t=>t.id===e.target.value)!;setTemplate(next.id);setDataset(defaultDataset(next.settings,snapshot.datasets));setValues({...next.settings,'output.directory':''});if(!name.trim())setName(next.name);setDirty(true);setErrors({});}} slotProps={{htmlInput:{'aria-label':'训练模板'}}}>{snapshot.templates.map(t=><MenuItem key={t.id} value={t.id}>{t.name}</MenuItem>)}</TextField>}
      <TextField autoFocus name="name" placeholder="任务名称" fullWidth size="small" value={name} disabled={pending} error={!!errors.name} helperText={errors.name} slotProps={{htmlInput:{maxLength:80,'aria-label':'任务名称'}}} onChange={e=>{setName(e.target.value);setDirty(true);setErrors({...errors,name:''});}}/>
    <TextField select name="base.model" fullWidth size="small" value={modelPath} disabled={pending} error={!!errors['base.model']} helperText={errors['base.model']} onChange={e=>{setDirty(true);setValues(current=>({...current,'base.model':e.target.value}));setErrors(current=>({...current,'base.model':''}));}} slotProps={{htmlInput:{'aria-label':'训练模型'}}}>
     {modelPath&&!registered&&<MenuItem value={modelPath}>当前模型（未登记）</MenuItem>}
     {models.map(asset=><MenuItem key={asset.id} value={asset.path}>{asset.name}</MenuItem>)}
     {!models.length&&!modelPath&&<MenuItem value="" disabled>暂无已登记模型</MenuItem>}
    </TextField>
    <TextField select name="dataset" fullWidth size="small" value={dataset} disabled={pending} error={!!errors.dataset} helperText={errors.dataset} onChange={e=>{setDataset(e.target.value);setDirty(true);setErrors({});const next=snapshot.datasets?.find(d=>d.id===e.target.value);if(next)setValues(current=>({...current,'prompt.format':next.format}));}} slotProps={{htmlInput:{'aria-label':'训练数据集'}}}>
     {!dataset&&<MenuItem value="" disabled>请选择数据集</MenuItem>}
     {snapshot.datasets?.filter(d=>!d.archived).map(d=><MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}
    </TextField>
    <TextField name="output.directory" placeholder="输出目录" fullWidth size="small" value={values['output.directory'] || ''} disabled={pending} error={!!errors['output.directory']} helperText={errors['output.directory']} slotProps={{htmlInput:{maxLength:1024,'aria-label':'输出目录'}}} onChange={e=>{setDirty(true);setValues(current=>({...current,'output.directory':e.target.value}));setErrors(current=>({...current,'output.directory':''}));}}/>
    </Stack></FormSection>
    <FormSection title="训练参数" expanded={advanced} onToggle={()=>setAdvanced(value=>!value)} preview={`${values['epochs']||'—'} 轮 · 有效 batch ${effectiveBatch||'—'} · ${dynamic?'动态提前停止':'固定轮数'}`}><Stack spacing={2}><TextField select fullWidth value={dynamic?'dynamic':'fixed'} disabled={pending} onChange={e=>{const next=e.target.value;setDirty(true);setValues(current=>({...current,'early.stopping.patience':next==='dynamic'?(Number(current['early.stopping.patience'])>0?current['early.stopping.patience']:'3'):'0'}));setErrors(current=>({...current,'early.stopping.patience':''}));}} slotProps={{htmlInput:{'aria-label':'训练策略'}}}><MenuItem value="fixed">固定轮数</MenuItem><MenuItem value="dynamic">动态提前停止</MenuItem></TextField>{Number.isFinite(effectiveBatch)&&effectiveBatch>0&&<Typography variant="body2" color="text.secondary">有效 batch：{effectiveBatch} 条样本（{values['batch.size']} × {values['accumulation.steps']}）</Typography>}<Box sx={{display:'grid',gridTemplateColumns:{xs:'1fr',sm:'1fr 1fr'},gap:2}}>{fields.filter(f=>f.min!==undefined&&(dynamic||f.key!=='early.stopping.patience')).map(input)}</Box></Stack></FormSection>
   </Stack></DialogContent>
   <DialogActions><Button onClick={close} disabled={pending}>取消</Button><Button type="submit" variant="contained" disabled={pending}>{pending?'保存中…':'保存任务'}</Button></DialogActions>
  </Box>
 </Dialog><Dialog open={discard} onClose={()=>setDiscard(false)} aria-labelledby="discard-title"><DialogTitle id="discard-title">放弃未保存的修改？</DialogTitle><DialogActions><Button onClick={()=>setDiscard(false)}>继续编辑</Button><Button color="error" onClick={onClose}>放弃修改</Button></DialogActions></Dialog></>;
}
type Confirmation={path:string;title:string;name:string;revision:number;settings?:Settings;values?:Record<string,unknown>;remove?:boolean;stop?:boolean;export?:boolean};
export default function App({themeMode='dark',onToggleTheme=()=>{}}:{themeMode?:'light'|'dark';onToggleTheme?:()=>void}) {
 const [snapshot,setSnapshot]=useState<Snapshot>(); const latest=useRef<Snapshot>();
 const [online,setOnline]=useState(false);const [error,setError]=useState('');const [loading,setLoading]=useState(false);
 const [pending,setPending]=useState(false);const mutation=useRef(false);const inFlight=useRef<AbortController>();
 const [creating,setCreating]=useState(false);const [editing,setEditing]=useState<Profile>();
 const [confirmation,setConfirmation]=useState<Confirmation>();const [dialogError,setDialogError]=useState('');
 const [selected,setSelected]=useState<string>();
 const [archived,setArchived]=useState(false);const [logs,setLogs]=useState<string>();const [logsPending,setLogsPending]=useState(false);
 const readPage=()=>{const value=new URLSearchParams(window.location.search).get('page');return value==='models' || value==='datasets'?value:'tasks';};
 const [page,setPage]=useState(readPage);
 useEffect(()=>{const pop=()=>setPage(readPage());window.addEventListener('popstate',pop);return()=>window.removeEventListener('popstate',pop);},[]);
 function navigate(value:string){setPage(value);const url=new URL(window.location.href);if(value==='tasks')url.searchParams.delete('page');else url.searchParams.set('page',value);window.history.pushState(null,'',url);}

 const refresh=useCallback(async(force=false)=>{
  if(inFlight.current){if(!force)return;inFlight.current.abort();}
  const controller=new AbortController();inFlight.current=controller;setLoading(true);
  const timeout=setTimeout(()=>controller.abort(),10000);
  try {const next=await getSnapshot(controller.signal);if(inFlight.current!==controller)return;latest.current=next;setSnapshot(current=>current&&JSON.stringify(current)===JSON.stringify(next)?current:next);setOnline(true);}
  catch{if(inFlight.current===controller)setOnline(false);}
  finally{clearTimeout(timeout);if(inFlight.current===controller){inFlight.current=undefined;setLoading(false);}}
 },[]);
 useEffect(()=>{void refresh();const timer=setInterval(()=>{if(!document.hidden)void refresh();},5000);const visible=()=>{if(!document.hidden)void refresh();};document.addEventListener('visibilitychange',visible);return()=>{clearInterval(timer);document.removeEventListener('visibilitychange',visible);inFlight.current?.abort();inFlight.current=undefined;};},[refresh]);
 const visibleRuns=snapshot?.runs.filter(item=>!!item.archived===archived)||[];
 const run=visibleRuns.find(item=>item.id===selected) || visibleRuns[0];
 const disabled=!online || pending || !!snapshot?.stopping;
 async function execute(path:string,values:Record<string,unknown>={},revision?:number) {
  if(!latest.current || mutation.current || disabled)return;
  mutation.current=true;setPending(true);setError('');setDialogError('');
  try{await action({...latest.current,revision:revision ?? latest.current.revision},path,values);setConfirmation(undefined);await refresh(true);}
  catch(e){const message=e instanceof Error?e.message:'操作失败';if(confirmation)setDialogError(message);else setError(message);await refresh(true);}
  finally{mutation.current=false;setPending(false);}
 }
 const confirm=(value:Omit<Confirmation,'revision'>)=>{setDialogError('');setConfirmation({...value,revision:snapshot!.revision});};
 const profiles=snapshot?.profiles.filter(p=>p.enabled) || [];
 const task=(profile:Profile)=><Box component="section" key={profile.id} sx={{px:2,py:1.5,transition:'background-color 150ms ease','&:hover':{backgroundColor:'action.hover'},'&:not(:last-child)':{borderBottom:1,borderColor:'divider'}}}>
  <Stack direction="row" sx={{justifyContent:"space-between",alignItems:"center",gap:2,flexWrap:"wrap"}}>
   <Box><Typography variant="subtitle2" sx={{fontWeight:600}}>{profile.name}</Typography><Typography variant="caption" color="text.secondary">训练集 {profile.counts?.['training.data']?.toLocaleString('zh-CN') ?? '—'} 条 · 验证集 {profile.counts?.['validation.data']?.toLocaleString('zh-CN') ?? '—'} 条</Typography></Box>
   <Stack direction="row" sx={{gap:1,flexWrap:"wrap",'& .MuiButton-root':{whiteSpace:"nowrap"}}}>
    <Tooltip title="修改未来运行的配置"><Button disabled={disabled || !!snapshot?.busy} onClick={()=>{setEditing(profile);setCreating(true);}}>编辑</Button></Tooltip><Tooltip title="只删除任务配置，保留历史运行"><Button color="error" disabled={disabled || snapshot!.runs.some(r=>r.profile===profile.id && active.has(r.status))} onClick={()=>confirm({path:`profiles/${profile.id}/remove`,title:'删除任务？',name:profile.name,remove:true})}>删除</Button></Tooltip>
    <Tooltip title="按当前配置开始训练"><Button variant="outlined" disabled={disabled || snapshot!.busy} onClick={()=>confirm({path:`profiles/${profile.id}/start`,title:'开始训练？',name:profile.name,settings:profile.settings})}>开始训练</Button></Tooltip>
   </Stack>
  </Stack>
  <Accordion disableGutters elevation={0} sx={{'&:before':{display:'none'}}}><AccordionSummary expandIcon={<span aria-hidden>⌄</span>}>数据与参数</AccordionSummary><AccordionDetails sx={{p:0,pt:1}}><Parameters settings={profile.settings}/></AccordionDetails></Accordion>
 </Box>;
 const desktop=useMediaQuery('(min-width:600px)');
 const completedSteps=run?.progress.completed_step??run?.progress.step??0;
 const progressPercent=run?.progress.max_steps?Math.min(100,100*completedSteps/run.progress.max_steps):0;
 const exportPercent=run?.progress.export_total?Math.min(100,100*(run.progress.export_step||0)/run.progress.export_total):undefined;
 const preparePercent=run?.progress.prepare_total?Math.min(100,100*(run.progress.prepare_step||0)/run.progress.prepare_total):0;
 const overallPercent=run?.status==='completed'?100:run?.status==='exporting'?90+(exportPercent??0)*0.1:run?.status==='starting'?preparePercent*0.1:10+progressPercent*0.8;
 return <Container maxWidth="lg" sx={{py:{xs:2,sm:3},px:{xs:2,sm:3}}}>
 <Box sx={{display:'grid',gridTemplateColumns:{xs:'1fr',sm:'156px minmax(0,1fr)'},gap:{xs:2,sm:4}}}>
  <Box component="nav" sx={{pr:{sm:3},borderRight:{sm:1},borderColor:{sm:'divider'}}}>
   <Typography component="h1" variant="h6" sx={{fontWeight:600,mb:2}}>训练管理</Typography>
   <Tabs orientation={desktop?'vertical':'horizontal'} value={page} onChange={(_,value)=>navigate(value)} aria-label="训练管理导航" sx={{width:'100%',alignItems:'stretch','.MuiTabs-flexContainer':{gap:.5}}}><Tab value="tasks" label="任务"/><Tab value="models" label="模型"/><Tab value="datasets" label="数据集"/></Tabs>
   <Stack direction="row" spacing={1} sx={{mt:2,alignItems:'center'}}><Link underline="hover" href={snapshot?.backendUrl || 'http://127.0.0.1:5177/'} target="_blank" rel="noopener" sx={{ml:1}}>打开 Veto</Link><Tooltip title={`切换为${themeMode==='dark'?'浅色':'深色'}主题`}><Button size="small" aria-label={`切换为${themeMode==='dark'?'浅色':'深色'}主题`} onClick={onToggleTheme}>{themeMode==='dark'?'浅色':'深色'}</Button></Tooltip></Stack>
  </Box>
  <Box sx={{minWidth:0}}>
  {loading && !snapshot && <LinearProgress aria-label="加载训练任务"/>}
  {!online && !loading && <Alert severity="error" sx={{mb:2}} action={<Button color="inherit" onClick={()=>void refresh()}>重试</Button>}>无法连接训练服务</Alert>}
  {error && <Alert severity="error" sx={{mb:2}} onClose={()=>setError('')}>{error}</Alert>}
  {page==='models' && snapshot && <AssetsPage kind="model" snapshot={snapshot} disabled={disabled} onRefresh={()=>refresh(true)}/>}
  {page==='datasets' && snapshot && <DatasetPage snapshot={snapshot} disabled={disabled} onRefresh={()=>refresh(true)}/>}
  {page==='tasks' && <>
  <Stack direction="row" sx={{alignItems:"center",justifyContent:"space-between",mb:2}}><Typography component="h2" variant="h6">任务</Typography><Stack direction="row" spacing={1}><Button disabled={loading} onClick={()=>void refresh()}>刷新</Button><Tooltip title="创建新的训练任务"><Button variant="contained" disabled={disabled || !snapshot} onClick={()=>{setEditing(undefined);setCreating(true);}}>创建任务</Button></Tooltip></Stack></Stack>
  {profiles.length?<Paper variant="outlined">{profiles.map(task)}</Paper>:snapshot&&<Paper variant="outlined" sx={{p:3}}><Typography color="text.secondary">暂无任务</Typography></Paper>}
  <Stack direction="row" sx={{justifyContent:'space-between',alignItems:'center',mt:4,mb:2}}><Typography component="h2" variant="h6">{archived?'已归档运行':'运行记录'}</Typography><Tooltip title={archived?'返回当前运行':'查看已归档运行'}><Button onClick={()=>{setArchived(!archived);setSelected(undefined);}}>{archived?'返回运行记录':'查看归档'}</Button></Tooltip></Stack>
  {snapshot && !visibleRuns.length && <Typography color="text.secondary">暂无运行记录</Typography>}
  {run && <Paper variant="outlined" sx={{display:'grid',gridTemplateColumns:{xs:'1fr',sm:'220px minmax(0,1fr)'}}}>
   <List aria-label="运行记录" sx={{maxHeight:360,overflow:'auto'}}>{visibleRuns.map(item=><ListItemButton key={item.id} selected={item.id===run.id} onClick={()=>setSelected(item.id)}><ListItemText primary={item.name || item.profile} secondary={`${status[item.status] || item.status} · ${new Date(item.updated).toLocaleString('zh-CN')}`} /></ListItemButton>)}</List>
   <Box sx={{p:2,minWidth:0,borderLeft:{sm:1},borderColor:{sm:'divider'}}}>
    <Stack direction="row" sx={{alignItems:"center",justifyContent:"space-between",gap:2}}><Chip label={status[run.status] || run.status} size="small" color={statusColor(run.status)} variant="outlined"/>{['starting','running','pause_pending','pausing'].includes(run.status) && <Tooltip title="在下一保存点暂停并保存"><Button disabled={disabled || ['pause_pending','pausing'].includes(run.status)} onClick={()=>void execute(`runs/${run.id}/pause`)}>{['pause_pending','pausing'].includes(run.status)?'等待保存…':'暂停并保存'}</Button></Tooltip>}</Stack>
    <Typography variant="caption" color="text.secondary" sx={{display:'block',mt:1,overflowWrap:'anywhere'}}>{run.name || run.profile}</Typography>
    {!!run.artifacts?.length && <Box sx={{mt:2}}><Typography variant="subtitle2">模型产物</Typography>{run.artifacts.map(path=><Typography key={path} variant="body2" sx={{overflowWrap:'anywhere',mt:1}}>{path.replaceAll('\\','/').split('/').pop()}</Typography>)}<Tooltip title="在模型管理中查看产物"><Button onClick={()=>navigate('models')}>查看模型</Button></Tooltip></Box>}
    {active.has(run.status)&&<Tooltip title="立即停止，不生成新保存点"><Button color="error" disabled={disabled} onClick={()=>confirm({path:`runs/${run.id}/stop`,title:run.status==='exporting'?'立即停止导出？':'立即停止训练？',name:run.name || run.profile,stop:true})}>立即停止</Button></Tooltip>}
    {(run.status==='starting'||run.status==='running')&&!!run.progress.prepare_total&&!run.progress.max_steps&&<Box sx={{mt:2}}><Stack direction="row" sx={{justifyContent:'space-between',alignItems:'center',mb:0.75,gap:1,flexWrap:'wrap'}}><Typography variant="body2" color="text.secondary">数据准备</Typography><Typography variant="body2" color="text.secondary">{run.progress.prepare_step||0} / {run.progress.prepare_total} 条 · 整体 {Math.floor(overallPercent)}%</Typography></Stack><LinearProgress variant="determinate" value={overallPercent} aria-label="数据准备进度"/></Box>}
    {active.has(run.status)&&run.status!=='exporting'&&!!run.progress.max_steps&&<Box sx={{mt:2}}><Stack direction="row" sx={{justifyContent:'space-between',alignItems:'center',mb:0.75,gap:1,flexWrap:'wrap'}}><Typography variant="body2" color="text.secondary">第 {run.progress.epoch||0} 轮 · 有效 batch {run.progress.effective_batch??'—'}</Typography><Typography variant="body2" color="text.secondary">{completedSteps} / {run.progress.max_steps} 步 · 整体 {Math.floor(overallPercent)}%</Typography></Stack><LinearProgress variant="determinate" value={overallPercent} aria-label="整体进度"/></Box>}
    {run.status==='exporting'&&<Box sx={{mt:2}}><Stack direction="row" sx={{justifyContent:'space-between',alignItems:'center',mb:0.75,gap:1,flexWrap:'wrap'}}><Typography variant="body2" color="text.secondary">训练已完成，正在合并与导出</Typography><Typography variant="body2" color="text.secondary">{exportPercent===undefined?'等待转换进度':`${run.progress.export_step||0} / ${run.progress.export_total} 张量 · 整体 ${Math.floor(overallPercent)}%`}</Typography></Stack><LinearProgress variant="determinate" value={overallPercent} aria-label="整体进度"/></Box>}
    {run.status==='completed'&&<Box sx={{mt:2}}><Stack direction="row" sx={{justifyContent:'space-between',alignItems:'center',mb:0.75}}><Typography variant="body2" color="text.secondary">全部完成</Typography><Typography variant="body2" color="text.secondary">100%</Typography></Stack><LinearProgress variant="determinate" value={100} aria-label="导出完成"/></Box>}
    {run.message && run.status!=='completed' && <Typography variant="body2" sx={{mt:2}}>{run.message}</Typography>}
    <Stack direction="row" sx={{gap:3,my:2,flexWrap:'wrap'}}>{Object.entries(run.progress).filter(([key])=>['completed_step','epoch','loss','eval_loss'].includes(key)).map(([key,value])=><Box key={key}><Typography variant="caption" color="text.secondary">{metricLabels[key]}</Typography><Typography>{new Intl.NumberFormat('zh-CN',{maximumFractionDigits:4}).format(value)}</Typography></Box>)}</Stack>
    <Stack direction="row" spacing={1} sx={{mt:2,flexWrap:'wrap'}} useFlexGap>{run.exportReady&&<Tooltip title="使用已有模型文件重新导出"><Button variant="outlined" disabled={disabled||snapshot!.busy||archived} onClick={()=>confirm({path:`runs/${run.id}/export`,title:'重新导出模型？',name:run.name || run.profile,export:true})}>重试导出</Button></Tooltip>}<Tooltip title="查看本轮训练和导出日志"><Button disabled={disabled||logsPending} onClick={async()=>{setLogsPending(true);try{const result=await action(snapshot!,`runs/${run.id}/logs`);setLogs(result.text);}catch(e){setError((e as Error).message);}finally{setLogsPending(false);}}}>查看日志</Button></Tooltip>{!active.has(run.status)&&<Tooltip title={archived?'恢复到当前运行列表':'归档此运行，可稍后恢复'}><Button disabled={disabled} onClick={()=>void execute(`runs/${run.id}/${archived?'restore':'archive'}`)}>{archived?'恢复记录':'归档记录'}</Button></Tooltip>}</Stack>
    {!!run.checkpoints.length && run.status!=='completed' && !run.exportReady && <><Divider sx={{my:2}}/><Typography variant="subtitle2">保存点</Typography>
    {run.checkpoints.length?run.checkpoints.map(point=><Stack key={point} direction="row" sx={{alignItems:"center",justifyContent:"space-between",gap:1}}><Typography variant="body2">{point}</Typography><Tooltip title="从选定保存点继续训练"><Button disabled={disabled || snapshot!.busy || archived} onClick={()=>confirm({path:`runs/${run.id}/resume`,title:'恢复训练？',name:`${run.name || run.profile} / ${point}`,values:{checkpoint:point}})}>恢复</Button></Tooltip></Stack>):<Typography variant="body2" color="text.secondary" sx={{mt:1}}>暂无保存点</Typography>}</>}
    <Accordion disableGutters elevation={0} sx={{'&:before':{display:'none'}}}><AccordionSummary expandIcon={<span aria-hidden>⌄</span>}>输出目录</AccordionSummary><AccordionDetails><Typography variant="body2" sx={{overflowWrap:'anywhere'}}>{run.output}</Typography></AccordionDetails></Accordion>
   </Box>
  </Paper>}
  </>}
  </Box>
 </Box>
  <Dialog open={logs!==undefined} onClose={()=>setLogs(undefined)} fullWidth maxWidth="md"><DialogTitle>运行日志</DialogTitle><DialogContent><Box component="pre" sx={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',fontSize:12,maxHeight:480,overflow:'auto'}}>{logs}</Box></DialogContent><DialogActions><Button onClick={()=>setLogs(undefined)}>关闭</Button></DialogActions></Dialog>
  {creating && snapshot && <CreateTask profile={editing} defaults={editing?.settings || snapshot.templates[0]?.settings || {}} snapshot={snapshot} onSaved={async()=>{await refresh(true);}} onClose={()=>setCreating(false)}/>}
  <Dialog open={!!confirmation} onClose={()=>{if(!pending)setConfirmation(undefined);}} fullWidth maxWidth="sm" aria-labelledby="confirm-title">
   <DialogTitle id="confirm-title">{confirmation?.title}</DialogTitle><DialogContent><Typography sx={{mb:2,overflowWrap:'anywhere'}}>{confirmation?.name}</Typography>{confirmation?.stop&&<Typography sx={{mb:2}}>不会生成新保存点；已有文件会保留。</Typography>}{confirmation?.export&&<Typography variant="body2">只重新合并和导出，不会重新训练。</Typography>}{confirmation?.remove && <Typography variant="body2">只删除任务配置，不删除运行记录、数据集或模型文件。</Typography>}{confirmation?.settings && <Parameters settings={confirmation.settings}/>} {dialogError && <Alert severity="error" sx={{mt:2}}>{dialogError}</Alert>}</DialogContent>
   <DialogActions><Button autoFocus disabled={pending} onClick={()=>setConfirmation(undefined)}>取消</Button><Button variant="contained" color={confirmation?.remove?'error':'primary'} disabled={disabled} onClick={()=>{if(confirmation)void execute(confirmation.path,{confirmed:true,...confirmation.values},confirmation.revision);}}>{pending?'处理中…':confirmation?.stop?'立即停止':confirmation?.remove?'删除':confirmation?.export?'重试导出':confirmation?.values?'恢复训练':'开始训练'}</Button></DialogActions>
  </Dialog>
 </Container>;
}
