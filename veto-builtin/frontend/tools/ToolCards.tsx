import { DetailCallCard, Fields, StructuredToolResult, WebFetchResult, WebSearchResult } from './ToolDetailViews';
import React from './runtime';
import { useI18n } from './runtime';
import {
  GREP_NO_MATCHES,
  GROUP_TOOLS,
  MEMORY_TOOLS,
  parseCommandOutput,
  parseFileToolStatus,
  parseGrepContent,
  parseGrepSearchArgs,
  parseListDirArgs,
  parseListDirContent,
  parseLoadSkillArgs,
  parseReplaceFileArgs,
  parseRunCommandArgs,
  parseTaskStarted,
  parseTaskStatusArgs,
  parseTaskStatusContent,
  parseTaskStopArgs,
  parseViewFileArgs,
  parseViewFileContent,
  parseWriteFileArgs,
  toolSummary,
  type GrepSearchArgs,
  type ReplaceFileArgs,
  type RunCommandArgs,
  type ViewFileArgs,
  type WriteFileArgs,
} from './toolContent';
import { CodeHighlight } from './runtime';
import { StreamingMarkdown } from './runtime';

/**
 * ToolCards — special-case renderers for the native/agent tool ledger
 * entries (run_command, the file tools, view_file, list_dir, grep_search,
 * load_skill, think, memory + group tools). They reuse the ledger card shell
 * (bg-panel + border-rule, mono header) and the fixed dark code surface so
 * they sit naturally next to the generic renderings in LedgerEntry. The
 * generic JSON card remains for unknown tools.
 */

/** Fixed dark-surface text colors, same pair CodeHighlight's toolbar uses. */
const CODE_TEXT = 'text-[#DDE3EA]';
const CODE_MUTED = 'text-[#7A8694]';

const CardShell: React.FC<{ header: React.ReactNode; children: React.ReactNode }> = ({
  header,
  children,
}) => (
  <div className="bg-panel border border-rule rounded-lg overflow-hidden">
    <div className="px-3 py-1.5 border-b border-rule font-mono text-xs flex items-center gap-2 min-w-0">
      {header}
    </div>
    {children}
  </div>
);

const Chip: React.FC<{ children: React.ReactNode; className?: string; title?: string }> = ({
  children,
  className = '',
  title,
}) => (
  <span
    title={title}
    className={`text-[10px] uppercase tracking-wider rounded px-1.5 py-0.5 border border-rule text-dim shrink-0 ${className}`}
  >
    {children}
  </span>
);

// ---- run_command ----

