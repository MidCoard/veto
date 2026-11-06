import {useEffect, useRef, useState} from 'react';
import {Alert, Autocomplete, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, LinearProgress, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableHead, TablePagination, TableRow, TextField, Typography} from '@mui/material';
import {action, type Snapshot} from './api';

const splits:Record<string,string>={train:'训练',validation:'验证',test:'测试'};
type Row={id:string;name:string;split:string;enabled:boolean;records:number};
type Page={rows:Row[];total:number;counts:Record<string,number>;disabled:number;revision:number};
export default function DatasetPage({snapshot,disabled,onRefresh}:{snapshot:Snapshot;disabled:boolean;onRefresh:()=>Promise<void>}) {
 const [selected,select]=useState('');const [showArchived,setShowArchived]=useState(false);const [upload,setUpload]=useState<File>();const [data,setData]=useState<Page>();
 const [query,setQuery]=useState('');const [filter,setFilter]=useState('');const [page,setPage]=useState(0);
 const [pending,setPending]=useState(false);const [error,setError]=useState('');
 const [dialog,setDialog]=useState('');const [name,setName]=useState('');const [format,setFormat]=useState('instruction');
 const [preview,setPreview]=useState<{name:string;records:Record<string,unknown>[]}>();
 const [sample,setSample]=useState('');const [text,setText]=useState('');const [split,setSplit]=useState('train');const [path,setPath]=useState('');
 const [dirty,setDirty]=useState(false);const [discard,setDiscard]=useState(false);const sequence=useRef(0);
 const dataset=snapshot.datasets?.find(d=>d.id===selected);const blocked=disabled||pending;
 async function load() {
  if(!selected)return;
  const ticket=++sequence.current;setPending(true);setError('');
  try {const result=await action(snapshot,`datasets/${selected}/browse`,{query,split:filter,page});if(ticket===sequence.current)setData(result);}
  catch(e){if(ticket===sequence.current)setError((e as Error).message);}
  finally{if(ticket===sequence.current)setPending(false);}
 }
 useEffect(()=>{setData(undefined);void load();return()=>{sequence.current++;};},[selected,query,filter,page]);
 useEffect(()=>{const warn=(e:BeforeUnloadEvent)=>{if(dirty){e.preventDefault();e.returnValue='';}};window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn);},[dirty]);
 function close(){if(pending)return;if(dirty)setDiscard(true);else setDialog('');}
 async function uploadFile(){
  if(!upload)return;
  if(upload.size>300_000_000)throw new Error('文件需要在 300 MB 以内。');
  const response=await fetch(`/api/datasets/${selected}/upload?split=${split}&datasetRevision=${data?.revision}&name=${encodeURIComponent(upload.name)}`,{method:'POST',headers:{'X-Veto-CSRF':snapshot.csrf},body:upload,signal:AbortSignal.timeout(600000)});
  const result=await response.json();if(!response.ok)throw new Error(result.error||'上传失败');return result;
 }
 async function showPreview(row:Row) {
  if(blocked)return;setPending(true);setError('');
  try{const result=await action(snapshot,`datasets/${selected}/preview`,{sample:row.id});setPreview(result);setDirty(false);setDialog('preview');}
  catch(e){setError((e as Error).message);}finally{setPending(false);}
 }
 async function mutate(operation:string,values:Record<string,unknown>={}) {
  if(blocked)return;setPending(true);setError('');
  try {
   const result=operation==='import'&&upload?await uploadFile():await action(snapshot,operation==='create'?'datasets/create':`datasets/${selected}/${operation}`,{datasetRevision:data?.revision,...values});
   setDirty(false);setDialog('');await onRefresh();
   if(operation==='create'){select(result.id);setPage(0);}else if(operation==='archive'||operation==='restore_dataset'){select('');setData(undefined);}else await load();
  }catch(e){setError((e as Error).message);}finally{setPending(false);}
 }
 function submit(){void mutate(dialog==='edit'?'save':dialog,dialog==='create'?{name,format}:{sample,split,path,name,...(dialog==='edit'?{record:JSON.parse(text)}:{})});}
 return <Stack spacing={2}>
  <Stack direction="row" sx={{justifyContent:"space-between",alignItems:"center"}}><Typography variant="h6">数据集</Typography><Button variant="contained" disabled={blocked} onClick={()=>{setName('');setFormat('instruction');setDirty(false);setError('');setDialog('create');}}>新建数据集</Button></Stack>
  <Button sx={{alignSelf:'flex-start'}} onClick={()=>{setShowArchived(!showArchived);select('');}}>{showArchived?'返回数据集':'查看归档'}</Button>
  {error&&!dialog&&<Alert severity="error">{error}</Alert>}
  <TextField select size="small" label="数据集" value={selected} disabled={blocked} onChange={e=>{select(e.target.value);setPage(0);setQuery('');}}><MenuItem value="">选择数据集</MenuItem>{snapshot.datasets?.filter(d=>!!d.archived===showArchived).map(d=><MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}</TextField>
  {!snapshot.datasets?.length&&<Typography color="text.secondary">新建数据集后，可以添加样本或导入 JSONL 文件。</Typography>}
  {dataset&&<>
   <Stack direction="row" spacing={1} useFlexGap sx={{flexWrap:"wrap"}}><Button disabled={blocked||!data||showArchived} onClick={()=>{setText(JSON.stringify(dataset.format==='native_tools'?{messages:[],tools:[]}:{instruction:'',output:''},null,2));setSplit('train');setDirty(false);setError('');setDialog('edit');}}>新增样本</Button><Button disabled={blocked||!data||showArchived} onClick={()=>{setUpload(undefined);setPath('');setSplit('train');setDirty(false);setError('');setDialog('import');}}>导入 JSONL</Button><Button disabled={blocked||!data} onClick={()=>{setName(dataset.name);setDirty(false);setError('');setDialog('rename');}}>重命名</Button><Button disabled={blocked||!data} onClick={()=>{setDirty(false);setError('');setDialog(showArchived?'restore_dataset':'archive');}}>{showArchived?'恢复数据集':'归档数据集'}</Button></Stack>
   <Stack direction={{xs:'column',sm:'row'}} spacing={2}><TextField size="small" label="搜索文件名" value={query} onChange={e=>{setQuery(e.target.value);setPage(0);}} fullWidth/><TextField select size="small" label="划分" value={filter} sx={{minWidth:140}} onChange={e=>{setFilter(e.target.value);setPage(0);}}><MenuItem value="">全部</MenuItem>{Object.entries(splits).map(([key,label])=><MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField></Stack>
   {data&&<Typography variant="body2" color="text.secondary">训练 {data.counts.train} · 验证 {data.counts.validation} · 测试 {data.counts.test}{data.disabled?` · 已停用 ${data.disabled}`:''}</Typography>}
   {pending&&<LinearProgress/>}
   <Paper variant="outlined"><Box sx={{overflowX:'auto'}}><Table size="small" sx={{minWidth:900}}><TableHead><TableRow><TableCell>文件</TableCell><TableCell>样本数</TableCell><TableCell>划分</TableCell><TableCell>状态</TableCell><TableCell align="right">操作</TableCell></TableRow></TableHead><TableBody>{data?.rows.map(row=><TableRow key={row.id}><TableCell sx={{maxWidth:420,overflowWrap:'anywhere'}}>{row.name}</TableCell><TableCell>{row.records}</TableCell><TableCell><TextField select size="small" label="划分" value={row.split} disabled={blocked||showArchived} onChange={e=>void mutate('split',{sample:row.id,split:e.target.value})}>{Object.entries(splits).map(([key,label])=><MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField></TableCell><TableCell><Chip size="small" label={row.enabled?'已启用':'已停用'}/></TableCell><TableCell align="right" sx={{whiteSpace:'nowrap'}}><Button disabled={blocked} onClick={()=>void showPreview(row)}>预览</Button><Button disabled={blocked||showArchived} onClick={()=>void mutate('toggle',{sample:row.id,enabled:!row.enabled})}>{row.enabled?'停用':'启用'}</Button><Button disabled={blocked||showArchived} color="error" onClick={()=>{setSample(row.id);setError('');setDialog('delete');}}>删除</Button></TableCell></TableRow>)}</TableBody></Table></Box>{data&&!data.rows.length&&<Typography sx={{p:3}} color="text.secondary">{query||filter?'没有匹配的文件':'暂无文件'}</Typography>}<TablePagination component="div" count={data?.total||0} page={page} rowsPerPage={20} rowsPerPageOptions={[20]} onPageChange={(_,value)=>setPage(value)} labelDisplayedRows={({from,to,count})=>`${from}–${to} / ${count}`}/></Paper>
  </>}
  <Dialog open={!!dialog} onClose={close} fullWidth maxWidth={dialog==='edit'||dialog==='preview'?'md':'sm'} aria-labelledby="dataset-dialog-title"><DialogTitle id="dataset-dialog-title">{{create:'新建数据集',edit:'新增样本',import:'导入 JSONL 文件',preview:'文件预览',delete:'永久删除此文件？',rename:'重命名数据集',archive:'归档此数据集？',restore_dataset:'恢复此数据集？'}[dialog]}</DialogTitle><DialogContent><Stack spacing={2} sx={{pt:1}}>{error&&<Alert severity="error">{error}</Alert>}
   {dialog==='preview'&&<><Typography variant="subtitle2">{preview?.name}</Typography>{preview?.records.map((record,index)=><Paper key={index} variant="outlined" sx={{p:2}}><Box component="pre" sx={{m:0,whiteSpace:'pre-wrap',overflowWrap:'anywhere',fontSize:12}}>{JSON.stringify(record,null,2)}</Box></Paper>)}</>}
   {dialog==='rename'&&<TextField autoFocus label="名称" value={name} disabled={pending} onChange={e=>{setName(e.target.value);setDirty(true);}}/>}
   {dialog==='create'&&<><TextField autoFocus label="名称" value={name} disabled={pending} onChange={e=>{setName(e.target.value);setDirty(true);}}/><TextField select label="样本结构" value={format} disabled={pending} onChange={e=>{setFormat(e.target.value);setDirty(true);}}><MenuItem value="instruction">指令与回答</MenuItem><MenuItem value="native_tools">工具调用</MenuItem></TextField></>}
   {dialog==='import'&&<><Button component="label" variant="outlined" disabled={pending}>选择 JSONL 文件<input hidden type="file" accept=".jsonl,.json" onChange={e=>{setUpload(e.target.files?.[0]);setPath('');setDirty(true);}}/></Button>{upload&&<Typography sx={{overflowWrap:'anywhere'}}>{upload.name}</Typography>}</>}
   {dialog==='import'&&!upload&&<Autocomplete freeSolo options={snapshot.assets.filter(a=>a.kind==='dataset').map(a=>a.path)} inputValue={path} disabled={pending} onInputChange={(_,value)=>{setPath(value);setDirty(true);}} renderInput={params=><TextField {...params} autoFocus label="JSONL 文件路径"/>}/>}
   {(dialog==='edit'||dialog==='import')&&<TextField select label="数据划分" value={split} disabled={pending} onChange={e=>{setSplit(e.target.value);setDirty(true);}}>{Object.entries(splits).map(([key,label])=><MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField>}
   {dialog==='edit'&&<TextField multiline minRows={12} maxRows={24} label="样本 JSON" value={text} disabled={pending} onChange={e=>{setText(e.target.value);setDirty(true);}} slotProps={{htmlInput:{spellCheck:false},input:{sx:{fontFamily:'monospace',fontSize:13}}}}/>}
   {dialog==='delete'&&<Typography>将永久删除此文件及其中的样本。已有训练快照不受影响。</Typography>}
   {dialog==='archive'&&<Typography>不再用于新任务，文件会保留，可随时恢复。</Typography>}
  </Stack></DialogContent>{dialog==='preview'?<DialogActions><Button onClick={()=>setDialog('')}>关闭</Button></DialogActions>:<DialogActions><Button disabled={pending} onClick={close}>取消</Button><Button variant="contained" disabled={blocked||(dialog==='create'&&!name.trim())||(dialog==='import'&&!path&&!upload)} color={dialog==='delete'?'error':'primary'} onClick={submit}>{pending?'处理中…':dialog==='delete'?'永久删除':dialog==='import'?'导入':'保存'}</Button></DialogActions>}</Dialog>
  <Dialog open={discard} onClose={()=>setDiscard(false)}><DialogTitle>放弃未保存的修改？</DialogTitle><DialogActions><Button onClick={()=>setDiscard(false)}>继续编辑</Button><Button color="error" onClick={()=>{setDiscard(false);setDirty(false);setDialog('');}}>放弃修改</Button></DialogActions></Dialog>
 </Stack>;
}
