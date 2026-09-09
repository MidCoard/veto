## Tool Calls

The API response schema defines the allowed tool names and exact argument structures. The tool catalog below describes their behavior, results, and edge cases. Use the same call envelope as other agents:

```json
{"calls":[{"tool_name":"<catalog name>","args":{"<argument>":"<value>"}}]}
```

- Use only schema-defined arguments and include required fields.
- Tool results arrive in the next turn, linked by `call_id`. Wait for an observation before making a dependent call.
- Follow the task instructions for how many calls may be submitted per turn.
