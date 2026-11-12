import {useEffect, useRef, useState} from 'react';
import {Alert, Autocomplete, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, LinearProgress, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableHead, TablePagination, TableRow, TextField, Typography} from '@mui/material';
import {action, type Snapshot} from './api';
import Tooltip from './Hint';
import {RecordEditor, RecordView, type Sample} from './SampleRecord';
import BulkToolsDialog from './BulkToolsDialog';

const splits:Record<string,string>={train:'训练',validation:'验证',test:'测试'};
type Row={id:string;name:string;split:string;enabled:boolean;records:number};
type Page={rows:Row[];total:number;counts:Record<string,number>;disabled:number;revision:number};
type RecordPage={name:string;record:Sample;index:number;total:number;split:string;enabled:boolean};
type ToolGroup={signature:string;function:Sample;count:number};
export default function DatasetPage({snapshot,disabled,onRefresh}:{snapshot:Snapshot;disabled:boolean;onRefresh:()=>Promise<void>}) {
 const [selected,select]=useState('');const [showArchived,setShowArchived]=useState(false);const [upload,setUpload]=useState<File>();const [data,setData]=useState<Page>();
 const [query,setQuery]=useState('');const [filter,setFilter]=useState('');const [page,setPage]=useState(0);
 const [pending,setPending]=useState(false);const [error,setError]=useState('');
 const [dialog,setDialog]=useState('');const [name,setName]=useState('');const [format,setFormat]=useState('instruction');
 const [recordPage,setRecordPage]=useState<RecordPage>();const [recordRow,setRecordRow]=useState<Row>();
 const [sample,setSample]=useState('');const [split,setSplit]=useState('train');const [path,setPath]=useState('');
 const [draft,setDraft]=useState<Sample>({});const [draftValid,setDraftValid]=useState(true);const [editing,setEditing]=useState(false);
 const [recordNumber,setRecordNumber]=useState('');const [saved,setSaved]=useState(false);
 const [dirty,setDirty]=useState(false);const [discard,setDiscard]=useState<''|'close'|'view'>('');const [bulkOpen,setBulkOpen]=useState(false);const [createOpen,setCreateOpen]=useState(false);const [toolGroups,setToolGroups]=useState<ToolGroup[]>([]);const sequence=useRef(0);
 const dataset=snapshot.datasets?.find(d=>d.id===selected);const blocked=disabled||pending;
 async function load() {
  if(!selected)return;
  const ticket=++sequence.current;setPending(true);setError('');
  try {const [result,tools]=await Promise.all([action(snapshot,`datasets/${selected}/browse`,{query,split:filter,page}),dataset?.format==='native_tools'?action(snapshot,`datasets/${selected}/tool_groups`,{}):Promise.resolve({groups:[]})]);if(ticket===sequence.current){setData(result);setToolGroups(tools.groups);}}
  catch(e){if(ticket===sequence.current)setError((e as Error).message);}
  finally{if(ticket===sequence.current)setPending(false);}
 }
 useEffect(()=>{setData(undefined);setToolGroups([]);void load();return()=>{sequence.current++;};},[selected,query,filter,page]);
 useEffect(()=>{const warn=(e:BeforeUnloadEvent)=>{if(dirty){e.preventDefault();e.returnValue='';}};window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn);},[dirty]);
 function close(){if(pending)return;if(dirty)setDiscard('close');else setDialog('');}
 function backToView(){if(dirty)setDiscard('view');else setEditing(false);}
 function confirmDiscard(){const target=discard;setDiscard('');setDirty(false);if(target==='close')setDialog('');else{setDraft(recordPage?.record||{});setDraftValid(true);setError('');setEditing(false);}}
 async function uploadFile(){
  if(!upload)return;
  if(upload.size>300_000_000)throw new Error('文件需要在 300 MB 以内。');
  const response=await fetch(`/api/datasets/${selected}/upload?split=${split}&datasetRevision=${data?.revision}&name=${encodeURIComponent(upload.name)}`,{method:'POST',headers:{'X-Veto-CSRF':snapshot.csrf},body:upload,signal:AbortSignal.timeout(600000)});
  const result=await response.json();if(!response.ok)throw new Error(result.error||'上传失败');return result;
 }
 async function showRecord(row:Row,index:number) {
  if(blocked)return;setPending(true);setError('');
  try{const result=await action(snapshot,`datasets/${selected}/record`,{sample:row.id,index});setRecordRow(row);setSample(row.id);setRecordPage(result);setDraft(result.record);setRecordNumber(String(index+1));setSaved(false);setDraftValid(true);setEditing(false);setDirty(false);setDialog('record');}
  catch(e){setError((e as Error).message);}finally{setPending(false);}
 }
 async function jumpToRecord(){
  if(!recordRow||!recordPage)return;
  const value=Number(recordNumber);
  if(!/^\d+$/.test(recordNumber)||value<1||value>recordPage.total){setError(`请输入 1 到 ${recordPage.total} 的样本序号。`);return;}
  await showRecord(recordRow,value-1);
 }
 async function mutate(operation:string,values:Record<string,unknown>={}) {
  if(blocked)return;setPending(true);setError('');
  try {
   const result=operation==='import'&&upload?await uploadFile():await action(snapshot,operation==='create'?'datasets/create':`datasets/${selected}/${operation}`,{datasetRevision:data?.revision,...values});
   setDirty(false);
   if(operation==='update_record'&&recordRow&&recordPage){
    const refreshed=await action(snapshot,`datasets/${selected}/record`,{sample:recordRow.id,index:recordPage.index});setRecordPage(refreshed);setDraft(refreshed.record);setRecordNumber(String(refreshed.index+1));setEditing(false);setSaved(true);await onRefresh();await load();return;
   }
   setDialog('');await onRefresh();
   if(operation==='create'){select(result.id);setPage(0);}else if(operation==='archive'||operation==='restore_dataset'){select('');setData(undefined);}else await load();
  }catch(e){setError((e as Error).message);}finally{setPending(false);}
 }
 function submit(){
  const operation=dialog==='edit'?'save':dialog==='record'?'update_record':dialog;
  void mutate(operation,dialog==='create'?{name,format}:{sample,split,path,name,...((dialog==='edit'||dialog==='record')?{record:draft}:{}),...(dialog==='record'?{index:recordPage?.index}:{})});
 }
 function changeDraft(value:Sample,valid:boolean){setDraft(value);setDraftValid(valid);setDirty(JSON.stringify(value)!==JSON.stringify(recordPage?.record||{}));}
 function newSample(){
  setDraft(dataset?.format==='native_tools'?{messages:[{role:'user',content:''},{role:'assistant',content:''}],tool_refs:toolGroups.map(group=>String(group.function.name||'')).filter(Boolean)}:{instruction:'',output:''});
  setDraftValid(true);setEditing(true);setSplit('train');setDirty(false);setError('');setDialog('edit');
 }
 return <Stack spacing={2}>
  <Stack direction="row" sx={{justifyContent:"space-between",alignItems:"center"}}><Typography variant="h6">数据集</Typography><Tooltip title="创建新的样本数据集"><Button variant="contained" disabled={blocked} onClick={()=>{setName('');setFormat('instruction');setDirty(false);setError('');setDialog('create');}}>新建数据集</Button></Tooltip></Stack>
  <Button sx={{alignSelf:'flex-start'}} onClick={()=>{setShowArchived(!showArchived);select('');}}>{showArchived?'返回数据集':'查看归档'}</Button>
  {error&&!dialog&&<Alert severity="error">{error}</Alert>}
  <TextField select size="small" label="数据集" value={selected} disabled={blocked} onChange={e=>{select(e.target.value);setPage(0);setQuery('');}}><MenuItem value="">选择数据集</MenuItem>{snapshot.datasets?.filter(d=>!!d.archived===showArchived).map(d=><MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}</TextField>
  {!snapshot.datasets?.length&&<Typography color="text.secondary">新建数据集后，可以添加样本或导入 JSONL 文件。</Typography>}
  {dataset&&<>
   <Stack direction="row" spacing={1} useFlexGap sx={{flexWrap:"wrap"}}><Tooltip title="向当前数据集添加一条样本"><Button disabled={blocked||!data||showArchived} onClick={newSample}>新增样本</Button></Tooltip>{dataset.format==='native_tools'&&!toolGroups.length&&<Button variant="outlined" disabled={blocked||!data||showArchived} onClick={()=>setCreateOpen(true)}>添加定义</Button>}<Tooltip title="将 JSONL 文件导入当前数据集"><Button disabled={blocked||!data||showArchived} onClick={()=>{setUpload(undefined);setPath('');setSplit('train');setDirty(false);setError('');setDialog('import');}}>导入 JSONL</Button></Tooltip><Tooltip title="修改数据集名称"><Button disabled={blocked||!data} onClick={()=>{setName(dataset.name);setDirty(false);setError('');setDialog('rename');}}>重命名</Button></Tooltip><Tooltip title={showArchived?'恢复到普通数据集列表':'停用此数据集，文件会保留'}><Button disabled={blocked||!data} onClick={()=>{setDirty(false);setError('');setDialog(showArchived?'restore_dataset':'archive');}}>{showArchived?'恢复数据集':'归档数据集'}</Button></Tooltip></Stack>
   {toolGroups.length>0&&<Paper variant="outlined" sx={{overflow:'hidden'}}><Stack direction="row" sx={{px:1.5,py:1,alignItems:'center',gap:1,bgcolor:'action.hover',flexWrap:'wrap'}} useFlexGap><Box sx={{flex:1,minWidth:160}}><Typography variant="subtitle2" sx={{fontWeight:650}}>工具定义</Typography><Typography variant="caption" color="text.secondary">{toolGroups.length} 个定义 · {toolGroups.reduce((sum,group)=>sum+group.count,0).toLocaleString('zh-CN')} 条引用</Typography></Box><Button disabled={blocked||showArchived} onClick={()=>setCreateOpen(true)}>添加定义</Button><Button variant="outlined" disabled={blocked||showArchived} onClick={()=>setBulkOpen(true)}>编辑定义</Button></Stack><Stack direction="row" spacing={1} useFlexGap sx={{p:1.5,borderTop:1,borderColor:'divider',flexWrap:'wrap'}}>{toolGroups.map(group=><Chip key={group.signature} color="primary" variant="outlined" label={String(group.function.name||'未知工具')}/>)}</Stack></Paper>}
   <Stack direction={{xs:'column',sm:'row'}} spacing={2}><TextField size="small" label="搜索文件名" value={query} onChange={e=>{setQuery(e.target.value);setPage(0);}} fullWidth/><TextField select size="small" label="划分" value={filter} sx={{minWidth:140}} onChange={e=>{setFilter(e.target.value);setPage(0);}}><MenuItem value="">全部</MenuItem>{Object.entries(splits).map(([key,label])=><MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField></Stack>
   {data&&<Typography variant="body2" color="text.secondary">训练 {data.counts.train} · 验证 {data.counts.validation} · 测试 {data.counts.test}{data.disabled?` · 已停用 ${data.disabled}`:''}</Typography>}
   {pending&&<LinearProgress/>}
   <Paper variant="outlined"><Box sx={{overflowX:'auto'}}><Table size="small" sx={{minWidth:{xs:560,sm:0},width:'100%',tableLayout:'fixed'}}><TableHead><TableRow><TableCell width="19%">文件</TableCell><TableCell width="12%">样本数</TableCell><TableCell width="17%">划分</TableCell><TableCell width="16%">状态</TableCell><TableCell width="36%" align="right">操作</TableCell></TableRow></TableHead><TableBody>{data?.rows.map(row=><TableRow key={row.id}><TableCell sx={{overflowWrap:'anywhere'}}>{row.name}</TableCell><TableCell>{row.records}</TableCell><TableCell><TextField select size="small" label="划分" value={row.split} disabled={blocked||showArchived} onChange={e=>void mutate('split',{sample:row.id,split:e.target.value})}>{Object.entries(splits).map(([key,label])=><MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField></TableCell><TableCell><Chip size="small" variant="outlined" color={row.enabled?'success':'default'} label={row.enabled?'已启用':'已停用'}/></TableCell><TableCell align="right" sx={{whiteSpace:'nowrap',px:1}}><Tooltip title="逐条查看和编辑文件中的样本"><Button disabled={blocked} onClick={()=>void showRecord(row,0)}>样本</Button></Tooltip><Tooltip title={row.enabled?'停用后不参与新训练快照':'启用后参与新训练快照'}><Button disabled={blocked||showArchived} onClick={()=>void mutate('toggle',{sample:row.id,enabled:!row.enabled})}>{row.enabled?'停用':'启用'}</Button></Tooltip><Tooltip title="永久删除此文件及其中样本"><Button disabled={blocked||showArchived} color="error" onClick={()=>{setSample(row.id);setError('');setDialog('delete');}}>删除</Button></Tooltip></TableCell></TableRow>)}</TableBody></Table></Box>{data&&!data.rows.length&&<Typography sx={{p:3}} color="text.secondary">{query||filter?'没有匹配的文件':'暂无文件'}</Typography>}<TablePagination component="div" count={data?.total||0} page={page} rowsPerPage={20} rowsPerPageOptions={[20]} onPageChange={(_,value)=>setPage(value)} labelDisplayedRows={({from,to,count})=>`${from}–${to} / ${count}`}/></Paper>
  </>}
  <Dialog open={!!dialog} onClose={close} fullWidth maxWidth={dialog==='edit'||dialog==='record'?'md':'sm'} aria-labelledby="dataset-dialog-title"><DialogTitle id="dataset-dialog-title">{{create:'新建数据集',edit:'新增样本',import:'导入 JSONL 文件',record:editing?'编辑样本':'样本',delete:'永久删除此文件？',rename:'重命名数据集',archive:'归档此数据集？',restore_dataset:'恢复此数据集？'}[dialog]}</DialogTitle><DialogContent><Stack spacing={2} sx={{pt:1}}>{error&&<Alert severity="error">{error}</Alert>}
   {discard&&<Paper variant="outlined" sx={{p:2}}><Stack spacing={2}><Typography>放弃未保存的修改？</Typography><Stack direction="row" spacing={1} sx={{justifyContent:'flex-end'}}><Button onClick={()=>setDiscard('')}>继续编辑</Button><Button color="error" onClick={confirmDiscard}>{discard==='view'?'放弃并返回查看':'放弃并关闭'}</Button></Stack></Stack></Paper>}
   <Box sx={{display:discard?'none':'contents'}}>
   {dialog==='record'&&recordPage&&recordRow&&<>
    <Paper variant="outlined" sx={{p:1.25,bgcolor:'background.default',borderRadius:2}}><Stack direction={{xs:'column',sm:'row'}} spacing={1.25} sx={{alignItems:{sm:'center'}}}><Typography variant="subtitle2" sx={{flex:1,minWidth:0,overflowWrap:'anywhere',fontWeight:650}}>{recordPage.name}</Typography><Stack direction="row" spacing={0.5} sx={{alignItems:'center',justifyContent:'flex-end',flexShrink:0}}><Button disabled={blocked||editing||recordPage.index===0} onClick={()=>void showRecord(recordRow,recordPage.index-1)}>上一条</Button><TextField size="small" type="number" variant="standard" value={recordNumber} disabled={blocked||editing} onChange={e=>{setRecordNumber(e.target.value);setError('');}} onKeyDown={e=>{if(e.key==='Enter')void jumpToRecord();}} onBlur={()=>{const value=Number(recordNumber);if(recordNumber===String(recordPage.index+1))return;if(/^\d+$/.test(recordNumber)&&value>=1&&value<=recordPage.total)void jumpToRecord();else{setRecordNumber(String(recordPage.index+1));setError('');}}} slotProps={{htmlInput:{min:1,max:recordPage.total,step:1,'aria-label':'样本序号'}}} sx={{width:52,'& .MuiInputBase-root':{minHeight:30,bgcolor:'transparent'},'& input':{px:0,py:0.25,textAlign:'center',fontWeight:650,MozAppearance:'textfield'},'& input::-webkit-inner-spin-button,& input::-webkit-outer-spin-button':{WebkitAppearance:'none',m:0}}}/><Typography variant="body2" color="text.secondary" sx={{whiteSpace:'nowrap',px:0.25}}>/ {recordPage.total}</Typography><Button disabled={blocked||editing||recordPage.index+1>=recordPage.total} onClick={()=>void showRecord(recordRow,recordPage.index+1)}>下一条</Button></Stack></Stack></Paper>
    {saved&&<Alert severity="success" sx={{py:0.25}}>已保存到 {recordPage.name}</Alert>}
    {editing?<RecordEditor key={`${recordRow.id}-${recordPage.index}`} record={draft} format={dataset?.format||'instruction'} availableTools={toolGroups.map(group=>group.function)} onChange={changeDraft}/>:<RecordView record={recordPage.record} format={dataset?.format||'instruction'}/>}
   </>}
   {dialog==='rename'&&<TextField autoFocus label="名称" value={name} disabled={pending} onChange={e=>{setName(e.target.value);setDirty(true);}}/>}
   {dialog==='create'&&<><TextField autoFocus label="名称" value={name} disabled={pending} onChange={e=>{setName(e.target.value);setDirty(true);}}/><TextField select label="样本结构" value={format} disabled={pending} onChange={e=>{setFormat(e.target.value);setDirty(true);}}><MenuItem value="instruction">指令与回答</MenuItem><MenuItem value="native_tools">工具调用</MenuItem></TextField></>}
   {dialog==='import'&&<><Button component="label" variant="outlined" disabled={pending}>选择 JSONL 文件<input hidden type="file" accept=".jsonl,.json" onChange={e=>{setUpload(e.target.files?.[0]);setPath('');setDirty(true);}}/></Button>{upload&&<Typography sx={{overflowWrap:'anywhere'}}>{upload.name}</Typography>}</>}
   {dialog==='import'&&!upload&&<Autocomplete freeSolo options={snapshot.assets.filter(a=>a.kind==='dataset').map(a=>a.path)} inputValue={path} disabled={pending} onInputChange={(_,value)=>{setPath(value);setDirty(true);}} renderInput={params=><TextField {...params} autoFocus label="JSONL 文件路径"/>}/>}
   {(dialog==='edit'||dialog==='import')&&<TextField select label="数据划分" value={split} disabled={pending} onChange={e=>{setSplit(e.target.value);setDirty(true);}}>{Object.entries(splits).map(([key,label])=><MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField>}
   {dialog==='edit'&&<RecordEditor record={draft} format={dataset?.format||'instruction'} availableTools={toolGroups.map(group=>group.function)} onChange={changeDraft}/>}
   {dialog==='delete'&&<Typography>将永久删除此文件及其中的样本。已有训练快照不受影响。</Typography>}
   {dialog==='archive'&&<Typography>不再用于新任务，文件会保留，可随时恢复。</Typography>}
   </Box>
  </Stack></DialogContent>{!discard&&(dialog==='record'?<DialogActions>{editing&&<Button disabled={pending} onClick={backToView}>返回</Button>}<Button disabled={pending} onClick={close}>关闭</Button>{!editing&&<Button variant="contained" disabled={blocked||showArchived} onClick={()=>{setDraft(recordPage?.record||{});setDraftValid(true);setDirty(false);setSaved(false);setEditing(true);}}>编辑</Button>}{editing&&<Button variant="contained" disabled={blocked||!draftValid} onClick={submit}>{pending?'处理中…':'保存'}</Button>}</DialogActions>:<DialogActions><Button disabled={pending} onClick={close}>取消</Button><Button variant="contained" disabled={blocked||(dialog==='create'&&!name.trim())||(dialog==='import'&&!path&&!upload)||(dialog==='edit'&&!draftValid)} color={dialog==='delete'?'error':'primary'} onClick={submit}>{pending?'处理中…':dialog==='delete'?'永久删除':dialog==='import'?'导入':'保存'}</Button></DialogActions>)}</Dialog>
  {dataset&&data&&<BulkToolsDialog key={`${selected}-${bulkOpen}`} snapshot={snapshot} datasetId={selected} revision={data.revision} open={bulkOpen} onClose={()=>setBulkOpen(false)} onComplete={async()=>{await onRefresh();await load();if(recordRow&&recordPage){const refreshed=await action(snapshot,`datasets/${selected}/record`,{sample:recordRow.id,index:recordPage.index});setRecordPage(refreshed);setDraft(refreshed.record);setDirty(false);}}}/>}
  {dataset&&data&&<BulkToolsDialog key={`${selected}-create-${createOpen}`} snapshot={snapshot} datasetId={selected} revision={data.revision} open={createOpen} create onClose={()=>setCreateOpen(false)} onComplete={async()=>{await onRefresh();await load();}}/>}
 </Stack>;
}
