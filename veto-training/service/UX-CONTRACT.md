# Trainer interactions

MUI Button, TextField, Select, Dialog and Accordion own controls and focus behavior.
React preserves form drafts and disclosure state across polling. Snapshot refresh
runs every 5 seconds while visible. Offline/stale actions fail without automatic
mutation retries. Keep real errors visible, no routine connection banners. Desktop
uses a compact navigation rail and grouped lists; narrow screens stack. Veto's 8px
thin scrollbar is shared by document, dialogs and tables. Preserve keyboard focus.

Create saves a recipe without training. Start/resume require confirmation. Resume
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

CreateTask chooses an owner template (Core screening or Secret), preserves editable settings, and saves without launching. Model selection uses registered trainable model names rather than a directory input; local-path registration belongs to the Model page. Existing unregistered recipes remain readable. New tasks select a compatible managed dataset by default. Editing restores the managed dataset that owns the frozen snapshots, and template changes do not erase task names. Task forms contain no JSONL/path source controls. Managed datasets own their prompt format, so the independent format control is absent. Edit affects future runs only. Saving validates fields; every start automatically validates files and runtime prerequisites. These checks do not claim model quality. Completed runs expose model artifacts and register them in Models; deployment is separate.

All task cards show cached train/validation nonblank JSONL record counts. Counting does not imply semantic validation. Automatic validation errors appear in the action dialog. MUI theme uses compact desktop controls, 4px corners, flat selected tabs and no ripple, with visible keyboard focus.


Dataset management shards at 1,000 records or 32 MB and uses file-level split, enable/disable, confirmed permanent deletion and three-record preview. Tasks automatically snapshot enabled files; existing runs remain unchanged. Legacy recycled records migrate into disabled files. No manual snapshot or recycle-bin action remains. Reuse MUI dialogs and controls.

Dataset writes use per-dataset revision checks. Failed imports publish no partial files. New-sample input uses MUI multiline input; tables keep a readable minimum width within a local horizontal scroll region on narrow screens. Dataset budget checks preserve the global balance while scanning only the managed write scope.

Models supports Hub download, explicit pause/resume and automatic registration after complete download. Tokens are ephemeral, never stored in download metadata. Tasks may choose a managed dataset; save freezes train/validation exports. Runs expose bounded local logs, immediate stop, archive and restore. Dataset import accepts a browser file or a server path; archives retain samples and snapshots.

Completed runs use task names and artifact filenames; full paths remain in model management and the output-directory disclosure. Hide redundant completed progress and empty resume sections. Training and validation loss remain distinct.

Retry export requires confirmation and never restarts optimization. It is available only with a complete merged model, saved adapter or optimization-finished checkpoint; such finished checkpoints never appear as ordinary resume choices. Keep retry failure in the run and logs. Ordinary dialogs and model references omit internal run IDs.
