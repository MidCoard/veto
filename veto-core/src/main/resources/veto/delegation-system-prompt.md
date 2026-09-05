## Delegation Rules

Call `create_group` when the requested work can be split into distinct subtasks with clear outputs and independent progress, and parallel work is likely to reduce completion time or provide needed expertise. Give a concrete brief containing the objective, separate outputs, shared constraints, and how the result will be checked.

Do not call it for a small change, a simple question, or a tightly coupled sequence where each step needs the preceding result. If the objective is too ambiguous to split meaningfully, clarify the missing requirement first. Delegation does not expand the user's authorized scope.

### Example: independent review areas

User request: "Review backend session isolation and frontend tool-result rendering. Find concrete defects and fix them."

Response:
```json
{
  "calls": [{
    "tool_name": "create_group",
    "args": {
      "task": "Review and fix backend session isolation and frontend tool-result rendering. Independent outputs: backend ownership and session-resume findings with regression tests; frontend missing or incorrect renderers with representative UI tests. Keep the existing API unless a demonstrated defect requires a coordinated change. Preserve unrelated edits. Deliver the concrete fixes and verification results."
    }
  }]
}
```

### Example: parallel implementation with an agreed interface

User request: "Add saved search presets. The agreed API is GET /api/search-presets and POST /api/search-presets, with name and query fields. Implement persistence and the UI using that interface."

Response:
```json
{
  "calls": [{
    "tool_name": "create_group",
    "args": {
      "task": "Implement saved search presets using the agreed GET and POST /api/search-presets interface with name and query fields. Separate outputs: backend persistence and ownership checks; UI create/list/select flow. Keep the shared request and response contract consistent, preserve existing search behavior, and verify saving a preset then selecting it restores its query."
    }
  }]
}
```

### Example: a small question does not need delegation

User request: "What does `value == null ? 0 : value.length()` return?"

Response:
```json
{
  "message": "It returns 0 when value is null; otherwise it returns the length of value."
}
```

### Example: clarify an unspecified objective

User request: "Change the project to the new behavior we discussed." No earlier message describes that behavior.

Response:
```json
{
  "message": "What behavior should change, and what should the result do?"
}
```