/** run_command TOOL_CALL: terminal-style card, one `$ …` prompt line per command. */
export const RunCommandCallCard: React.FC<{ command: RunCommandArgs }> = ({ command }) => {
  const { t } = useI18n();
  const showConnect = command.connect !== null && command.connect !== 'STOP_ON_FAILURE';
  return (
    <CardShell
      header={
        <>
          <span className="text-accent shrink-0">run_command</span>
          {showConnect && <Chip>{command.connect}</Chip>}
          {command.timeout !== null && (
            <Chip title={t('tool.timeout')}>
              {command.timeout === 0
                ? t('tool.timeoutNoCap')
                : t('tool.timeoutValue', { s: command.timeout })}
            </Chip>
          )}
        </>
      }
    >
      <div className="code-surface bg-codebg px-3 py-2 font-mono text-xs leading-relaxed max-h-64 overflow-y-auto">
        {command.commands.map((cmd, index) => (
          <div key={index} className="whitespace-pre-wrap break-words">
            <span className={`${CODE_MUTED} select-none`}>$ </span>
            <span className={CODE_TEXT}>{[cmd.executable, ...cmd.args].map(part => /[\s"']/.test(part) || part === '' ? JSON.stringify(part) : part).join(' ')}</span>
          </div>
        ))}
        {command.cwd !== null && (
          <div className={`mt-1 text-[11px] ${CODE_MUTED} whitespace-pre-wrap break-words`}>
            {t('tool.cwd')}: {command.cwd}
          </div>
        )}
      </div>
    </CardShell>
  );
};

/** run_command TOOL_RESPONSE: terminal output; stderr in verdict tone, exit code as a badge. */
export const RunCommandResultView: React.FC<{ text: string }> = ({ text }) => {
  const { t } = useI18n();
  const output = parseCommandOutput(text);
  return (
    <div className="mt-1 code-surface bg-codebg border border-rule rounded-lg p-2 font-mono text-xs max-h-64 overflow-y-auto">
      {output.stdout.length > 0 && (
        <pre className={`whitespace-pre-wrap break-words ${CODE_TEXT}`}>{output.stdout}</pre>
      )}
      {output.stderr !== null && output.stderr.length > 0 && (
        <>
          <div className={`text-[10px] ${CODE_MUTED} select-none`}>[stderr]</div>
          <pre className="whitespace-pre-wrap break-words text-verdict">{output.stderr}</pre>
        </>
      )}
      {output.exitCode !== null && (
        <span
          className={`inline-block mt-1 px-1.5 py-0.5 rounded text-[10px] ${
            output.exitCode === 0 ? 'bg-pass/15 text-pass' : 'bg-verdict/15 text-verdict'
          }`}
        >
          {t('tool.exitCode', { code: output.exitCode })}
        </span>
      )}
    </div>
  );
};

// ---- run_task / view_task / stop_task (background tasks) ----

/** run_task TOOL_CALL: terminal-style card like run_command, flagged as a background task. */
export const RunTaskCallCard: React.FC<{ command: RunCommandArgs }> = ({ command }) => {
  const { t } = useI18n();
  return (
    <CardShell
      header={
        <>
          <span className="text-accent shrink-0">run_task</span>
          <Chip className="text-pass border-pass/40">{t('tool.task')}</Chip>
          {command.timeout !== null && (
            <Chip title={t('tool.timeout')}>
              {command.timeout === 0
                ? t('tool.timeoutNoCap')
                : t('tool.timeoutValue', { s: command.timeout })}
            </Chip>
          )}
        </>
      }
    >
      <div className="code-surface bg-codebg px-3 py-2 font-mono text-xs leading-relaxed max-h-64 overflow-y-auto">
        {command.commands.map((cmd, index) => (
          <div key={index} className="whitespace-pre-wrap break-words">
            <span className={`${CODE_MUTED} select-none`}>$ </span>
            <span className={CODE_TEXT}>{[cmd.executable, ...cmd.args].map(part => /[\s"']/.test(part) || part === '' ? JSON.stringify(part) : part).join(' ')}</span>
          </div>
        ))}
        {command.cwd !== null && (
          <div className={`mt-1 text-[11px] ${CODE_MUTED} whitespace-pre-wrap break-words`}>
            {t('tool.cwd')}: {command.cwd}
          </div>
        )}
      </div>
    </CardShell>
  );
};

/** run_task TOOL_RESPONSE: a started-task chip (taskId + pid). */
export const TaskStartedView: React.FC<{ text: string }> = ({ text }) => {
  const { t } = useI18n();
  const started = parseTaskStarted(text);
  if (started === null) return <PlainResultBody text={text} />;
  return (
    <div className="mt-1 flex flex-wrap items-center gap-2">
      <span className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md bg-pass/10 border border-pass/30 text-xs text-pass">
        <span className="w-1.5 h-1.5 rounded-full bg-pass animate-pulse" />
        {t('tool.taskStarted')}
      </span>
      <span className="font-mono text-xs text-paper">{started.taskId}</span>
      {started.pid !== null && <Chip>pid {started.pid}</Chip>}
      {started.command !== null && <pre className="w-full whitespace-pre-wrap break-words text-xs font-mono">{started.command}</pre>}
      {started.cwd !== null && <p className="w-full text-xs text-dim break-all">{t('tool.cwd')}: {started.cwd}</p>}
    </div>
  );
};

/** view_task / stop_task TOOL_CALL: a taskId chip (or "all tasks" when taskId omitted). */
export const TaskRefCallCard: React.FC<{ toolName: string; taskId: string | null }> = ({
  toolName,
  taskId,
}) => (
  <CardShell
    header={
      <>
        <span className="text-accent shrink-0">{toolName}</span>
        <Chip className="normal-case">{taskId !== null ? taskId : '· all ·'}</Chip>
      </>
    }
  >
    {null}
  </CardShell>
);

/** view_task / stop_task TOOL_RESPONSE: alive/exit status + recent output (when present). */
export const TaskStatusResultView: React.FC<{ text: string }> = ({ text }) => {
  const { t } = useI18n();
  const status = parseTaskStatusContent(text);
  if (status === null) return <PlainResultBody text={text} />;
  if ('count' in status) {
    return <div className="space-y-2"><p className="text-xs font-mono text-dim">{t('tool.taskCount', { count: status.count })}</p>
      {status.tasks.map(task => <TaskStatusResultView key={task.taskId} text={JSON.stringify(task)} />)}</div>;
  }
  return (
    <div className="mt-1 space-y-1">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs text-paper">{status.taskId}</span>
        {status.alive ? (
          <span className="inline-flex items-center gap-1.5 text-xs text-pass">
            <span className="w-1.5 h-1.5 rounded-full bg-pass animate-pulse" />
            {t('tool.taskAlive')}
          </span>
        ) : (
          <Chip>{t('tool.taskExit', { code: status.exitCode ?? -1 })}</Chip>
        )}
      </div>
      <Fields values={Object.fromEntries(Object.entries(status).filter(([key]) => !['taskId', 'alive', 'exitCode', 'recentOutput'].includes(key)))} />
      {status.recentOutput !== null && status.recentOutput.length > 0 && (
        <pre className={`code-surface bg-codebg border border-rule rounded-lg p-2 font-mono text-xs whitespace-pre-wrap break-words max-h-48 overflow-y-auto ${CODE_TEXT}`}>
          {status.recentOutput}
        </pre>
      )}
    </div>
  );
};

// ---- write_to_file / replace_file_content ----

/** write_to_file TOOL_CALL: path header (+ overwrite badge) over a scrollable code block. */
export const WriteFileCallCard: React.FC<{ args: WriteFileArgs }> = ({ args }) => {
  const { t } = useI18n();
  return (
    <CardShell
      header={
        <>
          <span className="text-accent shrink-0">write_to_file</span>
          <span className="text-paper truncate">{args.absolutePath}</span>
          {args.overwrite && <Chip>{t('tool.overwrite')}</Chip>}
        </>
      }
    >
      <div className="max-h-96 overflow-y-auto">
        <CodeHighlight code={args.codeContent} language="text" showLineNumbers={false} />
      </div>
    </CardShell>
  );
};

/** replace_file_content TOOL_CALL: path header over stacked old (red) / new (green) blocks. */
export const ReplaceFileCallCard: React.FC<{ args: ReplaceFileArgs }> = ({ args }) => {
  const { t } = useI18n();
  return (
    <CardShell
      header={
        <>
          <span className="text-accent shrink-0">replace_file_content</span>
          <span className="text-paper truncate">{args.absolutePath}</span>
        </>
      }
    >
      <div className="p-2 space-y-2">
        <div>
          <div className="text-[10px] uppercase tracking-wider text-dim mb-1">
            {t('tool.replaceBefore')}
          </div>
          <pre className="font-mono text-xs text-paper whitespace-pre-wrap break-words bg-verdict/10 border-l-2 border-verdict rounded-r p-2 max-h-48 overflow-y-auto">
            {args.targetContent}
          </pre>
        </div>
        <div>
          <div className="text-[10px] uppercase tracking-wider text-dim mb-1">
            {t('tool.replaceAfter')}
          </div>
          <pre className="font-mono text-xs text-paper whitespace-pre-wrap break-words bg-pass/10 border-l-2 border-pass rounded-r p-2 max-h-48 overflow-y-auto">
            {args.replacementContent}
          </pre>
        </div>
      </div>
    </CardShell>
  );
};

// ---- view_file ----

/** view_file TOOL_CALL: file path + optional :start-end range chip. */
export const ViewFileCallCard: React.FC<{ args: ViewFileArgs }> = ({ args }) => (
  <CardShell
    header={
      <>
        <span className="text-accent shrink-0">view_file</span>
        <span className="text-paper truncate" title={args.path}>
          {args.path}
        </span>
        {(args.startLine !== null || args.endLine !== null) && (
          <Chip>
            :{args.startLine ?? ''}-{args.endLine ?? ''}
          </Chip>
        )}
      </>
    }
  >
    {null}
  </CardShell>
);

/** view_file TOOL_RESPONSE: code lines with a real line-number gutter. */
export const ViewFileResultView: React.FC<{ text: string }> = ({ text }) => {
  const lines = parseViewFileContent(text);
  if (lines === null) return <PlainResultBody text={text} />;
  const gutterWidth = `${String(lines[lines.length - 1]?.n ?? 1).length + 1}ch`;
  return (
    <div className="code-surface bg-codebg border border-rule rounded-lg px-3 py-2 font-mono text-xs max-h-80 overflow-y-auto">
      {lines.map((line) => (
        <div key={line.n} className="flex whitespace-pre-wrap break-words">
          <span
            className={`${CODE_MUTED} text-right select-none shrink-0 pr-3`}
            style={{ minWidth: gutterWidth }}
          >
            {line.n}
          </span>
          <span className={CODE_TEXT}>{line.text}</span>
        </div>
      ))}
    </div>
  );
};

// ---- list_dir ----

const FolderGlyph: React.FC<{ className?: string }> = ({ className = '' }) => (
  <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
      d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z" />
  </svg>
);

const FileGlyph: React.FC<{ className?: string }> = ({ className = '' }) => (
  <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
      d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
  </svg>
);

/** list_dir TOOL_CALL: folder glyph + directory path. */
export const ListDirCallCard: React.FC<{ path: string }> = ({ path }) => (
  <CardShell
    header={
      <>
        <span className="text-accent shrink-0">list_dir</span>
        <FolderGlyph className="w-3.5 h-3.5 text-dim shrink-0" />
        <span className="text-paper truncate" title={path}>
          {path}
        </span>
      </>
    }
  >
    {null}
  </CardShell>
);

/** list_dir TOOL_RESPONSE: compact listing; directories stand out from files. */
export const ListDirResultView: React.FC<{ text: string }> = ({ text }) => {
  const entries = parseListDirContent(text);
  if (entries === null) return <PlainResultBody text={text} />;
  return (
    <div className="bg-panel border border-rule rounded-lg px-3 py-2 max-h-64 overflow-y-auto">
      {entries.map((entry) => (
        <div key={`${entry.isDir ? 'd' : 'f'}:${entry.name}`} className="flex items-center gap-1.5 py-0.5">
          {entry.isDir ? (
            <FolderGlyph className="w-3.5 h-3.5 text-accent/70 shrink-0" />
          ) : (
            <FileGlyph className="w-3.5 h-3.5 text-dim/60 shrink-0" />
          )}
          <span
            className={`font-mono text-xs truncate ${entry.isDir ? 'text-paper' : 'text-dim'}`}
            title={entry.name}
          >
            {entry.name}
            {entry.isDir && '/'}
          </span>
        </div>
      ))}
    </div>
  );
};

// ---- grep_search ----

/** grep_search TOOL_CALL: quoted query + search path + include/-i chips. */
export const GrepSearchCallCard: React.FC<{ args: GrepSearchArgs }> = ({ args }) => (
  <CardShell
    header={
      <>
        <span className="text-accent shrink-0">grep_search</span>
        <span className="text-paper truncate" title={args.query}>
          &ldquo;{args.query}&rdquo;
        </span>
      </>
    }
  >
    <div className="px-3 py-2 flex flex-wrap items-center gap-1.5">
      <span className="font-mono text-[11px] text-dim truncate" title={args.searchPath}>
        {args.searchPath}
      </span>
      {args.caseInsensitive && <Chip>Aa</Chip>}
      {args.includes.map((glob) => (
        <Chip key={glob}>{glob}</Chip>
      ))}
    </div>
  </CardShell>
);

/** grep_search TOOL_RESPONSE: path:line: text rows; muted empty state. */
export const GrepResultView: React.FC<{ text: string }> = ({ text }) => {
  if (text === GREP_NO_MATCHES) {
    return <p className="text-xs font-mono text-dim/70 italic">{GREP_NO_MATCHES}</p>;
  }
  const rows = parseGrepContent(text);
  if (rows === null) return <PlainResultBody text={text} />;
  return (
    <div className="bg-panel border border-rule rounded-lg px-3 py-2 max-h-80 overflow-y-auto">
      {rows.map((row, index) => (
        <div key={index} className="font-mono text-xs whitespace-pre-wrap break-words py-0.5">
          <span className="text-dim">{row.path}</span>
          <span className="text-accent">:{row.line}</span>
          <span className="text-paper/80">: {row.text}</span>
        </div>
      ))}
    </div>
  );
};

// ---- load_skill ----

/** load_skill TOOL_CALL: skill name chip. */
export const LoadSkillCallCard: React.FC<{ skillName: string }> = ({ skillName }) => (
  <CardShell
    header={
      <>
        <span className="text-accent shrink-0">load_skill</span>
        <Chip className="normal-case">{skillName}</Chip>
      </>
    }
  >
    {null}
  </CardShell>
);

/** load_skill TOOL_RESPONSE: the skill body as markdown. */
export const LoadSkillResultView: React.FC<{ text: string }> = ({ text }) => {
  if (text.startsWith('{')) return <PlainResultBody text={text} />;
  return (
    <div className="bg-panel border border-rule rounded-lg px-3 py-2 max-h-96 overflow-y-auto">
      <StreamingMarkdown content={text} isStreaming={false} />
    </div>
  );
};

// ---- think / memory / group tools ----

/** think TOOL_CALL: a bare dim row — no card chrome, its result is empty. */
export const ThinkRow: React.FC = () => {
  const { t } = useI18n();
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-dim italic py-1">
      <span className="w-1.5 h-1.5 rounded-full bg-dim/50" />
      {t('tool.thinking')}
    </span>
  );
};

/** Memory tools render as compact one-line rows. */
export const MemoryToolRow: React.FC<{
  toolName: string;
  args: Record<string, unknown> | undefined;
}> = ({ toolName, args }) => {
  const summary = toolSummary(toolName, args);
  return (
    <div className="flex items-center gap-2 py-1 min-w-0">
      <svg className="w-3 h-3 text-dim shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
          d="M12 3l7 9-7 9-7-9 7-9z" />
      </svg>
      <span className="font-mono text-xs text-dim shrink-0">{toolName}</span>
      {summary !== '' && (
        <span className="text-xs text-paper/70 truncate" title={summary}>
          {summary}
        </span>
      )}
    </div>
  );
};

/** Group/DAG tools (create_group / create_node / …): delegation-style card. */
export const DelegationCallCard: React.FC<{
  toolName: string;
  args: Record<string, unknown> | undefined;
}> = ({ toolName, args }) => {
  const summary = toolSummary(toolName, args);
  return (
    <CardShell
      header={
        <>
          <span className="text-accent shrink-0">{toolName}</span>
          {summary !== '' && (
            <span className="text-paper/80 truncate" title={summary}>
              {summary}
            </span>
          )}
        </>
      }
    >
      {null}
    </CardShell>
  );
};

/** Compact path-centric cards for find/move/delete, with raw JSON fallback kept in the dispatcher. */
const PathOperationCallCard: React.FC<{
  toolName: string;
  primary: string;
  secondary?: string;
  badge?: string;
}> = ({ toolName, primary, secondary, badge }) => (
  <CardShell
    header={
      <>
        <span className="text-accent shrink-0">{toolName}</span>
        {badge !== undefined && <Chip>{badge}</Chip>}
      </>
    }
  >
    <div className="code-surface bg-codebg px-3 py-2 font-mono text-xs text-paper">
      <div className="break-all">{primary}</div>
      {secondary !== undefined && (
        <div className="mt-1 flex gap-2 break-all text-dim">
          <span aria-hidden="true">→</span>
          <span>{secondary}</span>
        </div>
      )}
    </div>
  </CardShell>
);

const JsonResultView: React.FC<{ text: string }> = ({ text }) => {
  try {
    const value: unknown = JSON.parse(text);
    return (
      <div className="mt-1 overflow-hidden rounded-lg border border-rule code-surface bg-codebg">
        <CodeHighlight code={JSON.stringify(value, null, 2)} language="json" showLineNumbers={false} />
      </div>
    );
  } catch {
    return <PlainResultBody text={text} />;
  }
};

// ---- Dispatcher ----

/** The generic tool-call card: tool-name header over the raw args JSON. */
export const GenericToolCallCard: React.FC<{
  toolName: string;
  args: Record<string, unknown> | undefined;
}> = ({ toolName, args }) => (
  <CardShell header={<span className="text-accent">{toolName}</span>}>
    {args !== undefined && Object.keys(args).length > 0 && (
      <CodeHighlight code={JSON.stringify(args, null, 2)} language="json" showLineNumbers={false} />
    )}
  </CardShell>
);

/**
 * The ONE tool-call card used everywhere a tool call renders — ledger
 * TOOL_CALL entries and pending-veto cards alike. Well-known tools get their
 * special card; anything else (or unparseable args) falls back to the
 * generic JSON card.
 */
const ToolCallPreview: React.FC<{
  toolName: string;
  args: Record<string, unknown> | undefined;
}> = ({ toolName, args }) => {
  if (['web_search', 'web_fetch', 'web_read', 'inspect_group', 'create_group', 'create_mate', 'create_task', 'create_monitor', 'inspect_monitor', 'pause_monitor', 'resume_monitor', 'cancel_monitor', 'create_node', 'post_message', 'input_task'].includes(toolName)) return <DetailCallCard toolName={toolName} args={args} />;
  if (toolName === 'run_command') {
    const command = parseRunCommandArgs(args);
    if (command !== null) return <RunCommandCallCard command={command} />;
  } else if (toolName === 'run_task') {
    const command = parseRunCommandArgs(args);
    if (command !== null) return <RunTaskCallCard command={command} />;
  } else if (toolName === 'view_task') {
    return <TaskRefCallCard toolName={toolName} taskId={parseTaskStatusArgs(args).taskId} />;
  } else if (toolName === 'stop_task') {
    return <TaskRefCallCard toolName={toolName} taskId={parseTaskStopArgs(args)} />;
  } else if (toolName === 'write_to_file') {
    const write = parseWriteFileArgs(args);
    if (write !== null) return <WriteFileCallCard args={write} />;
  } else if (toolName === 'replace_file_content') {
    const replace = parseReplaceFileArgs(args);
    if (replace !== null) return <ReplaceFileCallCard args={replace} />;
  } else if (toolName === 'view_file') {
    const view = parseViewFileArgs(args);
    if (view !== null) return <ViewFileCallCard args={view} />;
  } else if (toolName === 'list_dir') {
    const dir = parseListDirArgs(args);
    if (dir !== null) return <ListDirCallCard path={dir} />;
  } else if (toolName === 'grep_search') {
    const grep = parseGrepSearchArgs(args);
    if (grep !== null) return <GrepSearchCallCard args={grep} />;
  } else if (toolName === 'load_skill') {
    const skill = parseLoadSkillArgs(args);
    if (skill !== null) return <LoadSkillCallCard skillName={skill} />;
  } else if (toolName === 'think') {
    return <ThinkRow />;
  } else if (
    toolName === 'find_files' &&
    args !== undefined &&
    typeof args.absolutePath === 'string' &&
    typeof args.pattern === 'string'
  ) {
    return (
      <PathOperationCallCard
        toolName={toolName}
        primary={args.absolutePath}
        badge={args.pattern}
      />
    );
  } else if (
    toolName === 'move_path' &&
    args !== undefined &&
    typeof args.sourceAbsolutePath === 'string' &&
    typeof args.destinationAbsolutePath === 'string'
  ) {
    return (
      <PathOperationCallCard
        toolName={toolName}
        primary={args.sourceAbsolutePath}
        secondary={args.destinationAbsolutePath}
      />
    );
  } else if (
    toolName === 'delete_path' &&
    args !== undefined &&
    typeof args.absolutePath === 'string'
  ) {
    return (
      <PathOperationCallCard
        toolName={toolName}
        primary={args.absolutePath}
        badge={args.recursive === true ? 'recursive' : 'single'}
      />
    );
  } else if (MEMORY_TOOLS.includes(toolName)) {
    return <MemoryToolRow toolName={toolName} args={args} />;
  } else if (GROUP_TOOLS.includes(toolName)) {
    return <DelegationCallCard toolName={toolName} args={args} />;
  }
  return <GenericToolCallCard toolName={toolName} args={args} />;
};

export const ToolCallCard = ToolCallPreview;

/**
 * The ONE tool-call row used everywhere a tool call renders - ledger TOOL_CALL entries and
 * pending-veto cards alike. It is a bare `flex gap-3` row: a left tag column (a turn tag for a
 * confirmed call, a verdict stamp for a pending HITL call) over the shared {@link ToolCallCard}.
 * An optional `footer` (the HITL decision buttons) renders below the SAME card, so the card itself
 * is identical in both states - only the tag + footer differ.
 */
export const ToolCallRow: React.FC<{
  tag: React.ReactNode;
  toolName: string;
  args: Record<string, unknown> | undefined;
  footer?: React.ReactNode;
  className?: string;
}> = ({ tag, toolName, args, footer, className = '' }) => (
  <div className={`ledger-enter flex gap-3 py-2 ${className}`}>
    {tag}
    <div className="min-w-0 flex-1">
      <ToolCallCard toolName={toolName} args={args} />
      {footer != null && <div className="mt-2">{footer}</div>}
    </div>
  </div>
);

/** The default collapsible result body (plain pre-wrap on the panel surface). */
export const PlainResultBody: React.FC<{ text: string }> = ({ text }) => (
  <pre className="text-xs font-mono text-dim whitespace-pre-wrap break-words bg-panel border border-rule rounded-lg p-2 max-h-64 overflow-y-auto">
    {text}
  </pre>
);

/**
 * The ONE tool-result body for collapsible tool_result entries, keyed on the
 * producing tool. Unknown tools get the plain pre-wrap body.
 */
export const ToolResultBody: React.FC<{ toolName: string | undefined; text: string; success?: boolean }> = ({
  toolName,
  text,
  success,
}) => {
  const { t } = useI18n();
  if (success === false && toolName !== 'run_command') return <PlainResultBody text={text} />;
  if (toolName === 'web_search') return <WebSearchResult text={text} />;
  // web_read is retained only for rendering earlier session records.
  if (toolName === 'web_fetch' || toolName === 'web_read') return <WebFetchResult text={text} />;
  if (toolName === 'write_to_file' || toolName === 'replace_file_content') return <FileToolStatusChip text={text} />;
  if (text === '' && ['think', 'create_group', 'disband_group'].includes(toolName ?? '')) return <p className="text-xs text-dim">{t('tool.completed')}</p>;
  if (toolName === 'run_command') return <RunCommandResultView text={text} />;
  if (toolName === 'run_task') return <TaskStartedView text={text} />;
  if (toolName === 'view_task') return <TaskStatusResultView text={text} />;
  if (toolName === 'stop_task') return <TaskStatusResultView text={text} />;
  if (toolName === 'view_file') return <ViewFileResultView text={text} />;
  if (toolName === 'list_dir') return <ListDirResultView text={text} />;
  if (toolName === 'grep_search') return <GrepResultView text={text} />;
  if (toolName === 'load_skill') return <LoadSkillResultView text={text} />;
  if (
    toolName === 'find_files' ||
    toolName === 'move_path' ||
    toolName === 'delete_path' ||
    toolName === 'input_task'
  ) {
    return <StructuredToolResult toolName={toolName} text={text} />;
  }
  if (MEMORY_TOOLS.includes(toolName ?? '') || GROUP_TOOLS.includes(toolName ?? '')) return <PlainResultBody text={text} />;
  return <JsonResultView text={text} />;
};

/**
 * write_to_file / replace_file_content TOOL_RESPONSE: compact status chip.
 * Falls back to plain pre-wrap text when the body is not the compact JSON.
 */
export const FileToolStatusChip: React.FC<{ text: string }> = ({ text }) => {
  const status = parseFileToolStatus(text);
  if (status === null) {
    return (
      <pre className="text-xs font-mono text-dim whitespace-pre-wrap break-words bg-panel border border-rule rounded-lg p-2 max-h-64 overflow-y-auto">
        {text}
      </pre>
    );
  }
  if (status.ok) {
    return (
      <span className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md bg-pass/10 border border-pass/30 text-xs min-w-0 max-w-full">
        <svg className="w-3.5 h-3.5 text-pass shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
        </svg>
        <span className="font-mono text-paper break-all">{status.file}</span>
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md bg-verdict/10 border border-verdict/30 text-xs text-verdict min-w-0 max-w-full break-words">
      <svg className="w-3.5 h-3.5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
      </svg>
      <span className="break-words">{status.error}</span>
    </span>
  );
};
