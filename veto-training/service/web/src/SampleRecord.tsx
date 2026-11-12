import {useState, type ReactNode} from 'react';
import MarkdownText from './MarkdownText';
import {Box, Button, Chip, Menu, MenuItem, Paper, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography, useTheme} from '@mui/material';

export type Sample=Record<string,unknown>;
type Message={role?:unknown;content?:unknown;tool_calls?:unknown;[key:string]:unknown};
type Change=(value:Sample,valid:boolean)=>void;

export function JsonSyntax({value}:{value:unknown}) {
 const theme=useTheme();const dark=theme.palette.mode==='dark';
 const colors={key:theme.palette.primary.main,string:dark?'#7BD88F':'#237804',number:dark?'#F0B35A':'#9A6700',literal:dark?'#C5A3FF':'#8250DF'};
 const source=JSON.stringify(value,null,2);const parts:ReactNode[]=[];let cursor=0;
 const tokens=/("(?:\\.|[^"\\])*")(\s*:)?|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(\btrue\b|\bfalse\b)|(\bnull\b)/g;
 for(let match=tokens.exec(source);match;match=tokens.exec(source)){
  if(match.index>cursor)parts.push(source.slice(cursor,match.index));
  const color=match[2]?colors.key:match[1]?colors.string:match[3]?colors.number:colors.literal;
  parts.push(<Box component="span" key={match.index} sx={{color}}>{match[0]}</Box>);cursor=match.index+match[0].length;
 }
 if(cursor<source.length)parts.push(source.slice(cursor));
 return <Box component="pre" role="group" aria-label="JSON 语法预览" sx={{m:0,p:1.5,borderRadius:1,bgcolor:'background.default',whiteSpace:'pre-wrap',overflowWrap:'anywhere',fontFamily:'monospace',fontSize:12,lineHeight:1.6}}>{parts}</Box>;
}

function ViewSwitch({raw,onChange,full=false}:{raw:boolean;onChange:()=>void;full?:boolean}) {
 return <ToggleButtonGroup size="small" exclusive value={raw?'raw':'rendered'} onChange={(_,value)=>{if(value)onChange();}} sx={{height:30,p:'2px',flexShrink:0,bgcolor:'action.hover',border:0,borderRadius:1.5,'& .MuiToggleButton-root':{px:1.25,minWidth:54,border:0,borderRadius:1,fontSize:12,color:'text.secondary'},'& .MuiToggleButton-root:hover':{bgcolor:'background.paper',color:'text.primary'},'& .MuiToggleButton-root.Mui-selected':{bgcolor:'background.paper',color:'primary.main',boxShadow:'0 1px 3px rgba(15,23,42,.12)'},'& .MuiToggleButton-root.Mui-selected:hover':{bgcolor:'background.paper'}}}><ToggleButton value="rendered">阅读</ToggleButton><ToggleButton value="raw">{full?'完整 JSON':'原文'}</ToggleButton></ToggleButtonGroup>;
}

function RawText({value}:{value:unknown}) {
 return <Box component="pre" role="group" aria-label="原始内容" sx={{m:0,p:0,whiteSpace:'pre-wrap',overflowWrap:'anywhere',fontFamily:'"Cascadia Code", "SFMono-Regular", Consolas, monospace',fontSize:12,lineHeight:1.65}}>{typeof value==='string'?value:JSON.stringify(value,null,2)}</Box>;
}

function RenderedText({value}:{value:unknown}) {
 if(typeof value==='string'){
  const text=value.trim();let json:Sample|unknown[]|undefined;
  if((text.startsWith('{')&&text.endsWith('}'))||(text.startsWith('[')&&text.endsWith(']'))){try{json=JSON.parse(value) as Sample|unknown[];}catch{json=undefined;}}
  return json?<JsonSyntax value={json}/>:<MarkdownText content={value}/>;
 }
 return <JsonSyntax value={value}/>;
}

function TextValue({label,value,initialRaw=false}:{label:string;value:unknown;initialRaw?:boolean}) {
 const [raw,setRaw]=useState(initialRaw);
 return <Paper variant="outlined" sx={{overflow:'hidden'}}><Stack direction="row" sx={{px:1.25,py:0.5,justifyContent:'space-between',alignItems:'center',bgcolor:'action.hover'}}><Typography variant="caption" sx={{fontWeight:600,color:'text.primary'}}>{label}</Typography><ViewSwitch raw={raw} onChange={()=>setRaw(value=>!value)}/></Stack><Box sx={{p:1.5,borderTop:1,borderColor:'divider'}}>{raw?<RawText value={value}/>:<RenderedText value={value}/>}</Box></Paper>;
}

function isEmptyCall(message:Message) {
 return message.role==='assistant'&&(message.content===null||typeof message.content==='string'&&!message.content.trim())&&(!Array.isArray(message.tool_calls)||message.tool_calls.length===0);
}

