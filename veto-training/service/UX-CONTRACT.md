# Trainer interactions

MUI Button, TextField, Select and Dialog own controls and focus behavior.
React preserves form drafts and disclosure state across polling. Snapshot refresh
runs every 5 seconds while visible. Offline/stale actions fail without automatic
mutation retries. Keep real errors visible, no routine connection banners. Desktop
uses a compact navigation rail and grouped lists; narrow screens stack. Veto's 8px
thin scrollbar is shared by document, dialogs, tables and dropdowns. Menus use compact elevated popovers with visible selection. Buttons, navigation, rows and status chips have distinct hover/focus/semantic color states in both themes. Preserve keyboard focus.

Create saves a recipe without training and uses scoped reserve checks to avoid full-tree scan latency. Task forms use sample-reader section styling. Polling retains the React tree only when the complete snapshot is unchanged, so live progress always renders. Active runs show epoch, effective batch, step/max_steps and percentage from epoch start, including 0% before the first optimizer log. Export uses a separate stage: indeterminate merge state, then converter tensor current/total and percentage. Training 100% never means the artifact is ready; 全部完成 ·100% appears only after artifacts are registered. Training policy offers fixed epochs or dynamic early stopping with configurable maximum epochs and patience; the UI displays effective batch from micro-batch × accumulation. Start/resume require confirmation. Resume
uses its frozen run recipe. Pause is acknowledged only when the worker saves a
complete checkpoint. Remove deletes the task from the list, persists across service
restarts and retains historical runs, recipes, datasets and models. Active tasks
cannot be removed. Disabled presets are omitted. At most ten current profiles and
twenty runs are listed. Unsaved form dismissal requires discard confirmation.

| Capability | Canonical owner | Source of truth | Verification |
|---|---|---|---|
| Form | CreateTask / MUI TextField | profiles.validate | React and service tests |
| Select/Listbox | MUI Select via TextField | api.ts formats | keyboard/browser |
| CRUD | App / Manager.action | service state | React/service tests |
| Toast | MUI Alert inline | request result | failed mutation test |
| Scrollbar | document / MUI Dialog | DESIGN.md | responsive browser |

Requests remain loopback-only, same-origin and CSRF/revision checked. Emotion uses
a per-document nonce for styles under CSP. Scripts remain same-origin only.

The Model tab uses one Add Model dialog with Hugging Face and existing-local sources. Both feed the same catalog with task/run references. Remove Model permanently deletes the actual model after a full-path irreversible warning, including complete Hub cache entries. Affected task names appear in the warning; tasks remain and need a replacement rather than blocking deletion. Completed registered downloads are hidden and GGUF is marked inference-only. Task deletion removes configuration only. Task forms always require an active managed dataset and contain no JSONL/path source controls. Query-string navigation preserves the selected page on reload.

CreateTask chooses an owner template (Core screening or Secret), preserves editable settings, and saves without launching. Model selection uses registered trainable model names rather than a directory input; local-path registration belongs to the Model page. Existing unregistered recipes remain readable. New tasks select a compatible managed dataset by default. Editing restores the managed dataset that owns the frozen snapshots, and template changes do not erase task names. Task forms contain no JSONL/path source controls. Managed datasets own their prompt format, so the independent format control is absent. Edit affects future runs only. Users configure each task's output directory; unique run children are shown before start and in details. Saving does not create output. Every start automatically validates files, target-volume reserve and runtime prerequisites. These checks do not claim model quality. Completed runs expose model artifacts and register them in Models; deployment is separate.

All task cards show cached train/validation nonblank JSONL record counts. Counting does not imply semantic validation. Automatic validation errors appear in the action dialog. MUI theme uses compact desktop controls, 4px corners, flat selected tabs and no ripple, with visible keyboard focus.


Dataset management shards at 1,000 records or 32 MB and uses file-level split, enable/disable and confirmed permanent deletion. The Samples action opens one record with previous/next navigation, structured source editing, Markdown-rendered prose and syntax-colored JSON. A full-record JSON mode preserves complete inspection, while each message has an independent raw/rendered MUI toggle. The structured editor adds/removes tools, calls and messages. Dataset definitions are stored once in dataset.json; records store and display editable tool_refs references, with full native tools materialized only in training snapshots. Users can add a shared definition from the Dataset page without editing files. Roles are changed through reader-style clickable badges; the record back action is 返回. Back to View preserves the record dialog and confirms discard inline without a second dialog; an add followed by delete is unchanged and does not prompt. Role cards use compact tinted badges, semantic role accents and aligned header controls; bodies avoid nested panels and redundant labels. Filename and previous / editable-current / total / next controls share one record toolbar; Enter or blur jumps without a separate button. Editor fields avoid duplicate floating labels while retaining accessible names. Tool definitions and calls have independent 阅读/原文 views. Saving keeps the current record open with persisted-content feedback, and numeric record jump avoids repeated next clicks. System uses Markdown, user starts as the complete raw JSON message with optional Markdown content rendering, assistant uses JSON/native tool-call cards, and empty assistant content with no tool_calls property displays a no-call state; JSON text inside content is not a no-call convention. Every collapsed role has a preview, and the editor shares the reader's role accents, tinted headers and spacing. Markdown supports GFM tables and fenced code without raw HTML or automatic remote-image requests; invalid JSON or native-message structure blocks saving. Desktop tables keep all file actions and full status labels visible without horizontal scrolling; narrow-screen overflow stays inside the table. Tasks automatically snapshot enabled files; existing runs remain unchanged. Legacy recycled records migrate into disabled files. No manual snapshot or recycle-bin action remains. Reuse MUI dialogs and controls.

Dataset writes use per-dataset revision checks. Failed imports publish no partial files. New-sample input uses MUI multiline input; tables keep a readable minimum width within a local horizontal scroll region on narrow screens. Dataset budget checks preserve the global balance while scanning only the managed write scope.

Models supports Hub download, explicit pause/resume and automatic registration after complete download. Tokens are ephemeral, never stored in download metadata. Tasks may choose a managed dataset; save freezes train/validation exports. Runs expose bounded local logs, immediate stop, archive and restore. Dataset import accepts a browser file or a server path; archives retain samples and snapshots.

Completed runs use task names and only currently existing artifact filenames; deleted files are not shown as available. Full paths remain in model management and the output-directory disclosure. Hide redundant completed progress and empty resume sections. Training and validation loss remain distinct. MUI Tooltips explain actions, and the user's light/dark choice persists locally.

Retry export requires confirmation and never restarts optimization. It is available only with a complete merged model, saved adapter or optimization-finished checkpoint; such finished checkpoints never appear as ordinary resume choices. Keep retry failure in the run and logs. Ordinary dialogs and model references omit internal run IDs.

Pause pending/pausing states remain visible while numeric progress continues to arrive. Overall percentage counts completed optimizer steps, not a step that has only started. Phase transitions survive a full numeric-progress queue. A failed conversion remains retryable and cannot become completed merely because a partial GGUF file exists. Checkpoint retention preserves a verified recovery point when a newer save is interrupted.
