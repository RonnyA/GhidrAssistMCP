package ghidrassistmcp;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import docking.ComponentProvider;
import ghidra.framework.plugintool.PluginTool;
import ghidra.util.HelpLocation;

/** Per-window view of the shared application configuration and request log. */
public final class GhidrAssistMCPProvider extends ComponentProvider {
    private final GhidrAssistMCPManager manager;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextField host = new JTextField(20);
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(8080, 1, 65535, 1));
    private final JCheckBox enabled = new JCheckBox("Enable MCP Server");
    private final JCheckBox async = new JCheckBox("Enable async tool execution");
    private final DefaultTableModel tableModel = new DefaultTableModel(
        new String[]{"Enabled", "Tool Name", "Description"}, 0) {
        @Override public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
    };
    private final JTable tools = new JTable(tableModel);
    private final JTextArea log = new JTextArea(20, 60);
    private volatile boolean detached;
    private final Consumer<String> logListener = snapshot -> SwingUtilities.invokeLater(() -> {
        if (!detached) {
            log.setText(snapshot);
            log.setCaretPosition(log.getDocument().getLength());
        }
    });
    private boolean subscribed;

    public GhidrAssistMCPProvider(PluginTool tool, GhidrAssistMCPManager manager) {
        super(tool, "GhidrAssistMCP", "GhidrAssistMCPPlugin");
        this.manager = manager;
        buildComponent();
        setHelpLocation(new HelpLocation("GhidrAssistMCP", "GhidrAssistMCP_Provider"));
    }

    private void buildComponent() {
        JPanel configuration = new JPanel(new BorderLayout(8, 8));
        JPanel settings = new JPanel(new GridLayout(3, 2, 8, 8));
        settings.add(new JLabel("Host:"));
        settings.add(host);
        settings.add(new JLabel("Port:"));
        settings.add(port);
        settings.add(enabled);
        settings.add(async);
        async.setToolTipText("Long-running tools return a task ID when enabled.");
        configuration.add(settings, BorderLayout.NORTH);
        tools.getColumnModel().getColumn(0).setMaxWidth(65);
        tools.getColumnModel().getColumn(1).setPreferredWidth(180);
        tools.getColumnModel().getColumn(2).setPreferredWidth(480);
        tools.setAutoCreateRowSorter(true);
        configuration.add(new JScrollPane(tools), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refreshConfiguration());
        JButton save = new JButton("Save Configuration");
        save.addActionListener(event -> saveSettings());
        buttons.add(refresh);
        buttons.add(save);
        configuration.add(buttons, BorderLayout.SOUTH);
        tabs.addTab("Configuration", configuration);

        JPanel logPanel = new JPanel(new BorderLayout());
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logPanel.add(new JScrollPane(log), BorderLayout.CENTER);
        JPanel logButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clear = new JButton("Clear Shared Log");
        clear.addActionListener(event -> manager.getLog().clear());
        logButtons.add(clear);
        logPanel.add(logButtons, BorderLayout.SOUTH);
        tabs.addTab("Log", logPanel);
    }

    private void saveSettings() {
        if (tools.isEditing() && !tools.getCellEditor().stopCellEditing()) {
            return;
        }
        Map<String, Boolean> states = new HashMap<>();
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            states.put((String) tableModel.getValueAt(row, 1),
                (Boolean) tableModel.getValueAt(row, 0));
        }
        try {
            manager.applyConfiguration(host.getText().trim(), (Integer) port.getValue(),
                enabled.isSelected(), async.isSelected(), states);
        } catch (IllegalArgumentException e) {
            manager.onLogMessage("Configuration not saved: " + e.getMessage());
        }
    }

    public void onBackendReady() {
        if (!subscribed) {
            subscribed = true;
            manager.getLog().subscribe(logListener);
        }
        refreshConfiguration();
    }

    public void refreshConfiguration() {
        SwingUtilities.invokeLater(() -> {
            if (detached) {
                return;
            }
            host.setText(manager.getCurrentHost());
            port.setValue(manager.getCurrentPort());
            enabled.setSelected(manager.isServerEnabled());
            async.setSelected(manager.getBackend().isAsyncExecutionEnabled());
            refreshToolsList();
        });
    }

    public void refreshToolsList() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshToolsList);
            return;
        }
        if (detached) {
            return;
        }
        tableModel.setRowCount(0);
        Map<String, Boolean> states = manager.getBackend().getToolEnabledStates();
        manager.getBackend().getAllTools().stream()
            .sorted(java.util.Comparator.comparing(tool -> tool.name()))
            .forEach(tool -> tableModel.addRow(new Object[]{
                states.getOrDefault(tool.name(), false), tool.name(), tool.description()}));
    }

    public void detach() {
        detached = true;
        if (subscribed) {
            manager.getLog().unsubscribe(logListener);
            subscribed = false;
        }
    }

    public void logMessage(String message) { manager.onLogMessage(message); }
    public void logSession(String message) { manager.onSessionEvent(message); }
    @Override public JComponent getComponent() { return tabs; }
}
