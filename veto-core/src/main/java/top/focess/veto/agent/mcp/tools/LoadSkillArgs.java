package top.focess.veto.agent.mcp.tools;

import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.ToolDoc;

/**
 * Parameter container for the {@code load_skill} agent tool.. {@code load_skill} is an {@link
 * top.focess.veto.agent.mcp.AgentToolDefinition} (engine-provided, always-on), not a {@link
 * top.focess.veto.agent.mcp.NativeMcpTool}; its args record carries the schema source reflected by
 * the translator.
 */
@ToolDoc(
        description =
                """
                #### When to use
                Use `load_skill` to load a skill's full instructions into your context as an observation, when \
                the current task maps to a named skill listed under "## Available Skills" - e.g. `verify_suite`, \
                `git-rebase`. A skill bundles a reusable procedure (steps, checks, conventions) that you should \
                follow verbatim for that class of work. Loading it makes its body available for the rest of the \
                turn.

                #### When NOT to use
                - Do not call `load_skill` speculatively for skills not listed in "## Available Skills" - the \
                name must match a registered skill.
                - Do not call it when you already know the procedure and no skill is advertised; just proceed.
                - Do not call it repeatedly for the same skill in one turn; load once and act.

                #### Behavior
                Looks up the skill by `skillName` among the skills advertised for this session and returns its \
                full instruction body as an observation. The skill body is guidance/instructions (DATA framing is \
                applied at ingress). After loading, follow the skill's procedure for the remainder of the turn.

                #### Return format
                The skill's full instruction body as an observation (text). If the skill name is unknown, an \
                error observation is returned.

                #### Errors & edge cases
                - Unknown `skillName` -> error observation (skill not found). Check the "## Available Skills" \
                list for the exact name.
                - `skillName` is case-sensitive and must match exactly.
                - Loading a skill does not execute anything; it only provides instructions you then act on with \
                other tools.

                #### Security
                `load_skill` is an `AgentToolDefinition` (engine-provided, always-on). The Gateway returns \
                `NotScreened` - no path, no host content. The skill body is trusted deployer-provided guidance, \
                not user-supplied content; it is not semantically screened. Safe to call any time.
                """,
        examples = {
            "{\"skillName\": \"git-rebase\"}",
            "{\"skillName\": \"verify_suite\"}",
            "{\"skillName\": \"deploy\"}",
            "{\"skillName\": \"refactor-extract-method\"}",
            "{\"skillName\": \"write-tests\"}",
            "{\"skillName\": \"code-review\"}"
        })
public record LoadSkillArgs(
        @Doc("The name of the skill to load (e.g. 'verify_suite').") String skillName) {}
