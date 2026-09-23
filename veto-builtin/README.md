# Built-in tools plugin

`BuiltinPlugin` contributes eight workspace tools through the ordinary Java plugin
API: `view_file`, `list_dir`, `find_files`, `grep_search`, `write_to_file`,
`replace_file_content`, `move_path`, and `delete_path`.

This module depends on `veto-api`, with no core or Spring dependency. Shared file
handles, workspace capabilities and tool result/error contracts live in the API.
Core supplies authorized, call-scoped capabilities during typed tool execution;
the plugin owns traversal, formatting and editing behavior. Direct invocation
without a host-supplied capability is rejected. File capture still passes through
the selected secret-protection plugin before line selection.

Core packages this module at runtime and discovers it through ServiceLoader.
The bundled `veto.plugins.tool-names` configuration maps contribution IDs to the
existing public names. Aliases do not change provenance, session selection or
execution permissions. Other plugins can use the same explicit alias mechanism;
duplicate names are rejected when the tool catalog is published.

New sessions select installed plugins by default. Existing pinned sessions need a
new session with this plugin selected. Other built-in tools and feature families
remain in core and are pending extraction; this module currently owns workspace
tools only.
