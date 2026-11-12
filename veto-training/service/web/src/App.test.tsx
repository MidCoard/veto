import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import App from './App';
import {RecordEditor, RecordView} from './SampleRecord';
import { fields, type Snapshot } from './api';
const settings=Object.fromEntries(fields.map(f=>[f.key,f.min===undefined?'local/path':String(f.min || 1)]));settings['prompt.format']='native_tools';settings['learning.rate']='0.0001';settings['lora.dropout']='0.05';
let state:Snapshot;
const response=(body:unknown,ok=true)=>Promise.resolve({ok,json:async()=>body} as Response);
const setOutput=()=>fireEvent.change(screen.getByRole('textbox',{name:'输出目录'}),{target:{value:'D:/test-output'}});
beforeEach(()=>{window.history.replaceState(null,'','/');state={revision:1,csrf:'token',busy:false,stopping:false,backendUrl:'http://localhost:5177',profiles:[{id:'secret',name:'Secret',enabled:true,settings},{id:'core',name:'Core',enabled:false,settings}],runs:[],assets:[],datasets:[{id:'secret-data',name:'Secret samples',format:'native_tools',revision:2},{id:'core-data',name:'Core samples',format:'instruction',revision:3}],templates:[{id:'secret',name:'Secret',settings},{id:'screening',name:'Core 意图识别模型',settings:{...settings,'prompt.format':'instruction','sequence.length':'1024'}}]};vi.stubGlobal('fetch',vi.fn(()=>response(state)));});
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
test('shows configured tasks without obsolete notices',async()=>{render(<App/>);await screen.findByText('Secret');expect(screen.queryByText('Core')).not.toBeInTheDocument();expect(screen.queryByText('本机管理')).not.toBeInTheDocument();expect(screen.getByRole('button',{name:'删除'})).toBeEnabled();});
test('refresh preserves expanded parameters',async()=>{render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'数据与参数'}));expect(screen.getByRole('button',{name:'数据与参数'})).toHaveAttribute('aria-expanded','true');fireEvent.click(screen.getByRole('button',{name:'刷新'}));await waitFor(()=>expect(screen.getByRole('button',{name:'刷新'})).toBeEnabled());expect(screen.getByRole('button',{name:'数据与参数'})).toHaveAttribute('aria-expanded','true');});
test('live preparation and training progress rerender when revision does not change',async()=>{
 state.runs=[{id:'run',name:'Secret run',profile:'secret',status:'running',updated:'2026-11-09T00:00:00Z',message:'准备中',output:'D:/output',progress:{prepare_step:5,prepare_total:10},checkpoints:[]}];
 render(<App/>);expect(await screen.findByText('5 / 10 条 · 整体 10%')).toBeVisible();
 state={...state,runs:[{...state.runs[0],status:'running',progress:{current_step:1,completed_step:0,max_steps:10,epoch:1,effective_batch:8,loss:1}}]};
 fireEvent.click(screen.getByRole('button',{name:'刷新'}));
 expect(await screen.findByText('0 / 10 步 · 整体 10%')).toBeVisible();expect(screen.getByText('第 1 轮 · 有效 batch 8')).toBeVisible();
 state={...state,runs:[{...state.runs[0],progress:{completed_step:2,max_steps:10,epoch:1.2,effective_batch:8,loss:0.9}}]};
 fireEvent.click(screen.getByRole('button',{name:'刷新'}));
 expect(await screen.findByText('2 / 10 步 · 整体 26%')).toBeVisible();
});

test('export has a separate stage and only completion shows overall 100 percent',async()=>{
 state.runs=[{id:'run',name:'Secret run',profile:'secret',status:'exporting',updated:'2026-11-09T00:00:00Z',message:'导出中',output:'D:/output',progress:{completed_step:4,max_steps:4,epoch:1,effective_batch:1,export_step:5,export_total:10},checkpoints:[]}];
 render(<App/>);expect(await screen.findByText('5 / 10 张量 · 整体 95%')).toBeVisible();expect(screen.getByText('训练已完成，正在合并与导出')).toBeVisible();
 state={...state,runs:[{...state.runs[0],status:'completed'}]};
 fireEvent.click(screen.getByRole('button',{name:'刷新'}));
 expect(await screen.findByText('全部完成')).toBeVisible();expect(screen.getByText('100%')).toBeVisible();
});