function roleAccent(role:string,dark:boolean) {
 const palette=dark?{system:'#A5B4C5',user:'#7CB3FF',assistant:'#4FD1C5',tool:'#A3D9A5'}:{system:'#526274',user:'#2563EB',assistant:'#0F7C72',tool:'#39734A'};
 return palette[role as keyof typeof palette]||palette.system;
}

function messagePreview(message:Message) {
 if(isEmptyCall(message))return '无需调用工具';
 const calls=Array.isArray(message.tool_calls)?message.tool_calls:[];
 if(calls.length)return calls.map(call=>{const fn=(call as Sample).function as Sample|undefined;return String(fn?.name||'未知工具');}).join(' · ');
 const text=typeof message.content==='string'?message.content.replace(/\s+/g,' ').trim():'';
 return text||'无正文';
}

function ToolCall({call}:{call:Sample}) {
 const [raw,setRaw]=useState(false);const fn=call.function as Sample|undefined;
 return <Paper variant="outlined" sx={{overflow:'hidden',borderLeft:3,borderLeftColor:'primary.main'}}><Stack direction="row" sx={{px:1.25,py:0.75,justifyContent:'space-between',alignItems:'center',gap:1,bgcolor:'action.hover'}}><Chip size="small" color="primary" label={`调用 ${String(fn?.name||'未知工具')}`}/><ViewSwitch raw={raw} onChange={()=>setRaw(value=>!value)}/></Stack><Box sx={{p:1.25,borderTop:1,borderColor:'divider'}}>{raw?<RawText value={call}/>:<><Typography variant="caption" color="text.secondary" sx={{display:'block',mb:0.75,fontWeight:600}}>参数</Typography><JsonSyntax value={fn?.arguments??{}}/></>}</Box></Paper>;
}

function ToolReferences({record,availableTools=[],onChange}:{record:Sample;availableTools?:Sample[];onChange?:(references:string[])=>void}) {
 const references=Array.isArray(record.tool_refs)?record.tool_refs.filter((name):name is string=>typeof name==='string'):[];
 if(!references.length&&!onChange)return null;
 const available=availableTools.map(tool=>String(tool.name||'')).filter(name=>name&&!references.includes(name));
 return <Stack direction="row" spacing={1} useFlexGap sx={{alignItems:'center',flexWrap:'wrap',minHeight:32}}><Typography variant="caption" color="text.secondary" sx={{fontWeight:650}}>工具引用</Typography>{references.map(name=><Chip key={name} size="small" variant="outlined" color="primary" label={name} onDelete={onChange?()=>onChange(references.filter(value=>value!==name)):undefined}/>) }{onChange&&available.length>0&&<TextField select size="small" value="" onChange={e=>onChange([...references,e.target.value])} slotProps={{htmlInput:{'aria-label':'添加工具引用'}}} sx={{width:120}}><MenuItem value="" disabled>添加引用</MenuItem>{available.map(name=><MenuItem key={name} value={name}>{name}</MenuItem>)}</TextField>}</Stack>;
}

function MessageView({message,index}:{message:Message;index:number}) {
 const theme=useTheme();const dark=theme.palette.mode==='dark';
 const text=typeof message.content==='string'?message.content:'';const long=text.length>240;const role=String(message.role||'');
 const [raw,setRaw]=useState(role==='user');const [expanded,setExpanded]=useState(!long);
 const calls=Array.isArray(message.tool_calls)?message.tool_calls:[];
 const accent=roleAccent(role,dark);
 return <Paper variant="outlined" sx={{overflow:'hidden',borderLeft:3,borderLeftColor:accent,borderColor:dark?`${accent}48`:`${accent}38`}}>
  <Stack direction="row" sx={{pl:1.25,pr:0.75,py:0.75,alignItems:'center',gap:1,minHeight:46,minWidth:0,flexWrap:{xs:'wrap',sm:'nowrap'},bgcolor:dark?`${accent}14`:`${accent}0B`}}>
   <Chip size="small" label={role||'未知角色'} sx={{height:24,px:0.25,bgcolor:`${accent}18`,color:accent,borderColor:`${accent}55`,fontWeight:650,letterSpacing:0.2}} variant="outlined"/>
   {!expanded&&<Typography variant="body2" color="text.secondary" noWrap sx={{minWidth:0,flex:1}}>{messagePreview(message)}</Typography>}
   <Stack direction="row" spacing={0.75} sx={{alignItems:'center',ml:'auto',flexShrink:0}}><ViewSwitch raw={raw} onChange={()=>setRaw(value=>!value)}/><Button onClick={()=>setExpanded(value=>!value)} aria-label={`展开或收起消息 ${index+1}`} aria-expanded={expanded} sx={{minWidth:56,minHeight:30,color:accent,fontWeight:600,'&:hover':{bgcolor:`${accent}18`}}}>{expanded?'收起':'展开'}</Button></Stack>
  </Stack>
  {expanded&&<Box sx={{p:{xs:1.25,sm:1.5},borderTop:1,borderColor:dark?`${accent}35`:'divider'}}>{raw?<RawText value={message}/>:<Stack spacing={1.25}>
   {isEmptyCall(message)?<Box sx={{px:1.25,py:1,borderRadius:1,bgcolor:`${accent}0D`,color:accent}}><Typography variant="body2" sx={{fontWeight:550}}>无需调用工具</Typography></Box>:text?<Box sx={{fontSize:14,lineHeight:1.7}}>{calls.length>0&&<Typography variant="caption" color="text.secondary" sx={{display:'block',mb:0.75,fontWeight:600}}>内容</Typography>}<RenderedText value={message.content}/></Box>:calls.length===0&&<Typography variant="body2" color="text.secondary">无正文</Typography>}
   {calls.map((call,callIndex)=><ToolCall key={callIndex} call={call as Sample}/>)}
  </Stack>}</Box>}
 </Paper>;
}

