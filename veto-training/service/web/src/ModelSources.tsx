import {useState, type FormEvent} from 'react';
import {Accordion,AccordionDetails,AccordionSummary,Alert,Box,Button,Dialog,DialogActions,DialogContent,DialogTitle,LinearProgress,MenuItem,Paper,Stack,TextField,Typography} from '@mui/material';
import {action,type Snapshot} from './api';
import Tooltip from './Hint';

const labels:Record<string,string>={discovering:'读取模型信息',downloading:'下载中',paused:'已暂停',failed:'下载失败',completed:'已下载'};
export default function ModelSources({snapshot,disabled,onRefresh}:{snapshot:Snapshot;disabled:boolean;onRefresh:()=>Promise<void>}) {
 const [open,setOpen]=useState(false);const [source,setSource]=useState<'hub'|'local'>('hub');
 const [repo,setRepo]=useState('');const [revision,setRevision]=useState('main');const [token,setToken]=useState('');
 const [name,setName]=useState('');const [path,setPath]=useState('');
 const [resume,setResume]=useState('');const [error,setError]=useState('');const [pending,setPending]=useState(false);
 const close=()=>{if(!pending){setOpen(false);setToken('');}};
 async function submit(event:FormEvent) {
  event.preventDefault();if(pending)return;
  setPending(true);setError('');
  try{
   if(resume)await action(snapshot,`downloads/${resume}/resume`,{repo,revisionName:revision,token});
   else if(source==='hub')await action(snapshot,'downloads/create',{repo,revisionName:revision,token});
   else await action(snapshot,'assets/create',{kind:'model',name,path});
   setOpen(false);setToken('');await onRefresh();
  }catch(e){setError((e as Error).message);}finally{setPending(false);}
 }
 const invalid=resume?!repo.trim():source==='hub'?!repo.trim()||!revision.trim():!name.trim()||!path.trim();
 return <>
  <Stack direction="row" sx={{justifyContent:'flex-end'}}><Tooltip title="从 Hugging Face 或本地添加模型"><Button variant="contained" disabled={disabled||snapshot.busy} onClick={()=>{setOpen(true);setSource('hub');setResume('');setRepo('');setRevision('main');setToken('');setName('');setPath('');setError('');}}>添加模型</Button></Tooltip></Stack>
  {!!snapshot.downloads?.length&&<Stack spacing={1.5} sx={{mt:2,mb:2}}>{snapshot.downloads.map(row=><Paper variant="outlined" key={row.id} sx={{p:2}}>
   <Stack direction="row" sx={{justifyContent:'space-between',gap:1,alignItems:'center'}}><Typography sx={{overflowWrap:'anywhere'}}>{row.repo}</Typography><Typography variant="body2" sx={{whiteSpace:'nowrap'}}>{labels[row.status]}</Typography></Stack>
   {['discovering','downloading'].includes(row.status)&&<LinearProgress sx={{my:1.5}} variant={row.total?'determinate':'indeterminate'} value={row.total?100*row.downloaded/row.total:0}/>}
   {!!row.total&&<Typography variant="caption" color="text.secondary">{(row.downloaded/1e6).toFixed(1)} / {(row.total/1e6).toFixed(1)} MB</Typography>}
   {row.message&&<Alert severity="error" sx={{mt:1}}>{row.message}</Alert>}
   {['discovering','downloading'].includes(row.status)&&<Button disabled={disabled||pending} onClick={async()=>{setPending(true);setError('');try{await action(snapshot,`downloads/${row.id}/pause`);await onRefresh();}catch(e){setError((e as Error).message);}finally{setPending(false);}}}>暂停下载</Button>}
   {['paused','failed'].includes(row.status)&&<Button disabled={disabled||pending||snapshot.busy} onClick={()=>{setResume(row.id);setRepo(row.repo);setToken('');setError('');setOpen(true);}}>继续下载</Button>}
  </Paper>)}</Stack>}
  {error&&!open&&<Alert severity="error">{error}</Alert>}
  <Dialog open={open} onClose={close} fullWidth maxWidth="sm" aria-labelledby="model-source-title"><Box component="form" onSubmit={submit} noValidate>
   <DialogTitle id="model-source-title">{resume?'继续下载':'添加模型'}</DialogTitle><DialogContent><Stack spacing={2} sx={{pt:1}}>
    {error&&<Alert severity="error">{error}</Alert>}
    {!resume&&<TextField select label="来源" value={source} disabled={pending} onChange={e=>{setSource(e.target.value as 'hub'|'local');setError('');}}><MenuItem value="hub">Hugging Face</MenuItem><MenuItem value="local">本地已有模型</MenuItem></TextField>}
    {(source==='hub'||!!resume)&&<TextField autoFocus label="模型仓库" placeholder="组织名/模型名" value={repo} disabled={pending||!!resume} onChange={e=>setRepo(e.target.value)}/>}
    {!resume&&source==='hub'&&<TextField label="版本或提交" value={revision} disabled={pending} onChange={e=>setRevision(e.target.value)}/>}
    {!resume&&source==='local'&&<><TextField autoFocus label="名称" value={name} disabled={pending} onChange={e=>setName(e.target.value)} slotProps={{htmlInput:{maxLength:80}}}/><TextField label="模型目录或 GGUF 文件" value={path} disabled={pending} onChange={e=>setPath(e.target.value)} slotProps={{htmlInput:{maxLength:1024}}}/></>}
    {(source==='hub'||!!resume)&&<Accordion disableGutters elevation={0}><AccordionSummary expandIcon={<span aria-hidden>⌄</span>}>私有或受限仓库</AccordionSummary><AccordionDetails><TextField fullWidth type="password" autoComplete="off" label="访问令牌" value={token} disabled={pending} onChange={e=>setToken(e.target.value)}/></AccordionDetails></Accordion>}
   </Stack></DialogContent><DialogActions><Button disabled={pending} onClick={close}>取消</Button><Button variant="contained" type="submit" disabled={pending||disabled||invalid}>{pending?'处理中…':resume?'继续下载':source==='hub'?'下载':'登记'}</Button></DialogActions>
  </Box></Dialog>
 </>;
}
