package ghidrassistmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ghidra.program.model.listing.Program;
import ghidrassistmcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;

class ExplicitToolTest {
    @Test void operationCannotBeOverriddenAndSchemaOnlyExposesRelevantInputs() {
        RecordingTool implementation = new RecordingTool();
        ExplicitTool reader = new ExplicitTool(implementation, "get_pseudocode", "Read C pseudocode")
            .select("format", "decompiler").parameters("function").required("function").readOnly();
        assertEquals(List.of("function"), reader.getInputSchema().required());
        assertFalse(reader.getInputSchema().properties().containsKey("format"));
        assertTrue(reader.isReadOnly());
        assertFalse(reader.isDestructive());
        assertEquals("get_code", reader.getLegacyName());
        assertTrue(reader.execute(Map.of("function", "main", "format", "disassembly"), null).isError());
        assertNull(implementation.lastArguments);
        reader.execute(Map.of("function", "main"), null);
        assertEquals(Map.of("function", "main", "format", "decompiler"), implementation.lastArguments);
    }

    @Test void validatesIntegerBoundsAndTranslatesRenamedArguments() {
        RecordingTool implementation = new RecordingTool();
        ExplicitTool reader = new ExplicitTool(implementation, "get_hexdump", "Read hex bytes")
            .alias("length", "len").parameters("length").required("length").readOnly();
        assertTrue(reader.execute(Map.of("length", -1), null).isError());
        assertTrue(reader.execute(Map.of("length", 2.5), null).isError());
        assertTrue(reader.execute(Map.of("length", "64"), null).isError());
        assertNull(implementation.lastArguments);
        reader.execute(Map.of("length", 64), null);
        assertEquals(Map.of("len", 64), implementation.lastArguments);
    }

    @Test void rejectsMissingAlternativeSelectorBeforeCallingImplementation() {
        RecordingTool implementation = new RecordingTool();
        ExplicitTool reader = new ExplicitTool(implementation, "get_structure", "Read structure")
            .parameters("function", "len").required().oneOfParameters("function", "len").readOnly();
        assertTrue(reader.execute(Map.of(), null).isError());
        reader.execute(Map.of("len", 0), null);
        assertEquals(Map.of("len", 0), implementation.lastArguments);
    }

    @Test void emptyCommentCanClearAnExistingFunctionComment() {
        RecordingTool implementation = new RecordingTool();
        ExplicitTool writer = new ExplicitTool(implementation, "set_function_comment", "Set or clear comment")
            .parameters("comment").required("comment").mutation(false, true);
        assertFalse(Boolean.TRUE.equals(writer.execute(Map.of("comment", ""), null).isError()));
        assertEquals(Map.of("comment", ""), implementation.lastArguments);
    }

    private static final class RecordingTool implements McpTool {
        Map<String, Object> lastArguments;
        @Override public String getName() { return "get_code"; }
        @Override public String getDescription() { return "Legacy test"; }
        @Override public boolean isReadOnly() { return false; }
        @Override public boolean isDestructive() { return true; }
        @Override public McpSchema.JsonSchema getInputSchema() {
            return new McpSchema.JsonSchema("object", Map.of(
                "function", Map.of("type", "string"), "format", Map.of("type", "string"),
                "len", Map.of("type", "number"), "comment", Map.of("type", "string")), List.of("function", "format"), null, null, null);
        }
        @Override public McpSchema.CallToolResult execute(Map<String, Object> arguments, Program program) {
            lastArguments = arguments;
            return McpSchema.CallToolResult.builder().addTextContent("ok").build();
        }
    }
}
