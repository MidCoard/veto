package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.workflow.ModelFlow;

class ModelFlowStackTest {
    @Test
    void defaultIsBottomAndOnlyCurrentFrameCanPop() {
        var stack = new ModelFlowStack();
        ModelFlow first = runtime -> {};
        ModelFlow second = runtime -> {};
        assertTrue(stack.defaultSelected());
        assertThrows(IllegalStateException.class, () -> stack.pop(first));
        stack.push(first);
        stack.pushNested(first, second);
        assertSame(second, stack.top());
        assertThrows(IllegalStateException.class, () -> stack.pop(first));
        stack.pop(second);
        assertSame(first, stack.top());
        stack.pop(first);
        assertTrue(stack.defaultSelected());
    }

    @Test
    void depthIsBoundedWithoutChangingCurrentFlow() {
        var stack = new ModelFlowStack();
        ModelFlow flow = runtime -> {};
        for (int i = 0; i < 8; i++) stack.push(flow);
        assertThrows(IllegalStateException.class, () -> stack.push(flow));
        assertSame(flow, stack.top());
    }

    @Test
    void failedFrameAndItsChildrenCannotPoisonTheNextRequest() {
        var stack = new ModelFlowStack();
        ModelFlow parent = runtime -> {};
        ModelFlow failed = runtime -> {};
        ModelFlow child = runtime -> {};
        stack.push(parent);
        stack.pushNested(parent, failed);
        stack.pushNested(failed, child);
        stack.discardFailed(failed);
        assertSame(parent, stack.top());
        stack.discardFailed(failed);
        assertSame(parent, stack.top());
    }
}
