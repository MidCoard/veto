package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import top.focess.veto.group.BlackboardMessage;
import top.focess.veto.group.GroupTools;

class AgentToolParameterContractTest {
    @Test
    void postMessageAdvertisesAndValidatesItsActualMessageTypes() {
        var argsClass = ToolDocs.nonNullClass(GroupTools.PostMessage.Args.class);
        JsonNode values =
                ToolSchemaCompiler.compileFromRecord(argsClass)
                        .path("properties")
                        .path("type")
                        .path("enum");
        var messageTypes =
                EnumSet.allOf(ToolDocs.nonNullClass(BlackboardMessage.MessageType.class));
        assertEquals(messageTypes.size(), values.size());
        ObjectMapper mapper = new ObjectMapper();
        for (BlackboardMessage.MessageType type : messageTypes) {
            var args = mapper.createObjectNode().put("type", type.name()).put("payload", "note");
            assertDoesNotThrow(
                    () -> NativeToolArgumentValidator.validate("post_message", args, argsClass));
        }
        var invalid = mapper.createObjectNode().put("type", "UNKNOWN").put("payload", "note");
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                NativeToolArgumentValidator.validate(
                                        "post_message", invalid, argsClass));
        assertEquals("INVALID_ARGUMENTS", error.errorCode());
    }
}