test('cancel start never sends a mutation',async()=>{render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'开始训练'}));const dialog=screen.getByRole('dialog');expect(within(dialog).getByText('Secret')).toBeVisible();fireEvent.click(within(dialog).getByRole('button',{name:'取消'}));expect(vi.mocked(fetch).mock.calls.every(([,options])=>!options?.method)).toBe(true);});
test('confirmed removal calls the task endpoint and refreshes',async()=>{vi.mocked(fetch).mockImplementation((_,options)=>{if(options?.method==='POST'){state={...state,revision:2,profiles:[]};return response({accepted:true});}return response(state);});render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'删除'}));fireEvent.click(within(screen.getByRole('dialog')).getByRole('button',{name:'删除'}));await screen.findByText('暂无任务');const call=vi.mocked(fetch).mock.calls.find(([,options])=>options?.method==='POST');expect(call?.[0]).toBe('/api/profiles/secret/remove');expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({revision:1,confirmed:true});});
test('empty name is validated without posting or losing form input',async()=>{render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'创建任务'}));fireEvent.click(screen.getByRole('button',{name:'保存任务'}));expect(await screen.findByText('请输入任务名称')).toBeVisible();expect(vi.mocked(fetch).mock.calls.every(([,options])=>!options?.method)).toBe(true);});
test('failed operation stays in the confirmation dialog',async()=>{vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({error:'状态已变化'},false):response(state));render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'删除'}));fireEvent.click(within(screen.getByRole('dialog')).getByRole('button',{name:'删除'}));expect(await screen.findByText('状态已变化')).toBeVisible();expect(screen.getByRole('dialog')).toBeVisible();});

test('create saves a recipe without starting training',async()=>{vi.mocked(fetch).mockImplementation((_,options)=>{if(options?.method==='POST'){const body=JSON.parse(options.body as string);state={...state,revision:2,profiles:[...state.profiles,{id:'custom-new',name:body.name,enabled:true,settings:body.settings}]};return response({accepted:true});}return response(state);});render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'创建任务'}));fireEvent.change(screen.getByRole('textbox',{name:'任务名称'}),{target:{value:'New task'}});setOutput();fireEvent.click(screen.getByRole('button',{name:'保存任务'}));await screen.findByText('New task');expect(vi.mocked(fetch).mock.calls.filter(([,options])=>options?.method==='POST').map(([url])=>url)).toEqual(['/api/profiles/create']);});

test('Add Model registers existing local content through the shared source dialog',async()=>{window.history.replaceState(null,'','/?page=models');vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'添加模型'}));fireEvent.mouseDown(screen.getByRole('combobox',{name:'来源'}));fireEvent.click(screen.getByRole('option',{name:'本地已有模型'}));fireEvent.change(screen.getByRole('textbox',{name:'名称'}),{target:{value:'Base'}});fireEvent.change(screen.getByRole('textbox',{name:'模型目录或 GGUF 文件'}),{target:{value:'D:/models/base'}});fireEvent.click(screen.getByRole('button',{name:'登记'}));await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());expect(vi.mocked(fetch).mock.calls.filter(([,options])=>options?.method==='POST').map(([url])=>url)).toEqual(['/api/assets/create']);});

test('Core template changes the recipe and saves without training',async()=>{vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'创建任务'}));fireEvent.mouseDown(screen.getByRole('combobox',{name:'训练模板'}));fireEvent.click(screen.getByRole('option',{name:'Core 意图识别模型'}));expect(screen.getByRole('textbox',{name:'任务名称'})).toHaveValue('Core 意图识别模型');setOutput();fireEvent.click(screen.getByRole('button',{name:'保存任务'}));await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());const posts=vi.mocked(fetch).mock.calls.filter(([,options])=>options?.method==='POST');expect(posts).toHaveLength(1);expect(JSON.parse(posts[0][1]?.body as string)).toMatchObject({template:'screening',settings:{'prompt.format':'instruction','sequence.length':'1024'}});});

test('all task types show counts before a configuration check',async()=>{state.profiles[0].counts={'training.data':10000,'validation.data':899};state.profiles.push({id:'screening',name:'Core task',enabled:true,settings,counts:{'training.data':960,'validation.data':480}});render(<App/>);expect(await screen.findByText('训练集 10,000 条 · 验证集 899 条')).toBeVisible();expect(screen.getByText('训练集 960 条 · 验证集 480 条')).toBeVisible();expect(screen.queryByText('配置检查通过')).not.toBeInTheDocument();});


