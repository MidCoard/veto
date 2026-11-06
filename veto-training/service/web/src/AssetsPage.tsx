import { useState } from 'react';
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Stack, Typography } from '@mui/material';
import ModelSources from './ModelSources';
import { action, type Asset, type Snapshot } from './api';

const formatLabels:Record<string,string>={huggingface:'Hugging Face',gguf:'GGUF',native_tools:'工具调用',instruction:'instruction / output',raw:'instruction / output',unknown:'未识别'};
function size(bytes:number|null) {
 if(bytes===null)return null;
 return bytes<1024?`${bytes} B`:bytes<1024**2?`${(bytes/1024).toFixed(1)} KB`:`${(bytes/1024**2).toFixed(1)} MB`;
}
export default function AssetsPage({kind,snapshot,disabled,onRefresh}:{kind:'model';snapshot:Snapshot;disabled:boolean;onRefresh:()=>Promise<void>}) {
 const [error,setError]=useState('');const [pending,setPending]=useState(false);const [remove,setRemove]=useState<Asset>();
 const [revision,setRevision]=useState(snapshot.revision);
 const entries=snapshot.assets.filter(a=>a.kind===kind);
 async function removeEntry() {
  if(!remove || pending)return;
  setPending(true);setError('');
  try{await action({...snapshot,revision},`assets/${remove.id}/remove`,{confirmed:true});await onRefresh();setRemove(undefined);}
  catch(e){setError(e instanceof Error?e.message:'移除失败');}
  finally{setPending(false);}
 }
 return <>
  <Typography component="h2" variant="h6" sx={{mb:2}}>模型</Typography>
  <ModelSources snapshot={snapshot} disabled={disabled} onRefresh={onRefresh}/>
  {entries.length?<Paper variant="outlined" sx={{mt:2}}>{entries.map(asset=><Box component="section" key={asset.id} sx={{px:2,py:1.5,'&:not(:last-child)':{borderBottom:1,borderColor:'divider'}}}>
   <Stack direction="row" sx={{justifyContent:'space-between',alignItems:'center',gap:2}}><Typography variant="subtitle2" component="h3" sx={{overflowWrap:'anywhere',minWidth:0}}>{asset.name}</Typography><Button color="error" disabled={disabled} onClick={()=>{setError('');setRevision(snapshot.revision);setRemove(asset);}}>移除模型</Button></Stack>
   <Typography variant="caption" color="text.secondary" title={asset.path} sx={{display:'block',overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap',my:.75}}>{asset.path}</Typography>
   <Stack direction="row" spacing={1} useFlexGap sx={{flexWrap:'wrap'}}><Chip size="small" label={formatLabels[asset.format] || asset.format}/>{asset.available&&<Chip size="small" label={asset.trainable?'可训练':'仅推理'}/>} {asset.bytes!==null && <Chip size="small" label={size(asset.bytes)}/>} {!asset.available && <Chip size="small" color="error" label="路径不可用"/>}</Stack>
   {(asset.tasks.length>0 || asset.runs.length>0) && <Accordion disableGutters elevation={0} sx={{'&:before':{display:'none'}}}><AccordionSummary expandIcon={<span aria-hidden>⌄</span>}>{asset.tasks.length} 个任务 · {asset.runs.length} 次运行</AccordionSummary><AccordionDetails sx={{p:0,pt:1}}><Stack spacing={1}>{asset.tasks.map(name=><Typography variant="body2" key={'task-'+name}>任务：{name}</Typography>)}{asset.runs.map(id=><Typography variant="body2" key={'run-'+id} sx={{overflowWrap:'anywhere'}}>运行：{id}</Typography>)}</Stack></AccordionDetails></Accordion>}
  </Box>)}</Paper>:<Typography color="text.secondary" sx={{mt:2}}>暂无模型</Typography>}
  <Dialog open={!!remove} onClose={()=>{if(!pending)setRemove(undefined);}} fullWidth maxWidth="sm" aria-labelledby="asset-remove-title"><DialogTitle id="asset-remove-title">永久移除模型？</DialogTitle><DialogContent><Typography sx={{mb:1,overflowWrap:'anywhere'}}>{remove?.name}</Typography><Typography variant="body2" color="text.secondary" sx={{mb:2,overflowWrap:'anywhere'}}>{remove?.path}</Typography><Alert severity="warning">将永久删除模型本身及其本地文件，不可撤销。已有运行记录保留。</Alert>{!!remove?.tasks.length&&<Typography variant="body2" sx={{mt:2}}>使用此模型的任务：{remove.tasks.join('、')}。删除后需要重新选择模型。</Typography>}{error && <Alert severity="error" sx={{mt:2}}>{error}</Alert>}</DialogContent><DialogActions><Button autoFocus disabled={pending} onClick={()=>setRemove(undefined)}>取消</Button><Button color="error" variant="contained" disabled={pending || disabled} onClick={()=>void removeEntry()}>{pending?'处理中…':'永久移除模型'}</Button></DialogActions></Dialog>
 </>;
}
