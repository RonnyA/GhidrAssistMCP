/*
 * MCP tool for getting disassembly at an arbitrary address range.
 * Unlike GetCodeTool which requires a defined function, this works on any
 * disassembled code regardless of function boundaries.
 */
package ghidrassistmcp.tools;

import java.util.List;
import java.util.Map;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.CommentType;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Program;
import ghidrassistmcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that gets disassembly at any address range, not limited to functions.
 * Useful for examining code that Ghidra hasn't grouped into functions (inline
 * continuations, data pools mixed with code, etc.).
 */
public class GetDisassemblyTool implements McpTool {

    @Override
    public String getName() {
        return "get_disassembly";
    }

    @Override
    public String getDescription() {
        return "Get disassembly at an arbitrary address range (not limited to functions). " +
               "Returns instructions with mnemonics, operands, and comments. " +
               "Also shows undefined/data bytes in the range.";
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public McpSchema.JsonSchema getInputSchema() {
        return new McpSchema.JsonSchema("object",
            Map.of(
                "address", Map.of(
                    "type", "string",
                    "description", "Start address (e.g., 'ram:2e9b' or '0x2e9b')"
                ),
                "count", Map.of(
                    "type", "number",
                    "description", "Number of instruction/data units to return (default 50, max 500)"
                )
            ),
            List.of("address"), null, null, null);
    }

    @Override
    public McpSchema.CallToolResult execute(Map<String, Object> arguments, Program currentProgram) {
        if (currentProgram == null) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("No program currently loaded")
                .build();
        }

        String addressStr = (String) arguments.get("address");
        if (addressStr == null || addressStr.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("address parameter is required")
                .build();
        }

        // Parse count parameter
        int count = 50;
        Object countObj = arguments.get("count");
        if (countObj != null) {
            if (countObj instanceof Integer) {
                count = (Integer) countObj;
            } else if (countObj instanceof Double) {
                count = ((Double) countObj).intValue();
            } else if (countObj instanceof Long) {
                count = ((Long) countObj).intValue();
            }
        }
        if (count < 1) count = 1;
        if (count > 500) count = 500;

        // Parse address
        Address startAddr = currentProgram.getAddressFactory().getAddress(addressStr);
        if (startAddr == null) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("Invalid address: " + addressStr)
                .build();
        }

        StringBuilder result = new StringBuilder();
        result.append("Disassembly at ").append(startAddr).append(":\n\n");

        var listing = currentProgram.getListing();
        var memory = currentProgram.getMemory();
        Address currentAddr = startAddr;
        int unitCount = 0;

        while (unitCount < count && currentAddr != null && memory.contains(currentAddr)) {
            // Try to get an instruction at this address
            Instruction instruction = listing.getInstructionAt(currentAddr);
            if (instruction != null) {
                result.append(instruction.getAddress()).append(": ");

                // Show raw bytes
                try {
                    byte[] bytes = instruction.getBytes();
                    for (byte b : bytes) {
                        result.append(String.format("%02x ", b & 0xFF));
                    }
                    int pad = 12 - (bytes.length * 3);
                    for (int p = 0; p < pad; p++) result.append(' ');
                } catch (Exception e) {
                    result.append("?? ??       ");
                }

                result.append(instruction.getMnemonicString());

                // Add operands
                for (int i = 0; i < instruction.getNumOperands(); i++) {
                    if (i == 0) {
                        result.append(" ");
                    } else {
                        result.append(", ");
                    }
                    result.append(instruction.getDefaultOperandRepresentation(i));
                }

                // Add EOL comment
                String comment = instruction.getComment(CommentType.EOL);
                if (comment != null && !comment.trim().isEmpty()) {
                    result.append(" ; ").append(comment.trim());
                }

                result.append("\n");

                // Advance past this instruction
                currentAddr = instruction.getMaxAddress().next();
                unitCount++;
            } else {
                // No instruction — check for defined data
                var data = listing.getDefinedDataAt(currentAddr);
                if (data != null) {
                    result.append(currentAddr).append(": ");
                    result.append("<data> ").append(data.getDataType().getName());
                    result.append(" = ").append(data.getValue());
                    String comment = data.getComment(CommentType.EOL);
                    if (comment != null && !comment.trim().isEmpty()) {
                        result.append(" ; ").append(comment.trim());
                    }
                    result.append("\n");
                    currentAddr = data.getMaxAddress().next();
                } else {
                    // Undefined byte/word — show raw value
                    try {
                        int wordSize = currentAddr.getAddressSpace().getAddressableUnitSize();
                        if (wordSize == 2) {
                            short val = memory.getShort(currentAddr);
                            result.append(currentAddr).append(": ");
                            result.append(String.format("%02x %02x       ", (val >> 8) & 0xFF, val & 0xFF));
                            result.append(String.format("<undefined> 0x%04X", val & 0xFFFF));
                        } else {
                            byte val = memory.getByte(currentAddr);
                            result.append(currentAddr).append(": ");
                            result.append(String.format("%02x          ", val & 0xFF));
                            result.append(String.format("<undefined> 0x%02X", val & 0xFF));
                        }
                        // Check for labels/symbols
                        var symbol = currentProgram.getSymbolTable().getPrimarySymbol(currentAddr);
                        if (symbol != null) {
                            result.append(" (").append(symbol.getName()).append(")");
                        }
                        result.append("\n");
                    } catch (Exception e) {
                        result.append(currentAddr).append(": <unreadable>\n");
                    }
                    currentAddr = currentAddr.next();
                }
                unitCount++;
            }
        }

        result.append("\nTotal units: ").append(unitCount);

        return McpSchema.CallToolResult.builder()
            .addTextContent(result.toString())
            .build();
    }
}
