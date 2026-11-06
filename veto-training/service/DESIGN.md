# Trainer UI

React 18 and Material UI own this standalone training console. `web/src/main.tsx`
creates the MUI theme; controls use MUI defaults and layout uses `sx`. Do not add
parallel button/input/dialog CSS. Match Veto's graphite/cyan console and 8px thin
scrollbar across document, dialogs and tables. A narrow desktop navigation rail,
compact grouped lists and dense forms replace stretched cards; narrow screens stack.
No redundant branding or instructional banners. Long paths remain readable without
inflating list rows. Python serves the Vite build from `web/dist`; training remains in
independent processes. Main Veto UI is outside this migration milestone.

The Model tab has one Add Model dialog for Hugging Face downloads and existing local content; both feed the same catalog and retain task/run references. Remove Model permanently deletes the actual model after full-path irreversible confirmation, including complete Hub cache entries. Bound tasks remain listed in the warning and need a replacement model; they do not block deletion. Completed registered downloads are hidden, and GGUF is inference-only. Task deletion removes configuration only. CreateTask selects registered models and active managed datasets only. Dataset paths and JSONL source selection are not task-form controls; JSONL import belongs to Dataset management. Query-string navigation preserves the selected page on reload.

CreateTask chooses an owner template (Core screening or Secret), preserves editable settings, and saves without launching. It selects registered trainable models by display name; other local models are registered on the Model page first. Existing unregistered recipes remain usable, while GGUF is not trainable. New tasks default to an active managed dataset compatible with the owner template. Managed-dataset selection is restored from frozen snapshots when editing, and template changes preserve user-entered task names. Task forms have no JSONL/path source controls. A selected managed dataset owns the prompt format; no independent format selector remains active. Edit affects future runs only. Saving validates fields; every start automatically validates files and runtime prerequisites. These checks do not claim model quality. Completed runs expose model artifacts and register them in Models; deployment is separate.

All task cards show cached train/validation nonblank JSONL record counts. Counting does not imply semantic validation. Automatic validation errors appear in the action dialog. MUI theme uses compact desktop controls, 4px corners, flat selected tabs and no ripple, with visible keyboard focus.


Dataset management shards JSONL files at 1,000 records or 32 MB, with file-level split, enable/disable, permanent deletion and bounded three-record preview. Saving a task automatically creates a fixed snapshot of enabled files; existing runs remain unchanged. Legacy and oversized current stores migrate through journaled staging, retaining recycled records in disabled files. No manual snapshot or recycle-bin action remains. Reuse MUI dialogs and controls.

Models supports Hub download, explicit pause/resume and automatic registration after complete download. Tokens are ephemeral, never stored in download metadata. Tasks may choose a managed dataset; save freezes train/validation exports. Runs expose bounded local logs, immediate stop, archive and restore. Dataset import accepts a browser file or a server path; archives retain samples and snapshots.

Completed runs show human task names and artifact filenames. Full paths remain in model management and the output-directory disclosure. Hide the completed progress bar, repeated completion message and empty resume section; keep training and validation loss separate.

Failed or interrupted exports expose retry only when a complete merged model, saved adapter or optimization-finished checkpoint exists. Export never reruns optimization; finished checkpoints are export sources, not ordinary resume inputs. Explicit conversion retry replaces only the current run's expected partial GGUF outputs. Ordinary confirmations and asset references use task/run names rather than internal run IDs.