test('manual dataset creates a sample only after save',async()=>{
 window.history.replaceState(null,'','/?page=datasets');
 state.datasets=[{id:'core',name:'Core samples',format:'instruction',revision:0}];
 vi.mocked(fetch).mockImplementation((url,options)=>options?.method==='POST'?response(String(url).endsWith('/browse')?{rows:[],total:0,counts:{train:0,validation:0,test:0},revision:0}:{accepted:true}):response(state));
 render(<App/>);await screen.findByRole('button',{name:'新建数据集'});
 fireEvent.mouseDown(screen.getByRole('combobox',{name:'数据集'}));fireEvent.click(await screen.findByRole('option',{name:'Core samples'}));
 await waitFor(()=>expect(screen.getByRole('button',{name:'新增样本'})).toBeEnabled());
 fireEvent.click(screen.getByRole('button',{name:'新增样本'}));
 fireEvent.change(screen.getByRole('textbox',{name:'指令'}),{target:{value:'manual'}});
 fireEvent.change(screen.getByRole('textbox',{name:'回答'}),{target:{value:'safe'}});
 expect(vi.mocked(fetch).mock.calls.some(([url])=>String(url).endsWith('/save'))).toBe(false);
 fireEvent.click(within(screen.getByRole('dialog')).getByRole('button',{name:'保存'}));
 await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
 const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url).endsWith('/save'));
 expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({datasetRevision:0,split:'train',record:{instruction:'manual',output:'safe'}});
});

test('start validates automatically and keeps failure in the dialog',async()=>{vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({error:'模型缺少权重文件'},false):response(state));render(<App/>);await screen.findByText('Secret');expect(screen.queryByRole('button',{name:'检查配置'})).not.toBeInTheDocument();fireEvent.click(screen.getByRole('button',{name:'开始训练'}));fireEvent.click(within(screen.getByRole('dialog')).getByRole('button',{name:'开始训练'}));expect(await screen.findByText('模型缺少权重文件')).toBeVisible();});
test('Add Model submits a Hugging Face source through the same dialog',async()=>{window.history.replaceState(null,'','/?page=models');vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'添加模型'}));expect(screen.getByRole('combobox',{name:'来源'})).toHaveTextContent('Hugging Face');fireEvent.change(screen.getByRole('textbox',{name:'模型仓库'}),{target:{value:'org/model'}});fireEvent.click(screen.getByRole('button',{name:'下载'}));await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url)==='/api/downloads/create');expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({repo:'org/model',revisionName:'main'});});

test('failed export retries without showing an empty checkpoint section or internal run id',async()=>{
 state.runs=[{id:'internal-run-id',name:'Export task',profile:'secret',status:'failed',updated:new Date().toISOString(),output:'D:/output',progress:{},checkpoints:[],exportReady:true}];
 vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'重试导出'}));
 const dialog=screen.getByRole('dialog');expect(within(dialog).getByText('Export task')).toBeVisible();expect(within(dialog).queryByText(/internal-run-id/)).not.toBeInTheDocument();
 fireEvent.click(within(dialog).getByRole('button',{name:'重试导出'}));
 await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
 const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url)==='/api/runs/internal-run-id/export');
 expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({confirmed:true});
 expect(screen.queryByText('暂无保存点')).not.toBeInTheDocument();
});

test('create selects a registered model by name instead of entering its directory',async()=>{
 state.assets=[{id:'tiny',kind:'model',name:'Tiny model',path:'D:/models/tiny',format:'huggingface',available:true,trainable:true,bytes:null,tasks:[],runs:[]},{id:'gguf',kind:'model',name:'Inference only',path:'D:/models/output.gguf',format:'gguf',available:true,trainable:false,bytes:10,tasks:[],runs:[]}];
 vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'创建任务'}));
 expect(screen.queryByRole('textbox',{name:'模型目录'})).not.toBeInTheDocument();
 fireEvent.change(screen.getByRole('textbox',{name:'任务名称'}),{target:{value:'Registered model task'}});
 fireEvent.mouseDown(screen.getByRole('combobox',{name:'训练模型'}));
 fireEvent.click(screen.getByRole('option',{name:'Tiny model'}));
 expect(screen.queryByRole('option',{name:'Inference only'})).not.toBeInTheDocument();
 setOutput();
 fireEvent.click(screen.getByRole('button',{name:'保存任务'}));
 await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
 const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url)==='/api/profiles/create');
 expect(JSON.parse(call?.[1]?.body as string).settings['base.model']).toBe('D:/models/tiny');
});

test('changing templates preserves a user-entered task name',async()=>{
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'创建任务'}));
 fireEvent.change(screen.getByRole('textbox',{name:'任务名称'}),{target:{value:'My own task'}});
 fireEvent.mouseDown(screen.getByRole('combobox',{name:'训练模板'}));
 fireEvent.click(screen.getByRole('option',{name:'Core 意图识别模型'}));
 expect(screen.getByRole('textbox',{name:'任务名称'})).toHaveValue('My own task');
});

