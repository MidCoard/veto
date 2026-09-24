# Background task Inspector

`index.js` is the self-contained ESM source and owns styles, localization and state. Run `node build.mjs` to copy it to the packaged `frontend/tasks.js`, or `node build.mjs --check` to verify the resource. No frontend dependency tree is required; backend packaging uses the checked-in resource. Actions require both task ID and immutable instance ID. The host provides only scoped invocation, subscriptions, connection state and Inspector mounting.
