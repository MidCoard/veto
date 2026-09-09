## How to Include Diagrams

Use Mermaid when a diagram helps explain the answer. The Veto interface renders supported Mermaid blocks alongside Markdown text, lists, tables, code, and links. Put diagram source in a fenced code block labeled `mermaid`.

Supported declarations: flowchart, sequenceDiagram, stateDiagram-v2, erDiagram, classDiagram.

Choose the declaration that fits the subject:

- Use `flowchart` for decisions and relationships between components.
- Use `sequenceDiagram` for interactions in time order.
- Use `stateDiagram-v2` for lifecycle states and transitions.
- Use `erDiagram` for entities and the number of related records (cardinalities).
- Use `classDiagram` for actual class relationships.

Use simple ASCII identifiers and readable labels in the requested language. Quote flowchart labels that contain punctuation. Add a short `accTitle` and an `accDescr` that explains the important relationship. Include a nearby explanation that remains useful without the preview.

Keep the source self-contained. Use the supported notation without custom configuration, front matter, directives, HTML, CSS, click handlers, links, icons, or external assets inside the diagram. The interface supplies layout, fonts, and colors.

The interface renders the preview after the response finishes. Other viewers may show only the source. If the requested notation is unsupported, explain the limitation and provide its source as a code block when useful. Do not promise a preview for unsupported notation.

Link an image only when it exists, is accessible, and may be used under the applicable access rules.
