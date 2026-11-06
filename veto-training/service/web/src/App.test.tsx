import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import App from './App';
import { fields, type Snapshot } from './api';
const settings=Object.fromEntries(fields.map(f=>[f.key,f.min===undefined?'local/path':String(f.min || 1)]));settings['prompt.format']='native_tools';settings['learning.rate']='0.0001';settings['lora.dropout']='0.05';
let state:Snapshot;
const response=(body:unknown,ok=true)=>Promise.resolve({ok,json:async()=>body} as Response);
beforeEach(()=>{window.history.replaceState(null,'','/');state={revision:1,csrf:'token',busy:false,stopping:false,backendUrl:'http://localhost:5177',profiles:[{id:'secret',name:'Secret',enabled:true,settings},{id:'core',name:'Core',enabled:false,settings}],runs:[],assets:[],datasets:[{id:'secret-data',name:'Secret samples',format:'native_tools',revision:2},{id:'core-data',name:'Core samples',format:'instruction',revision:3}],templates:[{id:'secret',name:'Secret',settings},{id:'screening',name:'Core 意图识别模型',settings:{...settings,'prompt.format':'instruction','sequence.length':'1024'}}]};vi.stubGlobal('fetch',vi.fn(()=>response(state)));});
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
test('shows configured tasks without obsolete notices',async()=>{render(<App/>);await screen.findByText('Secret');expect(screen.queryByText('Core')).not.toBeInTheDocument();expect(screen.queryByText('本机管理')).not.toBeInTheDocument();expect(screen.getByRole('button',{name:'删除'})).toBeEnabled();});
test('refresh preserves expanded parameters',async()=>{render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'数据与参数'}));expect(screen.getByRole('button',{name:'数据与参数'})).toHaveAttribute('aria-expanded','true');fireEvent.click(screen.getByRole('button',{name:'刷新'}));await waitFor(()=>expect(screen.getByRole('button',{name:'刷新'})).toBeEnabled());expect(screen.getByRole('button',{name:'数据与参数'})).toHaveAttribute('aria-expanded','true');});
test('cancel start never sends a mutation',async()=>{render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'开始训练'}));const dialog=screen.getByRole('dialog');expect(within(dialog).getByText('Secret')).toBeVisible();fireEvent.click(within(dialog).getByRole('button',{name:'取消'}));expect(vi.mocked(fetch).mock.calls.every(([,options])=>!options?.method)).toBe(true);});
test('confirmed removal calls the task endpoint and refreshes',async()=>{vi.mocked(fetch).mockImplementation((_,options)=>{if(options?.method==='POST'){state={...state,revision:2,profiles:[]};return response({accepted:true});}return response(state);});render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'删除'}));fireEvent.click(within(screen.getByRole('dialog')).getByRole('button',{name:'删除'}));await screen.findByText('暂无任务');const call=vi.mocked(fetch).mock.calls.find(([,options])=>options?.method==='POST');expect(call?.[0]).toBe('/api/profiles/secret/remove');expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({revision:1,confirmed:true});});
test('empty name is validated without posting or losing form input',async()=>{render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'创建任务'}));fireEvent.click(screen.getByRole('button',{name:'保存任务'}));expect(await screen.findByText('请输入任务名称')).toBeVisible();expect(vi.mocked(fetch).mock.calls.every(([,options])=>!options?.method)).toBe(true);});
test('failed operation stays in the confirmation dialog',async()=>{vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({error:'状态已变化'},false):response(state));render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'删除'}));fireEvent.click(within(screen.getByRole('dialog')).getByRole('button',{name:'删除'}));expect(await screen.findByText('状态已变化')).toBeVisible();expect(screen.getByRole('dialog')).toBeVisible();});

test('create saves a recipe without starting training',async()=>{vi.mocked(fetch).mockImplementation((_,options)=>{if(options?.method==='POST'){const body=JSON.parse(options.body as string);state={...state,revision:2,profiles:[...state.profiles,{id:'custom-new',name:body.name,enabled:true,settings:body.settings}]};return response({accepted:true});}return response(state);});render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'创建任务'}));fireEvent.change(screen.getByRole('textbox',{name:'任务名称'}),{target:{value:'New task'}});fireEvent.click(screen.getByRole('button',{name:'保存任务'}));await screen.findByText('New task');expect(vi.mocked(fetch).mock.calls.filter(([,options])=>options?.method==='POST').map(([url])=>url)).toEqual(['/api/profiles/create']);});