test('editing restores the managed dataset that owns the frozen snapshots',async()=>{
 state.datasets=[{id:'managed',name:'Managed samples',format:'instruction',revision:4}];
 state.profiles[0].settings={...settings,'training.data':'D:/work/training-datasets/managed/exports/4-abcd/train.jsonl','validation.data':'D:/work/training-datasets/managed/exports/4-abcd/validation.jsonl'};
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'编辑'}));
 const dialog=screen.getByRole('dialog');
 expect(within(dialog).getByRole('combobox',{name:'训练数据集'})).toHaveTextContent('Managed samples');
 expect(within(dialog).queryByRole('textbox',{name:'训练集（JSONL）'})).not.toBeInTheDocument();
 expect(within(dialog).queryByRole('textbox',{name:'验证集（JSONL）'})).not.toBeInTheDocument();
});

test('new tasks default to a compatible managed dataset and hide redundant paths and format',async()=>{
 state.datasets=[{id:'secret-data',name:'Secret samples',format:'native_tools',revision:2},{id:'core-data',name:'Core samples',format:'instruction',revision:3}];
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'创建任务'}));
 expect(screen.getByRole('combobox',{name:'训练数据集'})).toHaveTextContent('Secret samples');
 expect(screen.queryByRole('textbox',{name:'训练集（JSONL）'})).not.toBeInTheDocument();
 expect(screen.queryByRole('combobox',{name:'数据格式'})).not.toBeInTheDocument();
 fireEvent.mouseDown(screen.getByRole('combobox',{name:'训练模板'}));fireEvent.click(screen.getByRole('option',{name:'Core 意图识别模型'}));
 expect(screen.getByRole('combobox',{name:'训练数据集'})).toHaveTextContent('Core samples');
 fireEvent.mouseDown(screen.getByRole('combobox',{name:'训练数据集'}));
 expect(screen.queryByRole('option',{name:'使用 JSONL 文件'})).not.toBeInTheDocument();
 expect(screen.queryByRole('combobox',{name:'训练集（JSONL）'})).not.toBeInTheDocument();
 expect(screen.queryByRole('combobox',{name:'验证集（JSONL）'})).not.toBeInTheDocument();
});

test('saving a managed dataset enforces its format instead of a stale recipe value',async()=>{
 state.datasets=[{id:'managed',name:'Managed samples',format:'instruction',revision:4}];
 state.profiles[0].settings={...settings,'prompt.format':'native_tools','training.data':'D:/work/training-datasets/managed/exports/4-abcd/train.jsonl','validation.data':'D:/work/training-datasets/managed/exports/4-abcd/validation.jsonl'};
 vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'编辑'}));
 expect(screen.queryByRole('combobox',{name:'数据格式'})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'保存任务'}));
 await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
 const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url)==='/api/profiles/secret/update');
 expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({dataset:'managed',datasetRevision:4,settings:{'prompt.format':'instruction'}});
});

test('tasks require Dataset management and offer no JSONL source fallback',async()=>{
 state.datasets=[];
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'创建任务'}));
 fireEvent.change(screen.getByRole('textbox',{name:'任务名称'}),{target:{value:'Needs dataset'}});
 fireEvent.click(screen.getByRole('button',{name:'保存任务'}));
 expect(await screen.findByText('请选择数据集')).toBeVisible();
 expect(screen.queryByText('使用 JSONL 文件')).not.toBeInTheDocument();
 expect(screen.queryByRole('combobox',{name:'训练集（JSONL）'})).not.toBeInTheDocument();
 expect(vi.mocked(fetch).mock.calls.every(([,options])=>!options?.method)).toBe(true);
});

