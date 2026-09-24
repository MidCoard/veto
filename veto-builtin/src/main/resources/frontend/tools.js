// translations.ts
var en = {
  "tool.origin.plugin": "Plugin",
  "tool.origin.native": "Native",
  "tool.origin.agent_loop": "Agent Loop",
  "tool.origin.external_mcp": "External MCP",
  "tool.origin.label": "Tool source",
  "tool.failureNoDetail": "The tool did not provide an error description. Check the full records for context.",
  "tool.webRead.executionHelp": "Reader runtime metadata. Token counts are cumulative across its model calls, separate from the parent agent.",
  "tool.webRead.maskedId": "The execution identifier was masked in this stored result. This marker alone does not prove that an API key was present.",
  "tool.field.promptTokens": "Cumulative input tokens",
  "tool.field.completionTokens": "Cumulative output tokens",
  "tool.execution.running": "Running",
  "tool.execution.waiting": "No recorded result; execution outcome unknown",
  "tool.execution.failed": "Failed",
  "tool.execution.success": "Succeeded",
  "tool.execution.completed": "Completed \xB7 outcome unknown",
  "tool.contentPreview": "Content preview",
  "tool.field.connect": "Sequence",
  "tool.field.network": "Network access",
  "tool.field.timeout": "Timeout (seconds)",
  "tool.field.taskId": "Task",
  "tool.field.content": "Content",
  "tool.field.appendNewline": "Append newline",
  "tool.field.closeStdin": "Close input",
  "tool.field.absolutePath": "Path",
  "tool.field.startLine": "From line",
  "tool.field.endLine": "To line",
  "tool.field.pattern": "Pattern",
  "tool.field.query": "Search",
  "tool.field.caseInsensitive": "Ignore case",
  "tool.field.includes": "Include files",
  "tool.field.sourceAbsolutePath": "From",
  "tool.field.destinationAbsolutePath": "To",
  "tool.field.recursive": "Recursive",
  "tool.field.overwrite": "Overwrite",
  "tool.field.allowed_domains": "Allowed domains",
  "tool.field.blocked_domains": "Blocked domains",
  "tool.field.ids": "Sections",
  "tool.field.outcome": "Coverage",
  "tool.field.answer": "Answer",
  "tool.field.evidenceIds": "Evidence",
  "tool.field.limitations": "Limitations",
  "tool.field.skillName": "Skill",
  "tool.field.mode": "Mode",
  "tool.field.promoteMemoryId": "Memory to promote",
  "tool.field.projectId": "Project",
  "tool.field.memoryId": "Memory",
  "tool.field.task": "Objective",
  "tool.field.sinceSeq": "Since event",
  "tool.field.waitSeconds": "Wait (seconds)",
  "tool.field.nodeId": "Node",
  "tool.field.description": "Work description",
  "tool.field.skillset": "Legacy label",
  "tool.field.dependsOn": "Dependencies",
  "tool.field.type": "Message type",
  "tool.field.receiver": "Recipient",
  "tool.field.payload": "Message",
  "tool.value.yes": "Yes",
  "tool.value.no": "No",
  "tool.summary.fetchPage": "Fetch the page assigned to this reader.",
  "tool.summary.disband": "Disband the current group.",
  "tool.summary.allTasks": "List background tasks.",
  "tool.summary.group": "Inspect current group progress.",
  "tool.summary.missingCommand": "Command details unavailable.",
  "tool.field.url": "url",
  "tool.field.objective": "objective",
  "tool.field.commands": "commands",
  "tool.field.executable": "executable",
  "tool.field.args": "args",
  "tool.field.base": "base",
  "tool.field.path": "path",
  "tool.field.cwd": "cwd",
  "tool.field.truncated": "truncated",
  "tool.field.truncationReason": "truncationReason",
  "tool.field.skippedEntries": "skippedEntries",
  "tool.field.durationMs": "durationMs",
  "tool.field.modelCalls": "modelCalls",
  "tool.field.model": "model",
  "tool.field.id": "id",
  "tool.field.count": "count",
  "tool.field.tasks": "tasks",
  "tool.field.alive": "alive",
  "tool.field.command": "command",
  "tool.field.exitCode": "exitCode",
  "tool.field.recentOutput": "recentOutput",
  "tool.field.status": "status",
  "tool.field.success": "success",
  "tool.field.errorCode": "errorCode",
  "tool.field.format": "format",
  "tool.field.nodes": "nodes",
  "tool.field.state": "state",
  "tool.field.dependencies": "dependencies",
  "tool.field.report": "report",
  "tool.field.retries": "retries",
  "tool.field.createdAt": "createdAt",
  "tool.field.startedAt": "startedAt",
  "tool.field.endedAt": "endedAt",
  "tool.field.agentId": "agentId",
  "tool.field.groupId": "groupId",
  "tool.field.messages": "messages",
  "tool.field.sender": "sender",
  "tool.field.sequence": "sequence",
  "tool.field.timestamp": "timestamp",
  "tool.field.evidence": "evidence",
  "tool.field.section": "section",
  "tool.field.quote": "quote",
  "tool.field.title": "title",
  "tool.enum.connect.AND": "AND",
  "tool.enum.connect.OR": "OR",
  "tool.enum.connect.PIPE": "PIPE",
  "tool.enum.connect.SEQUENTIAL": "SEQUENTIAL",
  "tool.enum.connect.SINGLE": "SINGLE",
  "tool.enum.connect.AND_THEN": "AND_THEN",
  "tool.enum.mode.WRITE": "WRITE",
  "tool.enum.mode.PROMOTE": "PROMOTE",
  "tool.enum.type.TASK_DISPATCH": "TASK_DISPATCH",
  "tool.enum.type.ARTIFACT_REF": "ARTIFACT_REF",
  "tool.enum.type.LOG_REF": "LOG_REF",
  "tool.enum.type.FEEDBACK": "FEEDBACK",
  "tool.enum.type.STATUS": "STATUS",
  "tool.enum.type.ACCEPT": "ACCEPT",
  "tool.enum.outcome.complete": "complete",
  "tool.enum.outcome.partial": "partial",
  "tool.enum.outcome.not_found": "not_found",
  "tool.enum.status.success": "success",
  "tool.enum.status.failed": "failed",
  "tool.enum.status.error": "error",
  "tool.enum.status.running": "running",
  "tool.enum.status.completed": "completed",
  "tool.enum.state.IDLE": "IDLE",
  "tool.enum.state.RUNNING": "RUNNING",
  "tool.enum.state.WAITING": "WAITING",
  "tool.enum.state.PAUSED": "PAUSED",
  "tool.enum.state.TERMINATED": "TERMINATED",
  "tool.enum.state.COMPLETED": "COMPLETED",
  "tool.enum.state.FAILED": "FAILED",
  "tool.enum.state.PENDING": "PENDING",
  "tool.enum.state.INTERCEPTED": "INTERCEPTED",
  "tool.enum.truncationReason.RESULT_LIMIT": "RESULT_LIMIT",
  "tool.enum.truncationReason.BYTE_LIMIT": "BYTE_LIMIT",
  "tool.enum.truncationReason.DEPTH_LIMIT": "DEPTH_LIMIT",
  "tool.activityCall": "Tool call",
  "tool.webRead.complete": "Answer found",
  "tool.webRead.partial": "Partially checked",
  "tool.webRead.not_found": "Information not found",
  "tool.webRead.evidence": "Source evidence",
  "tool.webRead.limitations": "Reading limitations",
  "tool.webRead.execution": "Reader execution",
  "tool.noResults": "No results",
  "tool.resultCount": "{count} results",
  "tool.rawResult": "Original result",
  "tool.yes": "Yes",
  "tool.no": "No",
  "tool.noParameters": "No parameters",
  "tool.completed": "Completed",
  "tool.rawArguments": "All arguments",
  "tool.cwd": "cwd",
  "tool.exitCode": "exit code {code}",
  "tool.overwrite": "overwrite",
  "tool.replaceBefore": "Before",
  "tool.replaceAfter": "After",
  "tool.thinking": "Thinking\u2026",
  "tool.timeout": "Wall-clock timeout the agent set for this command",
  "tool.timeoutValue": "{s}s cap",
  "tool.timeoutNoCap": "Configured maximum",
  "tool.task": "task",
  "tool.taskStarted": "task started",
  "tool.taskAlive": "running",
  "tool.taskExit": "exited {code}",
  "tool.taskCount": "{count} task(s)"
};
var zh = {
  "tool.origin.plugin": "Plugin",
  "tool.origin.native": "Native",
  "tool.origin.agent_loop": "Agent Loop",
  "tool.origin.external_mcp": "External MCP",
  "tool.origin.label": "\u5DE5\u5177\u6765\u6E90",
  "tool.failureNoDetail": "\u5DE5\u5177\u6CA1\u6709\u8FD4\u56DE\u5177\u4F53\u9519\u8BEF\u8BF4\u660E\uFF0C\u8BF7\u67E5\u770B\u5B8C\u6574\u8BB0\u5F55\u4E86\u89E3\u4E0A\u4E0B\u6587\u3002",
  "tool.webRead.executionHelp": "\u7F51\u9875\u9605\u8BFB\u5668\u7684\u8FD0\u884C\u5143\u6570\u636E\u3002Token \u6570\u4E3A\u5176\u591A\u6B21\u6A21\u578B\u8C03\u7528\u7684\u7D2F\u8BA1\u91CF\uFF0C\u4E0E\u4E3B Agent \u5206\u5F00\u7EDF\u8BA1\u3002",
  "tool.webRead.maskedId": "\u6B64\u5386\u53F2\u7ED3\u679C\u4E2D\u7684\u6267\u884C\u6807\u8BC6\u5DF2\u88AB\u8131\u654F\u3002\u4EC5\u51ED\u8BE5\u6807\u8BB0\u4E0D\u80FD\u65AD\u5B9A\u539F\u503C\u662F API key\u3002",
  "tool.field.promptTokens": "\u7D2F\u8BA1\u8F93\u5165 tokens",
  "tool.field.completionTokens": "\u7D2F\u8BA1\u8F93\u51FA tokens",
  "tool.execution.running": "\u6267\u884C\u4E2D",
  "tool.execution.waiting": "\u672A\u8BB0\u5F55\u7ED3\u679C\uFF0C\u6267\u884C\u6548\u679C\u672A\u77E5",
  "tool.execution.failed": "\u6267\u884C\u5931\u8D25",
  "tool.execution.success": "\u6267\u884C\u6210\u529F",
  "tool.execution.completed": "\u5DF2\u5B8C\u6210 \xB7 \u7ED3\u679C\u72B6\u6001\u672A\u77E5",
  "tool.contentPreview": "\u5185\u5BB9\u9884\u89C8",
  "tool.field.connect": "\u6267\u884C\u987A\u5E8F",
  "tool.field.network": "\u7F51\u7EDC\u8BBF\u95EE",
  "tool.field.timeout": "\u8D85\u65F6\uFF08\u79D2\uFF09",
  "tool.field.taskId": "\u4EFB\u52A1",
  "tool.field.content": "\u5185\u5BB9",
  "tool.field.appendNewline": "\u8FFD\u52A0\u6362\u884C",
  "tool.field.closeStdin": "\u5173\u95ED\u8F93\u5165",
  "tool.field.absolutePath": "\u8DEF\u5F84",
  "tool.field.startLine": "\u8D77\u59CB\u884C",
  "tool.field.endLine": "\u7ED3\u675F\u884C",
  "tool.field.pattern": "\u5339\u914D\u6A21\u5F0F",
  "tool.field.query": "\u641C\u7D22",
  "tool.field.caseInsensitive": "\u5FFD\u7565\u5927\u5C0F\u5199",
  "tool.field.includes": "\u5305\u542B\u6587\u4EF6",
  "tool.field.sourceAbsolutePath": "\u6765\u6E90",
  "tool.field.destinationAbsolutePath": "\u76EE\u6807",
  "tool.field.recursive": "\u9012\u5F52",
  "tool.field.overwrite": "\u8986\u76D6",
  "tool.field.allowed_domains": "\u5141\u8BB8\u57DF\u540D",
  "tool.field.blocked_domains": "\u6392\u9664\u57DF\u540D",
  "tool.field.ids": "\u7AE0\u8282",
  "tool.field.outcome": "\u8986\u76D6\u60C5\u51B5",
  "tool.field.answer": "\u7B54\u6848",
  "tool.field.evidenceIds": "\u8BC1\u636E",
  "tool.field.limitations": "\u9650\u5236",
  "tool.field.skillName": "\u6280\u80FD",
  "tool.field.mode": "\u6A21\u5F0F",
  "tool.field.promoteMemoryId": "\u63D0\u5347\u8BB0\u5FC6",
  "tool.field.projectId": "\u9879\u76EE",
  "tool.field.memoryId": "\u8BB0\u5FC6",
  "tool.field.task": "\u76EE\u6807",
  "tool.field.sinceSeq": "\u8D77\u59CB\u4E8B\u4EF6",
  "tool.field.waitSeconds": "\u7B49\u5F85\uFF08\u79D2\uFF09",
  "tool.field.nodeId": "\u8282\u70B9",
  "tool.field.description": "\u5DE5\u4F5C\u8BF4\u660E",
  "tool.field.skillset": "\u5386\u53F2\u6807\u7B7E",
  "tool.field.dependsOn": "\u4F9D\u8D56",
  "tool.field.type": "\u6D88\u606F\u7C7B\u578B",
  "tool.field.receiver": "\u63A5\u6536\u8005",
  "tool.field.payload": "\u6D88\u606F",
  "tool.value.yes": "\u662F",
  "tool.value.no": "\u5426",
  "tool.summary.fetchPage": "\u83B7\u53D6\u5206\u914D\u7ED9\u5F53\u524D\u9605\u8BFB\u4EE3\u7406\u7684\u9875\u9762\u3002",
  "tool.summary.disband": "\u89E3\u6563\u5F53\u524D\u5DE5\u4F5C\u7EC4\u3002",
  "tool.summary.allTasks": "\u67E5\u770B\u540E\u53F0\u4EFB\u52A1\u5217\u8868\u3002",
  "tool.summary.group": "\u67E5\u770B\u5F53\u524D\u5DE5\u4F5C\u7EC4\u8FDB\u5EA6\u3002",
  "tool.summary.missingCommand": "\u547D\u4EE4\u8BE6\u60C5\u4E0D\u53EF\u7528\u3002",
  "tool.field.url": "\u7F51\u5740",
  "tool.field.objective": "\u76EE\u6807",
  "tool.field.commands": "\u547D\u4EE4\u5217\u8868",
  "tool.field.executable": "\u7A0B\u5E8F",
  "tool.field.args": "\u53C2\u6570",
  "tool.field.base": "\u641C\u7D22\u76EE\u5F55",
  "tool.field.path": "\u8DEF\u5F84",
  "tool.field.cwd": "\u5DE5\u4F5C\u76EE\u5F55",
  "tool.field.truncated": "\u7ED3\u679C\u5DF2\u622A\u65AD",
  "tool.field.truncationReason": "\u622A\u65AD\u539F\u56E0",
  "tool.field.skippedEntries": "\u8DF3\u8FC7\u6761\u76EE\u6570",
  "tool.field.durationMs": "\u8017\u65F6\uFF08\u6BEB\u79D2\uFF09",
  "tool.field.modelCalls": "\u6A21\u578B\u8C03\u7528\u6B21\u6570",
  "tool.field.model": "\u6A21\u578B",
  "tool.field.id": "\u6807\u8BC6",
  "tool.field.count": "\u6570\u91CF",
  "tool.field.tasks": "\u4EFB\u52A1\u5217\u8868",
  "tool.field.alive": "\u4ECD\u5728\u8FD0\u884C",
  "tool.field.command": "\u547D\u4EE4",
  "tool.field.exitCode": "\u9000\u51FA\u7801",
  "tool.field.recentOutput": "\u6700\u8FD1\u8F93\u51FA",
  "tool.field.status": "\u72B6\u6001",
  "tool.field.success": "\u6210\u529F",
  "tool.field.errorCode": "\u9519\u8BEF\u4EE3\u7801",
  "tool.field.format": "\u683C\u5F0F",
  "tool.field.nodes": "\u8282\u70B9\u5217\u8868",
  "tool.field.state": "\u72B6\u6001",
  "tool.field.dependencies": "\u4F9D\u8D56",
  "tool.field.report": "\u62A5\u544A",
  "tool.field.retries": "\u91CD\u8BD5\u6B21\u6570",
  "tool.field.createdAt": "\u521B\u5EFA\u65F6\u95F4",
  "tool.field.startedAt": "\u5F00\u59CB\u65F6\u95F4",
  "tool.field.endedAt": "\u7ED3\u675F\u65F6\u95F4",
  "tool.field.agentId": "\u4EE3\u7406\u6807\u8BC6",
  "tool.field.groupId": "\u5DE5\u4F5C\u7EC4\u6807\u8BC6",
  "tool.field.messages": "\u6D88\u606F\u5217\u8868",
  "tool.field.sender": "\u53D1\u9001\u8005",
  "tool.field.sequence": "\u5E8F\u53F7",
  "tool.field.timestamp": "\u65F6\u95F4",
  "tool.field.evidence": "\u8BC1\u636E",
  "tool.field.section": "\u7AE0\u8282",
  "tool.field.quote": "\u5F15\u7528",
  "tool.field.title": "\u6807\u9898",
  "tool.enum.connect.AND": "\u5168\u90E8\u6210\u529F\u540E\u7EE7\u7EED",
  "tool.enum.connect.OR": "\u5931\u8D25\u65F6\u7EE7\u7EED",
  "tool.enum.connect.PIPE": "\u7BA1\u9053\u8FDE\u63A5",
  "tool.enum.connect.SEQUENTIAL": "\u4F9D\u6B21\u6267\u884C",
  "tool.enum.connect.SINGLE": "\u5355\u6761\u547D\u4EE4",
  "tool.enum.connect.AND_THEN": "\u6210\u529F\u540E\u7EE7\u7EED",
  "tool.enum.mode.WRITE": "\u5199\u5165",
  "tool.enum.mode.PROMOTE": "\u63D0\u5347",
  "tool.enum.type.TASK_DISPATCH": "\u4EFB\u52A1\u5206\u914D",
  "tool.enum.type.ARTIFACT_REF": "\u4EA7\u7269\u5F15\u7528",
  "tool.enum.type.LOG_REF": "\u65E5\u5FD7\u5F15\u7528",
  "tool.enum.type.FEEDBACK": "\u53CD\u9988",
  "tool.enum.type.STATUS": "\u72B6\u6001\u901A\u77E5",
  "tool.enum.type.ACCEPT": "\u9A8C\u6536",
  "tool.enum.outcome.complete": "\u5B8C\u6574",
  "tool.enum.outcome.partial": "\u90E8\u5206\u5B8C\u6210",
  "tool.enum.outcome.not_found": "\u672A\u627E\u5230",
  "tool.enum.status.success": "\u6210\u529F",
  "tool.enum.status.failed": "\u5931\u8D25",
  "tool.enum.status.error": "\u9519\u8BEF",
  "tool.enum.status.running": "\u6267\u884C\u4E2D",
  "tool.enum.status.completed": "\u5DF2\u5B8C\u6210",
  "tool.enum.state.IDLE": "\u7A7A\u95F2",
  "tool.enum.state.RUNNING": "\u6267\u884C\u4E2D",
  "tool.enum.state.WAITING": "\u7B49\u5F85\u4E2D",
  "tool.enum.state.PAUSED": "\u5DF2\u6682\u505C",
  "tool.enum.state.TERMINATED": "\u5DF2\u505C\u6B62",
  "tool.enum.state.COMPLETED": "\u5DF2\u5B8C\u6210",
  "tool.enum.state.FAILED": "\u5931\u8D25",
  "tool.enum.state.PENDING": "\u7B49\u5F85\u4E2D",
  "tool.enum.state.INTERCEPTED": "\u7B49\u5F85\u5BA1\u6279",
  "tool.enum.truncationReason.RESULT_LIMIT": "\u8FBE\u5230\u7ED3\u679C\u6570\u91CF\u4E0A\u9650",
  "tool.enum.truncationReason.BYTE_LIMIT": "\u8FBE\u5230\u5185\u5BB9\u5927\u5C0F\u4E0A\u9650",
  "tool.enum.truncationReason.DEPTH_LIMIT": "\u8FBE\u5230\u76EE\u5F55\u6DF1\u5EA6\u4E0A\u9650",
  "tool.activityCall": "\u5DE5\u5177\u8C03\u7528",
  "tool.webRead.complete": "\u5DF2\u627E\u5230\u7B54\u6848",
  "tool.webRead.partial": "\u5DF2\u68C0\u67E5\u90E8\u5206\u5185\u5BB9",
  "tool.webRead.not_found": "\u672A\u627E\u5230\u76F8\u5173\u4FE1\u606F",
  "tool.webRead.evidence": "\u539F\u6587\u8BC1\u636E",
  "tool.webRead.limitations": "\u9605\u8BFB\u9650\u5236",
  "tool.webRead.execution": "\u9605\u8BFB\u6267\u884C\u8BE6\u60C5",
  "tool.noResults": "\u6CA1\u6709\u7ED3\u679C",
  "tool.resultCount": "{count} \u9879\u7ED3\u679C",
  "tool.rawResult": "\u539F\u59CB\u7ED3\u679C",
  "tool.yes": "\u662F",
  "tool.no": "\u5426",
  "tool.noParameters": "\u65E0\u53C2\u6570",
  "tool.completed": "\u5DF2\u5B8C\u6210",
  "tool.rawArguments": "\u5B8C\u6574\u53C2\u6570",
  "tool.cwd": "\u5DE5\u4F5C\u76EE\u5F55",
  "tool.exitCode": "\u9000\u51FA\u7801 {code}",
  "tool.overwrite": "\u8986\u76D6",
  "tool.replaceBefore": "\u66FF\u6362\u524D",
  "tool.replaceAfter": "\u66FF\u6362\u540E",
  "tool.thinking": "\u601D\u8003\u4E2D\u2026",
  "tool.timeout": "\u4EE3\u7406\u4E3A\u6B64\u547D\u4EE4\u8BBE\u7F6E\u7684\u8D85\u65F6\u65F6\u95F4",
  "tool.timeoutValue": "\u9650\u65F6 {s} \u79D2",
  "tool.timeoutNoCap": "\u914D\u7F6E\u7684\u6700\u5927\u65F6\u9650",
  "tool.task": "\u540E\u53F0\u4EFB\u52A1",
  "tool.taskStarted": "\u4EFB\u52A1\u5DF2\u542F\u52A8",
  "tool.taskAlive": "\u8FD0\u884C\u4E2D",
  "tool.taskExit": "\u5DF2\u9000\u51FA {code}",
  "tool.taskCount": "{count} \u4E2A\u4EFB\u52A1"
};

