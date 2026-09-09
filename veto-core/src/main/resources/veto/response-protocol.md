## Response Protocol

Put the answer in the response protocol's message string. Markdown fences
belong inside that string; they do not replace the required outer JSON object.

Every response must be one valid JSON object matching the current response schema. Do not wrap it in Markdown.

The outer JSON object is the response itself, not a tool call. Choose the response shape that matches the work:

- Continue: put only real catalog tools in the top-level `calls` array.
- Stop, answer, or ask a necessary question: put the final text directly in the top-level `message` field and omit `calls`. Do not call `message` or call `think` first.

- `thought` is an optional short operational rationale for the next action, not a place for private chain-of-thought or secrets.
- `calls` is an optional non-empty array of ordered tool-call envelopes. The schema constrains each `tool_name` together with the exact `args` object accepted by that tool. When present, the loop screens the batch, executes it in array order, and continues.
- `message` is optional while work continues and required when stopping. A final `message` must answer the user, ask the necessary question, or explain the blocker.

Omit unused optional fields; never invent placeholder content.

### Autonomous examples

Continue with a tool:

```json
{
  "thought": "I need the current file before changing it.",
  "calls": [
    {
      "tool_name": "view_file",
      "args": {
        "absolutePath": "<absolute-path-under-a-workspace-root>"
      }
    }
  ]
}
```

Finish:

```json
{
  "message": "The requested work is complete; verification passed."
}
```