test('dataset records navigate, edit and retain file-level enable/disable',async()=>{
 window.history.replaceState(null,'','/?page=datasets');
 state.datasets=[{id:'core',name:'Core samples',format:'instruction',revision:1}];
 vi.mocked(fetch).mockImplementation((url,options)=>{
  if(options?.method!=='POST')return response(state);
  if(String(url).endsWith('/browse'))return response({rows:[{id:'file',name:'input.jsonl',split:'train',enabled:true,records:12}],total:1,counts:{train:12,validation:0,test:0},disabled:0,revision:1});
  if(String(url).endsWith('/record')){const index=JSON.parse(options.body as string).index;return response({name:'input.jsonl',index,total:12,record:{instruction:index?'second':'hello',output:'safe'}});}
  return response({accepted:true});
 });
 render(<App/>);fireEvent.mouseDown(await screen.findByRole('combobox',{name:'数据集'}));fireEvent.click(await screen.findByRole('option',{name:'Core samples'}));
 expect(await screen.findByText('input.jsonl')).toBeVisible();
 expect(screen.queryByRole('button',{name:'生成训练快照'})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'回收站'})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'样本'}));
 expect(await screen.findByRole('spinbutton',{name:'样本序号'})).toHaveValue(1);expect(screen.getByText('hello')).toBeVisible();expect(screen.queryByRole('button',{name:'跳转'})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'下一条'}));
 await waitFor(()=>expect(screen.getByRole('spinbutton',{name:'样本序号'})).toHaveValue(2));expect(screen.getByText('second')).toBeVisible();
 fireEvent.change(screen.getByRole('spinbutton',{name:'样本序号'}),{target:{value:'12'}});
 fireEvent.keyDown(screen.getByRole('spinbutton',{name:'样本序号'}),{key:'Enter'});
 await waitFor(()=>expect(screen.getByRole('spinbutton',{name:'样本序号'})).toHaveValue(12));expect(screen.getByRole('button',{name:'下一条'})).toBeDisabled();
 fireEvent.click(screen.getByRole('button',{name:'编辑'}));
 fireEvent.change(screen.getByRole('textbox',{name:'指令'}),{target:{value:'edited'}});
 fireEvent.click(screen.getByRole('button',{name:'保存'}));
 await waitFor(()=>{const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url).endsWith('/update_record'));expect(call).toBeDefined();expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({sample:'file',index:11,record:{instruction:'edited',output:'safe'}});});
 expect(await screen.findByText('已保存到 input.jsonl')).toBeVisible();expect(screen.getByRole('dialog',{name:'样本'})).toBeVisible();expect(screen.getByRole('spinbutton',{name:'样本序号'})).toHaveValue(12);
 fireEvent.click(screen.getByRole('button',{name:'关闭'}));
 fireEvent.click(await screen.findByRole('button',{name:'停用'}));
 await waitFor(()=>{const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url).endsWith('/toggle'));expect(call).toBeDefined();expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({sample:'file',enabled:false});});
});

test('bound tasks warn but do not block permanent model removal',async()=>{
 window.history.replaceState(null,'','/?page=models');
 state.assets=[{id:'bound',kind:'model',name:'Bound model',path:'D:/models/bound',format:'huggingface',available:true,trainable:true,bytes:null,tasks:['Bound task'],runs:[]}];
 vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'移除模型'}));
 const dialog=screen.getByRole('dialog');expect(within(dialog).getByText(/Bound task/)).toBeVisible();
 fireEvent.click(within(dialog).getByRole('button',{name:'永久移除模型'}));
 await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
 const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url)==='/api/assets/bound/remove');
 expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({confirmed:true});
});

test('native tool arguments use syntax preview and structured JSON editing',async()=>{
 window.history.replaceState(null,'','/?page=datasets');
 state.datasets=[{id:'secret',name:'Tool samples',format:'native_tools',revision:1}];
 const record={messages:[{role:'assistant',content:'### Working\n\n**done**',tool_calls:[{id:'call',type:'function',function:{name:'view_file',arguments:{path:'README.md'}}}]}],tool_refs:['view_file']};
 vi.mocked(fetch).mockImplementation((url,options)=>{
  if(options?.method!=='POST')return response(state);
  if(String(url).endsWith('/browse'))return response({rows:[{id:'file',name:'tools.jsonl',split:'train',enabled:true,records:1}],total:1,counts:{train:1,validation:0,test:0},disabled:0,revision:1});
  if(String(url).endsWith('/record'))return response({name:'tools.jsonl',index:0,total:1,record});
  if(String(url).endsWith('/tool_groups'))return response({groups:[{signature:'view',function:{name:'view_file'},count:1},{signature:'import',function:{name:'import_credential'},count:0}],activeJob:null});
  return response({accepted:true});
 });
 render(<App/>);fireEvent.mouseDown(await screen.findByRole('combobox',{name:'数据集'}));fireEvent.click(await screen.findByRole('option',{name:'Tool samples'}));
 fireEvent.click(await screen.findByRole('button',{name:'样本'}));
 expect((await screen.findAllByLabelText('JSON 语法预览'))[0]).toHaveTextContent('README.md');expect(screen.getByText('工具引用')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'编辑'}));
 fireEvent.click(screen.getByRole('button',{name:'返回'}));expect(screen.getByRole('dialog',{name:'样本'})).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'编辑'}));
 const argument=screen.getByRole('textbox',{name:'工具参数（JSON 对象）'});
 fireEvent.change(argument,{target:{value:'{'}});expect(screen.getByRole('button',{name:'保存'})).toBeDisabled();
 fireEvent.change(argument,{target:{value:'{"path":"DESIGN.md"}'}});expect(screen.getByRole('button',{name:'保存'})).toBeEnabled();
 fireEvent.click(screen.getByRole('button',{name:'返回'}));
 fireEvent.click(screen.getByRole('button',{name:'放弃并返回查看'}));expect(screen.getByRole('dialog',{name:'样本'})).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'编辑'}));
 fireEvent.change(screen.getByRole('textbox',{name:'工具参数（JSON 对象）'}),{target:{value:'{"path":"DESIGN.md"}'}});
 fireEvent.mouseDown(screen.getByRole('combobox',{name:'添加工具引用'}));fireEvent.click(screen.getByRole('option',{name:'import_credential'}));
 fireEvent.click(screen.getByRole('button',{name:'添加工具调用'}));
 const callNames=screen.getAllByRole('textbox',{name:'工具调用名称'});fireEvent.change(callNames[1],{target:{value:'import_credential'}});
 fireEvent.click(screen.getByRole('button',{name:'保存'}));
 await waitFor(()=>{const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url).endsWith('/update_record'));const body=JSON.parse(call?.[1]?.body as string);expect(body.record.messages[0].tool_calls).toHaveLength(2);expect(body.record.tool_refs).toEqual(['view_file','import_credential']);expect(body.record.tools).toBeUndefined();expect(body.record.messages[0].tool_calls[0].function.arguments).toEqual({path:'DESIGN.md'});});
},90000);

