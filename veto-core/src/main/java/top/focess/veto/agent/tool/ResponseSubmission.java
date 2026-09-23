package top.focess.veto.agent.tool;

import java.lang.annotation.*;
import top.focess.veto.api.agent.tool.ToolDocs;

/** Declares an exclusive loop-control submission independently of the tool's name. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ResponseSubmission {
    Kind value();

    enum Kind {
        PLAN,
        ANSWER
    }

    final class Metadata {
        private Metadata() {}

        public static Kind kindOf(ToolDefinition definition) {
            if (!(definition instanceof LocalToolDefinition local)) return null;
            var annotation =
                    local.toolClass()
                            .getAnnotation(ToolDocs.nonNullClass(ResponseSubmission.class));
            return annotation == null ? null : annotation.value();
        }
    }
}
