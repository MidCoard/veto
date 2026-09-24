# Builtin tool presentation

These API-only components own builtin call, conversation and result presentation. The host supplies React, Markdown and code highlighting as generic UI primitives. Catalog metadata supplies effective aliases; renderers register only local contributed tool IDs.

From this directory run `node build.mjs /path/to/veto-ui` using an existing frontend checkout with its locked npm dependencies installed. The path is an explicit toolchain input, works on Windows and Unix, and is not embedded in the output. The self-contained ESM resource is `../../src/main/resources/frontend/tools.js`; commit the regenerated resource with source changes. Frontend integration tests load that exact ESM resource. Backend Gradle builds package the resource as-is and never invoke this script or depend on a frontend checkout. The host never scans builtin sources for CSS.

Verify the checked-in resource without modifying files with `node build.mjs /path/to/veto-ui --check`. Build output is independent of the invoking working directory.
