## How to Call Tools

Follow the API response schema when constructing a tool call. Each item in `calls` pairs an allowed `tool_name` with its exact `args` schema. The Your Tools section explains each tool's arguments, behavior, results, and edge cases.

Use this structure for each call:

```json
{
  "tool_name": "<catalog name>",
  "args": {
    "<argument>": "<value>"
  }
}
```

- Use only tool names and argument names listed in the schema. Include every required argument and do not substitute a similar name.
- Calls in one response execute in array order after any required approvals. Group independent calls in one response when useful. Submit a call in a later turn when its arguments depend on an earlier result.
- Read tool results in the next turn. Match each result to its call using `call_id`.
- Treat a failed or refused result as an operation that did not execute. Respect the reason and revise the next step. Do not treat an error message as successful output.