test('Add Model registers existing local content through the shared source dialog',async()=>{window.history.replaceState(null,'','/?page=models');vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));render(<App/>);fireEvent.click(await screen.findByRole('button',{name:'添加模型'}));fireEvent.mouseDown(screen.getByRole('combobox',{name:'来源'}));fireEvent.click(screen.getByRole('option',{name:'本地已有模型'}));fireEvent.change(screen.getByRole('textbox',{name:'名称'}),{target:{value:'Base'}});fireEvent.change(screen.getByRole('textbox',{name:'模型目录或 GGUF 文件'}),{target:{value:'D:/models/base'}});fireEvent.click(screen.getByRole('button',{name:'登记'}));await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());expect(vi.mocked(fetch).mock.calls.filter(([,options])=>options?.method==='POST').map(([url])=>url)).toEqual(['/api/assets/create']);});

test('Core template changes the recipe and saves without training',async()=>{vi.mocked(fetch).mockImplementation((_,options)=>options?.method==='POST'?response({accepted:true}):response(state));render(<App/>);await screen.findByText('Secret');fireEvent.click(screen.getByRole('button',{name:'创建任务'}));fireEvent.mouseDown(screen.getByRole('combobox',{name:'训练模板'}));fireEvent.click(screen.getByRole('option',{name:'Core 意图识别模型'}));expect(screen.getByRole('textbox',{name:'任务名称'})).toHaveValue('Core 意图识别模型');fireEvent.click(screen.getByRole('button',{name:'保存任务'}));await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());const posts=vi.mocked(fetch).mock.calls.filter(([,options])=>options?.method==='POST');expect(posts).toHaveLength(1);expect(JSON.parse(posts[0][1]?.body as string)).toMatchObject({template:'screening',settings:{'prompt.format':'instruction','sequence.length':'1024'}});});

test('all task types show counts before a configuration check',async()=>{state.profiles[0].counts={'training.data':10000,'validation.data':899};state.profiles.push({id:'screening',name:'Core task',enabled:true,settings,counts:{'training.data':960,'validation.data':480}});render(<App/>);expect(await screen.findByText('训练集 10,000 条 · 验证集 899 条')).toBeVisible();expect(screen.getByText('训练集 960 条 · 验证集 480 条')).toBeVisible();expect(screen.queryByText('配置检查通过')).not.toBeInTheDocument();});


test('manual dataset creates a sample only after save',async()=>{
 window.history.replaceState(null,'','/?page=datasets');
 state.datasets=[{id:'core',name:'Core samples',format:'instruction',revision:0}];
 vi.mocked(fetch).mockImplementation((url,options)=>options?.method==='POST'?response(String(url).endsWith('/browse')?{rows:[],total:0,counts:{train:0,validation:0,test:0},revision:0}:{accepted:true}):response(state));
 render(<App/>);await screen.findByRole('button',{name:'新建数据集'});
 fireEvent.mouseDown(screen.getByRole('combobox',{name:'数据集'}));fireEvent.click(await screen.findByRole('option',{name:'Core samples'}));
 await waitFor(()=>expect(screen.getByRole('button',{name:'新增样本'})).toBeEnabled());
 fireEvent.click(screen.getByRole('button',{name:'新增样本'}));
 fireEvent.change(screen.getByRole('textbox',{name:'样本 JSON'}),{target:{value:JSON.stringify({instruction:'manual',output:'safe'})}});
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

test('dataset files support preview and enable/disable without snapshot or recycle-bin actions',async()=>{
 window.history.replaceState(null,'','/?page=datasets');
 state.datasets=[{id:'core',name:'Core samples',format:'instruction',revision:1}];
 vi.mocked(fetch).mockImplementation((url,options)=>{
  if(options?.method!=='POST')return response(state);
  if(String(url).endsWith('/browse'))return response({rows:[{id:'file',name:'input.jsonl',split:'train',enabled:true,records:12}],total:1,counts:{train:12,validation:0,test:0},disabled:0,revision:1});
  if(String(url).endsWith('/preview'))return response({name:'input.jsonl',records:[{instruction:'hello',output:'safe'}]});
  return response({accepted:true});
 });
 render(<App/>);fireEvent.mouseDown(await screen.findByRole('combobox',{name:'数据集'}));fireEvent.click(await screen.findByRole('option',{name:'Core samples'}));
 expect(await screen.findByText('input.jsonl')).toBeVisible();
 expect(screen.queryByRole('button',{name:'生成训练快照'})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'回收站'})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'预览'}));
 expect(await screen.findByText('文件预览')).toBeVisible();expect(screen.getByText(/hello/)).toBeVisible();
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