// runtime.tsx
var React;
var host;
var Locale;
function configure(value) {
  host = value;
  React = value.React;
  Locale = React.createContext("en");
}
function useI18n() {
  const lang = React.useContext(Locale);
  const dictionary = lang.startsWith("zh") ? zh : en;
  const t = (key, values = {}) => (dictionary[key] ?? en[key] ?? key).replace(/\{(\w+)\}/g, (_match, name) => String(values[name] ?? `{${name}}`));
  return { t, lang };
}
function LocaleScope({ locale, children }) {
  return /* @__PURE__ */ React.createElement(Locale.Provider, { value: locale }, children);
}
function CodeHighlight(props) {
  return /* @__PURE__ */ React.createElement(host.components.CodeBlock, { ...props });
}
function StreamingMarkdown(props) {
  return /* @__PURE__ */ React.createElement(host.components.Markdown, { ...props });
}

// toolLabels.ts
var lookup = (t, key, fallback) => Object.prototype.hasOwnProperty.call(en, key) ? t(key) : fallback;
var toolFieldLabel = (t, field) => lookup(t, `tool.field.${field}`, field);
var toolValueLabel = (t, field, value) => lookup(t, `tool.enum.${field}.${value}`, value);

// ToolDetailViews.tsx
var surface = "rounded-lg border border-rule bg-panel p-3 text-xs space-y-2 max-h-80 overflow-auto";
var pre = "whitespace-pre-wrap break-words font-mono text-xs";
function ResultText({ text }) {
  return /* @__PURE__ */ React.createElement("pre", { className: `${surface} ${pre} text-dim` }, text);
}
function SourceLink({ url, children }) {
  let safe = false;
  try {
    const parsed = new URL(url);
    safe = ["http:", "https:"].includes(parsed.protocol) && !parsed.username && !parsed.password;
  } catch {
  }
  return safe ? /* @__PURE__ */ React.createElement("a", { className: "text-accent break-all hover:underline", href: url, target: "_blank", rel: "noopener noreferrer" }, children ?? url) : /* @__PURE__ */ React.createElement("span", { className: "break-all" }, children ?? url);
}
function LegacyWebFetchResult({ text }) {
  const match = /^\[(\d{3})\] (\S+)\r?\n\r?\n([\s\S]*)$/.exec(text);
  if (!match) return /* @__PURE__ */ React.createElement(ResultText, { text });
  return /* @__PURE__ */ React.createElement("div", { className: surface }, /* @__PURE__ */ React.createElement("div", { className: "flex gap-2" }, /* @__PURE__ */ React.createElement("span", { className: "font-mono" }, match[1]), /* @__PURE__ */ React.createElement(SourceLink, { url: match[2] })), /* @__PURE__ */ React.createElement("pre", { className: pre }, match[3]));
}
function WebSearchResult({ text }) {
  const { t } = useI18n();
  if (text === "(no results)") return /* @__PURE__ */ React.createElement("p", { className: "text-xs text-dim" }, t("tool.noResults"));
  const header = /^Found (\d+) results:\r?\n\r?\n/.exec(text);
  const sourceAt = text.lastIndexOf("\nSources:\n");
  if (!header || sourceAt < 0) return /* @__PURE__ */ React.createElement(ResultText, { text });
  const body = text.slice(header[0].length, sourceAt);
  const matches = [...body.matchAll(/^(\d+)\. (.+)\n   (\S+)\n([\s\S]*?)(?=^\d+\. |$(?![\s\S]))/gm)];
  if (matches.length !== Number(header[1]) || matches.map((m) => m[0]).join("") !== body) return /* @__PURE__ */ React.createElement(ResultText, { text });
  return /* @__PURE__ */ React.createElement("div", { className: surface }, /* @__PURE__ */ React.createElement("p", { className: "text-dim" }, t("tool.resultCount", { count: matches.length })), /* @__PURE__ */ React.createElement("ol", { className: "space-y-3" }, matches.map((m, index) => /* @__PURE__ */ React.createElement("li", { key: index }, /* @__PURE__ */ React.createElement(SourceLink, { url: m[3] }, m[1], ". ", m[2]), /* @__PURE__ */ React.createElement("div", { className: "text-dim break-all" }, m[3]), /* @__PURE__ */ React.createElement("p", { className: "whitespace-pre-wrap" }, m[4].trim())))), /* @__PURE__ */ React.createElement("details", null, /* @__PURE__ */ React.createElement("summary", { className: "cursor-pointer text-dim" }, t("tool.rawResult")), /* @__PURE__ */ React.createElement("pre", { className: pre }, text)));
}
function object(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function webReadEvidence(value) {
  return object(value) && typeof value.url === "string" && typeof value.section === "string" && typeof value.quote === "string";
}
function WebFetchResult({ text }) {
  const { t } = useI18n();
  let value;
  try {
    value = JSON.parse(text);
    if (object(value) && value.status === "success" && value.format === "json" && typeof value.content === "string") {
      value = JSON.parse(value.content);
    }
  } catch {
    return /* @__PURE__ */ React.createElement(LegacyWebFetchResult, { text });
  }
  if (!object(value) || !["complete", "partial", "not_found"].includes(String(value.outcome)) || typeof value.answer !== "string" || !Array.isArray(value.evidence) || !value.evidence.every(webReadEvidence) || !Array.isArray(value.limitations) || !value.limitations.every((item) => typeof item === "string")) {
    return /* @__PURE__ */ React.createElement(ResultText, { text });
  }
  const outcome = value.outcome;
  return /* @__PURE__ */ React.createElement("div", { className: surface }, /* @__PURE__ */ React.createElement("span", { className: "inline-block rounded border border-rule px-2 py-0.5 text-dim" }, t(`tool.webRead.${outcome}`)), /* @__PURE__ */ React.createElement("p", { className: "whitespace-pre-wrap break-words text-paper" }, value.answer), value.evidence.length > 0 && /* @__PURE__ */ React.createElement("section", { className: "space-y-2" }, /* @__PURE__ */ React.createElement("h4", { className: "text-dim" }, t("tool.webRead.evidence")), value.evidence.map((item, index) => /* @__PURE__ */ React.createElement("figure", { key: index, className: "space-y-1" }, /* @__PURE__ */ React.createElement("figcaption", null, /* @__PURE__ */ React.createElement(SourceLink, { url: item.url }, item.section || item.url), /* @__PURE__ */ React.createElement("div", { className: "text-dim break-all" }, item.url)), /* @__PURE__ */ React.createElement("blockquote", { className: "border-l-2 border-accent/40 pl-3 whitespace-pre-wrap break-words" }, item.quote)))), value.limitations.length > 0 && /* @__PURE__ */ React.createElement("section", null, /* @__PURE__ */ React.createElement("h4", { className: "text-dim" }, t("tool.webRead.limitations")), /* @__PURE__ */ React.createElement("ul", { className: "list-disc pl-4 space-y-1" }, value.limitations.map((item, index) => /* @__PURE__ */ React.createElement("li", { className: "whitespace-pre-wrap break-words", key: index }, item)))), /* @__PURE__ */ React.createElement("details", null, /* @__PURE__ */ React.createElement("summary", { className: "cursor-pointer text-dim" }, t("tool.rawResult")), /* @__PURE__ */ React.createElement("pre", { className: pre }, text)));
}
function Fields({ values, literal = false }) {
  const { t } = useI18n();
  return /* @__PURE__ */ React.createElement("dl", { className: "space-y-2" }, Object.entries(values).map(([key, value]) => /* @__PURE__ */ React.createElement("div", { key }, /* @__PURE__ */ React.createElement("dt", { className: "font-mono text-dim" }, literal ? key : toolFieldLabel(t, key)), /* @__PURE__ */ React.createElement("dd", { className: "whitespace-pre-wrap break-words text-paper" }, value === null ? "\u2014" : typeof value === "boolean" ? t(value ? "tool.yes" : "tool.no") : Array.isArray(value) ? /* @__PURE__ */ React.createElement("ul", { className: "space-y-1" }, value.map((item, index) => /* @__PURE__ */ React.createElement("li", { key: index }, object(item) ? /* @__PURE__ */ React.createElement(Fields, { values: item, literal }) : String(item)))) : object(value) ? /* @__PURE__ */ React.createElement(Fields, { values: value, literal }) : literal ? String(value) : toolValueLabel(t, key, String(value))))));
}
function StructuredToolResult({ toolName, text }) {
  const { t } = useI18n();
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    return /* @__PURE__ */ React.createElement(ResultText, { text });
  }
  if (!object(value)) return /* @__PURE__ */ React.createElement(ResultText, { text });
  if (toolName === "find_files") {
    if (typeof value.base !== "string" || typeof value.pattern !== "string" || !Array.isArray(value.matches) || !value.matches.every((v) => typeof v === "string")) return /* @__PURE__ */ React.createElement(ResultText, { text });
    const { matches, ...metadata } = value;
    return /* @__PURE__ */ React.createElement("div", { className: surface }, /* @__PURE__ */ React.createElement(Fields, { values: metadata }), /* @__PURE__ */ React.createElement("p", { className: "text-dim" }, t("tool.resultCount", { count: matches.length })), /* @__PURE__ */ React.createElement("ul", { className: "font-mono space-y-1" }, matches.map((path, i) => /* @__PURE__ */ React.createElement("li", { className: "break-all", key: i }, path))));
  }
  return /* @__PURE__ */ React.createElement("div", { className: surface }, /* @__PURE__ */ React.createElement(Fields, { values: value }));
}
function DetailCallCard({ toolName, args }) {
  const { t } = useI18n();
  return /* @__PURE__ */ React.createElement("div", { className: surface }, /* @__PURE__ */ React.createElement("div", { className: "font-mono text-accent" }, toolName), args && Object.keys(args).length > 0 ? /* @__PURE__ */ React.createElement(Fields, { values: args }) : /* @__PURE__ */ React.createElement("p", { className: "text-dim" }, t("tool.noParameters")));
}

