/* 
 * 
 */
package ghidrassistmcp;

import java.util.List;
import java.util.Map;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;

import ghidra.MiscellaneousPluginPackage;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.app.services.ProgramManager;
import ghidra.framework.plugintool.*;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;
import ghidra.util.SystemUtilities;

/**
 * GhidrAssistMCP Plugin - Provides an MCP (Model Context Protocol) server for Ghidra analysis capabilities.
 * Features a configurable UI with tool management and request logging.
 */
@PluginInfo(
	status = PluginStatus.STABLE,
	packageName = MiscellaneousPluginPackage.NAME,
	category = PluginCategoryNames.COMMON,
	shortDescription = "MCP Server for Ghidra",
	description = "Provides a configurable MCP (Model Context Protocol) server for Ghidra analysis capabilities with tool management and logging."
)
public class GhidrAssistMCPPlugin extends ProgramPlugin {

	private GhidrAssistMCPProvider provider;
	private GhidrAssistMCPManager manager;
	private WindowFocusListener windowFocusListener;

	// Current UI location tracking
	private volatile ProgramLocation currentLocation1;

	/**
	 * Plugin constructor.
	 * 
	 * @param tool The plugin tool that this plugin is added to.
	 */
	public GhidrAssistMCPPlugin(PluginTool tool) {
		super(tool);
		
	}

	@Override
	public void init() {
		super.init();

		// Get the singleton manager
		manager = GhidrAssistMCPManager.getInstance();

		provider = new GhidrAssistMCPProvider(tool, manager);
		tool.addComponentProvider(provider, false);
		manager.registerTool(tool, this, provider);
		windowFocusListener = new WindowAdapter() {
			@Override
			public void windowGainedFocus(WindowEvent event) {
				manager.setActiveTool(tool);
			}
		};
		tool.getToolFrame().addWindowFocusListener(windowFocusListener);
	}
	
	/**
	 * Apply new configuration from the UI.
	 * Delegates to the singleton manager which handles server restart if needed.
	 */
	public void applyConfiguration(String host, int port, boolean enabled, boolean asyncEnabled, Map<String, Boolean> toolStates) {
		if (manager != null) {
			manager.applyConfiguration(host, port, enabled, asyncEnabled, toolStates);
		}
	}
	
	@Override
	protected void programActivated(Program program) {
		super.programActivated(program);
		currentLocation1 = null;

		// Activation in an unfocused browser must not steal the request context.
		if (manager != null && (tool.getToolFrame().isActive() || manager.getActiveTool() == tool)) {
			manager.setActiveTool(tool);
		}

		GhidrAssistMCPBackend backend = getBackend();
		if (backend != null) {
			backend.onProgramActivated(program);
		}
	}
	
	@Override
	protected void locationChanged(ProgramLocation loc) {
		super.locationChanged(loc);
		this.currentLocation1 = loc;

		// Update the cursor without overriding focus in another browser.
		if (manager != null && (tool.getToolFrame().isActive() || manager.getActiveTool() == tool)) {
			manager.setActivePlugin(this);
		}

	}
	
	@Override
	protected void programDeactivated(Program program) {
		super.programDeactivated(program);
		currentLocation1 = null;
		GhidrAssistMCPBackend backend = getBackend();
		if (backend != null) {
			backend.onProgramDeactivated(program);
		}
		if (provider != null) {
			provider.logSession("Program deactivated: " + (program != null ? program.getName() : "null"));
		}
	}
	
	@Override
	protected void dispose() {
		if (windowFocusListener != null) {
			tool.getToolFrame().removeWindowFocusListener(windowFocusListener);
			windowFocusListener = null;
		}
		if (manager != null) {
			manager.unregisterTool(tool);
		}
		if (provider != null) {
			provider.detach();
			tool.removeComponentProvider(provider);
			provider = null;
		}
		currentLocation1 = null;
		super.dispose();
	}

	/**
	 * Get the MCP backend for tool management.
	 * Returns the shared backend from the singleton manager.
	 */
	public GhidrAssistMCPBackend getBackend() {
		return manager != null ? manager.getBackend() : null;
	}

	/**
	 * Get the singleton manager.
	 */
	public GhidrAssistMCPManager getManager() {
		return manager;
	}

	/**
	 * Get the current server configuration.
	 * Returns configuration from the singleton manager.
	 */
	public String getCurrentHost() {
		return manager != null ? manager.getCurrentHost() : "localhost";
	}

	public int getCurrentPort() {
		return manager != null ? manager.getCurrentPort() : 8080;
	}

	public boolean isServerEnabled() {
		return manager != null ? manager.isServerEnabled() : false;
	}
	
	/**
	 * Get the current program using ProgramManager service for accurate tracking.
	 * This method properly handles multi-program scenarios.
	 */
	@Override
	public Program getCurrentProgram() {
		return SystemUtilities.runSwingNow(() -> {
			PluginTool browser = tool;
			if (browser == null) {
				return null;
			}
			ProgramManager pm = browser.getService(ProgramManager.class);
			return pm != null ? pm.getCurrentProgram() : super.getCurrentProgram();
		});
	}

	/**
	 * Get all open programs in the current tool.
	 * This allows tools to list and select from multiple open programs.
	 */
	public List<Program> getAllOpenPrograms() {
		return SystemUtilities.runSwingNow(() -> {
			PluginTool browser = tool;
			ProgramManager pm = browser == null ? null : browser.getService(ProgramManager.class);
			return pm == null ? List.of() : List.of(pm.getAllOpenPrograms());
		});
	}

	/**
	 * Find an open program by name.
	 * Supports partial matching if exact match not found.
	 *
	 * @param programName The name of the program to find
	 * @return The matching program, or null if not found
	 */
	public Program getProgramByName(String programName) {
		return manager == null ? null : manager.getProgramByName(programName);
	}

	/**
	 * Get the current UI address from the location tracker.
	 */
	public Address getCurrentAddress() {
		return SystemUtilities.runSwingNow(() -> {
			ProgramLocation location = currentLocation1;
			return location != null && location.getProgram() == getCurrentProgram() ?
				location.getAddress() : null;
		});
	}

	/**
	 * Get the current function containing the UI cursor.
	 */
	public Function getCurrentFunction() {
		return SystemUtilities.runSwingNow(() -> {
			Program program = getCurrentProgram();
			Address address = getCurrentAddress();
			return program == null || address == null ? null :
				program.getFunctionManager().getFunctionContaining(address);
		});
	}

}
