/**
 * Parsers for the args and raw text bodies of well-known tools
 * (run_command, write_to_file, replace_file_content). Everything here is
 * defensive: shapes that don't match the backend's documented format return
 * null so callers can fall back to the generic rendering.
 */

/** run_command observation: stdout, optional "\n[stderr]\n<stderr>", optional trailing "(exit code: N)". */
export interface CommandOutput {
  stdout: string;
  stderr: string | null;
  /** Null when the command exited 0 (the backend only emits non-zero codes). */
  exitCode: number | null;
}

const EXIT_CODE_RE = /\n?\(exit code: (-?\d+)\)\s*$/;
const STDERR_SEPARATOR = '\n[stderr]\n';

/** Split a run_command result body into stdout / stderr / exit code. Always succeeds. */
export function parseCommandOutput(text: string): CommandOutput {
  let rest = text;
  let exitCode: number | null = null;
  const exitMatch = EXIT_CODE_RE.exec(rest);
  if (exitMatch !== null) {
    exitCode = Number.parseInt(exitMatch[1], 10);
    rest = rest.slice(0, exitMatch.index);
  }
  let stderr: string | null = null;
  let stdout = rest;
  const separatorAt = rest.indexOf(STDERR_SEPARATOR);
  if (separatorAt !== -1) {
    stdout = rest.slice(0, separatorAt);
    stderr = rest.slice(separatorAt + STDERR_SEPARATOR.length);
  }
  return { stdout, stderr, exitCode };
}

/** Compact JSON status returned by write_to_file / replace_file_content. */
export interface FileToolStatus {
  ok: boolean;
  file: string | null;
  error: string | null;
}

/** Parse {"status":"ok","file":…} / {"status":"error","error":…}; null on any other shape. */
export function parseFileToolStatus(text: string): FileToolStatus | null {
  try {
    const parsed: unknown = JSON.parse(text);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
    const record = parsed as Record<string, unknown>;
    if (record.status === 'ok' && typeof record.file === 'string') {
      return { ok: true, file: record.file, error: null };
    }
    if (record.status === 'error' && typeof record.error === 'string') {
      return {
        ok: false,
        file: typeof record.file === 'string' ? record.file : null,
        error: record.error,
      };
    }
    return null;
  } catch {
    return null;
  }
}

// ---- Tool-call args ----

export interface RunCommandArgs {
  commands: { executable: string; args: string[] }[];
  cwd: string | null;
  /** Null when omitted (the backend defaults to STOP_ON_FAILURE). */
  connect: string | null;
  /** Timeout in seconds the agent set (0 = configured maximum); null when absent. */
  timeout: number | null;
}

function asStringArray(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null;
  return value.every((item) => typeof item === 'string') ? (value as string[]) : null;
}

/** Parse run_command call args; null unless `commands` is a well-formed array. */
export function parseRunCommandArgs(args: Record<string, unknown> | undefined): RunCommandArgs | null {
  if (args === undefined || !Array.isArray(args.commands)) return null;
  const commands: RunCommandArgs['commands'] = [];
  for (const entry of args.commands as unknown[]) {
    if (typeof entry !== 'object' || entry === null) return null;
    const record = entry as Record<string, unknown>;
    if (typeof record.executable !== 'string') return null;
    const argv = record.args === undefined ? [] : asStringArray(record.args);
    if (argv === null) return null;
    commands.push({ executable: record.executable, args: argv });
  }
  return {
    commands,
    cwd: typeof args.cwd === 'string' ? args.cwd : null,
    connect: typeof args.connect === 'string' ? args.connect : null,
    timeout: typeof args.timeout === 'number' ? args.timeout : null,
  };
}

// ---- background tasks (run_task / view_task / stop_task) ----

/** A started background task (the run_task result body). */
export interface TaskStarted {
  taskId: string;
  pid: number | null;
  command: string | null;
  cwd: string | null;
}

/** Parse a run_task result body `{status:"started",taskId,...}`; null when not that shape. */
export function parseTaskStarted(text: string): TaskStarted | null {
  try {
    const obj = JSON.parse(text) as Record<string, unknown>;
    if (obj.status !== 'started' || typeof obj.taskId !== 'string') return null;
    return {
      taskId: obj.taskId,
      pid: typeof obj.pid === 'number' ? obj.pid : null,
      command: typeof obj.command === 'string' ? obj.command : null,
      cwd: typeof obj.cwd === 'string' ? obj.cwd : null,
    };
  } catch {
    return null;
  }
}

/** Parse view_task call args (taskId omitted = list all). */
export function parseTaskStatusArgs(
  args: Record<string, unknown> | undefined,
): { taskId: string | null; lines: number | null } {
  return {
    taskId: args !== undefined && typeof args.taskId === 'string' ? args.taskId : null,
    lines: args !== undefined && typeof args.lines === 'number' ? args.lines : null,
  };
}

