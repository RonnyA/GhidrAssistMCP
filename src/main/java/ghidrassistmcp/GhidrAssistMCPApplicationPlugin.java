package ghidrassistmcp;

import java.beans.PropertyChangeEvent;
import javax.swing.SwingUtilities;

import ghidra.MiscellaneousPluginPackage;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.services.ProgramManager;
import ghidra.framework.main.ApplicationLevelOnlyPlugin;
import ghidra.framework.main.FrontEndTool;
import ghidra.framework.model.Project;
import ghidra.framework.model.ToolManager;
import ghidra.framework.model.Workspace;
import ghidra.framework.model.WorkspaceChangeListener;
import ghidra.framework.plugintool.Plugin;
import ghidra.framework.plugintool.PluginEvent;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.ProjectPluginEvent;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.util.Msg;

/** Application owner of MCP; CodeBrowser plugins are adapters to this runtime. */
@PluginInfo(status = PluginStatus.RELEASED, packageName = MiscellaneousPluginPackage.NAME,
    category = PluginCategoryNames.COMMON, shortDescription = "GhidrAssistMCP application server",
    description = "Starts GhidrAssistMCP in the project manager and shares it across CodeBrowsers.",
    eventsConsumed = {ProjectPluginEvent.class})
public final class GhidrAssistMCPApplicationPlugin extends Plugin implements ApplicationLevelOnlyPlugin {
    private GhidrAssistMCPManager manager;
    private GhidrAssistMCPProvider provider;
    private ToolManager observedTools;
    private final WorkspaceChangeListener workspaceListener = new WorkspaceChangeListener() {
        @Override public void toolAdded(Workspace workspace, PluginTool added) {
            SwingUtilities.invokeLater(() -> attachBrowser(added));
        }
        @Override public void toolRemoved(Workspace workspace, PluginTool removed) { }
        @Override public void workspaceAdded(Workspace workspace) { }
        @Override public void workspaceRemoved(Workspace workspace) { }
        @Override public void workspaceSetActive(Workspace workspace) { }
        @Override public void propertyChange(PropertyChangeEvent event) { }
    };

    public GhidrAssistMCPApplicationPlugin(PluginTool tool) {
        super(tool);
        if (!(tool instanceof FrontEndTool)) {
            throw new IllegalArgumentException("GhidrAssistMCP application plugin requires the project manager");
        }
    }

    @Override protected void init() {
        super.init();
        manager = GhidrAssistMCPManager.getInstance();
        provider = new GhidrAssistMCPProvider(tool, manager);
        tool.addComponentProvider(provider, false);
        manager.registerFrontEnd((FrontEndTool) tool, provider);
        observeProject(tool.getProjectManager().getActiveProject());
    }

    @Override public void processEvent(PluginEvent event) {
        if (event instanceof ProjectPluginEvent projectEvent) {
            observeProject(projectEvent.getProject());
            manager.getBackend().clearCache();
            manager.onSessionEvent(projectEvent.getProject() == null ? "Project closed" :
                "Project opened: " + projectEvent.getProject().getName());
        }
    }

    private void observeProject(Project project) {
        if (observedTools != null) {
            observedTools.removeWorkspaceChangeListener(workspaceListener);
            observedTools = null;
        }
        if (project != null) {
            observedTools = project.getToolManager();
            observedTools.addWorkspaceChangeListener(workspaceListener);
            for (PluginTool browser : observedTools.getRunningTools()) {
                SwingUtilities.invokeLater(() -> attachBrowser(browser));
            }
        }
    }

    private void attachBrowser(PluginTool browser) {
        if (observedTools == null || manager == null || manager.isRegistered(browser) ||
            browser.getService(ProgramManager.class) == null) {
            return;
        }
        // Ignore a tool that was closed while this callback waited on the EDT.
        if (!java.util.Arrays.asList(observedTools.getRunningTools()).contains(browser)) {
            return;
        }
        try {
            GhidrAssistMCPManager.addTransientPlugin(browser, GhidrAssistMCPPlugin.class.getName());
        } catch (Exception e) {
            Msg.error(this, "Could not attach shared MCP view to " + browser.getName(), e);
        }
    }

    @Override protected void dispose() {
        observeProject(null);
        if (manager != null) {
            manager.unregisterFrontEnd((FrontEndTool) tool);
            manager = null;
        }
        if (provider != null) {
            provider.detach();
            tool.removeComponentProvider(provider);
            provider = null;
        }
        super.dispose();
    }
}
