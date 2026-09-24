import React, { configure, LocaleScope, useI18n } from './runtime';
import { ToolCallCard, ToolResultBody } from './ToolCards';
import ToolConversationDetails, { toolHeaderField } from './ToolConversationDetails';
import type { FrontendHost, ToolPresentationProps } from '../../../veto-api/frontend';
import css from './style.css';
export const tools = ['answer_with_citations','ask_user','cancel_group_task','cancel_monitor','create_group','create_mate','create_monitor','create_task','delete_path','disband_group','find_files','forget_memory','grep_search','input_task','inspect_group','inspect_monitor','list_dir','load_skill','move_path','pause_monitor','post_message','read_github_repository','recall_memory','remove_mate','remove_node','replace_file_content','resume_monitor','run_command','run_task','stop_task','submit_plan','view_file','view_task','web_fetch','web_search','write_memory','write_to_file'];
export function activate(host: FrontendHost) {
 configure(host);
 for (const toolName of tools) {
  const wrap=(View: typeof ToolCallCard | typeof ToolResultBody | typeof Conversation) => (props: ToolPresentationProps) => <LocaleScope locale={props.context.locale}><div className="veto-tools"><style>{css}</style><View {...props} text={props.text??''} toolName={toolName}/></div></LocaleScope>;
  host.registerToolRenderer(toolName, { call: wrap(ToolCallCard), result: wrap(ToolResultBody), conversation: wrap(Conversation), headerTarget: args => {const key=toolHeaderField(toolName,args);return key&&typeof args[key]==='string'?String(args[key]):undefined;} });
 }
}
function Conversation({toolName='',args={},result}: ToolPresentationProps) {
 return <div className="min-w-0"><ToolConversationDetails toolName={toolName} args={args} headerField={toolHeaderField(toolName,args)} result={result}/>
 {['web_fetch','web_read'].includes(toolName)&&typeof args.objective==='string'&&<p className="p-3 whitespace-pre-wrap break-words">{args.objective}</p>}
 {['codeContent','targetContent','replacementContent'].filter(key=>typeof args[key]==='string').map(key=><ContentPreview key={key} field={key} content={String(args[key])}/>)}

 </div>;
}

function ContentPreview({field,content}:{field:string;content:string}) {
 const {t}=useI18n();const excerpt=content.split('\n').slice(0,8).join('\n').slice(0,1000);
 return <div className="min-w-0 p-3"><p className="mb-1 text-[10px] text-dim">{t(field==='targetContent'?'tool.replaceBefore':field==='replacementContent'?'tool.replaceAfter':'tool.contentPreview')}</p><pre className="whitespace-pre-wrap break-words rounded-md border-l-2 border-pass/50 bg-ink/60 px-3 py-2 font-mono text-xs text-paper">{excerpt}{excerpt.length<content.length?'\n…':''}</pre></div>;
}
export {Fields} from './ToolDetailViews';
export {LocaleScope} from './runtime';