test('removing the last call omits tool_calls and keeps empty assistant content',()=>{
 const onChange=vi.fn();
 const record={messages:[{role:'assistant',content:'',tool_calls:[{id:'call',type:'function',function:{name:'view_file',arguments:{}}}]}],tool_refs:['view_file']};
 render(<RecordEditor format="native_tools" record={record} onChange={onChange}/>);
 const call=screen.getByRole('textbox',{name:'工具调用名称'}).closest('.MuiPaper-root') as HTMLElement;
 fireEvent.click(within(call).getByRole('button',{name:'删除'}));
 expect(onChange).toHaveBeenLastCalledWith({messages:[{role:'assistant',content:''}],tool_refs:['view_file']},true);
});

test('editor role badge uses the reader badge and opens role choices',()=>{
 const onChange=vi.fn();const record={messages:[{role:'assistant',content:''}],tool_refs:[]};
 render(<RecordEditor format="native_tools" record={record} onChange={onChange}/>);
 fireEvent.click(screen.getByRole('button',{name:'修改消息 1 角色'}));
 fireEvent.click(screen.getByRole('menuitem',{name:'user'}));
 expect(onChange).toHaveBeenLastCalledWith({messages:[{role:'user',content:''}],tool_refs:[]},true);
});

test('task configures effective batch and fixed or dynamic training policy',async()=>{
 vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'创建任务'}));
 fireEvent.change(screen.getByRole('textbox',{name:'任务名称'}),{target:{value:'Policy'}});setOutput();
 fireEvent.click(screen.getByRole('button',{name:'展开'}));
 fireEvent.change(screen.getByRole('spinbutton',{name:'单批样本数'}),{target:{value:'2'}});
 fireEvent.change(screen.getByRole('spinbutton',{name:'梯度累积次数'}),{target:{value:'4'}});
 expect(screen.getByText('有效 batch：8 条样本（2 × 4）')).toBeVisible();
 fireEvent.change(screen.getByRole('spinbutton',{name:'最大训练轮数（Epoch）'}),{target:{value:'10'}});
 fireEvent.change(screen.getByRole('spinbutton',{name:'连续无改善轮数'}),{target:{value:'3'}});
 fireEvent.mouseDown(screen.getByRole('combobox',{name:'训练策略'}));fireEvent.click(screen.getByRole('option',{name:'固定轮数'}));
 expect(screen.queryByRole('spinbutton',{name:'连续无改善轮数'})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'保存任务'}));
 await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
 const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url)==='/api/profiles/create');
 const saved=JSON.parse(call?.[1]?.body as string).settings;
 expect(saved).toMatchObject({epochs:'10','batch.size':'2','accumulation.steps':'4','early.stopping.patience':'0'});
});

test('task saves the user-configured output directory in its recipe',async()=>{
 vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));
 render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'创建任务'}));
 fireEvent.change(screen.getByRole('textbox',{name:'任务名称'}),{target:{value:'Custom output'}});
 fireEvent.change(screen.getByRole('textbox',{name:'输出目录'}),{target:{value:'D:/chosen/training-output'}});
 expect(screen.queryByText('每轮运行会创建独立子目录')).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'保存任务'}));
 await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
 const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url)==='/api/profiles/create');
 expect(JSON.parse(call?.[1]?.body as string).settings['output.directory']).toBe('D:/chosen/training-output');
});