// toolContent.ts
var EXIT_CODE_RE = /\n?\(exit code: (-?\d+)\)\s*$/;
var STDERR_SEPARATOR = "\n[stderr]\n";
function parseCommandOutput(text) {
  let rest = text;
  let exitCode = null;
  const exitMatch = EXIT_CODE_RE.exec(rest);
  if (exitMatch !== null) {
    exitCode = Number.parseInt(exitMatch[1], 10);
    rest = rest.slice(0, exitMatch.index);
  }
  let stderr = null;
  let stdout = rest;
  const separatorAt = rest.indexOf(STDERR_SEPARATOR);
  if (separatorAt !== -1) {
    stdout = rest.slice(0, separatorAt);
    stderr = rest.slice(separatorAt + STDERR_SEPARATOR.length);
  }
  return { stdout, stderr, exitCode };
}
function parseFileToolStatus(text) {
  try {
    const parsed = JSON.parse(text);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
    const record = parsed;
    if (record.status === "ok" && typeof record.file === "string") {
      return { ok: true, file: record.file, error: null };
    }
    if (record.status === "error" && typeof record.error === "string") {
      return {
        ok: false,
        file: typeof record.file === "string" ? record.file : null,
        error: record.error
      };
    }
    return null;
  } catch {
    return null;
  }
}
function asStringArray(value) {
  if (!Array.isArray(value)) return null;
  return value.every((item) => typeof item === "string") ? value : null;
}
function parseRunCommandArgs(args) {
  if (args === void 0 || !Array.isArray(args.commands)) return null;
  const commands = [];
  for (const entry of args.commands) {
    if (typeof entry !== "object" || entry === null) return null;
    const record = entry;
    if (typeof record.executable !== "string") return null;
    const argv = record.args === void 0 ? [] : asStringArray(record.args);
    if (argv === null) return null;
    commands.push({ executable: record.executable, args: argv });
  }
  return {
    commands,
    cwd: typeof args.cwd === "string" ? args.cwd : null,
    connect: typeof args.connect === "string" ? args.connect : null,
    timeout: typeof args.timeout === "number" ? args.timeout : null
  };
}
function parseTaskStarted(text) {
  try {
    const obj = JSON.parse(text);
    if (obj.status !== "started" || typeof obj.taskId !== "string") return null;
    return {
      taskId: obj.taskId,
      pid: typeof obj.pid === "number" ? obj.pid : null,
      command: typeof obj.command === "string" ? obj.command : null,
      cwd: typeof obj.cwd === "string" ? obj.cwd : null
    };
  } catch {
    return null;
  }
}
function parseTaskStatusArgs(args) {
  return {
    taskId: args !== void 0 && typeof args.taskId === "string" ? args.taskId : null,
    lines: args !== void 0 && typeof args.lines === "number" ? args.lines : null
  };
}
function parseTaskStopArgs(args) {
  if (args === void 0 || typeof args.taskId !== "string") return null;
  return args.taskId;
}
function parseTaskStatusContent(text) {
  try {
    const obj = JSON.parse(text);
    if (typeof obj.taskId === "string" && typeof obj.alive === "boolean") {
      return {
        ...typeof obj.command === "string" ? { command: obj.command } : {},
        ...typeof obj.cwd === "string" ? { cwd: obj.cwd } : {},
        ...typeof obj.pid === "number" ? { pid: obj.pid } : {},
        ...typeof obj.uptimeSeconds === "number" ? { uptimeSeconds: obj.uptimeSeconds } : {},
        ...typeof obj.startedAt === "string" ? { startedAt: obj.startedAt } : {},
        taskId: obj.taskId,
        alive: obj.alive === true,
        exitCode: typeof obj.exitCode === "number" ? obj.exitCode : null,
        recentOutput: typeof obj.recentOutput === "string" ? obj.recentOutput : null
      };
    }
    if (typeof obj.count === "number" && Array.isArray(obj.tasks)) {
      const tasks = obj.tasks.map((task) => parseTaskStatusContent(JSON.stringify(task)));
      if (tasks.some((task) => task === null || "count" in task)) return null;
      return { count: obj.count, tasks };
    }
    return null;
  } catch {
    return null;
  }
}
function parseWriteFileArgs(args) {
  if (args === void 0) return null;
  if (typeof args.absolutePath !== "string" || typeof args.codeContent !== "string") return null;
  return { absolutePath: args.absolutePath, codeContent: args.codeContent, overwrite: args.overwrite === true };
}
function parseReplaceFileArgs(args) {
  if (args === void 0) return null;
  if (typeof args.absolutePath !== "string" || typeof args.targetContent !== "string" || typeof args.replacementContent !== "string") {
    return null;
  }
  return {
    absolutePath: args.absolutePath,
    targetContent: args.targetContent,
    replacementContent: args.replacementContent
  };
}
function asNumber(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}
function parseViewFileArgs(args) {
  if (args === void 0 || typeof args.absolutePath !== "string") return null;
  return {
    path: args.absolutePath,
    startLine: asNumber(args.startLine),
    endLine: asNumber(args.endLine)
  };
}
var VIEW_LINE_RE = /^(\d+): (.*)$/;
function parseViewFileContent(text) {
  const rawLines = text.replace(/\n$/, "").split("\n");
  if (rawLines.length === 1 && rawLines[0] === "") return [];
  const lines = [];
  for (const raw of rawLines) {
    const match = VIEW_LINE_RE.exec(raw);
    if (match === null) return null;
    lines.push({ n: Number.parseInt(match[1], 10), text: match[2] });
  }
  return lines;
}
function parseListDirArgs(args) {
  if (args === void 0 || typeof args.absolutePath !== "string") return null;
  return args.absolutePath;
}
function parseListDirContent(text) {
  if (text.startsWith("{")) return null;
  return text.split("\n").filter((line) => line !== "").map(
    (line) => line.endsWith("/") ? { name: line.slice(0, -1), isDir: true } : { name: line, isDir: false }
  );
}
function parseGrepSearchArgs(args) {
  if (args === void 0) return null;
  if (typeof args.absolutePath !== "string" || typeof args.query !== "string") return null;
  const includes = Array.isArray(args.includes) && args.includes.every((item) => typeof item === "string") ? args.includes : [];
  return {
    searchPath: args.absolutePath,
    query: args.query,
    caseInsensitive: args.caseInsensitive === true,
    includes
  };
}
var GREP_NO_MATCHES = "(no matches)";
var GREP_ROW_RE = /^(.*):(\d+): (.*)$/;
function parseGrepContent(text) {
  const rawLines = text.replace(/\n$/, "").split("\n");
  if (rawLines.length === 1 && rawLines[0] === "") return [];
  const rows = [];
  for (const raw of rawLines) {
    const match = GREP_ROW_RE.exec(raw);
    if (match === null) return null;
    rows.push({ path: match[1], line: Number.parseInt(match[2], 10), text: match[3] });
  }
  return rows;
}
function parseLoadSkillArgs(args) {
  if (args === void 0 || typeof args.skillName !== "string") return null;
  return args.skillName;
}
var SUMMARY_MAX = 80;
function excerpt(text) {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > SUMMARY_MAX ? `${flat.slice(0, SUMMARY_MAX)}\u2026` : flat;
}
function pickString(args, ...keys) {
  for (const key of keys) {
    const value = args[key];
    if (typeof value === "string" && value.trim() !== "") return value;
  }
  return "";
}
function toolSummary(toolName, args) {
  if (args === void 0) return "";
  switch (toolName) {
    case "write_memory":
      return excerpt(pickString(args, "content", "promoteMemoryId"));
    case "recall_memory":
      return excerpt(pickString(args, "query"));
    case "forget_memory":
      return pickString(args, "memoryId");
    case "create_group":
      return excerpt(pickString(args, "task"));
    case "create_node": {
      const id = pickString(args, "nodeId");
      const description = excerpt(pickString(args, "description"));
      if (id !== "" && description !== "") return `${id} \u2014 ${description}`;
      return id !== "" ? id : description;
    }
    case "remove_node":
      return pickString(args, "nodeId");
    case "post_message": {
      const type = pickString(args, "type");
      const receiver = pickString(args, "receiver");
      const payload = excerpt(pickString(args, "payload"));
      const route = [type, receiver].filter((part) => part !== "").join(" \u2192 ");
      return route !== "" && payload !== "" ? `${route}: ${payload}` : route || payload;
    }
    default:
      return "";
  }
}
var MEMORY_TOOLS = ["write_memory", "recall_memory", "forget_memory"];
var GROUP_TOOLS = [
  "create_group",
  "create_node",
  "remove_node",
  "post_message",
  "disband_group",
  "inspect_group"
];