export function RecordView({record,format}:{record:Sample;format:string}) {
 const [raw,setRaw]=useState(false);
 let content:ReactNode;
 if(format==='instruction')content=<Stack spacing={1.25}><TextValue label="指令" value={record.instruction}/><TextValue label="回答" value={record.output}/></Stack>;
 else {
  const messages=Array.isArray(record.messages)?record.messages as Message[]:[];
  content=<Stack spacing={1.5}>
   <ToolReferences record={record}/>
   {messages.map((message,index)=><MessageView key={index} message={message} index={index}/>)}
  </Stack>;
 }
 return <Stack spacing={1.25}><Box sx={{display:'flex',justifyContent:'flex-end'}}><ViewSwitch full raw={raw} onChange={()=>setRaw(value=>!value)}/></Box>{raw?<JsonSyntax value={record}/>:content}</Stack>;
}

export function RecordEditor({record,format,availableTools=[],onChange}:{record:Sample;format:string;availableTools?:Sample[];onChange:Change}) {
 const [drafts,setDrafts]=useState<Record<string,string>>({});
 const [invalid,setInvalid]=useState<Record<string,boolean>>({});
 const [roleMenu,setRoleMenu]=useState<{index:number;anchor:HTMLElement}>();
 const theme=useTheme();const dark=theme.palette.mode==='dark';
 if(format==='instruction'){
  const update=(key:string,value:string)=>onChange({...record,[key]:value},true);
  return <Stack spacing={1.25}><Paper variant="outlined" sx={{p:1.5}}><TextField autoFocus fullWidth multiline minRows={4} label="指令" value={String(record.instruction??'')} onChange={e=>update('instruction',e.target.value)}/></Paper><Paper variant="outlined" sx={{p:1.5}}><TextField fullWidth multiline minRows={6} label="回答" value={String(record.output??'')} onChange={e=>update('output',e.target.value)}/></Paper></Stack>;
 }
 const messages=Array.isArray(record.messages)?record.messages as Message[]:[];
 const valid=()=>Object.keys(invalid).length===0;
 const updateMessages=(next:Message[])=>onChange({...record,messages:next},valid());
 const updateJson=(key:string,text:string,apply:(value:Sample)=>Sample)=>{
  setDrafts(current=>({...current,[key]:text}));
  try {
   const parsed=JSON.parse(text) as unknown;
   if(!parsed||Array.isArray(parsed)||typeof parsed!=='object')throw new Error();
   const nextInvalid=Object.fromEntries(Object.entries(invalid).filter(([name])=>name!==key));
   setInvalid(nextInvalid);onChange(apply(parsed as Sample),Object.keys(nextInvalid).length===0);
  } catch {
   const nextInvalid={...invalid,[key]:true};setInvalid(nextInvalid);onChange(record,false);
  }
 };
 const jsonProps=(label:string)=>({htmlInput:{spellCheck:false,'aria-label':label},input:{sx:{fontFamily:'"Cascadia Code", "SFMono-Regular", Consolas, monospace',fontSize:12.5,lineHeight:1.6}}});
 return <Stack spacing={1.5}>
  <ToolReferences record={record} availableTools={availableTools} onChange={references=>onChange({...record,tool_refs:references},valid())}/>
  <Stack direction="row" sx={{justifyContent:'space-between',alignItems:'center',minHeight:40,mb:0.5}}><Typography variant="subtitle2" sx={{fontWeight:650}}>消息</Typography><Button variant="outlined" onClick={()=>updateMessages([...messages,{role:'assistant',content:''}])}>添加消息</Button></Stack>
  {messages.map((message,index)=>{
   const calls=Array.isArray(message.tool_calls)?message.tool_calls as Sample[]:[];
   const role=String(message.role||'user');const accent=roleAccent(role,dark);
   const updateCalls=(next:Sample[])=>updateMessages(messages.map((item,i)=>{if(i!==index)return item;const updated={...item};if(next.length)updated.tool_calls=next;else delete updated.tool_calls;return updated;}));
   return <Paper key={index} variant="outlined" sx={{overflow:'hidden',borderLeft:3,borderLeftColor:accent,borderColor:dark?`${accent}48`:`${accent}38`}}>
    <Stack direction="row" spacing={1} sx={{pl:1.25,pr:0.75,py:0.75,alignItems:'center',bgcolor:dark?`${accent}14`:`${accent}0B`}}><Chip clickable size="small" label={role} title="点击修改角色" aria-label={`修改消息 ${index+1} 角色`} onClick={e=>setRoleMenu({index,anchor:e.currentTarget})} sx={{height:24,px:0.25,bgcolor:`${accent}18`,color:accent,borderColor:`${accent}55`,fontWeight:650,letterSpacing:0.2}} variant="outlined"/><Box sx={{flex:1}}/><Button color="error" sx={{flexShrink:0,minWidth:52,whiteSpace:'nowrap'}} onClick={()=>updateMessages(messages.filter((_,i)=>i!==index))}>删除</Button></Stack>
    <Stack spacing={1.25} sx={{p:1.5,borderTop:1,borderColor:dark?`${accent}35`:'divider'}}>
     <TextField fullWidth multiline minRows={2} maxRows={8} placeholder="消息内容" value={typeof message.content==='string'?message.content:''} onChange={e=>updateMessages(messages.map((item,i)=>i===index?{...item,content:e.target.value}:item))} slotProps={{htmlInput:{'aria-label':'消息内容'}}}/>
     {calls.map((call,callIndex)=>{
      const fn=call.function as Sample|undefined;const key=`message-${index}-${callIndex}`;
      const updateSingleCall=(next:Sample)=>updateMessages(messages.map((item,i)=>i===index?{...item,tool_calls:calls.map((entry,c)=>c===callIndex?next:entry)}:item));
      return <Paper key={callIndex} variant="outlined" sx={{overflow:'hidden',borderLeft:3,borderLeftColor:'primary.main'}}>
       <Stack direction="row" spacing={1} sx={{px:1.25,py:0.75,alignItems:'center',bgcolor:'action.hover'}}><TextField fullWidth placeholder="工具调用名称" value={String(fn?.name??'')} onChange={e=>updateSingleCall({...call,function:{...fn,name:e.target.value}})} slotProps={{htmlInput:{'aria-label':'工具调用名称'}}} sx={{'& .MuiInputBase-root':{bgcolor:'background.paper'}}}/><Button color="error" sx={{flexShrink:0,minWidth:52,whiteSpace:'nowrap'}} onClick={()=>updateCalls(calls.filter((_,i)=>i!==callIndex))}>删除</Button></Stack>
       <Box sx={{p:1.25,borderTop:1,borderColor:'divider'}}><Typography variant="caption" color="text.secondary" sx={{display:'block',mb:0.75,fontWeight:600}}>参数</Typography><TextField fullWidth multiline minRows={4} value={drafts[key]??JSON.stringify(fn?.arguments??{},null,2)} error={!!invalid[key]} helperText={invalid[key]?'请输入有效的 JSON 对象':''} onChange={e=>updateJson(key,e.target.value,value=>({...record,messages:messages.map((item,i)=>i===index?{...item,tool_calls:calls.map((entry,c)=>c===callIndex?{...call,function:{...fn,arguments:value}}:entry)}:item)}))} slotProps={jsonProps('工具参数（JSON 对象）')}/></Box>
      </Paper>;
     })}
     {message.role==='assistant'&&<Button variant="outlined" sx={{alignSelf:'flex-start'}} onClick={()=>updateCalls([...calls,{id:`call_${Date.now().toString(36)}_${Math.random().toString(36).slice(2,8)}`,type:'function',function:{name:'',arguments:{}}}])}>添加工具调用</Button>}
    </Stack>
   </Paper>;
  })}
  <Menu anchorEl={roleMenu?.anchor} open={!!roleMenu} onClose={()=>setRoleMenu(undefined)}>{['system','user','assistant','tool'].map(role=><MenuItem key={role} selected={role===messages[roleMenu?.index??-1]?.role} onClick={()=>{if(roleMenu)updateMessages(messages.map((message,index)=>index===roleMenu.index?{...message,role}:message));setRoleMenu(undefined);}}>{role}</MenuItem>)}</Menu>
 </Stack>;
}
