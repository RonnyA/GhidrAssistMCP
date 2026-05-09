/*
 * MCP tool for clearing code/data definitions at an address range.
 */
package ghidrassistmcp.tools;

import java.util.List;
import java.util.Map;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidrassistmcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that clears (undefines) code and/or data definitions in an address range.
 * After clearing, the bytes become undefined and can be re-disassembled or re-typed.
 */
public class ClearListingTool implements McpTool {

    @Override
    public String getName() {
        return "clear_listing";
    }

    @Override
    public String getDescription() {
        return "Clear (undefine) code and data definitions in an address range. " +
               "Removes disassembled instructions and defined data, leaving raw undefined bytes. " +
               "Use before re-disassembling or re-typing a region.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public McpSchema.JsonSchema getInputSchema() {
        return new McpSchema.JsonSchema("object",
            Map.of(
                "address", Map.of(
                    "type", "string",
                    "description", "Start address of the range to clear (e.g., 'ram:2ebe')"
                ),
                "end_address", Map.of(
                    "type", "string",
                    "description", "End address of the range to clear (inclusive, e.g., 'ram:2ec9')"
                )
            ),
            List.of("address", "end_address"), null, null, null);
    }

    @Override
    public McpSchema.CallToolResult execute(Map<String, Object> arguments, Program currentProgram) {
        if (currentProgram == null) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("No program currently loaded")
                .build();
        }

        String startStr = (String) arguments.get("address");
        String endStr = (String) arguments.get("end_address");

        if (startStr == null || startStr.isEmpty() || endStr == null || endStr.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("Both address and end_address parameters are required")
                .build();
        }

        Address startAddr = currentProgram.getAddressFactory().getAddress(startStr);
        Address endAddr = currentProgram.getAddressFactory().getAddress(endStr);

        if (startAddr == null) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("Invalid start address: " + startStr)
                .build();
        }
        if (endAddr == null) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("Invalid end address: " + endStr)
                .build();
        }

        if (startAddr.compareTo(endAddr) > 0) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("Start address must be <= end address")
                .build();
        }

        try {
            int tid = currentProgram.startTransaction("MCP Clear Listing");
            try {
                currentProgram.getListing().clearCodeUnits(startAddr, endAddr, false);

                return McpSchema.CallToolResult.builder()
                    .addTextContent(String.format("Cleared listing from %s to %s", startAddr, endAddr))
                    .build();
            } finally {
                currentProgram.endTransaction(tid, true);
            }
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("Error clearing listing: " + e.getMessage())
                .build();
        }
    }
}
