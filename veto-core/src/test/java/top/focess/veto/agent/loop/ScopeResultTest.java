package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.workflow.Scope;

class ScopeResultTest {
    @Test
    void runtimeBookkeepingRemainsAvailableToChecksButIsNotPublished() {
        Scope scope = new Scope(new ObjectMapper());
        scope.put("CURRENT_STEPS", 3);
        scope.put("step_ok:read", true);
        scope.put("answer", "Ready for review.");
        scope.put("step_ok:generate", true);

        assertEquals("answer=Ready for review.", scope.synthesize());
        assertEquals(3, scope.get("CURRENT_STEPS"));
        assertEquals(true, scope.get("step_ok:read"));
    }

    @Test
    void bookkeepingOnlyScopeProducesNoVisibleResult() {
        Scope scope = new Scope(new ObjectMapper());
        scope.put("CURRENT_STEPS", 1);
        scope.put("step_ok:read", false);

        assertEquals("", scope.synthesize());
    }

    @Test
    void similarlyNamedUserBindingsArePreserved() {
        Scope scope = new Scope(new ObjectMapper());
        scope.put("CURRENT_STEPS_summary", "Three steps");
        scope.put("step_ok_summary", "Complete");

        assertEquals(
                "CURRENT_STEPS_summary=Three steps\nstep_ok_summary=Complete", scope.synthesize());
    }

    @Test
    void userBindingsHaveDeterministicOrderRegardlessOfInsertionAndBookkeeping() {
        Scope scope = new Scope(new ObjectMapper());
        scope.put("zeta", "First inserted");
        scope.put("CURRENT_STEPS", 2);
        scope.put("alpha", "Second inserted");
        scope.put("step_ok:read", true);
        scope.put("zeta", "Updated first binding");

        String expected = "alpha=Second inserted\nzeta=Updated first binding";
        assertEquals(expected, scope.synthesize());
        scope.put("CURRENT_STEPS", 3);
        scope.put("step_ok:generate", true);
        assertEquals(expected, scope.synthesize());

        Scope reversed = new Scope(new ObjectMapper());
        reversed.put("alpha", "Second inserted");
        reversed.put("zeta", "Updated first binding");
        assertEquals(expected, reversed.synthesize());
    }

    @Test
    void unavailableFailureOutputsAreOmittedFromVisibleResult() {
        Scope scope = new Scope(new ObjectMapper());
        scope.put("unavailable", Scope.UNDEFINED);
        scope.put("CURRENT_STEPS", 4);
        scope.put("step_ok:read", false);

        assertEquals("", scope.synthesize());
        scope.put("answer", "The source could not be read.");
        assertEquals("answer=The source could not be read.", scope.synthesize());
        assertEquals(Scope.UNDEFINED, scope.get("unavailable"));
    }
}