test('record prose renders Markdown safely with GFM and fenced code',()=>{
 const instruction='## Safety rules\n\n**important**\n\n- first item\n\n| Field | Value |\n| --- | --- |\n| mode | safe |\n\n```json\n{"ok":true}\n```';
 const output='[safe link](https://example.com)\n\n<script>alert(1)</script>\n\n![remote](https://example.com/private.png)';
 const {container}=render(<RecordView format="instruction" record={{instruction,output}}/>);
 expect(screen.getByRole('heading',{name:'Safety rules'})).toBeVisible();
 expect(screen.getByText('important').tagName).toBe('STRONG');
 expect(screen.getByRole('listitem')).toHaveTextContent('first item');
 expect(screen.getByRole('table')).toBeVisible();expect(screen.getByRole('columnheader',{name:'Field'})).toBeVisible();
 expect(container.querySelector('pre code')).toHaveTextContent('{"ok":true}');
 expect(screen.getByRole('link',{name:'safe link'})).toHaveAttribute('rel','noopener noreferrer');
 expect(screen.queryByText('alert(1)')).not.toBeInTheDocument();
 expect(container.querySelector('img')).not.toBeInTheDocument();expect(screen.getByText('[图片：remote]')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'完整 JSON'}));
 expect(screen.getByLabelText('JSON 语法预览')).toHaveTextContent('## Safety rules');expect(screen.queryByRole('heading',{name:'Safety rules'})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'阅读'}));expect(screen.getByRole('heading',{name:'Safety rules'})).toBeVisible();
});


test('role messages have independent views, standard empty calls and collapsed previews',()=>{
 const record={messages:[{role:'system',content:'## System heading'},{role:'user',content:'**literal**'},{role:'assistant',content:''}],tool_refs:[]};
 render(<RecordView format="native_tools" record={record}/>);
 expect(screen.getByRole('heading',{name:'System heading'})).toBeVisible();
 expect(screen.getByLabelText('原始内容')).toHaveTextContent('**literal**');expect(screen.queryByText('literal')).not.toBeInTheDocument();
 expect(screen.getByText('无需调用工具')).toBeVisible();expect(screen.queryByText('此消息不包含工具调用。')).not.toBeInTheDocument();
 const userDetails=screen.getByLabelText('原始内容').closest('.MuiPaper-root') as HTMLElement;
 expect(userDetails).toHaveTextContent('"role": "user"');
 fireEvent.click(within(userDetails).getByRole('button',{name:'阅读'}));expect(screen.getByText('literal').tagName).toBe('STRONG');
 const assistantDetails=screen.getByText('assistant').closest('.MuiPaper-root') as HTMLElement;
 fireEvent.click(within(assistantDetails).getByRole('button',{name:'原文'}));expect(screen.getByLabelText('原始内容')).toHaveTextContent('"content": ""');expect(screen.getByLabelText('原始内容')).not.toHaveTextContent('tool_calls');
 fireEvent.click(screen.getByRole('button',{name:'展开或收起消息 1'}));expect(screen.getByText('## System heading')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'展开或收起消息 2'}));expect(screen.getByText('**literal**')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'展开或收起消息 3'}));expect(screen.getByText('无需调用工具')).toBeVisible();
});


test('users can add a dataset-level tool definition for later references',async()=>{
 window.history.replaceState(null,'','/?page=datasets');
 state.datasets=[{id:'secret',name:'Tool samples',format:'native_tools',revision:1}];
 let groups:{signature:string;function:{name:string;description?:string};count:number}[]=[];let datasetRevision=1;
 vi.mocked(fetch).mockImplementation((url,options)=>{
  if(options?.method!=='POST')return response(state);
  if(String(url).endsWith('/browse'))return response({rows:[],total:0,counts:{train:0,validation:0,test:0},disabled:0,revision:datasetRevision});
  if(String(url).endsWith('/tool_groups'))return response({groups,activeJob:null});
  if(String(url).endsWith('/add_tool')){const body=JSON.parse(options.body as string);datasetRevision=2;groups=[{signature:'new',function:body.function,count:0}];return response({revision:2});}
  return response({accepted:true});
 });
 render(<App/>);fireEvent.mouseDown(await screen.findByRole('combobox',{name:'数据集'}));fireEvent.click(await screen.findByRole('option',{name:'Tool samples'}));
 fireEvent.click(await screen.findByRole('button',{name:'添加定义'}));
 const dialog=await screen.findByRole('dialog',{name:'新建工具定义'});
 fireEvent.change(within(dialog).getByRole('textbox',{name:'工具名称'}),{target:{value:'read_credential'}});
 fireEvent.change(within(dialog).getByRole('textbox',{name:'工具说明'}),{target:{value:'Read a credential reference'}});
 fireEvent.click(within(dialog).getByRole('button',{name:'添加定义'}));
 expect(await screen.findByText('已添加工具定义，现在可以在样本中引用。')).toBeVisible();
 const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url).endsWith('/add_tool'));
 expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({datasetRevision:1,function:{name:'read_credential',description:'Read a credential reference'}});
 expect(await screen.findByText('工具定义')).toBeVisible();
});

