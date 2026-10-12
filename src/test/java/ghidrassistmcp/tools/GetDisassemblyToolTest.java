package ghidrassistmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.address.GenericAddressSpace;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.SymbolTable;
import io.modelcontextprotocol.spec.McpSchema;

class GetDisassemblyToolTest {
    /**
     * On a word-addressed machine (the ND-100: 2 bytes per addressable unit) each undefined
     * word must be printed once and the walk must land on the next word. Address.next() moves
     * one BYTE, which printed every word twice with half-word rows such as "ram:0001.1".
     */
    @Test void undefinedWordsStepByAddressableUnitOnWordMachine() {
        AddressSpace space = new GenericAddressSpace("ram", 16, 2, AddressSpace.TYPE_RAM, 0);
        Address start = space.getAddress(0);
        AddressFactory factory = fake(AddressFactory.class, (method, args) -> start);
        // Four words of memory, big-endian: aa02 aa02 58f6 5902 (the first words of NPL-1896D.BPUN).
        short[] words = { (short) 0xAA02, (short) 0xAA02, (short) 0x58F6, (short) 0x5902 };
        Memory memory = fake(Memory.class, (method, args) -> switch (method) {
            case "contains" -> ((Address) args[0]).getAddressableWordOffset() < words.length;
            case "getShort" -> words[(int) ((Address) args[0]).getAddressableWordOffset()];
            default -> throw new UnsupportedOperationException(method);
        });
        Listing listing = fake(Listing.class, (method, args) -> null);
        SymbolTable symbols = fake(SymbolTable.class, (method, args) -> null);
        Program program = fake(Program.class, (method, args) -> switch (method) {
            case "getAddressFactory" -> factory;
            case "getMemory" -> memory;
            case "getListing" -> listing;
            case "getSymbolTable" -> symbols;
            default -> throw new UnsupportedOperationException(method);
        });
        var result = new GetDisassemblyTool().execute(Map.of("address", "0x0", "count", 50), program);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.contains(".1:"), "half-word row printed: " + text);
        assertTrue(text.contains("0000: aa 02       <undefined> 0xAA02\n"), text);
        assertTrue(text.contains("0001: aa 02       <undefined> 0xAA02\n"), text);
        assertTrue(text.contains("0002: 58 f6       <undefined> 0x58F6\n"), text);
        assertTrue(text.contains("0003: 59 02       <undefined> 0x5902\n"), text);
        assertTrue(text.contains("Total units: 4"), text);
    }
    private interface Answer { Object invoke(String method, Object[] args) throws Throwable; }
    private static <T> T fake(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
            (proxy, method, args) -> answer.invoke(method.getName(), args)));
    }
}
