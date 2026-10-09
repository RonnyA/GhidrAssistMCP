package ghidrassistmcp.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import ghidra.framework.main.FrontEndTool;
import ghidra.framework.model.DomainFile;
import ghidra.framework.model.DomainObject;
import ghidra.framework.model.Project;
import ghidra.framework.model.ProjectLocator;
import ghidra.framework.model.ProjectManager;
import ghidra.framework.options.SaveState;
import ghidra.util.SystemUtilities;
import ghidra.util.task.TaskMonitor;

/** Uses public Ghidra APIs and leaves the project-manager-owned MCP runtime alive. */
public final class GhidraProjectController implements ProjectController {
    // Ghidra 12.0/12.1 DefaultProject persists project/workspace metadata in this file.
    private static final String PROJECT_STATE_FILE = "projectState";
    static final String SAVE_CHECKPOINT_KEY = "GhidrAssistMCP.ProjectControl";
    static final String SAVE_CHECKPOINT_FIELD = "save_id";
    private final Host host;

    public GhidraProjectController(Supplier<FrontEndTool> owner) {
        this(new FrontEndHost(owner));
    }

    /** Test seam replaces GUI scheduling/access, keeping the lifecycle policy real. */
    GhidraProjectController(Host host) {
        this.host = java.util.Objects.requireNonNull(host);
    }

    @Override
    public Map<String, Object> createProject(String location, String name) throws Exception {
        ProjectLocator locator = locator(location, name, true);
        return host.onEdt(() -> {
            ProjectManager manager = host.projectManager();
            requireNoActiveProject(manager);
            if (manager.projectExists(locator) || locator.getMarkerFile().exists()
                    || locator.getProjectDir().exists()) {
                throw new IOException("Project already exists; no files were overwritten: " + locator.getMarkerFile());
            }
            Project created = manager.createProject(locator, null, true);
            if (created == null) {
                throw new IOException("Ghidra did not create the project: " + locator.getMarkerFile());
            }
            attach(created);
            return result(created, "created");
        });
    }

    @Override
    public Map<String, Object> openProject(String location, String name, boolean restoreState) throws Exception {
        ProjectLocator locator = locator(location, name, false);
        return host.onEdt(() -> {
            ProjectManager manager = host.projectManager();
            Project current = manager.getActiveProject();
            if (current != null && !current.isClosed()
                    && locator.equals(current.getProjectLocator())) {
                return result(current, "already_open");
            }
            requireNoActiveProject(manager);
            if (!locator.getMarkerFile().isFile() || !locator.getProjectDir().isDirectory()) {
                throw new IOException("Ghidra project not found: " + locator.getMarkerFile());
            }
            Project opened = manager.openProject(locator, restoreState, false);
            if (opened == null) {
                throw new IOException("Ghidra did not open the project: " + locator.getMarkerFile());
            }
            attach(opened);
            return result(opened, "opened");
        });
    }