/** Parse stop_task call args → the taskId (or null). */
export function parseTaskStopArgs(args: Record<string, unknown> | undefined): string | null {
  if (args === undefined || typeof args.taskId !== 'string') return null;
  return args.taskId;
}

/** A single-task view_task result body. */
export interface TaskStatusResult {
  command?: string;
  cwd?: string;
  pid?: number;
  uptimeSeconds?: number;
  startedAt?: string;
  taskId: string;
  alive: boolean;
  exitCode: number | null;
  recentOutput: string | null;
}

/** Parse a view_task result body. Handles both a single task and the list-all envelope. */
export function parseTaskStatusContent(text: string): TaskStatusResult | { count: number; tasks: TaskStatusResult[] } | null {
  try {
    const obj = JSON.parse(text) as Record<string, unknown>;
    if (typeof obj.taskId === 'string' && typeof obj.alive === 'boolean') {
      return {
        ...(typeof obj.command === 'string' ? { command: obj.command } : {}),
        ...(typeof obj.cwd === 'string' ? { cwd: obj.cwd } : {}),
        ...(typeof obj.pid === 'number' ? { pid: obj.pid } : {}),
        ...(typeof obj.uptimeSeconds === 'number' ? { uptimeSeconds: obj.uptimeSeconds } : {}),
        ...(typeof obj.startedAt === 'string' ? { startedAt: obj.startedAt } : {}),
        taskId: obj.taskId,
        alive: obj.alive === true,
        exitCode: typeof obj.exitCode === 'number' ? obj.exitCode : null,
        recentOutput: typeof obj.recentOutput === 'string' ? obj.recentOutput : null,
      };
    }
    if (typeof obj.count === 'number' && Array.isArray(obj.tasks)) {
      const tasks = obj.tasks.map(task => parseTaskStatusContent(JSON.stringify(task)));
      if (tasks.some(task => task === null || 'count' in task)) return null;
      return { count: obj.count, tasks: tasks as TaskStatusResult[] };
    }
    return null;
  } catch {
    return null;
  }
}

export interface WriteFileArgs {
  absolutePath: string;
  codeContent: string;
  overwrite: boolean;
}

/** Parse write_to_file call args; null when absolutePath/codeContent are missing. */
export function parseWriteFileArgs(args: Record<string, unknown> | undefined): WriteFileArgs | null {
  if (args === undefined) return null;
  if (typeof args.absolutePath !== 'string' || typeof args.codeContent !== 'string') return null;
  return { absolutePath: args.absolutePath, codeContent: args.codeContent, overwrite: args.overwrite === true };
}

export interface ReplaceFileArgs {
  absolutePath: string;
  targetContent: string;
  replacementContent: string;
}

/** Parse replace_file_content call args; null when any of the three fields is missing. */
export function parseReplaceFileArgs(
  args: Record<string, unknown> | undefined,
): ReplaceFileArgs | null {
  if (args === undefined) return null;
  if (
    typeof args.absolutePath !== 'string' ||
    typeof args.targetContent !== 'string' ||
    typeof args.replacementContent !== 'string'
  ) {
    return null;
  }
  return {
    absolutePath: args.absolutePath,
    targetContent: args.targetContent,
    replacementContent: args.replacementContent,
  };
}

// ---- Read-only tools: view_file / list_dir / grep_search / load_skill ----

function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

export interface ViewFileArgs {
  path: string;
  startLine: number | null;
  endLine: number | null;
}

/** Parse view_file call args; null when absolutePath is missing. */
export function parseViewFileArgs(args: Record<string, unknown> | undefined): ViewFileArgs | null {
  if (args === undefined || typeof args.absolutePath !== 'string') return null;
  return {
    path: args.absolutePath,
    startLine: asNumber(args.startLine),
    endLine: asNumber(args.endLine),
  };
}

export interface ViewFileLine {
  n: number;
  text: string;
}

const VIEW_LINE_RE = /^(\d+): (.*)$/;

/**
 * Split a view_file body ("<n>: <text>" per line) into gutter numbers + text.
 * Null when any line doesn't match (e.g. an error envelope) — caller falls
 * back to plain text.
 */
export function parseViewFileContent(text: string): ViewFileLine[] | null {
  const rawLines = text.replace(/\n$/, '').split('\n');
  if (rawLines.length === 1 && rawLines[0] === '') return [];
  const lines: ViewFileLine[] = [];
  for (const raw of rawLines) {
    const match = VIEW_LINE_RE.exec(raw);
    if (match === null) return null;
    lines.push({ n: Number.parseInt(match[1], 10), text: match[2] });
  }
  return lines;
}

