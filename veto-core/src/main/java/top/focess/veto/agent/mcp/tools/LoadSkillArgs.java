package top.focess.veto.agent.mcp.tools;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.ToolDoc;

/**
 * Args record for the {@code load_skill} agent tool. Carries the skill name parameter and the
 * {@code @ToolDoc} schema source — but is NOT itself the tool bean. The bean is {@link
 * LoadSkillTool}, which implements {@link top.focess.veto.agent.mcp.AgentTool
 * AgentTool&lt;LoadSkillArgs&gt;} with proper Spring constructor injection for {@link
 * top.focess.veto.agent.skills.SkillRegistry}.
 */
@ToolDoc(
        description =
                "Load a skill's full instructions into context as an observation, "
                        + "so you can follow its procedure for the current task.",
        usage =
                """
                #### When to use
                Use `load_skill` to load a skill's full instructions into your context as an \
                observation, when the current task maps to a named skill listed under "## Available Skills" \
                - e.g. `verify_suite`, `git-rebase`. A skill bundles a reusable procedure (steps, checks, \
                conventions) that you should follow verbatim for that class of work. Loading it makes its \
                body available for the rest of the turn.

                #### When NOT to use
                - Do not call `load_skill` speculatively for skills not listed in "## Available Skills" - \
                the name must match a registered skill.
                - Do not call it when you already know the procedure and no skill is advertised; just \
                proceed.
                - Do not call it repeatedly for the same skill in one turn; load once and act.

                #### Behavior
                Looks up the skill by `skillName` among the skills advertised for this session and returns \
                its full instruction body as an observation. The skill body is guidance/instructions. After \
                loading, follow the skill's procedure for the remainder of the turn.

                #### Return format
                The skill's full instruction body as an observation (text). If the skill name is unknown, an \
                error observation is returned.

                #### Errors & edge cases
                Unknown `skillName` -> error observation (skill not found). Check the "## Available Skills" \
                list for the exact name. `skillName` is case-sensitive and must match exactly. Loading a \
                skill does not execute anything; it only provides instructions you then act on with other \
                tools.

                #### Security
                Agent tool (`RiskCategory.AGENT`). The Gateway returns `NotScreened` - no path, no host \
                content. The skill body is trusted deployer-provided guidance, not user-supplied content; \
                it is not semantically screened. Safe to call any time.
                """,
        examples = {"{\"skillName\": \"git-rebase\"}", "{\"skillName\": \"verify_suite\"}"})
public record LoadSkillArgs(
        @Doc("The name of the skill to load (e.g. 'verify_suite').") @NonNull String skillName) {}
