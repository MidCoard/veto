# Guided execution comparison

Create two sessions in the UI using the same model, workspace, and tool result presentation. Enable Guided execution for one and disable it for the other. The setting is saved with the session and inherited by groups created from it; old sessions default to disabled.

Use this same task in both sessions (adjust the absolute workspace path if needed):

> Read D:/IdeaProjects/veto/examples/guided-comparison/release.txt first, then D:/IdeaProjects/veto/examples/guided-comparison/version.txt, and report the release and version. If this session supports guided execution, submit one guide containing the two reads, a generation step, and STOP. Otherwise use ordinary tool calls. Do not modify either file.

Expected evidence: `release=stable` and `version=42`. Expected answer: stable release, version 42. `guide-response.json` is a concrete model response for the enabled session. It is a response example, not a tool or a user command to execute directly.

## Observable differences

| | Disabled | Enabled |
|---|---|---|
| System prompt | Common rules and ordinary call examples | Also includes guided selection rules, full action semantics and five examples |
| Response schema | thought, calls, message | Also offers optional guide.actions |
| Execution | Ordinary tool-call iterations | May choose ordinary calls or submit a program immediately |
| Program submitted despite disabled setting | Rejected before any program tool executes | Full program validated before execution |
| Records | Actual calls and results | Also displays the submitted program separately from actual calls/results |

The deterministic integration test `GuidedExecutionTest.sameSequentialFileTaskUsesTwoGuidedCallsOrThreeOrdinaryCalls` uses real file tools with scripted model responses. It compares the same two file reads and the same final answer: guided uses two model requests (program + generation), sequential ordinary execution uses three. Model requests were 2 versus 3, actual tool calls 2 versus 2, and both returned `Stable release, version 42.` Prompt character counts depend on the current catalog and workspace. Its output reports actual prompt character lengths and call counts. This verifies the runtime path, not a live provider's willingness to select guided mode or a latency/cost benchmark. Ordinary batched reads can also use two model requests. Guided adds prompt/schema content, so it is not always cheaper.

`disabledSessionRejectsGuideBeforeExecutingItsTool` verifies that disabled sessions reject an attempted guide without executing any of its tools. Other guided tests cover approval/resume, step budgets, explicit failure branches, generation options and semantic conditions.

## Response contract

`guide` is a top-level execution choice with one required non-empty `actions` array. It is mutually exclusive with `calls`. A final answer without work requires `message`. The old `features.guided` and separate top-level `actions` fields are no longer part of the response contract. There is no preparatory mode-switch response or dummy think call.

The setting only permits guided execution; the model can still use ordinary calls. Disabling it removes guided rules/examples from the current system prompt, removes guide from the schema, and disables the runtime entrypoint. Historical prompt snapshots cannot override the current setting. All tools keep the same approvals, workspace restrictions and output masking in both paths.
