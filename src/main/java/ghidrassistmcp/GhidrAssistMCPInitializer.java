package ghidrassistmcp;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import javax.swing.SwingUtilities;

import ghidra.framework.ModuleInitializer;
import ghidra.framework.main.AppInfo;
import ghidra.framework.main.FrontEndTool;
import ghidra.util.Msg;
import ghidra.util.SystemUtilities;

/**
 * Ghidra discovers ModuleInitializer implementations ending in "Initializer".
 * The front end does not exist during module initialization, so attach when its
 * window opens. This also upgrades saved front-end profiles lacking the new plugin.
 */
public final class GhidrAssistMCPInitializer implements ModuleInitializer {
    @Override public String getName() { return "GhidrAssistMCP application startup"; }

    @Override public void run() {
        if (SystemUtilities.isInHeadlessMode()) {
            return;
        }
        Bootstrap bootstrap = new Bootstrap();
        Toolkit.getDefaultToolkit().addAWTEventListener(bootstrap, AWTEvent.WINDOW_EVENT_MASK);
        SwingUtilities.invokeLater(bootstrap::attach);
    }

    private static final class Bootstrap implements AWTEventListener {
        private boolean attached;

        @Override public void eventDispatched(AWTEvent event) {
            if (event instanceof WindowEvent window && window.getID() == WindowEvent.WINDOW_OPENED) {
                SwingUtilities.invokeLater(this::attach);
            }
        }

        private void attach() {
            if (attached) {
                return;
            }
            FrontEndTool frontEnd;
            try {
                frontEnd = AppInfo.getFrontEndTool();
            } catch (RuntimeException e) {
                // A splash or startup dialog can open before the front end is constructed.
                return;
            }
            if (frontEnd == null || frontEnd.getToolFrame() == null ||
                !frontEnd.getToolFrame().isShowing()) {
                return;
            }
            try {
                GhidrAssistMCPManager runtime = GhidrAssistMCPManager.findInstance();
                if (runtime == null || !runtime.hasFrontEndOwner()) {
                    GhidrAssistMCPManager.addTransientPlugin(frontEnd, GhidrAssistMCPApplicationPlugin.class.getName());
                }
                attached = true;
                Toolkit.getDefaultToolkit().removeAWTEventListener(this);
            } catch (Exception e) {
                Msg.error(this, "Could not start GhidrAssistMCP in the project manager", e);
            }
        }
    }
}