// ToolCards.tsx
var CODE_TEXT = "text-[#DDE3EA]";
var CODE_MUTED = "text-[#7A8694]";
var CardShell = ({
  header,
  children
}) => /* @__PURE__ */ React.createElement("div", { className: "bg-panel border border-rule rounded-lg overflow-hidden" }, /* @__PURE__ */ React.createElement("div", { className: "px-3 py-1.5 border-b border-rule font-mono text-xs flex items-center gap-2 min-w-0" }, header), children);
var Chip = ({
  children,
  className = "",
  title
}) => /* @__PURE__ */ React.createElement(
  "span",
  {
    title,
    className: `text-[10px] uppercase tracking-wider rounded px-1.5 py-0.5 border border-rule text-dim shrink-0 ${className}`
  },
  children
);
var RunCommandCallCard = ({ command }) => {
  const { t } = useI18n();
  const showConnect = command.connect !== null && command.connect !== "STOP_ON_FAILURE";
  return /* @__PURE__ */ React.createElement(
    CardShell,
    {
      header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, "run_command"), showConnect && /* @__PURE__ */ React.createElement(Chip, null, command.connect), command.timeout !== null && /* @__PURE__ */ React.createElement(Chip, { title: t("tool.timeout") }, command.timeout === 0 ? t("tool.timeoutNoCap") : t("tool.timeoutValue", { s: command.timeout })))
    },
    /* @__PURE__ */ React.createElement("div", { className: "code-surface bg-codebg px-3 py-2 font-mono text-xs leading-relaxed max-h-64 overflow-y-auto" }, command.commands.map((cmd, index) => /* @__PURE__ */ React.createElement("div", { key: index, className: "whitespace-pre-wrap break-words" }, /* @__PURE__ */ React.createElement("span", { className: `${CODE_MUTED} select-none` }, "$ "), /* @__PURE__ */ React.createElement("span", { className: CODE_TEXT }, [cmd.executable, ...cmd.args].map((part) => /[\s"']/.test(part) || part === "" ? JSON.stringify(part) : part).join(" ")))), command.cwd !== null && /* @__PURE__ */ React.createElement("div", { className: `mt-1 text-[11px] ${CODE_MUTED} whitespace-pre-wrap break-words` }, t("tool.cwd"), ": ", command.cwd))
  );
};
var RunCommandResultView = ({ text }) => {
  const { t } = useI18n();
  const output = parseCommandOutput(text);
  return /* @__PURE__ */ React.createElement("div", { className: "mt-1 code-surface bg-codebg border border-rule rounded-lg p-2 font-mono text-xs max-h-64 overflow-y-auto" }, output.stdout.length > 0 && /* @__PURE__ */ React.createElement("pre", { className: `whitespace-pre-wrap break-words ${CODE_TEXT}` }, output.stdout), output.stderr !== null && output.stderr.length > 0 && /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("div", { className: `text-[10px] ${CODE_MUTED} select-none` }, "[stderr]"), /* @__PURE__ */ React.createElement("pre", { className: "whitespace-pre-wrap break-words text-verdict" }, output.stderr)), output.exitCode !== null && /* @__PURE__ */ React.createElement(
    "span",
    {
      className: `inline-block mt-1 px-1.5 py-0.5 rounded text-[10px] ${output.exitCode === 0 ? "bg-pass/15 text-pass" : "bg-verdict/15 text-verdict"}`
    },
    t("tool.exitCode", { code: output.exitCode })
  ));
};
var RunTaskCallCard = ({ command }) => {
  const { t } = useI18n();
  return /* @__PURE__ */ React.createElement(
    CardShell,
    {
      header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, "run_task"), /* @__PURE__ */ React.createElement(Chip, { className: "text-pass border-pass/40" }, t("tool.task")), command.timeout !== null && /* @__PURE__ */ React.createElement(Chip, { title: t("tool.timeout") }, command.timeout === 0 ? t("tool.timeoutNoCap") : t("tool.timeoutValue", { s: command.timeout })))
    },
    /* @__PURE__ */ React.createElement("div", { className: "code-surface bg-codebg px-3 py-2 font-mono text-xs leading-relaxed max-h-64 overflow-y-auto" }, command.commands.map((cmd, index) => /* @__PURE__ */ React.createElement("div", { key: index, className: "whitespace-pre-wrap break-words" }, /* @__PURE__ */ React.createElement("span", { className: `${CODE_MUTED} select-none` }, "$ "), /* @__PURE__ */ React.createElement("span", { className: CODE_TEXT }, [cmd.executable, ...cmd.args].map((part) => /[\s"']/.test(part) || part === "" ? JSON.stringify(part) : part).join(" ")))), command.cwd !== null && /* @__PURE__ */ React.createElement("div", { className: `mt-1 text-[11px] ${CODE_MUTED} whitespace-pre-wrap break-words` }, t("tool.cwd"), ": ", command.cwd))
  );
};
var TaskStartedView = ({ text }) => {
  const { t } = useI18n();
  const started = parseTaskStarted(text);
  if (started === null) return /* @__PURE__ */ React.createElement(PlainResultBody, { text });
  return /* @__PURE__ */ React.createElement("div", { className: "mt-1 flex flex-wrap items-center gap-2" }, /* @__PURE__ */ React.createElement("span", { className: "inline-flex items-center gap-1.5 px-2 py-1 rounded-md bg-pass/10 border border-pass/30 text-xs text-pass" }, /* @__PURE__ */ React.createElement("span", { className: "w-1.5 h-1.5 rounded-full bg-pass animate-pulse" }), t("tool.taskStarted")), /* @__PURE__ */ React.createElement("span", { className: "font-mono text-xs text-paper" }, started.taskId), started.pid !== null && /* @__PURE__ */ React.createElement(Chip, null, "pid ", started.pid), started.command !== null && /* @__PURE__ */ React.createElement("pre", { className: "w-full whitespace-pre-wrap break-words text-xs font-mono" }, started.command), started.cwd !== null && /* @__PURE__ */ React.createElement("p", { className: "w-full text-xs text-dim break-all" }, t("tool.cwd"), ": ", started.cwd));
};
var TaskRefCallCard = ({
  toolName,
  taskId
}) => /* @__PURE__ */ React.createElement(
  CardShell,
  {
    header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, toolName), /* @__PURE__ */ React.createElement(Chip, { className: "normal-case" }, taskId !== null ? taskId : "\xB7 all \xB7"))
  },
  null
);
var TaskStatusResultView = ({ text }) => {
  const { t } = useI18n();
  const status = parseTaskStatusContent(text);
  if (status === null) return /* @__PURE__ */ React.createElement(PlainResultBody, { text });
  if ("count" in status) {
    return /* @__PURE__ */ React.createElement("div", { className: "space-y-2" }, /* @__PURE__ */ React.createElement("p", { className: "text-xs font-mono text-dim" }, t("tool.taskCount", { count: status.count })), status.tasks.map((task) => /* @__PURE__ */ React.createElement(TaskStatusResultView, { key: task.taskId, text: JSON.stringify(task) })));
  }
  return /* @__PURE__ */ React.createElement("div", { className: "mt-1 space-y-1" }, /* @__PURE__ */ React.createElement("div", { className: "flex flex-wrap items-center gap-2" }, /* @__PURE__ */ React.createElement("span", { className: "font-mono text-xs text-paper" }, status.taskId), status.alive ? /* @__PURE__ */ React.createElement("span", { className: "inline-flex items-center gap-1.5 text-xs text-pass" }, /* @__PURE__ */ React.createElement("span", { className: "w-1.5 h-1.5 rounded-full bg-pass animate-pulse" }), t("tool.taskAlive")) : /* @__PURE__ */ React.createElement(Chip, null, t("tool.taskExit", { code: status.exitCode ?? -1 }))), /* @__PURE__ */ React.createElement(Fields, { values: Object.fromEntries(Object.entries(status).filter(([key]) => !["taskId", "alive", "exitCode", "recentOutput"].includes(key))) }), status.recentOutput !== null && status.recentOutput.length > 0 && /* @__PURE__ */ React.createElement("pre", { className: `code-surface bg-codebg border border-rule rounded-lg p-2 font-mono text-xs whitespace-pre-wrap break-words max-h-48 overflow-y-auto ${CODE_TEXT}` }, status.recentOutput));
};
var WriteFileCallCard = ({ args }) => {
  const { t } = useI18n();
  return /* @__PURE__ */ React.createElement(
    CardShell,
    {
      header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, "write_to_file"), /* @__PURE__ */ React.createElement("span", { className: "text-paper truncate" }, args.absolutePath), args.overwrite && /* @__PURE__ */ React.createElement(Chip, null, t("tool.overwrite")))
    },
    /* @__PURE__ */ React.createElement("div", { className: "max-h-96 overflow-y-auto" }, /* @__PURE__ */ React.createElement(CodeHighlight, { code: args.codeContent, language: "text", showLineNumbers: false }))
  );
};
var ReplaceFileCallCard = ({ args }) => {
  const { t } = useI18n();
  return /* @__PURE__ */ React.createElement(
    CardShell,
    {
      header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, "replace_file_content"), /* @__PURE__ */ React.createElement("span", { className: "text-paper truncate" }, args.absolutePath))
    },
    /* @__PURE__ */ React.createElement("div", { className: "p-2 space-y-2" }, /* @__PURE__ */ React.createElement("div", null, /* @__PURE__ */ React.createElement("div", { className: "text-[10px] uppercase tracking-wider text-dim mb-1" }, t("tool.replaceBefore")), /* @__PURE__ */ React.createElement("pre", { className: "font-mono text-xs text-paper whitespace-pre-wrap break-words bg-verdict/10 border-l-2 border-verdict rounded-r p-2 max-h-48 overflow-y-auto" }, args.targetContent)), /* @__PURE__ */ React.createElement("div", null, /* @__PURE__ */ React.createElement("div", { className: "text-[10px] uppercase tracking-wider text-dim mb-1" }, t("tool.replaceAfter")), /* @__PURE__ */ React.createElement("pre", { className: "font-mono text-xs text-paper whitespace-pre-wrap break-words bg-pass/10 border-l-2 border-pass rounded-r p-2 max-h-48 overflow-y-auto" }, args.replacementContent)))
  );
};
var ViewFileCallCard = ({ args }) => /* @__PURE__ */ React.createElement(
  CardShell,
  {
    header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, "view_file"), /* @__PURE__ */ React.createElement("span", { className: "text-paper truncate", title: args.path }, args.path), (args.startLine !== null || args.endLine !== null) && /* @__PURE__ */ React.createElement(Chip, null, ":", args.startLine ?? "", "-", args.endLine ?? ""))
  },
  null
);
var ViewFileResultView = ({ text }) => {
  const lines = parseViewFileContent(text);
  if (lines === null) return /* @__PURE__ */ React.createElement(PlainResultBody, { text });
  const gutterWidth = `${String(lines[lines.length - 1]?.n ?? 1).length + 1}ch`;
  return /* @__PURE__ */ React.createElement("div", { className: "code-surface bg-codebg border border-rule rounded-lg px-3 py-2 font-mono text-xs max-h-80 overflow-y-auto" }, lines.map((line) => /* @__PURE__ */ React.createElement("div", { key: line.n, className: "flex whitespace-pre-wrap break-words" }, /* @__PURE__ */ React.createElement(
    "span",
    {
      className: `${CODE_MUTED} text-right select-none shrink-0 pr-3`,
      style: { minWidth: gutterWidth }
    },
    line.n
  ), /* @__PURE__ */ React.createElement("span", { className: CODE_TEXT }, line.text))));
};
var FolderGlyph = ({ className = "" }) => /* @__PURE__ */ React.createElement("svg", { className, fill: "none", stroke: "currentColor", viewBox: "0 0 24 24" }, /* @__PURE__ */ React.createElement(
  "path",
  {
    strokeLinecap: "round",
    strokeLinejoin: "round",
    strokeWidth: 2,
    d: "M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"
  }
));
var FileGlyph = ({ className = "" }) => /* @__PURE__ */ React.createElement("svg", { className, fill: "none", stroke: "currentColor", viewBox: "0 0 24 24" }, /* @__PURE__ */ React.createElement(
  "path",
  {
    strokeLinecap: "round",
    strokeLinejoin: "round",
    strokeWidth: 2,
    d: "M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
  }
));
var ListDirCallCard = ({ path }) => /* @__PURE__ */ React.createElement(
  CardShell,
  {
    header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, "list_dir"), /* @__PURE__ */ React.createElement(FolderGlyph, { className: "w-3.5 h-3.5 text-dim shrink-0" }), /* @__PURE__ */ React.createElement("span", { className: "text-paper truncate", title: path }, path))
  },
  null
);
var ListDirResultView = ({ text }) => {
  const entries = parseListDirContent(text);
  if (entries === null) return /* @__PURE__ */ React.createElement(PlainResultBody, { text });
  return /* @__PURE__ */ React.createElement("div", { className: "bg-panel border border-rule rounded-lg px-3 py-2 max-h-64 overflow-y-auto" }, entries.map((entry) => /* @__PURE__ */ React.createElement("div", { key: `${entry.isDir ? "d" : "f"}:${entry.name}`, className: "flex items-center gap-1.5 py-0.5" }, entry.isDir ? /* @__PURE__ */ React.createElement(FolderGlyph, { className: "w-3.5 h-3.5 text-accent/70 shrink-0" }) : /* @__PURE__ */ React.createElement(FileGlyph, { className: "w-3.5 h-3.5 text-dim/60 shrink-0" }), /* @__PURE__ */ React.createElement(
    "span",
    {
      className: `font-mono text-xs truncate ${entry.isDir ? "text-paper" : "text-dim"}`,
      title: entry.name
    },
    entry.name,
    entry.isDir && "/"
  ))));
};
var GrepSearchCallCard = ({ args }) => /* @__PURE__ */ React.createElement(
  CardShell,
  {
    header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, "grep_search"), /* @__PURE__ */ React.createElement("span", { className: "text-paper truncate", title: args.query }, "\u201C", args.query, "\u201D"))
  },
  /* @__PURE__ */ React.createElement("div", { className: "px-3 py-2 flex flex-wrap items-center gap-1.5" }, /* @__PURE__ */ React.createElement("span", { className: "font-mono text-[11px] text-dim truncate", title: args.searchPath }, args.searchPath), args.caseInsensitive && /* @__PURE__ */ React.createElement(Chip, null, "Aa"), args.includes.map((glob) => /* @__PURE__ */ React.createElement(Chip, { key: glob }, glob)))
);
var GrepResultView = ({ text }) => {
  if (text === GREP_NO_MATCHES) {
    return /* @__PURE__ */ React.createElement("p", { className: "text-xs font-mono text-dim/70 italic" }, GREP_NO_MATCHES);
  }
  const rows = parseGrepContent(text);
  if (rows === null) return /* @__PURE__ */ React.createElement(PlainResultBody, { text });
  return /* @__PURE__ */ React.createElement("div", { className: "bg-panel border border-rule rounded-lg px-3 py-2 max-h-80 overflow-y-auto" }, rows.map((row, index) => /* @__PURE__ */ React.createElement("div", { key: index, className: "font-mono text-xs whitespace-pre-wrap break-words py-0.5" }, /* @__PURE__ */ React.createElement("span", { className: "text-dim" }, row.path), /* @__PURE__ */ React.createElement("span", { className: "text-accent" }, ":", row.line), /* @__PURE__ */ React.createElement("span", { className: "text-paper/80" }, ": ", row.text))));
};
var LoadSkillCallCard = ({ skillName }) => /* @__PURE__ */ React.createElement(
  CardShell,
  {
    header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, "load_skill"), /* @__PURE__ */ React.createElement(Chip, { className: "normal-case" }, skillName))
  },
  null
);
var LoadSkillResultView = ({ text }) => {
  if (text.startsWith("{")) return /* @__PURE__ */ React.createElement(PlainResultBody, { text });
  return /* @__PURE__ */ React.createElement("div", { className: "bg-panel border border-rule rounded-lg px-3 py-2 max-h-96 overflow-y-auto" }, /* @__PURE__ */ React.createElement(StreamingMarkdown, { content: text, isStreaming: false }));
};
var ThinkRow = () => {
  const { t } = useI18n();
  return /* @__PURE__ */ React.createElement("span", { className: "inline-flex items-center gap-1.5 text-xs text-dim italic py-1" }, /* @__PURE__ */ React.createElement("span", { className: "w-1.5 h-1.5 rounded-full bg-dim/50" }), t("tool.thinking"));
};
var MemoryToolRow = ({ toolName, args }) => {
  const summary = toolSummary(toolName, args);
  return /* @__PURE__ */ React.createElement("div", { className: "flex items-center gap-2 py-1 min-w-0" }, /* @__PURE__ */ React.createElement("svg", { className: "w-3 h-3 text-dim shrink-0", fill: "none", stroke: "currentColor", viewBox: "0 0 24 24" }, /* @__PURE__ */ React.createElement(
    "path",
    {
      strokeLinecap: "round",
      strokeLinejoin: "round",
      strokeWidth: 2,
      d: "M12 3l7 9-7 9-7-9 7-9z"
    }
  )), /* @__PURE__ */ React.createElement("span", { className: "font-mono text-xs text-dim shrink-0" }, toolName), summary !== "" && /* @__PURE__ */ React.createElement("span", { className: "text-xs text-paper/70 truncate", title: summary }, summary));
};
var DelegationCallCard = ({ toolName, args }) => {
  const summary = toolSummary(toolName, args);
  return /* @__PURE__ */ React.createElement(
    CardShell,
    {
      header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, toolName), summary !== "" && /* @__PURE__ */ React.createElement("span", { className: "text-paper/80 truncate", title: summary }, summary))
    },
    null
  );
};
var PathOperationCallCard = ({ toolName, primary, secondary, badge }) => /* @__PURE__ */ React.createElement(
  CardShell,
  {
    header: /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("span", { className: "text-accent shrink-0" }, toolName), badge !== void 0 && /* @__PURE__ */ React.createElement(Chip, null, badge))
  },
  /* @__PURE__ */ React.createElement("div", { className: "code-surface bg-codebg px-3 py-2 font-mono text-xs text-paper" }, /* @__PURE__ */ React.createElement("div", { className: "break-all" }, primary), secondary !== void 0 && /* @__PURE__ */ React.createElement("div", { className: "mt-1 flex gap-2 break-all text-dim" }, /* @__PURE__ */ React.createElement("span", { "aria-hidden": "true" }, "\u2192"), /* @__PURE__ */ React.createElement("span", null, secondary)))
);
var JsonResultView = ({ text }) => {
  try {
    const value = JSON.parse(text);
    return /* @__PURE__ */ React.createElement("div", { className: "mt-1 overflow-hidden rounded-lg border border-rule code-surface bg-codebg" }, /* @__PURE__ */ React.createElement(CodeHighlight, { code: JSON.stringify(value, null, 2), language: "json", showLineNumbers: false }));
  } catch {
    return /* @__PURE__ */ React.createElement(PlainResultBody, { text });
  }
};
var GenericToolCallCard = ({ toolName, args }) => /* @__PURE__ */ React.createElement(CardShell, { header: /* @__PURE__ */ React.createElement("span", { className: "text-accent" }, toolName) }, args !== void 0 && Object.keys(args).length > 0 && /* @__PURE__ */ React.createElement(CodeHighlight, { code: JSON.stringify(args, null, 2), language: "json", showLineNumbers: false }));
var ToolCallPreview = ({ toolName, args }) => {
  if (["web_search", "web_fetch", "web_read", "inspect_group", "create_group", "create_mate", "create_task", "create_monitor", "inspect_monitor", "pause_monitor", "resume_monitor", "cancel_monitor", "create_node", "post_message", "input_task"].includes(toolName)) return /* @__PURE__ */ React.createElement(DetailCallCard, { toolName, args });
  if (toolName === "run_command") {
    const command = parseRunCommandArgs(args);
    if (command !== null) return /* @__PURE__ */ React.createElement(RunCommandCallCard, { command });
  } else if (toolName === "run_task") {
    const command = parseRunCommandArgs(args);
    if (command !== null) return /* @__PURE__ */ React.createElement(RunTaskCallCard, { command });
  } else if (toolName === "view_task") {
    return /* @__PURE__ */ React.createElement(TaskRefCallCard, { toolName, taskId: parseTaskStatusArgs(args).taskId });
  } else if (toolName === "stop_task") {
    return /* @__PURE__ */ React.createElement(TaskRefCallCard, { toolName, taskId: parseTaskStopArgs(args) });
  } else if (toolName === "write_to_file") {
    const write = parseWriteFileArgs(args);
    if (write !== null) return /* @__PURE__ */ React.createElement(WriteFileCallCard, { args: write });
  } else if (toolName === "replace_file_content") {
    const replace = parseReplaceFileArgs(args);
    if (replace !== null) return /* @__PURE__ */ React.createElement(ReplaceFileCallCard, { args: replace });
  } else if (toolName === "view_file") {
    const view = parseViewFileArgs(args);
    if (view !== null) return /* @__PURE__ */ React.createElement(ViewFileCallCard, { args: view });
  } else if (toolName === "list_dir") {
    const dir = parseListDirArgs(args);
    if (dir !== null) return /* @__PURE__ */ React.createElement(ListDirCallCard, { path: dir });
  } else if (toolName === "grep_search") {
    const grep = parseGrepSearchArgs(args);
    if (grep !== null) return /* @__PURE__ */ React.createElement(GrepSearchCallCard, { args: grep });
  } else if (toolName === "load_skill") {
    const skill = parseLoadSkillArgs(args);
    if (skill !== null) return /* @__PURE__ */ React.createElement(LoadSkillCallCard, { skillName: skill });
  } else if (toolName === "think") {
    return /* @__PURE__ */ React.createElement(ThinkRow, null);
  } else if (toolName === "find_files" && args !== void 0 && typeof args.absolutePath === "string" && typeof args.pattern === "string") {
    return /* @__PURE__ */ React.createElement(
      PathOperationCallCard,
      {
        toolName,
        primary: args.absolutePath,
        badge: args.pattern
      }
    );
  } else if (toolName === "move_path" && args !== void 0 && typeof args.sourceAbsolutePath === "string" && typeof args.destinationAbsolutePath === "string") {
    return /* @__PURE__ */ React.createElement(
      PathOperationCallCard,
      {
        toolName,
        primary: args.sourceAbsolutePath,
        secondary: args.destinationAbsolutePath
      }
    );
  } else if (toolName === "delete_path" && args !== void 0 && typeof args.absolutePath === "string") {
    return /* @__PURE__ */ React.createElement(
      PathOperationCallCard,
      {
        toolName,
        primary: args.absolutePath,
        badge: args.recursive === true ? "recursive" : "single"
      }
    );
  } else if (MEMORY_TOOLS.includes(toolName)) {
    return /* @__PURE__ */ React.createElement(MemoryToolRow, { toolName, args });
  } else if (GROUP_TOOLS.includes(toolName)) {
    return /* @__PURE__ */ React.createElement(DelegationCallCard, { toolName, args });
  }
  return /* @__PURE__ */ React.createElement(GenericToolCallCard, { toolName, args });
};
var ToolCallCard = ToolCallPreview;
var PlainResultBody = ({ text }) => /* @__PURE__ */ React.createElement("pre", { className: "text-xs font-mono text-dim whitespace-pre-wrap break-words bg-panel border border-rule rounded-lg p-2 max-h-64 overflow-y-auto" }, text);
var ToolResultBody = ({
  toolName,
  text,
  success
}) => {
  const { t } = useI18n();
  if (success === false && toolName !== "run_command") return /* @__PURE__ */ React.createElement(PlainResultBody, { text });
  if (toolName === "web_search") return /* @__PURE__ */ React.createElement(WebSearchResult, { text });
  if (toolName === "web_fetch" || toolName === "web_read") return /* @__PURE__ */ React.createElement(WebFetchResult, { text });
  if (toolName === "write_to_file" || toolName === "replace_file_content") return /* @__PURE__ */ React.createElement(FileToolStatusChip, { text });
  if (text === "" && ["think", "create_group", "disband_group"].includes(toolName ?? "")) return /* @__PURE__ */ React.createElement("p", { className: "text-xs text-dim" }, t("tool.completed"));
  if (toolName === "run_command") return /* @__PURE__ */ React.createElement(RunCommandResultView, { text });
  if (toolName === "run_task") return /* @__PURE__ */ React.createElement(TaskStartedView, { text });
  if (toolName === "view_task") return /* @__PURE__ */ React.createElement(TaskStatusResultView, { text });
  if (toolName === "stop_task") return /* @__PURE__ */ React.createElement(TaskStatusResultView, { text });
  if (toolName === "view_file") return /* @__PURE__ */ React.createElement(ViewFileResultView, { text });
  if (toolName === "list_dir") return /* @__PURE__ */ React.createElement(ListDirResultView, { text });
  if (toolName === "grep_search") return /* @__PURE__ */ React.createElement(GrepResultView, { text });
  if (toolName === "load_skill") return /* @__PURE__ */ React.createElement(LoadSkillResultView, { text });
  if (toolName === "find_files" || toolName === "move_path" || toolName === "delete_path" || toolName === "input_task") {
    return /* @__PURE__ */ React.createElement(StructuredToolResult, { toolName, text });
  }
  if (MEMORY_TOOLS.includes(toolName ?? "") || GROUP_TOOLS.includes(toolName ?? "")) return /* @__PURE__ */ React.createElement(PlainResultBody, { text });
  return /* @__PURE__ */ React.createElement(JsonResultView, { text });
};
var FileToolStatusChip = ({ text }) => {
  const status = parseFileToolStatus(text);
  if (status === null) {
    return /* @__PURE__ */ React.createElement("pre", { className: "text-xs font-mono text-dim whitespace-pre-wrap break-words bg-panel border border-rule rounded-lg p-2 max-h-64 overflow-y-auto" }, text);
  }
  if (status.ok) {
    return /* @__PURE__ */ React.createElement("span", { className: "inline-flex items-center gap-1.5 px-2 py-1 rounded-md bg-pass/10 border border-pass/30 text-xs min-w-0 max-w-full" }, /* @__PURE__ */ React.createElement("svg", { className: "w-3.5 h-3.5 text-pass shrink-0", fill: "none", stroke: "currentColor", viewBox: "0 0 24 24" }, /* @__PURE__ */ React.createElement("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 2, d: "M5 13l4 4L19 7" })), /* @__PURE__ */ React.createElement("span", { className: "font-mono text-paper break-all" }, status.file));
  }
  return /* @__PURE__ */ React.createElement("span", { className: "inline-flex items-center gap-1.5 px-2 py-1 rounded-md bg-verdict/10 border border-verdict/30 text-xs text-verdict min-w-0 max-w-full break-words" }, /* @__PURE__ */ React.createElement("svg", { className: "w-3.5 h-3.5 shrink-0", fill: "none", stroke: "currentColor", viewBox: "0 0 24 24" }, /* @__PURE__ */ React.createElement("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 2, d: "M6 18L18 6M6 6l12 12" })), /* @__PURE__ */ React.createElement("span", { className: "break-words" }, status.error));
};

// ToolConversationDetails.tsx
var conversationToolFields = {
  run_command: ["connect", "network", "timeout"],
  run_task: ["network", "timeout"],
  view_task: ["taskId"],
  stop_task: ["taskId"],
  input_task: ["taskId", "content", "appendNewline", "closeStdin"],
  view_file: ["absolutePath", "startLine", "endLine"],
  list_dir: ["absolutePath"],
  find_files: ["absolutePath", "pattern"],
  grep_search: ["absolutePath", "query", "caseInsensitive", "includes"],
  move_path: ["sourceAbsolutePath", "destinationAbsolutePath"],
  delete_path: ["absolutePath", "recursive"],
  write_to_file: ["overwrite"],
  replace_file_content: ["startLine", "endLine"],
  web_search: ["query", "allowed_domains", "blocked_domains"],
  web_fetch: [],
  web_read: [],
  fetch_page: [],
  find_sections: ["query"],
  read_sections: ["ids"],
  finish_read: ["outcome", "answer", "evidenceIds", "limitations"],
  load_skill: ["skillName"],
  think: [],
  recall_memory: ["query"],
  write_memory: ["mode", "content", "promoteMemoryId", "projectId"],
  forget_memory: ["memoryId"],
  create_group: ["task"],
  disband_group: [],
  inspect_group: ["sinceSeq", "waitSeconds"],
  create_mate: ["name", "responsibility"],
  create_task: ["taskId", "description", "mateId", "dependsOn"],
  create_monitor: ["purpose", "afterSeconds", "at"],
  create_node: ["nodeId", "description", "skillset", "dependsOn"],
  remove_node: ["nodeId"],
  post_message: ["type", "receiver", "payload"]
};
var headerFields = {
  view_file: ["absolutePath"],
  list_dir: ["absolutePath"],
  find_files: ["absolutePath"],
  grep_search: ["absolutePath"],
  write_to_file: ["absolutePath"],
  replace_file_content: ["absolutePath"],
  delete_path: ["absolutePath"],
  web_fetch: ["url"],
  web_read: ["url"],
  web_search: ["query"],
  find_sections: ["query"],
  recall_memory: ["query"],
  view_task: ["taskId"],
  stop_task: ["taskId"],
  input_task: ["taskId"],
  load_skill: ["skillName"],
  create_task: ["taskId"],
  create_mate: ["name"],
  create_node: ["nodeId"],
  remove_node: ["nodeId"],
  forget_memory: ["memoryId"],
  post_message: ["receiver"]
};
function toolHeaderField(toolName, args) {
  const candidates = headerFields[toolName] ?? (Object.prototype.hasOwnProperty.call(conversationToolFields, toolName) ? [] : ["url", "absolutePath", "path", "file_path"]);
  return candidates.find((key) => typeof args[key] === "string" && args[key] !== "");
}
var narrativeFields = /* @__PURE__ */ new Set(["query", "content", "task", "description", "payload", "answer", "limitations"]);
var clip = (value) => {
  const preview = value.split("\n").slice(0, 8).join("\n").slice(0, 1e3);
  return preview.length < value.length ? `${preview}
\u2026` : preview;
};
var object2 = (value) => typeof value === "object" && value !== null && !Array.isArray(value);
var meaningful = (value) => value !== void 0 && value !== null && value !== "" && (!Array.isArray(value) || value.length > 0);
function ToolConversationDetails({ toolName, args = {}, headerField, result }) {
  const { t } = useI18n();
  const label = (key) => toolFieldLabel(t, key);
  const valueText = (value) => typeof value === "boolean" ? t(value ? "tool.value.yes" : "tool.value.no") : Array.isArray(value) ? value.filter((v) => ["string", "number"].includes(typeof v)).slice(0, 20).join(", ") : ["string", "number"].includes(typeof value) ? String(value) : "";
  const known = Object.prototype.hasOwnProperty.call(conversationToolFields, toolName);
  const fields = (known ? conversationToolFields[toolName] : Object.keys(args).slice(0, 6)).filter((key) => key !== headerField && meaningful(args[key]) && valueText(args[key]) !== "");
  const prose = fields.filter((key) => narrativeFields.has(key));
  const metadata = fields.filter((key) => !narrativeFields.has(key) && !(toolName === "move_path" && ["sourceAbsolutePath", "destinationAbsolutePath"].includes(key)));
  const commandTool = toolName === "run_command" || toolName === "run_task";
  const commands = commandTool && Array.isArray(args.commands) ? args.commands.filter(object2).slice(0, 8) : [];
  const emptyLabel = toolName === "think" ? "tool.thinking" : toolName === "fetch_page" ? "tool.summary.fetchPage" : toolName === "disband_group" ? "tool.summary.disband" : toolName === "view_task" && !args.taskId ? "tool.summary.allTasks" : toolName === "inspect_group" && fields.length === 0 ? "tool.summary.group" : commandTool && commands.length === 0 ? "tool.summary.missingCommand" : null;
  if (!known && Object.values(args).some((value) => typeof value === "object" && value !== null)) return /* @__PURE__ */ React.createElement("div", { className: "p-3 text-xs" }, /* @__PURE__ */ React.createElement("pre", { className: "whitespace-pre-wrap break-words" }, JSON.stringify(args, null, 2)), result?.success === true && /* @__PURE__ */ React.createElement("pre", { className: "mt-2 whitespace-pre-wrap break-words" }, result.text));
  if (fields.length === 0 && commands.length === 0 && emptyLabel === null) return null;
  return /* @__PURE__ */ React.createElement("div", { className: "space-y-3 border-t border-rule/60 px-3 py-3 text-xs" }, commands.length > 0 && /* @__PURE__ */ React.createElement("div", { className: "space-y-1 rounded-md border border-rule/60 bg-ink px-3 py-2 font-mono text-paper" }, commands.map((command, index) => /* @__PURE__ */ React.createElement("div", { key: index, className: "flex gap-2" }, /* @__PURE__ */ React.createElement("span", { "aria-hidden": "true", className: "select-none text-dim" }, "$"), /* @__PURE__ */ React.createElement("pre", { className: "min-w-0 whitespace-pre-wrap break-words font-mono text-xs" }, clip([command.executable, ...Array.isArray(command.args) ? command.args : []].filter((v) => typeof v === "string").map((v) => /\s/.test(v) ? JSON.stringify(v) : v).join(" "))))), Array.isArray(args.commands) && args.commands.length > 8 && /* @__PURE__ */ React.createElement("span", { className: "text-dim" }, "\u2026")), toolName === "move_path" && /* @__PURE__ */ React.createElement("div", { className: "space-y-1 rounded-md border border-rule/60 bg-ink/50 px-3 py-2" }, ["sourceAbsolutePath", "destinationAbsolutePath"].filter((key) => meaningful(args[key])).map((key, index) => /* @__PURE__ */ React.createElement("div", { key, className: "flex items-start gap-2" }, /* @__PURE__ */ React.createElement("span", { className: "shrink-0 text-dim" }, index === 1 ? "\u2192 " : "", label(key)), /* @__PURE__ */ React.createElement("span", { className: "min-w-0 break-all font-mono" }, valueText(args[key]))))), prose.map((key) => /* @__PURE__ */ React.createElement("section", { key, className: "space-y-1.5" }, /* @__PURE__ */ React.createElement("h4", { className: "text-[10px] text-dim" }, label(key)), key === "content" && toolName === "input_task" ? /* @__PURE__ */ React.createElement("pre", { className: "rounded-md bg-ink px-3 py-2 whitespace-pre-wrap break-words font-mono" }, clip(valueText(args[key]))) : /* @__PURE__ */ React.createElement("p", { className: "whitespace-pre-wrap break-words leading-relaxed text-paper" }, clip(valueText(args[key]))))), metadata.length > 0 && /* @__PURE__ */ React.createElement("dl", { className: "flex flex-wrap gap-2" }, metadata.map((key) => /* @__PURE__ */ React.createElement("div", { key, className: "flex min-w-0 max-w-full flex-wrap items-baseline gap-x-2 rounded-md border border-rule/60 bg-raised/30 px-2 py-1" }, /* @__PURE__ */ React.createElement("dt", { className: "text-dim" }, known ? label(key) : label(key) === key ? key.replace(/([A-Z])/g, " $1").replace(/_/g, " ") : label(key)), /* @__PURE__ */ React.createElement("dd", { className: "min-w-0 whitespace-pre-wrap break-words" }, clip(toolValueLabel(t, key, valueText(args[key]))))))), emptyLabel && /* @__PURE__ */ React.createElement("p", { className: "text-dim" }, t(emptyLabel)));
}

// style.css
var style_default = '.veto-tools .visible {\n    visibility: visible\n}\n.veto-tools .fixed {\n    position: fixed\n}\n.veto-tools .mb-1 {\n    margin-bottom: 0.25rem\n}\n.veto-tools .mt-1 {\n    margin-top: 0.25rem\n}\n.veto-tools .mt-2 {\n    margin-top: 0.5rem\n}\n.veto-tools .block {\n    display: block\n}\n.veto-tools .inline-block {\n    display: inline-block\n}\n.veto-tools .flex {\n    display: flex\n}\n.veto-tools .inline-flex {\n    display: inline-flex\n}\n.veto-tools .h-1\\.5 {\n    height: 0.375rem\n}\n.veto-tools .h-3 {\n    height: 0.75rem\n}\n.veto-tools .h-3\\.5 {\n    height: 0.875rem\n}\n.veto-tools .max-h-48 {\n    max-height: 12rem\n}\n.veto-tools .max-h-64 {\n    max-height: 16rem\n}\n.veto-tools .max-h-80 {\n    max-height: 20rem\n}\n.veto-tools .max-h-96 {\n    max-height: 24rem\n}\n.veto-tools .w-1\\.5 {\n    width: 0.375rem\n}\n.veto-tools .w-3 {\n    width: 0.75rem\n}\n.veto-tools .w-3\\.5 {\n    width: 0.875rem\n}\n.veto-tools .w-full {\n    width: 100%\n}\n.veto-tools .min-w-0 {\n    min-width: 0px\n}\n.veto-tools .max-w-full {\n    max-width: 100%\n}\n.veto-tools .flex-1 {\n    flex: 1 1 0%\n}\n.veto-tools .shrink-0 {\n    flex-shrink: 0\n}\n@keyframes pulse {\n    50% {\n        opacity: .5\n    }\n}\n.veto-tools .animate-pulse {\n    animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite\n}\n.veto-tools .cursor-pointer {\n    cursor: pointer\n}\n.veto-tools .select-none {\n    user-select: none\n}\n.veto-tools .list-disc {\n    list-style-type: disc\n}\n.veto-tools .flex-wrap {\n    flex-wrap: wrap\n}\n.veto-tools .items-start {\n    align-items: flex-start\n}\n.veto-tools .items-center {\n    align-items: center\n}\n.veto-tools .items-baseline {\n    align-items: baseline\n}\n.veto-tools .gap-1\\.5 {\n    gap: 0.375rem\n}\n.veto-tools .gap-2 {\n    gap: 0.5rem\n}\n.veto-tools .gap-3 {\n    gap: 0.75rem\n}\n.veto-tools .gap-x-2 {\n    column-gap: 0.5rem\n}\n.veto-tools :is(.space-y-1 > :not([hidden]) ~ :not([hidden])) {\n    --tw-space-y-reverse: 0;\n    margin-top: calc(0.25rem * calc(1 - var(--tw-space-y-reverse)));\n    margin-bottom: calc(0.25rem * var(--tw-space-y-reverse))\n}\n.veto-tools :is(.space-y-1\\.5 > :not([hidden]) ~ :not([hidden])) {\n    --tw-space-y-reverse: 0;\n    margin-top: calc(0.375rem * calc(1 - var(--tw-space-y-reverse)));\n    margin-bottom: calc(0.375rem * var(--tw-space-y-reverse))\n}\n.veto-tools :is(.space-y-2 > :not([hidden]) ~ :not([hidden])) {\n    --tw-space-y-reverse: 0;\n    margin-top: calc(0.5rem * calc(1 - var(--tw-space-y-reverse)));\n    margin-bottom: calc(0.5rem * var(--tw-space-y-reverse))\n}\n.veto-tools :is(.space-y-3 > :not([hidden]) ~ :not([hidden])) {\n    --tw-space-y-reverse: 0;\n    margin-top: calc(0.75rem * calc(1 - var(--tw-space-y-reverse)));\n    margin-bottom: calc(0.75rem * var(--tw-space-y-reverse))\n}\n.veto-tools .overflow-auto {\n    overflow: auto\n}\n.veto-tools .overflow-hidden {\n    overflow: hidden\n}\n.veto-tools .overflow-y-auto {\n    overflow-y: auto\n}\n.veto-tools .truncate {\n    overflow: hidden;\n    text-overflow: ellipsis;\n    white-space: nowrap\n}\n.veto-tools .whitespace-pre-wrap {\n    white-space: pre-wrap\n}\n.veto-tools .break-words {\n    overflow-wrap: break-word\n}\n.veto-tools .break-all {\n    word-break: break-all\n}\n.veto-tools .rounded {\n    border-radius: 0.25rem\n}\n.veto-tools .rounded-full {\n    border-radius: 9999px\n}\n.veto-tools .rounded-lg {\n    border-radius: 0.5rem\n}\n.veto-tools .rounded-md {\n    border-radius: 0.375rem\n}\n.veto-tools .rounded-r {\n    border-top-right-radius: 0.25rem;\n    border-bottom-right-radius: 0.25rem\n}\n.veto-tools .border {\n    border-width: 1px\n}\n.veto-tools .border-b {\n    border-bottom-width: 1px\n}\n.veto-tools .border-l-2 {\n    border-left-width: 2px\n}\n.veto-tools .border-t {\n    border-top-width: 1px\n}\n.veto-tools .border-accent\\/40 {\n    border-color: rgb(var(--accent) / 0.4)\n}\n.veto-tools .border-pass {\n    --tw-border-opacity: 1;\n    border-color: rgb(var(--pass) / var(--tw-border-opacity, 1))\n}\n.veto-tools .border-pass\\/30 {\n    border-color: rgb(var(--pass) / 0.3)\n}\n.veto-tools .border-pass\\/40 {\n    border-color: rgb(var(--pass) / 0.4)\n}\n.veto-tools .border-pass\\/50 {\n    border-color: rgb(var(--pass) / 0.5)\n}\n.veto-tools .border-rule {\n    --tw-border-opacity: 1;\n    border-color: rgb(var(--rule) / var(--tw-border-opacity, 1))\n}\n.veto-tools .border-rule\\/60 {\n    border-color: rgb(var(--rule) / 0.6)\n}\n.veto-tools .border-verdict {\n    --tw-border-opacity: 1;\n    border-color: rgb(var(--verdict) / var(--tw-border-opacity, 1))\n}\n.veto-tools .border-verdict\\/30 {\n    border-color: rgb(var(--verdict) / 0.3)\n}\n.veto-tools .bg-codebg {\n    --tw-bg-opacity: 1;\n    background-color: rgb(20 24 31 / var(--tw-bg-opacity, 1))\n}\n.veto-tools .bg-dim\\/50 {\n    background-color: rgb(var(--dim) / 0.5)\n}\n.veto-tools .bg-ink {\n    --tw-bg-opacity: 1;\n    background-color: rgb(var(--ink) / var(--tw-bg-opacity, 1))\n}\n.veto-tools .bg-ink\\/50 {\n    background-color: rgb(var(--ink) / 0.5)\n}\n.veto-tools .bg-ink\\/60 {\n    background-color: rgb(var(--ink) / 0.6)\n}\n.veto-tools .bg-panel {\n    --tw-bg-opacity: 1;\n    background-color: rgb(var(--panel) / var(--tw-bg-opacity, 1))\n}\n.veto-tools .bg-pass {\n    --tw-bg-opacity: 1;\n    background-color: rgb(var(--pass) / var(--tw-bg-opacity, 1))\n}\n.veto-tools .bg-pass\\/10 {\n    background-color: rgb(var(--pass) / 0.1)\n}\n.veto-tools .bg-pass\\/15 {\n    background-color: rgb(var(--pass) / 0.15)\n}\n.veto-tools .bg-raised\\/30 {\n    background-color: rgb(var(--raised) / 0.3)\n}\n.veto-tools .bg-verdict\\/10 {\n    background-color: rgb(var(--verdict) / 0.1)\n}\n.veto-tools .bg-verdict\\/15 {\n    background-color: rgb(var(--verdict) / 0.15)\n}\n.veto-tools .p-2 {\n    padding: 0.5rem\n}\n.veto-tools .p-3 {\n    padding: 0.75rem\n}\n.veto-tools .px-1\\.5 {\n    padding-left: 0.375rem;\n    padding-right: 0.375rem\n}\n.veto-tools .px-2 {\n    padding-left: 0.5rem;\n    padding-right: 0.5rem\n}\n.veto-tools .px-3 {\n    padding-left: 0.75rem;\n    padding-right: 0.75rem\n}\n.veto-tools .py-0\\.5 {\n    padding-top: 0.125rem;\n    padding-bottom: 0.125rem\n}\n.veto-tools .py-1 {\n    padding-top: 0.25rem;\n    padding-bottom: 0.25rem\n}\n.veto-tools .py-1\\.5 {\n    padding-top: 0.375rem;\n    padding-bottom: 0.375rem\n}\n.veto-tools .py-2 {\n    padding-top: 0.5rem;\n    padding-bottom: 0.5rem\n}\n.veto-tools .py-3 {\n    padding-top: 0.75rem;\n    padding-bottom: 0.75rem\n}\n.veto-tools .pl-3 {\n    padding-left: 0.75rem\n}\n.veto-tools .pl-4 {\n    padding-left: 1rem\n}\n.veto-tools .pr-3 {\n    padding-right: 0.75rem\n}\n.veto-tools .text-right {\n    text-align: right\n}\n.veto-tools .font-mono {\n    font-family: "IBM Plex Mono", ui-monospace, monospace\n}\n.veto-tools .text-\\[10px\\] {\n    font-size: 10px\n}\n.veto-tools .text-\\[11px\\] {\n    font-size: 11px\n}\n.veto-tools .text-xs {\n    font-size: 0.75rem;\n    line-height: 1rem\n}\n.veto-tools .uppercase {\n    text-transform: uppercase\n}\n.veto-tools .normal-case {\n    text-transform: none\n}\n.veto-tools .italic {\n    font-style: italic\n}\n.veto-tools .leading-relaxed {\n    line-height: 1.625\n}\n.veto-tools .tracking-wider {\n    letter-spacing: 0.05em\n}\n.veto-tools .text-\\[\\#7A8694\\] {\n    --tw-text-opacity: 1;\n    color: rgb(122 134 148 / var(--tw-text-opacity, 1))\n}\n.veto-tools .text-\\[\\#DDE3EA\\] {\n    --tw-text-opacity: 1;\n    color: rgb(221 227 234 / var(--tw-text-opacity, 1))\n}\n.veto-tools .text-accent {\n    --tw-text-opacity: 1;\n    color: rgb(var(--accent) / var(--tw-text-opacity, 1))\n}\n.veto-tools .text-accent\\/70 {\n    color: rgb(var(--accent) / 0.7)\n}\n.veto-tools .text-dim {\n    --tw-text-opacity: 1;\n    color: rgb(var(--dim) / var(--tw-text-opacity, 1))\n}\n.veto-tools .text-dim\\/60 {\n    color: rgb(var(--dim) / 0.6)\n}\n.veto-tools .text-dim\\/70 {\n    color: rgb(var(--dim) / 0.7)\n}\n.veto-tools .text-paper {\n    --tw-text-opacity: 1;\n    color: rgb(var(--paper) / var(--tw-text-opacity, 1))\n}\n.veto-tools .text-paper\\/70 {\n    color: rgb(var(--paper) / 0.7)\n}\n.veto-tools .text-paper\\/80 {\n    color: rgb(var(--paper) / 0.8)\n}\n.veto-tools .text-pass {\n    --tw-text-opacity: 1;\n    color: rgb(var(--pass) / var(--tw-text-opacity, 1))\n}\n.veto-tools .text-verdict {\n    --tw-text-opacity: 1;\n    color: rgb(var(--verdict) / var(--tw-text-opacity, 1))\n}\n.veto-tools .filter {\n    filter: var(--tw-blur) var(--tw-brightness) var(--tw-contrast) var(--tw-grayscale) var(--tw-hue-rotate) var(--tw-invert) var(--tw-saturate) var(--tw-sepia) var(--tw-drop-shadow)\n}\n.veto-tools .hover\\:underline:hover {\n    text-decoration-line: underline\n}';

// index.tsx
var tools = ["answer_with_citations", "ask_user", "cancel_group_task", "cancel_monitor", "create_group", "create_mate", "create_monitor", "create_task", "delete_path", "disband_group", "find_files", "forget_memory", "grep_search", "input_task", "inspect_group", "inspect_monitor", "list_dir", "load_skill", "move_path", "pause_monitor", "post_message", "read_github_repository", "recall_memory", "remove_mate", "remove_node", "replace_file_content", "resume_monitor", "run_command", "run_task", "stop_task", "submit_plan", "view_file", "view_task", "web_fetch", "web_search", "write_memory", "write_to_file"];
function activate(host2) {
  configure(host2);
  for (const toolName of tools) {
    const wrap = (View) => (props) => /* @__PURE__ */ React.createElement(LocaleScope, { locale: props.context.locale }, /* @__PURE__ */ React.createElement("div", { className: "veto-tools" }, /* @__PURE__ */ React.createElement("style", null, style_default), /* @__PURE__ */ React.createElement(View, { ...props, text: props.text ?? "", toolName })));
    host2.registerToolRenderer(toolName, { call: wrap(ToolCallCard), result: wrap(ToolResultBody), conversation: wrap(Conversation), headerTarget: (args) => {
      const key = toolHeaderField(toolName, args);
      return key && typeof args[key] === "string" ? String(args[key]) : void 0;
    } });
  }
}
function Conversation({ toolName = "", args = {}, result }) {
  return /* @__PURE__ */ React.createElement("div", { className: "min-w-0" }, /* @__PURE__ */ React.createElement(ToolConversationDetails, { toolName, args, headerField: toolHeaderField(toolName, args), result }), ["web_fetch", "web_read"].includes(toolName) && typeof args.objective === "string" && /* @__PURE__ */ React.createElement("p", { className: "p-3 whitespace-pre-wrap break-words" }, args.objective), ["codeContent", "targetContent", "replacementContent"].filter((key) => typeof args[key] === "string").map((key) => /* @__PURE__ */ React.createElement(ContentPreview, { key, field: key, content: String(args[key]) })));
}
function ContentPreview({ field, content }) {
  const { t } = useI18n();
  const excerpt2 = content.split("\n").slice(0, 8).join("\n").slice(0, 1e3);
  return /* @__PURE__ */ React.createElement("div", { className: "min-w-0 p-3" }, /* @__PURE__ */ React.createElement("p", { className: "mb-1 text-[10px] text-dim" }, t(field === "targetContent" ? "tool.replaceBefore" : field === "replacementContent" ? "tool.replaceAfter" : "tool.contentPreview")), /* @__PURE__ */ React.createElement("pre", { className: "whitespace-pre-wrap break-words rounded-md border-l-2 border-pass/50 bg-ink/60 px-3 py-2 font-mono text-xs text-paper" }, excerpt2, excerpt2.length < content.length ? "\n\u2026" : ""));
}
export {
  Fields,
  LocaleScope,
  activate,
  tools
};
