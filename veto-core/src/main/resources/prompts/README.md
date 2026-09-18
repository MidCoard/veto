# Prompt sources

All bundled prompts live in `prompts/`. `PromptLibrary` discovers `**/*.mdc`
recursively in both the source tree and the packaged JAR.

## Where to make a change

| Area | Responsibility |
| --- | --- |
| `system/` | Three entry documents: main agent, tool agent, and web reader |
| `system/roles/` | Agent identities and responsibilities |
| `system/policies/` | General behavior, instruction authority, and answer presentation |
| `system/context/` | Workspace, environment, workspace rules, and available skills |
| `tools/` | Tool invocation, behavioral documentation, and result presentation |
| `workflows/plan/` | Executable plans and their generation and judgment steps |
| `workflows/delegation/` | Group creation, leader/mate coordination, and role transitions |
| `workflows/screening/` | Internal tool-call screening instructions and labels |
| `protocol/` | Shared native response channel, citation references, and completion |
| `runtime/interaction/` | Approval, feedback, cancellation, interruption, and refusal |
| `runtime/recovery/` | Resuming work and reporting recovered/background state |
| `runtime/validation/` | Actual response-validation diagnostics |
| `runtime/compaction/` | Context summarization inputs, instructions, and schema |

Start at `system/default-system-prompt.mdc` to see the main composition order.
Keep entry documents limited to composition; edit the responsible category for
behavior changes. Related workflow instructions and runtime messages stay together.

## One owner for each contract

- Native tool schemas define argument types, required fields, and constraints.
  The catalogue renders the argument list from the same translated schema the
  provider receives — never a second, hand-written schema — and adds behavior,
  results, errors, and examples around it.
- `protocol/response-contract.mdc` defines the response channel from actual runtime
  capabilities. All provider adapters use `provider-native`; validation retries
  reuse the same response contract.
- Runtime validation messages report real runtime state or validator failures.
  Do not add reminders tailored to a failed trial, substitute a different user
  task, or conceal errors to make a test pass.
- Model-authored observations and substituted values are data, not MDC source.

## Stable IDs and custom folders

The filename without `.mdc` is its globally unique lookup/include ID. The
front-matter `id` must agree. Moving a source between folders does not change
Java callers or `@include` directives. Duplicate basenames fail loading.

A trusted application extension can add `custom/my-project/review-rules.mdc`
and include it as `@include review-rules`. This mechanism does not load arbitrary
workspace files. Source changes require rebuilding the application.

## Compilation

1. `PromptLibrary` discovers the trusted resource package.
2. `PromptDocument` compiles version-2 MDC, validates required inputs, expands
   includes, and records source spans and message boundaries.
3. `PromptCompiler` provides context and assembles each model request.

`PromptSource` is the separate version-1 string compiler; it is not this loader.

## Compatibility and compaction

The delegation folder retains `leader-author` and `leader-pivot` for the legacy
`LlmLeader` API. Production uses `HeuristicLeader` and normal leader tools.
`mate-default-guidance` is the test-constructor fallback; production mate guidance
comes from the persona and configured role guidance.

Compaction validates structure, source IDs, source roles, and size. It cannot
prove the semantic accuracy of a model-written summary. Invalid summaries leave
original history intact; historical summaries remain readable as attributed data.
