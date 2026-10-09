package ghidrassistmcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ghidra.app.services.ProgramManager;
import ghidra.framework.main.FrontEndTool;
import ghidra.framework.model.DomainFile;
import ghidra.framework.model.Project;
import ghidra.framework.options.Options;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.preferences.Preferences;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.SystemUtilities;

/** One application runtime, independent of the number or lifetime of CodeBrowser views. */
public final class GhidrAssistMCPManager implements McpEventListener {
    private static GhidrAssistMCPManager instance;
    private static final String PREFIX = "GhidrAssistMCP.";
    private final GhidrAssistMCPBackend backend;
    private final McpLogModel log = new McpLogModel(2000);
    private final List<PluginTool> registeredTools = new CopyOnWriteArrayList<>();
    private final Map<PluginTool, GhidrAssistMCPPlugin> plugins = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<PluginTool, GhidrAssistMCPProvider> providers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService lifecycle = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "GhidrAssistMCP-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private volatile FrontEndTool frontEnd;
    private volatile PluginTool activeTool;
    private volatile GhidrAssistMCPPlugin activePlugin;
    private volatile GhidrAssistMCPServer server;
    private volatile String currentHost = "localhost";
    private volatile int currentPort = 8080;
    private volatile boolean serverEnabled = true;
    private volatile boolean disposed;

    private GhidrAssistMCPManager() {
        backend = new GhidrAssistMCPBackend();
        backend.setManager(this);
        backend.addEventListener(this);
        loadSettings();
    }

    public static synchronized GhidrAssistMCPManager getInstance() {
        if (instance == null || instance.disposed) {
            instance = new GhidrAssistMCPManager();
        }
        return instance;
    }

    /** Lookup without creating a GUI runtime (important for headless tools). */
    public static synchronized GhidrAssistMCPManager findInstance() {
        return instance;
    }

    public void registerFrontEnd(FrontEndTool tool, GhidrAssistMCPProvider provider) {
        frontEnd = tool;
        addProvider(tool, provider);
        onSessionEvent("Project manager attached; MCP is available before a program is opened");
        scheduleStart();
    }

    public void unregisterFrontEnd(FrontEndTool tool) {
        removeProvider(tool);
        if (frontEnd == tool) {
            frontEnd = null;
        }
        shutdownIfUnowned();
    }

    public boolean registerTool(PluginTool tool, GhidrAssistMCPPlugin plugin,
                                GhidrAssistMCPProvider provider) {
        if (tool == null || registeredTools.contains(tool)) {
            return false;
        }
        registeredTools.add(tool);
        plugins.put(tool, plugin);
        migrateToolSettings(tool);
        addProvider(tool, provider);
        setActiveTool(tool);
        onSessionEvent("CodeBrowser attached: " + tool.getName() +
            " (" + registeredTools.size() + " browser views)");
        scheduleStart();
        return registeredTools.size() == 1;
    }

    public boolean isRegistered(PluginTool tool) {
        return registeredTools.contains(tool);
    }

    public void unregisterTool(PluginTool tool) {
        removeProvider(tool);
        registeredTools.remove(tool);
        GhidrAssistMCPPlugin removed = plugins.remove(tool);
        if (activeTool == tool) {
            activeTool = null;
        }
        if (activePlugin == removed) {
            activePlugin = null;
        }
        onSessionEvent("CodeBrowser detached: " + tool.getName() +
            " (" + registeredTools.size() + " browser views)");
        shutdownIfUnowned();
    }

    private void addProvider(PluginTool tool, GhidrAssistMCPProvider provider) {
        if (provider != null) {
            providers.put(tool, provider);
            provider.onBackendReady();
        }
    }

    private void removeProvider(PluginTool tool) {
        GhidrAssistMCPProvider provider = providers.remove(tool);
        if (provider != null) {
            provider.detach();
        }
    }

