package ghidrassistmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ghidrassistmcp.GhidrAssistMCPBackend;
import ghidrassistmcp.tasks.McpTask;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.address.GenericAddressSpace;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;

class GetFunctionHashToolTest {
    @Test void hashesAllDisjointRangesInAddressOrderWithChunkedReads() throws Exception {
        AddressSpace space = new GenericAddressSpace("ram", 32, AddressSpace.TYPE_RAM, 0);
        Address entry = space.getAddress(0x1000);
        AddressSet body = new AddressSet();
        body.addRange(space.getAddress(0x4000), space.getAddress(0x4003));
        body.addRange(entry, space.getAddress(0x3002));
        byte[] expectedBytes = new byte[8199];
        for (int i = 0; i < 8195; i++) expectedBytes[i] = (byte) i;
        for (int i = 0; i < 4; i++) expectedBytes[8195 + i] = (byte) (100 + i);
        AtomicInteger reads = new AtomicInteger();
        Memory memory = fake(Memory.class, (method, args) -> {
            if (!method.equals("getBytes")) throw new UnsupportedOperationException(method);
            Address start = (Address) args[0];
            byte[] buffer = (byte[]) args[1];
            int offset = (Integer) args[2], count = (Integer) args[3];
            assertTrue(count <= 8192);
            int source = start.getOffset() >= 0x4000 ? 8195 + (int) (start.getOffset() - 0x4000)
                : (int) (start.getOffset() - 0x1000);
            System.arraycopy(expectedBytes, source, buffer, offset, count);
            reads.incrementAndGet();
            return count;
        });
        Program program = program(body, entry, memory);
        var result = new GetFunctionHashTool().execute(Map.of("function", "0x1000"), program);
        assertFalse(Boolean.TRUE.equals(result.isError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) result.structuredContent();
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expectedBytes)), content.get("hash"));
        assertEquals(8199L, content.get("bytes_hashed"));
        assertEquals(2, content.get("range_count"));
        assertEquals(3, reads.get());
        assertTrue(McpJsonDefaults.getSchemaValidator().validate(new GetFunctionHashTool().getOutputSchema(), content).valid());
    }

    @Test void defaultAsyncHandleAndCompletedHashBothSatisfyAdvertisedOutputSchema() {
        AddressSpace space = new GenericAddressSpace("ram", 32, AddressSpace.TYPE_RAM, 0);
        Address entry = space.getAddress(0x1000);
        AddressSet body = new AddressSet(entry, entry.add(3));
        Memory memory = fake(Memory.class, (method, args) -> {
            byte[] bytes = (byte[]) args[1];
            int count = (Integer) args[3];
            for (int i = 0; i < count; i++) bytes[i] = (byte) i;
            return count;
        });
        Program program = program(body, entry, memory);
        GhidrAssistMCPBackend backend = new GhidrAssistMCPBackend() {
            @Override public Program getCurrentProgram() { return program; }
            @Override public List<Program> getAllOpenPrograms() { return List.of(program); }
        };
        try {
            var schema = backend.getAvailableTools().stream().filter(tool -> tool.name().equals("get_function_hash"))
                .findFirst().orElseThrow().outputSchema();
            var submitted = backend.callTool("get_function_hash", Map.of("function", "0x1000"));
            assertFalse(Boolean.TRUE.equals(submitted.isError()));
            @SuppressWarnings("unchecked")
            Map<String, Object> handle = (Map<String, Object>) submitted.structuredContent();
            assertNotNull(handle);
            assertTrue(handle.containsKey("task_id"), handle.toString());
            var validation = McpJsonDefaults.getSchemaValidator().validate(schema, handle);
            assertTrue(validation.valid(), validation.errorMessage());
            McpTask task = backend.getTaskManager().getTask((String) handle.get("task_id"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!task.isTerminal() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000);
            assertEquals(McpTask.Status.COMPLETED, task.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, Object> finalContent = (Map<String, Object>) task.getResult().structuredContent();
            validation = McpJsonDefaults.getSchemaValidator().validate(schema, finalContent);
            assertTrue(validation.valid(), validation.errorMessage());
        } finally { backend.shutdown(); }
    }

    @Test void unreadableBytesAreAnErrorRatherThanAHashOfFabricatedZeros() {
        AddressSpace space = new GenericAddressSpace("ram", 32, AddressSpace.TYPE_RAM, 0);
        Address entry = space.getAddress(0x1000);
        AddressSet body = new AddressSet(entry, entry.add(3));
        Memory memory = fake(Memory.class, (method, args) -> 0);
        var result = new GetFunctionHashTool().execute(Map.of("function", "0x1000"), program(body, entry, memory));
        assertTrue(result.isError());
        assertNull(result.structuredContent());
    }

    private static Program program(AddressSet body, Address entry, Memory memory) {
        Function function = fake(Function.class, (method, args) -> switch (method) {
            case "getBody" -> body;
            case "getEntryPoint" -> entry;
            case "getName" -> "example";
            case "isExternal" -> false;
            default -> throw new UnsupportedOperationException(method);
        });
        FunctionManager functions = fake(FunctionManager.class, (method, args) -> function);
        AddressFactory factory = fake(AddressFactory.class, (method, args) -> entry);
        return fake(Program.class, (method, args) -> switch (method) {
            case "getMemory" -> memory;
            case "getFunctionManager" -> functions;
            case "getAddressFactory" -> factory;
            case "getName" -> "hash-test-program";
            case "getDomainFile" -> null;
            case "getModificationNumber" -> 0L;
            case "addConsumer" -> true;
            case "release" -> null;
            default -> throw new UnsupportedOperationException(method);
        });
    }
    private interface Answer { Object invoke(String method, Object[] args) throws Throwable; }
    private static <T> T fake(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
            (proxy, method, args) -> answer.invoke(method.getName(), args)));
    }
}