    private void attach(Project project) throws Exception {
        try {
            if (host.projectManager().getActiveProject() != project || project.isClosed()) {
                throw new IllegalStateException("The active Ghidra project changed before it could be displayed");
            }
            host.setActiveProject(project);
        } catch (Exception error) {
            // Release the newly acquired project lock if UI attachment fails.
            boolean stillActive = host.projectManager().getActiveProject() == project;
            if (!project.isClosed()) {
                try { project.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            }
            if (stillActive) {
                try { host.setActiveProject(null); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }

    @Override
    public Map<String, Object> saveProject() throws Exception {
        requireBackgroundThread();
        Project project = activeProject();
        try (LockedData data = lockOpenData(project)) {
            int saved = save(project, data);
            Map<String, Object> result = result(project, "saved");
            result.put("saved_files", saved);
            return result;
        }
    }

    @Override
    public Map<String, Object> closeProject(boolean save, boolean discardChanges) throws Exception {
        if (save && discardChanges) {
            throw new IllegalArgumentException("save and discard_changes are mutually exclusive");
        }
        requireBackgroundThread();
        Project project = host.onEdt(() -> host.projectManager().getActiveProject());
        if (project == null) {
            return new LinkedHashMap<>(Map.of("status", "already_closed", "name", "",
                "location", "", "project_path", ""));
        }
        Map<String, Object> result = result(project, "closed");
        try (LockedData data = lockOpenData(project)) {
            boolean changed = host.onEdt(() -> {
                requireCurrent(project);
                if (!discardChanges && host.hasUnsavedToolConfiguration(project)) {
                    throw new IllegalStateException("A tool has unsaved configuration. Save its configuration in Ghidra before closing, or use discard_changes=true.");
                }
                return project.hasChanged();
            });
            if (!save && !discardChanges && (changed || data.hasChanges())) {
                throw new IllegalStateException("Project or open data has unsaved changes. "
                    + "Use close_project with save=true or discard_changes=true.");
            }
            if (!save && !discardChanges) {
                String previousCheckpoint = host.onEdt(() -> {
                    requireCurrent(project);
                    SaveState state = project.getSaveableData(SAVE_CHECKPOINT_KEY);
                    return state == null ? null : state.getString(SAVE_CHECKPOINT_FIELD, null);
                });
                if (previousCheckpoint != null) {
                    verifyMetadataCheckpoint(project, previousCheckpoint);
                }
            }
            int saved = save ? save(project, data) : 0;
            host.onEdt(() -> {
                requireCurrent(project);
                data.verifyOpenData(project.getOpenData());
                if (!discardChanges && (project.hasChanged() || data.hasChanges()
                        || project.getOpenData().stream().anyMatch(DomainFile::isChanged)
                        || host.hasUnsavedToolConfiguration(project)
                        || host.hasUnsavedPluginData(project))) {
                    throw new IllegalStateException("Project or tool plugin data remains unsaved. Save it in Ghidra before closing, or use discard_changes=true. The project remains open.");
                }
                project.close();
                if (!project.isClosed()) {
                    throw new IllegalStateException("Ghidra did not close the project");
                }
                host.setActiveProject(null);
                host.projectManager().setLastOpenedProject(null);
                return null;
            });
            result.put("saved_files", saved);
            result.put("discarded_changes", discardChanges);
            return result;
        }
    }

    private Project activeProject() throws Exception {
        return host.onEdt(() -> {
            Project project = host.projectManager().getActiveProject();
            if (project == null || project.isClosed()) {
                throw new IllegalStateException("No Ghidra project is open. Use create_project or open_project first.");
            }
            requireCurrent(project);
            return project;
        });
    }

    private void requireCurrent(Project project) throws Exception {
        if (project.isClosed() || host.projectManager().getActiveProject() != project) {
            throw new IllegalStateException("The active Ghidra project changed; operation stopped");
        }
        if (host.hasRunningCommands(project)) {
            throw new IllegalStateException("A Ghidra tool is running a command. Wait for it to finish before saving or closing the project.");
        }
    }

    private static void requireNoActiveProject(ProjectManager manager) {
        if (manager.getActiveProject() != null) {
            throw new IllegalStateException("A Ghidra project is already open. Use close_project before opening or creating another project.");
        }
    }

    private LockedData lockOpenData(Project project) throws Exception {
        List<DomainFile> files = host.onEdt(() -> {
            requireCurrent(project);
            return List.copyOf(project.getOpenData());
        });
        LockedData data = new LockedData();
        data.observedFiles.addAll(files);
        try {
            for (DomainFile file : files) {
                if (file.isBusy()) {
                    throw new IllegalStateException("Project data is busy: " + file.getPathname());
                }
                Object consumer = new Object();
                DomainObject object = file.getOpenedDomainObject(consumer);
                if (object == null) {
                    if (file.isChanged()) {
                        throw new IllegalStateException("Changed project data is no longer open: " + file.getPathname());
                    }
                    continue;
                }
                if (data.objects.stream().anyMatch(existing -> existing == object)) {
                    object.release(consumer);
                    continue;
                }
                data.objects.add(object);
                data.files.add(file);
                data.consumers.add(consumer);
                if (object.isLocked() || !object.lock("GhidrAssistMCP project lifecycle")) {
                    throw new IllegalStateException("Project data is busy: " + file.getPathname());
                }
                data.locked.add(object);
            }
            return data;
        } catch (Exception error) {
            try { data.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private int save(Project project, LockedData data) throws Exception {
        int saved = 0;
        List<String> savedPaths = new ArrayList<>();
        try {
            // Check every changed file before the first save, avoiding predictable partial saves.
            for (int i = 0; i < data.files.size(); i++) {
                if (data.objects.get(i).isChanged() && !data.files.get(i).canSave()) {
                    throw new IOException("Changed project data is read-only or cannot be saved: " + data.files.get(i).getPathname());
                }
            }
            for (int i = 0; i < data.files.size(); i++) {
                DomainFile file = data.files.get(i);
                DomainObject object = data.objects.get(i);
                if (!object.isChanged()) {
                    continue;
                }
                host.onEdt(() -> { requireCurrent(project); return null; });
                file.save(TaskMonitor.DUMMY);
                if (object.isChanged() || file.isChanged()) {
                    throw new IOException("Project data remains changed after saving: " + file.getPathname());
                }
                saved++;
                savedPaths.add(file.getPathname());
            }
            saveMetadata(project);
            host.onEdt(() -> {
                requireCurrent(project);
                if (project.getOpenData().stream().anyMatch(DomainFile::isChanged)) {
                    throw new IOException("New or modified project data appeared while saving; it remains unsaved");
                }
                return null;
            });
            return saved;
        } catch (Exception error) {
            throw new IOException("Project save failed after " + saved + " file(s) saved"
                + (savedPaths.isEmpty() ? "" : " " + savedPaths) + ": " + error.getMessage(), error);
        }
    }

    private void saveMetadata(Project project) throws Exception {
        String checkpoint = UUID.randomUUID().toString();
        host.onEdt(() -> {
            requireCurrent(project);
            SaveState state = new SaveState();
            state.putString(SAVE_CHECKPOINT_FIELD, checkpoint);
            project.setSaveableData(SAVE_CHECKPOINT_KEY, state);
            project.save();
            return null;
        });
        verifyMetadataCheckpoint(project, checkpoint);
    }

    private static void verifyMetadataCheckpoint(Project project, String checkpoint) throws Exception {
        // DefaultProject.save catches I/O failures rather than throwing. Verify this
        // invocation's private metadata checkpoint, not merely an older file/dirty flag.
        // Also retain failure evidence across controller instances: ToolManager can clear
        // workspace dirty flags even when the subsequent metadata write fails.
        Path stateFile = project.getProjectLocator().getProjectDir().toPath().resolve(PROJECT_STATE_FILE);
        // Bypass Ghidra 12.0's older Xerces provider, which lacks modern JAXP security properties.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(stateFile.toFile());
        var root = document.getDocumentElement();
        var states = document.getElementsByTagName("STATE");
        boolean persisted = false;
        for (int i = 0; i < states.getLength(); i++) {
            var element = (org.w3c.dom.Element) states.item(i);
            var saveState = element.getParentNode();
            var projectData = saveState.getParentNode();
            if ("SAVE_STATE".equals(saveState.getNodeName())
                    && projectData instanceof org.w3c.dom.Element data
                    && "PROJECT_DATA_XML_NAME".equals(data.getTagName())
                    && SAVE_CHECKPOINT_KEY.equals(data.getAttribute("NAME"))
                    && data.getParentNode() == root
                    && "string".equals(element.getAttribute("TYPE"))
                    && SAVE_CHECKPOINT_FIELD.equals(element.getAttribute("NAME"))
                    && checkpoint.equals(element.getAttribute("VALUE"))) {
                persisted = true;
                break;
            }
        }
        if (!"PROJECT".equals(root.getTagName()) || !persisted) {
            throw new IOException("Project/workspace metadata was not saved: " + stateFile
                + ". Retry save_project before closing, or explicitly discard_changes.");
        }
    }

    private static ProjectLocator locator(String location, String name, boolean creating) {
        if (location == null || location.isBlank() || !Path.of(location).isAbsolute()
                || !Files.isDirectory(Path.of(location))) {
            throw new IllegalArgumentException("location must be an absolute existing parent directory");
        }
        if (name == null || name.isBlank() || name.equals(".")
                || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("name must be a non-empty simple project name");
        }
        if (creating && (!name.equals(name.trim()) || name.endsWith(".gpr") || name.endsWith(".rep"))) {
            throw new IllegalArgumentException("name must be a simple project name without surrounding whitespace or a .gpr/.rep suffix");
        }
        return new ProjectLocator(Path.of(location).toAbsolutePath().normalize().toString(), name);
    }

    private static Map<String, Object> result(Project project, String status) {
        ProjectLocator locator = project.getProjectLocator();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("name", project.getName());
        result.put("location", locator.getLocation());
        result.put("project_path", locator.getMarkerFile().getAbsolutePath());
        return result;
    }

    private static void requireBackgroundThread() {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Project data saving and closing must run outside the Swing event thread");
        }
    }

    private static final class LockedData implements AutoCloseable {
        final Set<DomainFile> observedFiles = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<DomainFile> files = new ArrayList<>();
        final List<DomainObject> objects = new ArrayList<>();
        final List<Object> consumers = new ArrayList<>();
        final List<DomainObject> locked = new ArrayList<>();
        boolean hasChanges() { return objects.stream().anyMatch(DomainObject::isChanged); }
        void verifyOpenData(List<DomainFile> currentFiles) {
            Set<DomainFile> current = Collections.newSetFromMap(new IdentityHashMap<>());
            current.addAll(currentFiles);
            if (!observedFiles.equals(current)) {
                throw new IllegalStateException("Open project data changed while preparing to close. The project remains open; retry close_project.");
            }
            for (DomainFile file : currentFiles) {
                if (file.isBusy()) {
                    throw new IllegalStateException("Project data is busy: " + file.getPathname());
                }
            }
        }
        @Override public void close() {
            RuntimeException failure = null;
            for (int i = locked.size() - 1; i >= 0; i--) {
                try { locked.get(i).unlock(); }
                catch (RuntimeException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
            for (int i = objects.size() - 1; i >= 0; i--) {
                try { objects.get(i).release(consumers.get(i)); }
                catch (RuntimeException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }

    @FunctionalInterface interface CheckedSupplier<T> { T get() throws Exception; }
    interface Host {
        <T> T onEdt(CheckedSupplier<T> action) throws Exception;
        ProjectManager projectManager();
        void setActiveProject(Project project);
        boolean hasRunningCommands(Project project);
        boolean hasUnsavedToolConfiguration(Project project);
        boolean hasUnsavedPluginData(Project project);
    }

    private static final class FrontEndHost implements Host {
        private final Supplier<FrontEndTool> owner;
        FrontEndHost(Supplier<FrontEndTool> owner) { this.owner = java.util.Objects.requireNonNull(owner); }
        private FrontEndTool owner() {
            FrontEndTool tool = owner.get();
            if (tool == null) throw new IllegalStateException("Project controls require the Ghidra GUI project manager");
            return tool;
        }
        @Override public ProjectManager projectManager() { return owner().getProjectManager(); }
        @Override public void setActiveProject(Project project) { owner().setActiveProject(project); }
        @Override public boolean hasRunningCommands(Project project) {
            if (owner().isExecutingCommand()) return true;
            for (var tool : project.getToolManager().getRunningTools()) {
                if (tool.isExecutingCommand()) return true;
            }
            return false;
        }
        @Override public boolean hasUnsavedToolConfiguration(Project project) {
            for (var tool : project.getToolManager().getRunningTools()) {
                if (tool.shouldSave()) return true;
            }
            return false;
        }
        @Override public boolean hasUnsavedPluginData(Project project) {
            for (var tool : project.getToolManager().getRunningTools()) {
                if (tool.hasUnsavedData()) return true;
            }
            return false;
        }
        @Override public <T> T onEdt(CheckedSupplier<T> action) throws Exception {
            AtomicReference<Exception> failure = new AtomicReference<>();
            T result = SystemUtilities.runSwingNow(() -> {
                try { return action.get(); }
                catch (Exception error) { failure.set(error); return null; }
            });
            if (failure.get() != null) throw failure.get();
            return result;
        }
    }
}