test('shared tool definitions stay at dataset level and update asynchronously',async()=>{
 window.history.replaceState(null,'','/?page=datasets');
 state.datasets=[{id:'secret',name:'Tool samples',format:'native_tools',revision:1}];
 const fn={name:'import_credential',description:'old',parameters:{type:'object'}};
 let datasetRevision=1;
 vi.mocked(fetch).mockImplementation((url,options)=>{
  if(options?.method!=='POST')return response(state);
  if(String(url).endsWith('/browse'))return response({rows:[{id:'file',name:'tools.jsonl',split:'train',enabled:true,records:2}],total:1,counts:{train:2,validation:0,test:0},disabled:0,revision:datasetRevision});
  if(String(url).endsWith('/tool_groups'))return response({groups:[{signature:'same',function:fn,count:2}],activeJob:null});
  if(String(url).endsWith('/record'))return response({name:'tools.jsonl',index:0,total:2,record:{messages:[{role:'assistant',content:''}],tool_refs:['import_credential']}});
  if(String(url).endsWith('/bulk_tools')){datasetRevision=2;return response({id:'job',status:'running',processed:0,total:1,updated:0});}
  if(String(url).endsWith('/bulk_status'))return response({id:'job',status:'completed',processed:1,total:1,updated:1});
  return response({accepted:true});
 });
 render(<App/>);fireEvent.mouseDown(await screen.findByRole('combobox',{name:'数据集'}));fireEvent.click(await screen.findByRole('option',{name:'Tool samples'}));
 expect(await screen.findByText('工具定义')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'样本'}));
 const recordDialog=await screen.findByRole('dialog',{name:'样本'});expect(within(recordDialog).queryByText('工具定义')).not.toBeInTheDocument();expect(within(recordDialog).getByText('工具引用')).toBeVisible();expect(within(recordDialog).getByText('import_credential')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'编辑'}));expect(screen.getByRole('button',{name:'返回'})).toBeVisible();expect(screen.getByRole('button',{name:'修改消息 1 角色'})).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'返回'}));fireEvent.click(screen.getByRole('button',{name:'关闭'}));
 await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
 fireEvent.click(screen.getByRole('button',{name:'编辑定义'}));
 expect(await screen.findByRole('dialog',{name:'编辑共享工具定义'})).toBeVisible();expect(screen.queryByRole('combobox',{name:'相同定义分组'})).not.toBeInTheDocument();
 fireEvent.change(screen.getByRole('textbox',{name:'工具说明'}),{target:{value:'concise'}});
 fireEvent.click(screen.getByRole('button',{name:'保存定义'}));
 expect(await screen.findByText('已更新 1 个工具定义。')).toBeVisible();
 const call=vi.mocked(fetch).mock.calls.find(([url])=>String(url).endsWith('/bulk_tools'));
 expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({datasetRevision:1,signature:'same',function:{name:'import_credential',description:'concise'}});
});


test('tool references and calls retain reading and raw views',()=>{
 const record={
  messages:[{role:'assistant',content:'',tool_calls:[{id:'call',type:'function',function:{name:'import_credential',arguments:{value:'abc'}}}]}],
  tool_refs:['import_credential']
 };
 render(<RecordView format="native_tools" record={record}/>);
 expect(screen.getByText('工具引用')).toBeVisible();expect(screen.getByText('import_credential')).toBeVisible();
 const callPaper=screen.getByText('调用 import_credential').closest('.MuiPaper-root') as HTMLElement;
 fireEvent.click(within(callPaper).getByRole('button',{name:'原文'}));expect(within(callPaper).getByLabelText('原始内容')).toHaveTextContent('import_credential');
 fireEvent.click(screen.getByRole('button',{name:'完整 JSON'}));expect(screen.getByLabelText('JSON 语法预览')).toHaveTextContent('tool_refs');expect(screen.getByLabelText('JSON 语法预览')).not.toHaveTextContent('"tools"');
});