    private void shutdownIfUnowned() {
        if (frontEnd != null || !registeredTools.isEmpty()) {
            return;
        }
        synchronized (GhidrAssistMCPManager.class) {
            if (disposed) {
                return;
            }
            disposed = true;
        }
        lifecycle.execute(() -> {
            stopServer();
            backend.removeEventListener(this);
            backend.shutdown();
            synchronized (GhidrAssistMCPManager.class) {
                if (instance == this) {
                    instance = null;
                }
            }
        });
    }

    /** Automatically attached runtime views must preserve any existing tool configuration changes. */
    static void addTransientPlugin(PluginTool tool, String pluginClass)
            throws ghidra.framework.plugintool.util.PluginException {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Runtime views must be attached on the Swing event thread");
        }
        boolean changedBefore = tool.hasConfigChanged();
        tool.addPlugin(pluginClass);
        tool.setConfigChanged(changedBefore);
    }

    public Project getProject() {
        return SystemUtilities.runSwingNow(() -> {
            FrontEndTool owner = frontEnd;
            if (owner != null) {
                return owner.getProjectManager().getActiveProject();
            }
            PluginTool tool = getActiveTool();
            return tool == null ? null : tool.getProject();
        });
    }

    /** Launch/reuse a CodeBrowser and transfer a program to its ProgramManager on the EDT. */
    public PluginTool openProgramInCodeBrowser(Project project, Program program) {
        return SystemUtilities.runSwingNow(() -> {
            if (project == null || project.isClosed() || getProject() != project) {
                throw new IllegalStateException("The Ghidra project changed before the program could be opened");
            }
            PluginTool browser = getActiveTool();
            if (browser == null || browser.getProject() != project ||
                browser.getService(ProgramManager.class) == null) {
                browser = project.getToolServices().launchTool("CodeBrowser", List.of());
            }
            if (browser == null || browser.getService(ProgramManager.class) == null) {
                throw new IllegalStateException("CodeBrowser could not be launched. Import its tool template in Ghidra.");
            }
            if (!isRegistered(browser)) {
                try {
                    addTransientPlugin(browser, GhidrAssistMCPPlugin.class.getName());
                } catch (Exception e) {
                    throw new IllegalStateException("Could not attach the shared MCP view", e);
                }
            }
            browser.getService(ProgramManager.class).openProgram(program);
            if (!ownsProgram(browser, program)) {
                throw new IllegalStateException("CodeBrowser did not accept the program");
            }
            browser.getToolFrame().toFront();
            return browser;
        });
    }

    public List<Program> getAllOpenPrograms() {
        return SystemUtilities.runSwingNow(() -> {
            List<Program> programs = new ArrayList<>();
            for (PluginTool tool : registeredTools) {
                ProgramManager pm = tool.getService(ProgramManager.class);
                if (pm == null) {
                    continue;
                }
                for (Program program : pm.getAllOpenPrograms()) {
                    if (!programs.contains(program)) {
                        programs.add(program);
                    }
                }
            }
            return programs;
        });
    }

    public void setActiveTool(PluginTool tool) {
        if (registeredTools.contains(tool)) {
            activeTool = tool;
            activePlugin = plugins.get(tool);
        }
    }

    public PluginTool getActiveTool() {
        PluginTool selected = activeTool;
        if (selected != null && registeredTools.contains(selected)) {
            return selected;
        }
        return registeredTools.isEmpty() ? null : registeredTools.get(0);
    }

    public PluginTool getToolForProgram(Program program) {
        return SystemUtilities.runSwingNow(() -> {
            PluginTool selected = getActiveTool();
            if (ownsProgram(selected, program)) {
                return selected;
            }
            for (PluginTool tool : registeredTools) {
                if (ownsProgram(tool, program)) {
                    return tool;
                }
            }
            return null;
        });
    }

    private boolean ownsProgram(PluginTool tool, Program program) {
        if (tool == null || program == null) {
            return false;
        }
        ProgramManager pm = tool.getService(ProgramManager.class);
        if (pm != null) {
            for (Program open : pm.getAllOpenPrograms()) {
                if (open == program) {
                    return true;
                }
            }
        }
        return false;
    }

    public void setActivePlugin(GhidrAssistMCPPlugin plugin) {
        if (plugins.containsValue(plugin)) {
            activePlugin = plugin;
            for (var entry : plugins.entrySet()) {
                if (entry.getValue() == plugin) {
                    activeTool = entry.getKey();
                    break;
                }
            }
        }
    }

    public GhidrAssistMCPPlugin getActivePlugin() {
        GhidrAssistMCPPlugin plugin = activePlugin;
        PluginTool selected = getActiveTool();
        return plugin != null && plugins.containsValue(plugin) ? plugin :
            selected == null ? null : plugins.get(selected);
    }

    public Program getCurrentProgram() {
        return SystemUtilities.runSwingNow(() -> {
            PluginTool selected = getActiveTool();
            if (selected != null) {
                ProgramManager pm = selected.getService(ProgramManager.class);
                return pm == null ? null : pm.getCurrentProgram();
            }
            return null;
        });
    }

    /** Ambiguous names never select whichever browser happened to register first. */
    public Program getProgramByName(String name) {
        if (name == null || name.isBlank()) {
            return getCurrentProgram();
        }
        List<Program> programs = getAllOpenPrograms();
        for (int stage = 0; stage < 4; stage++) {
            List<Program> matches = new ArrayList<>();
            for (Program program : programs) {
                DomainFile file = program.getDomainFile();
                String path = file == null ? "" : file.getPathname();
                boolean match = switch (stage) {
                    case 0 -> path.equals(name);
                    case 1 -> program.getName().equals(name);
                    case 2 -> program.getName().equalsIgnoreCase(name);
                    default -> program.getName().toLowerCase(Locale.ROOT)
                        .contains(name.toLowerCase(Locale.ROOT));
                };
                if (match) {
                    matches.add(program);
                }
            }
            if (!matches.isEmpty()) {
                if (matches.size() != 1) {
                    throw new IllegalArgumentException("Ambiguous program: " + name +
                        ". Use the full project path from list_open_programs.");
                }
                return matches.get(0);
            }
        }
        return null;
    }

    public GhidrAssistMCPBackend getBackend() { return backend; }
    public McpLogModel getLog() { return log; }
    public int getRegisteredToolCount() { return registeredTools.size(); }
    public boolean hasFrontEndOwner() { return frontEnd != null; }
    /** Application project owner; project controls must never use a CodeBrowser as owner. */
    public FrontEndTool getFrontEndTool() { return frontEnd; }
    public boolean isServerRunning() { return server != null; }
    public String getCurrentHost() { return currentHost; }
    public int getCurrentPort() { return currentPort; }
    public boolean isServerEnabled() { return serverEnabled; }

    public void applyConfiguration(String host, int port, boolean enabled, boolean asyncEnabled,
                                   Map<String, Boolean> toolStates) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("A host and port between 1 and 65535 are required");
        }
        boolean restart = !host.equals(currentHost) || port != currentPort || enabled != serverEnabled;
        currentHost = host;
        currentPort = port;
        serverEnabled = enabled;
        backend.setAsyncExecutionEnabled(asyncEnabled);
        if (toolStates != null) {
            backend.updateToolEnabledStates(toolStates);
        }
        Preferences.setProperty(PREFIX + "Server Host", host);
        Preferences.setProperty(PREFIX + "Server Port", String.valueOf(port));
        Preferences.setProperty(PREFIX + "Server Enabled", String.valueOf(enabled));
        Preferences.setProperty(PREFIX + "Async Execution Enabled", String.valueOf(asyncEnabled));
        for (var entry : backend.getToolEnabledStates().entrySet()) {
            Preferences.setProperty(PREFIX + "Tool." + entry.getKey(), entry.getValue().toString());
        }
        Preferences.store();
        providers.values().forEach(GhidrAssistMCPProvider::refreshConfiguration);
        onLogMessage("Configuration saved: " + host + ":" + port + " enabled=" + enabled);
        if (!disposed && restart) {
            lifecycle.execute(() -> { stopServer(); startServer(); });
        } else if (!disposed) {
            scheduleStart();
        }
    }

    private void loadSettings() {
        currentHost = Preferences.getProperty(PREFIX + "Server Host", "localhost");
        try {
            currentPort = Integer.parseInt(Preferences.getProperty(PREFIX + "Server Port", "8080"));
            if (currentPort < 1 || currentPort > 65535) {
                currentPort = 8080;
            }
        } catch (NumberFormatException e) {
            currentPort = 8080;
        }
        serverEnabled = Boolean.parseBoolean(Preferences.getProperty(PREFIX + "Server Enabled", "true"));
        backend.setAsyncExecutionEnabled(Boolean.parseBoolean(
            Preferences.getProperty(PREFIX + "Async Execution Enabled", "true")));
        Map<String, Boolean> states = new HashMap<>(backend.getToolEnabledStates());
        Map<String, String> legacyNames = backend.getToolLegacyNames();
        states.replaceAll((name, value) -> {
            String saved = Preferences.getProperty(PREFIX + "Tool." + name);
            if (saved == null) {
                saved = Preferences.getProperty(PREFIX + "Tool." + legacyNames.getOrDefault(name, name));
            }
            return saved == null ? value : Boolean.parseBoolean(saved);
        });
        backend.updateToolEnabledStates(states);
    }

    /** Migrate saved CodeBrowser options once, preserving each backend default. */
    private void migrateToolSettings(PluginTool tool) {
        Options options = tool.getOptions("GhidrAssistMCP");
        Map<String, Boolean> states = new HashMap<>(backend.getToolEnabledStates());
        Map<String, String> legacyNames = backend.getToolLegacyNames();
        boolean migrated = false;
        for (String name : states.keySet()) {
            if (Preferences.getProperty(PREFIX + "Tool." + name) != null) {
                continue;
            }
            String oldName = legacyNames.getOrDefault(name, name);
            String saved = Preferences.getProperty(PREFIX + "Tool." + oldName);
            String option = options.contains("Tool." + name) ? name : oldName;
            boolean enabled = states.get(name);
            if (saved != null) {
                enabled = Boolean.parseBoolean(saved);
            } else if (options.contains("Tool." + option)) {
                enabled = options.getBoolean("Tool." + option, enabled);
            } else {
                continue;
            }
            states.put(name, enabled);
            Preferences.setProperty(PREFIX + "Tool." + name, String.valueOf(enabled));
            migrated = true;
        }
        if (migrated) {
            backend.updateToolEnabledStates(states);
            Preferences.store();
            providers.values().forEach(GhidrAssistMCPProvider::refreshConfiguration);
        }
    }

    private void scheduleStart() {
        if (!disposed) {
            lifecycle.execute(this::startServer);
        }
    }

    private void startServer() {
        if (disposed || !serverEnabled || server != null) {
            return;
        }
        GhidrAssistMCPServer candidate = new GhidrAssistMCPServer(currentHost, currentPort, backend, null);
        try {
            candidate.start();
            server = candidate;
            onSessionEvent("Server started on " + currentHost + ":" + currentPort);
        } catch (Exception e) {
            try { candidate.stop(); } catch (Exception ignored) { /* best effort after failed start */ }
            onSessionEvent("Server startup failed: " + e.getMessage());
            Msg.error(this, "Failed to start MCP server", e);
        }
    }

    private void stopServer() {
        GhidrAssistMCPServer current = server;
        server = null;
        if (current != null) {
            try {
                current.stop();
                onSessionEvent("Server stopped");
            } catch (Exception e) {
                Msg.error(this, "Failed to stop MCP server", e);
                onSessionEvent("Server shutdown failed: " + e.getMessage());
            }
        }
    }

    @Override public void onToolRequest(String name, String parameters) {
        log.append("REQ: " + name + " " + McpLogModel.abbreviate(parameters));
    }
    @Override public void onToolResponse(String name, String response) {
        log.append("RES: " + name + " " + McpLogModel.abbreviate(response));
    }
    @Override public void onSessionEvent(String event) { log.append("SESSION: " + event); }
    @Override public void onLogMessage(String message) { log.append(message); }
}
