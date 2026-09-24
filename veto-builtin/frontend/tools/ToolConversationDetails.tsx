import React from './runtime';
import { toolFieldLabel, toolValueLabel } from './toolLabels';
import { useI18n } from './runtime';

// Explicitly curated against the native tool Args records, including reader-agent tools.
export const conversationToolFields: Record<string, string[]> = {
  run_command: ['connect', 'network', 'timeout'], run_task: ['network', 'timeout'],
  view_task: ['taskId'], stop_task: ['taskId'], input_task: ['taskId', 'content', 'appendNewline', 'closeStdin'],
  view_file: ['absolutePath', 'startLine', 'endLine'], list_dir: ['absolutePath'],
  find_files: ['absolutePath', 'pattern'], grep_search: ['absolutePath', 'query', 'caseInsensitive', 'includes'],
  move_path: ['sourceAbsolutePath', 'destinationAbsolutePath'], delete_path: ['absolutePath', 'recursive'],
  write_to_file: ['overwrite'], replace_file_content: ['startLine', 'endLine'],
  web_search: ['query', 'allowed_domains', 'blocked_domains'], web_fetch: [], web_read: [],
  fetch_page: [], find_sections: ['query'], read_sections: ['ids'],
  finish_read: ['outcome', 'answer', 'evidenceIds', 'limitations'],
  load_skill: ['skillName'], think: [],
  recall_memory: ['query'], write_memory: ['mode', 'content', 'promoteMemoryId', 'projectId'], forget_memory: ['memoryId'],
  create_group: ['task'], disband_group: [], inspect_group: ['sinceSeq', 'waitSeconds'],
  create_mate: ['name', 'responsibility'], create_task: ['taskId', 'description', 'mateId', 'dependsOn'],
  create_monitor: ['purpose', 'afterSeconds', 'at'],
  create_node: ['nodeId', 'description', 'skillset', 'dependsOn'], remove_node: ['nodeId'],
  post_message: ['type', 'receiver', 'payload'],
};

// Only identity/target fields belong in the header. Objectives and content stay in the body.
const headerFields: Record<string, string[]> = {
  view_file: ['absolutePath'], list_dir: ['absolutePath'], find_files: ['absolutePath'], grep_search: ['absolutePath'],
  write_to_file: ['absolutePath'], replace_file_content: ['absolutePath'], delete_path: ['absolutePath'],
  web_fetch: ['url'], web_read: ['url'], web_search: ['query'], find_sections: ['query'], recall_memory: ['query'],
  view_task: ['taskId'], stop_task: ['taskId'], input_task: ['taskId'], load_skill: ['skillName'],
  create_task: ['taskId'], create_mate: ['name'], create_node: ['nodeId'], remove_node: ['nodeId'], forget_memory: ['memoryId'], post_message: ['receiver'],
};
export function toolHeaderField(toolName: string, args: Record<string, unknown>): string | undefined {
  const candidates = headerFields[toolName] ?? (Object.prototype.hasOwnProperty.call(conversationToolFields, toolName) ? [] : ['url', 'absolutePath', 'path', 'file_path']);
  return candidates.find(key => typeof args[key] === 'string' && args[key] !== '');
}
const narrativeFields = new Set(['query', 'content', 'task', 'description', 'payload', 'answer', 'limitations']);

const clip = (value: string) => {
  const preview = value.split('\n').slice(0, 8).join('\n').slice(0, 1000);
  return preview.length < value.length ? `${preview}\n…` : preview;
};
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const meaningful = (value: unknown) => value !== undefined && value !== null && value !== '' && (!Array.isArray(value) || value.length > 0);

