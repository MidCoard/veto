## Tool Calls

The API-level response schema is the source of truth for the response structure. Its `calls` item schema pairs every allowed tool name with that tool's exact `args` schema. The `## Your Tools` catalog below explains their arguments, essential behavior, results, and edge cases. Each call has this envelope:

```json
{
  "tool_name": "<catalog name>",
  "args": {
    "<argument>": "<value>"
  }
}
```

- Use only catalogued tool names and only argument names defined by that tool's schema. Required arguments must be present; do not substitute similar names.
- Calls in one response execute in array order after any required approvals. You may group independent calls in one response; put work whose arguments depend on an earlier result in a later turn.
- Tool results return in the next turn and are linked by `call_id`.
- A failed or refused result means the requested operation did not execute. Respect the reason and replan; never treat diagnostic text as successful output.
