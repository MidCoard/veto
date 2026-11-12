import {useEffect, useRef, useState} from 'react';
import {Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, LinearProgress, MenuItem, Stack, TextField, Typography} from '@mui/material';
import {action, type Snapshot} from './api';
import type {Sample} from './SampleRecord';

type Group={signature:string;function:Sample;count:number};
type Job={id:string;status:string;processed:number;total:number;updated:number;error?:string};
export default function BulkToolsDialog({snapshot,datasetId,revision,open,create=false,onClose,onComplete}:{snapshot:Snapshot;datasetId:string;revision:number;open:boolean;create?:boolean;onClose:()=>void;onComplete:()=>Promise<void>}) {
 const [groups,setGroups]=useState<Group[]>([]);const [signature,setSignature]=useState('');const [fn,setFn]=useState<Sample>({});const [parameters,setParameters]=useState('{}');
 const [job,setJob]=useState<Job>();const [pending,setPending]=useState(false);const [error,setError]=useState('');const [saved,setSaved]=useState(false);const completed=useRef('');
 useEffect(()=>{if(!open)return;let active=true;setPending(true);setError('');setSaved(false);
  if(create){setGroups([]);setSignature('new');setFn({name:'',description:'',parameters:{type:'object',properties:{},additionalProperties:false}});setParameters(JSON.stringify({type:'object',properties:{},additionalProperties:false},null,2));setJob(undefined);setPending(false);return()=>{active=false;};}
  void action(snapshot,`datasets/${datasetId}/tool_groups`,{}).then(result=>{if(!active)return;setGroups(result.groups);setSignature(result.activeJob?.signature||result.groups[0]?.signature||'');if(result.activeJob)setJob(result.activeJob);}).catch(e=>{if(active)setError((e as Error).message);}).finally(()=>{if(active)setPending(false);});
  return()=>{active=false;};
 },[open,datasetId,create]);
 useEffect(()=>{const group=groups.find(item=>item.signature===signature);if(group){setFn(group.function);setParameters(JSON.stringify(group.function.parameters??{},null,2));}},[signature,groups]);
 useEffect(()=>{if(!job||job.status!=='running')return;let active=true;
  const poll=async()=>{try{const next=await action(snapshot,`datasets/${datasetId}/bulk_status`,{job:job.id});if(!active)return;setJob(next);if(next.status==='completed'&&completed.current!==next.id){completed.current=next.id;await onComplete();}}catch(e){if(active)setError((e as Error).message);}};
  const timer=window.setTimeout(()=>void poll(),400);return()=>{active=false;window.clearTimeout(timer);};
 },[job]);
 async function start(){
  setPending(true);setError('');
  try{
   const parsed=JSON.parse(parameters) as unknown;if(!parsed||Array.isArray(parsed)||typeof parsed!=='object')throw new Error('参数结构必须是 JSON 对象。');
   if(create){await action(snapshot,`datasets/${datasetId}/add_tool`,{datasetRevision:revision,function:{...fn,parameters:parsed}});await onComplete();setSaved(true);return;}
   const result=await action(snapshot,`datasets/${datasetId}/bulk_tools`,{datasetRevision:revision,signature,function:{...fn,parameters:parsed}});setJob(result);
  }catch(e){setError((e as Error).message);}finally{setPending(false);}
 }
 const running=job?.status==='running';
 return <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="bulk-tools-title"><DialogTitle id="bulk-tools-title">{create?'新建工具定义':groups.length===1?'编辑共享工具定义':'编辑工具定义'}</DialogTitle><DialogContent><Stack spacing={1.5} sx={{pt:1}}>
  {error&&<Alert severity="error">{error}</Alert>}
  {saved&&<Alert severity="success">已添加工具定义，现在可以在样本中引用。</Alert>}
  {job?.status==='completed'&&<Alert severity="success">已更新 {job.updated} 个工具定义。</Alert>}
  {job?.status==='failed'&&<Alert severity="error">{job.error||'批量更新失败'}</Alert>}
  {!create&&groups.length>1&&<TextField select fullWidth value={signature} disabled={pending||running||!groups.length} onChange={e=>setSignature(e.target.value)} slotProps={{htmlInput:{'aria-label':'相同定义分组'}}}>{groups.map(group=><MenuItem key={group.signature} value={group.signature}>{String(group.function.name)} · {group.count} 个定义</MenuItem>)}</TextField>}
  <TextField fullWidth placeholder="工具名称" value={String(fn.name??'')} disabled={!create||pending||running} title={create?'用于样本引用的唯一名称':'工具名称用于样本引用，不能修改'} onChange={e=>setFn(current=>({...current,name:e.target.value}))} slotProps={{htmlInput:{'aria-label':'工具名称'}}}/>
  <TextField fullWidth multiline minRows={2} placeholder="工具说明（可选）" value={String(fn.description??'')} disabled={pending||running} onChange={e=>setFn(current=>({...current,description:e.target.value}))} slotProps={{htmlInput:{'aria-label':'工具说明'}}}/>
  <Box><Typography variant="caption" color="text.secondary" sx={{display:'block',mb:0.75,fontWeight:600}}>参数结构</Typography><TextField fullWidth multiline minRows={8} maxRows={16} value={parameters} disabled={pending||running} onChange={e=>setParameters(e.target.value)} slotProps={{htmlInput:{spellCheck:false,'aria-label':'参数结构（JSON 对象）'},input:{sx:{fontFamily:'"Cascadia Code", "SFMono-Regular", Consolas, monospace',fontSize:12.5,lineHeight:1.6}}}}/></Box>
  {job&&<Box><LinearProgress variant={running?'determinate':'indeterminate'} value={job.total?job.processed/job.total*100:100}/><Typography variant="body2" color="text.secondary">已处理 {job.processed} / {job.total} 项 · 已更新 {job.updated} 个定义</Typography></Box>}
 </Stack></DialogContent><DialogActions><Button onClick={onClose}>关闭</Button><Button variant="contained" disabled={pending||running||(!create&&!signature)||!String(fn.name||'').trim()} onClick={()=>void start()}>{create?'添加定义':'保存定义'}</Button></DialogActions></Dialog>;
}