export default function ToolConversationDetails({ toolName, args = {}, headerField, result }: { toolName: string; args?: Record<string, unknown>; headerField?: string; result?: { text: string; success?: boolean } }) {
  const { t } = useI18n();
  const label = (key: string) => toolFieldLabel(t, key);
  const valueText = (value: unknown): string => typeof value === 'boolean' ? t(value ? 'tool.value.yes' : 'tool.value.no')
    : Array.isArray(value) ? value.filter(v => ['string', 'number'].includes(typeof v)).slice(0, 20).join(', ')
    : ['string', 'number'].includes(typeof value) ? String(value) : '';
  const known = Object.prototype.hasOwnProperty.call(conversationToolFields, toolName);
  const fields = (known ? conversationToolFields[toolName] : Object.keys(args).slice(0, 6)).filter(key => key !== headerField && meaningful(args[key]) && valueText(args[key]) !== '');
  const prose = fields.filter(key => narrativeFields.has(key));
  const metadata = fields.filter(key => !narrativeFields.has(key) && !(toolName === 'move_path' && ['sourceAbsolutePath', 'destinationAbsolutePath'].includes(key)));
  const commandTool = toolName === 'run_command' || toolName === 'run_task';
  const commands = commandTool && Array.isArray(args.commands) ? args.commands.filter(object).slice(0, 8) : [];
  const emptyLabel = toolName === 'think' ? 'tool.thinking'
    : toolName === 'fetch_page' ? 'tool.summary.fetchPage'
    : toolName === 'disband_group' ? 'tool.summary.disband'
    : toolName === 'view_task' && !args.taskId ? 'tool.summary.allTasks'
    : toolName === 'inspect_group' && fields.length === 0 ? 'tool.summary.group'
    : commandTool && commands.length === 0 ? 'tool.summary.missingCommand' : null;
  if (!known && Object.values(args).some(value => typeof value === 'object' && value !== null)) return <div className="p-3 text-xs"><pre className="whitespace-pre-wrap break-words">{JSON.stringify(args, null, 2)}</pre>{result?.success === true && <pre className="mt-2 whitespace-pre-wrap break-words">{result.text}</pre>}</div>;
  if (fields.length === 0 && commands.length === 0 && emptyLabel === null) return null;
  return <div className="space-y-3 border-t border-rule/60 px-3 py-3 text-xs">
    {commands.length > 0 && <div className="space-y-1 rounded-md border border-rule/60 bg-ink px-3 py-2 font-mono text-paper">
      {commands.map((command, index) => <div key={index} className="flex gap-2">
        <span aria-hidden="true" className="select-none text-dim">$</span>
        <pre className="min-w-0 whitespace-pre-wrap break-words font-mono text-xs">{clip([command.executable, ...(Array.isArray(command.args) ? command.args : [])].filter(v => typeof v === 'string').map(v => /\s/.test(v as string) ? JSON.stringify(v) : v).join(' '))}</pre>
      </div>)}
      {Array.isArray(args.commands) && args.commands.length > 8 && <span className="text-dim">…</span>}
    </div>}
    {toolName === 'move_path' && <div className="space-y-1 rounded-md border border-rule/60 bg-ink/50 px-3 py-2">
      {['sourceAbsolutePath', 'destinationAbsolutePath'].filter(key => meaningful(args[key])).map((key, index) => <div key={key} className="flex items-start gap-2">
        <span className="shrink-0 text-dim">{index === 1 ? '→ ' : ''}{label(key)}</span>
        <span className="min-w-0 break-all font-mono">{valueText(args[key])}</span>
      </div>)}
    </div>}
    {prose.map(key => <section key={key} className="space-y-1.5">
      <h4 className="text-[10px] text-dim">{label(key)}</h4>
      {key === 'content' && toolName === 'input_task'
        ? <pre className="rounded-md bg-ink px-3 py-2 whitespace-pre-wrap break-words font-mono">{clip(valueText(args[key]))}</pre>
        : <p className="whitespace-pre-wrap break-words leading-relaxed text-paper">{clip(valueText(args[key]))}</p>}
    </section>)}
    {metadata.length > 0 && <dl className="flex flex-wrap gap-2">
      {metadata.map(key => <div key={key} className="flex min-w-0 max-w-full flex-wrap items-baseline gap-x-2 rounded-md border border-rule/60 bg-raised/30 px-2 py-1">
        <dt className="text-dim">{known ? label(key) : label(key) === key ? key.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ') : label(key)}</dt>
        <dd className="min-w-0 whitespace-pre-wrap break-words">{clip(toolValueLabel(t, key, valueText(args[key])))}</dd>
      </div>)}
    </dl>}
    {emptyLabel && <p className="text-dim">{t(emptyLabel)}</p>}
  </div>;
}