/** Parse list_dir call args; null when absolutePath is missing. */
export function parseListDirArgs(args: Record<string, unknown> | undefined): string | null {
  if (args === undefined || typeof args.absolutePath !== 'string') return null;
  return args.absolutePath;
}

export interface DirEntry {
  name: string;
  isDir: boolean;
}

/**
 * Split a list_dir body into entries (trailing "/" marks a directory).
 * Null when the body looks like an error envelope instead of a listing.
 */
export function parseListDirContent(text: string): DirEntry[] | null {
  if (text.startsWith('{')) return null;
  return text
    .split('\n')
    .filter((line) => line !== '')
    .map((line) =>
      line.endsWith('/') ? { name: line.slice(0, -1), isDir: true } : { name: line, isDir: false },
    );
}

export interface GrepSearchArgs {
  searchPath: string;
  query: string;
  caseInsensitive: boolean;
  includes: string[];
}

/** Parse grep_search call args; null when searchPath/query are missing. */
export function parseGrepSearchArgs(
  args: Record<string, unknown> | undefined,
): GrepSearchArgs | null {
  if (args === undefined) return null;
  if (typeof args.absolutePath !== 'string' || typeof args.query !== 'string') return null;
  const includes =
    Array.isArray(args.includes) && args.includes.every((item) => typeof item === 'string')
      ? (args.includes as string[])
      : [];
  return {
    searchPath: args.absolutePath,
    query: args.query,
    caseInsensitive: args.caseInsensitive === true,
    includes,
  };
}

export interface GrepRow {
  path: string;
  line: number;
  text: string;
}

/** Backend's empty-grep marker. */
export const GREP_NO_MATCHES = '(no matches)';

// Greedy path: the line number is the LAST ":<digits>: " before the text —
// Windows paths carry their own "C:\" colon.
const GREP_ROW_RE = /^(.*):(\d+): (.*)$/;

/**
 * Split a grep_search body ("<file>:<n>: <text>" per line) into rows.
 * Null when any line doesn't match — caller falls back to plain text.
 */
export function parseGrepContent(text: string): GrepRow[] | null {
  const rawLines = text.replace(/\n$/, '').split('\n');
  if (rawLines.length === 1 && rawLines[0] === '') return [];
  const rows: GrepRow[] = [];
  for (const raw of rawLines) {
    const match = GREP_ROW_RE.exec(raw);
    if (match === null) return null;
    rows.push({ path: match[1], line: Number.parseInt(match[2], 10), text: match[3] });
  }
  return rows;
}

/** Parse load_skill call args; null when skillName is missing. */
export function parseLoadSkillArgs(args: Record<string, unknown> | undefined): string | null {
  if (args === undefined || typeof args.skillName !== 'string') return null;
  return args.skillName;
}

// ---- Memory & group/DAG tools: one-line arg summaries ----

const SUMMARY_MAX = 80;

function excerpt(text: string): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > SUMMARY_MAX ? `${flat.slice(0, SUMMARY_MAX)}…` : flat;
}

function pickString(args: Record<string, unknown>, ...keys: string[]): string {
  for (const key of keys) {
    const value = args[key];
    if (typeof value === 'string' && value.trim() !== '') return value;
  }
  return '';
}

/**
 * A one-line summary of a memory/group tool call's key args ("" when nothing
 * worth showing). Purely presentational — the full args remain in the entry.
 */
export function toolSummary(toolName: string, args: Record<string, unknown> | undefined): string {
  if (args === undefined) return '';
  switch (toolName) {
    case 'write_memory':
      return excerpt(pickString(args, 'content', 'promoteMemoryId'));
    case 'recall_memory':
      return excerpt(pickString(args, 'query'));
    case 'forget_memory':
      return pickString(args, 'memoryId');
    case 'create_group':
      return excerpt(pickString(args, 'task'));
    case 'create_node': {
      const id = pickString(args, 'nodeId');
      const description = excerpt(pickString(args, 'description'));
      if (id !== '' && description !== '') return `${id} — ${description}`;
      return id !== '' ? id : description;
    }
    case 'remove_node':
      return pickString(args, 'nodeId');
    case 'post_message': {
      const type = pickString(args, 'type');
      const receiver = pickString(args, 'receiver');
      const payload = excerpt(pickString(args, 'payload'));
      const route = [type, receiver].filter((part) => part !== '').join(' → ');
      return route !== '' && payload !== '' ? `${route}: ${payload}` : route || payload;
    }
    default:
      return '';
  }
}

/** Memory tools render as compact one-line rows, not cards. */
export const MEMORY_TOOLS = ['write_memory', 'recall_memory', 'forget_memory'];

/** Group/DAG delegation tools render as delegation cards. */
export const GROUP_TOOLS = [
  'create_group',
  'create_node',
  'remove_node',
  'post_message',
  'disband_group',
  'inspect_group',
];
