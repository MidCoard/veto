rootProject.name = "veto"

include(
    "veto-protocol",
    "veto-nullness-checker",
    "veto-secret-protection",
    "veto-api",
    "veto-builtin",
    "veto-plugin-runtime",
    "veto-core",
    "veto-terminal",
    "veto-training",
)

include("veto-llm-providers")
