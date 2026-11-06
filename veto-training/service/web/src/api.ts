export type Settings = Record<string, string>;
export type Profile = {id:string;name:string;enabled:boolean;purpose?:string;counts?:Record<string,number|null>;settings:Settings};
export type Download = {id:string;repo:string;status:string;downloaded:number;total:number;message?:string};
export type Run = {name?:string;archived?:boolean;id:string;profile:string;status:string;updated:string;message?:string;output:string;progress:Record<string,number>;checkpoints:string[];artifacts?:string[];exportReady?:boolean};
export type Asset = {id:string;kind:'model'|'dataset';name:string;path:string;format:string;available:boolean;trainable:boolean;bytes:number|null;tasks:string[];runs:string[]};
export type Dataset = {archived?:boolean;id:string;name:string;format:string;revision:number};
export type Snapshot = {downloads?:Download[];datasets?:Dataset[];revision:number;csrf:string;busy:boolean;stopping:boolean;backendUrl:string;profiles:Profile[];runs:Run[];assets:Asset[];templates:{id:string;name:string;settings:Settings}[]};
export const active = new Set(['starting','running','pause_pending','pausing','exporting']);
export const status:Record<string,string> = {starting:'加载中',running:'训练中',pause_pending:'等待暂停',pausing:'正在保存',paused:'已暂停',completed:'已导出',failed:'失败',interrupted:'已中断',exporting:'导出中'};
export const formats = {native_tools:'工具调用',instruction:'指令与回答',raw:'原始提示词与回答'};
export const fields: {key:string;label:string;min?:number;max?:number;step?:number|string}[] = [
 {key:'base.model',label:'训练模型'}, {key:'training.data',label:'训练集（JSONL）'}, {key:'validation.data',label:'验证集（JSONL）'},
 {key:'prompt.format',label:'数据格式'}, {key:'epochs',label:'训练轮数',min:1,max:100},
 {key:'learning.rate',label:'学习率',min:0.000000001,max:0.999999999,step:'any'},
 {key:'batch.size',label:'批大小',min:1,max:128}, {key:'accumulation.steps',label:'梯度累积',min:1,max:1024},
 {key:'sequence.length',label:'序列长度',min:32,max:65536}, {key:'lora.rank',label:'LoRA Rank',min:1,max:512},
 {key:'lora.alpha',label:'LoRA Alpha',min:1,max:1024}, {key:'lora.dropout',label:'LoRA Dropout',min:0,max:0.999999999,step:'any'},
 {key:'checkpoint.steps',label:'保存间隔（步）',min:1,max:100000}
];
export class ApiError extends Error { constructor(message:string,public field?:string) { super(message); } }
export async function getSnapshot(signal?:AbortSignal):Promise<Snapshot> {
 const response=await fetch('/api/state',{signal});
 if(!response.ok) throw new Error('无法读取训练状态');
 return response.json();
}
export async function action(snapshot:Snapshot,path:string,values:Record<string,unknown>={}) {
 const response=await fetch(`/api/${path}`,{method:'POST',headers:{'Content-Type':'application/json','X-Veto-CSRF':snapshot.csrf},body:JSON.stringify({revision:snapshot.revision,...values}),signal:AbortSignal.timeout(path.startsWith('datasets/') || path.startsWith('downloads/') || path.startsWith('profiles/') ? 180000 : 15000)});
 const body=await response.json();
 if(!response.ok) throw new ApiError(body.error || '操作失败',body.field);
 return body;
}
