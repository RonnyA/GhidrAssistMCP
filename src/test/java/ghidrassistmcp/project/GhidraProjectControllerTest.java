package ghidrassistmcp.project;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import ghidra.util.xml.XmlUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ghidra.framework.model.DomainFile;
import ghidra.framework.model.DomainObject;
import ghidra.framework.model.Project;
import ghidra.framework.model.ProjectLocator;
import ghidra.framework.model.ProjectManager;
import ghidra.framework.options.SaveState;

class GhidraProjectControllerTest {
    @TempDir Path directory;

    @BeforeAll static void registerGhidraProtocol() {
        ghidra.framework.protocol.ghidra.Handler.registerHandler();
    }

    @Test void createUsesPrivateRepositoryAndAttachesWithoutReplacingAnActiveProject() throws Exception {
        FakeHost host = new FakeHost();
        GhidraProjectController controller = new GhidraProjectController(host);
        Map<String, Object> created = controller.createProject(directory.toString(), "created");
        assertEquals("created", created.get("status"));
        assertEquals(directory.resolve("created.gpr").toString(), created.get("project_path"));
        assertSame(host.current.project, host.displayed);
        assertEquals(1, host.creates);
        assertNull(host.repository);
        assertTrue(host.remember);
        assertThrows(IllegalStateException.class, () -> controller.createProject(directory.toString(), "other"));
        assertEquals(1, host.creates);
    }

    @Test void existingProjectIsNeverOverwrittenAndInvalidLocationsNeverReachManager() throws Exception {
        FakeHost host = new FakeHost();
        GhidraProjectController controller = new GhidraProjectController(host);
        Files.createFile(directory.resolve("existing.gpr"));
        assertThrows(IOException.class, () -> controller.createProject(directory.toString(), "existing"));
        assertThrows(IllegalArgumentException.class, () -> controller.createProject("relative", "name"));
        assertThrows(IllegalArgumentException.class, () -> controller.createProject(directory.toString(), "../name"));
        assertEquals(0, host.creates);
        assertNull(host.current);
    }

    @Test void openPassesRestoreStateWithoutForcingLocksAndSameProjectIsIdempotent() throws Exception {
        FakeHost host = new FakeHost();
        new FakeProject(host, " legacy.rep ").makeFiles();
        GhidraProjectController controller = new GhidraProjectController(host);
        assertEquals("opened", controller.openProject(directory.toString(), " legacy.rep ", true).get("status"));
        assertTrue(host.restore);
        assertFalse(host.resetOwner);
        assertEquals("already_open", controller.openProject(directory.toString(), " legacy.rep ", false).get("status"));
        assertEquals(1, host.opens);
        assertThrows(IllegalStateException.class, () -> controller.openProject(directory.toString(), "other", false));
        assertSame(host.current.project, host.displayed);
    }

    @Test void attachmentFailureReleasesNewProjectAndPreservesAnyNewerActiveProject() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject replacement = new FakeProject(host, "replacement");
        host.attachFailure = () -> { host.current = replacement; throw new IllegalStateException("UI attachment failed"); };
        assertThrows(IllegalStateException.class,
            () -> new GhidraProjectController(host).createProject(directory.toString(), "created"));
        assertTrue(host.lastCreated.closed);
        assertSame(replacement, host.current);
        assertFalse(replacement.closed);
    }

    @Test void saveCoversAllDomainObjectTypesOffEdtAndVerifiesMetadataCheckpoint() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("save");
        FakeFile program = project.file("/program", true);
        FakeFile archive = project.file("/data-type-archive", true);
        FakeFile unchanged = project.file("/clean", false);
        Map<String, Object> result = new GhidraProjectController(host).saveProject();
        assertEquals("saved", result.get("status"));
        assertEquals(2, result.get("saved_files"));
        assertEquals(1, program.saves);
        assertEquals(1, archive.saves);
        assertEquals(0, unchanged.saves);
        assertEquals(1, project.metadataSaves);
        assertTrue(Files.readString(project.stateFile()).contains(GhidraProjectController.SAVE_CHECKPOINT_FIELD));
        assertReleased(program, archive, unchanged);
    }

    @Test void readonlyPreflightAvoidsPartialSavesAndReleasesEveryObject() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("readonly");
        FakeFile first = project.file("/first", true);
        FakeFile readonly = project.file("/readonly", true);
        readonly.saveable = false;
        IOException error = assertThrows(IOException.class, () -> new GhidraProjectController(host).saveProject());
        assertTrue(error.getMessage().contains("after 0 file(s)"));
        assertTrue(error.getMessage().contains("/readonly"));
        assertEquals(0, first.saves);
        assertEquals(0, project.metadataSaves);
        assertReleased(first, readonly);
    }

    @Test void failedSecondSaveReportsConfirmedPartialProgressAndReleasesLocks() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("partial");
        FakeFile first = project.file("/first", true);
        FakeFile second = project.file("/second", true);
        second.failSave = true;
        IOException error = assertThrows(IOException.class, () -> new GhidraProjectController(host).saveProject());
        assertTrue(error.getMessage().contains("after 1 file(s) saved [/first]"));
        assertTrue(error.getMessage().contains("/second"));
        assertFalse(first.changed);
        assertTrue(second.changed);
        assertEquals(0, project.metadataSaves);
        assertSame(project, host.current);
        assertReleased(first, second);
    }

    @Test void swallowedMetadataWriteFailureCannotPassWithOlderFileAndClearedDirtyFlag() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("metadata");
        GhidraProjectController controller = new GhidraProjectController(host);
        controller.saveProject();
        String oldState = Files.readString(project.stateFile());
        project.swallowMetadataWrite = true;
        project.metadataDirty = true;
        IOException error = assertThrows(IOException.class, () -> controller.closeProject(true, false));
        assertTrue(error.getMessage().contains("metadata was not saved"));
        assertFalse(project.metadataDirty);
        assertEquals(oldState, Files.readString(project.stateFile()));
        assertFalse(project.closed);
        assertSame(project.project, host.displayed);
        assertThrows(IOException.class, () -> new GhidraProjectController(host).closeProject(false, false));
        assertFalse(project.closed);
        project.swallowMetadataWrite = false;
        var closed = new GhidraProjectController(host).closeProject(true, false);
        assertEquals("closed", closed.get("status"));
        assertTrue(project.closed);
    }

    @Test void aReturnedSaveThatLeavesChangesDoesNotCountAsAConfirmedSavedFile() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("unconfirmed");
        FakeFile file = project.file("/still-dirty", true);
        file.afterSave = () -> file.changed = true;
        IOException error = assertThrows(IOException.class,
            () -> new GhidraProjectController(host).closeProject(true, false));
        assertTrue(error.getMessage().contains("after 0 file(s) saved"));
        assertTrue(error.getMessage().contains("remains changed after saving: /still-dirty"));
        assertEquals(0, project.metadataSaves);
        assertFalse(project.closed);
        assertSame(project, host.current);
        assertReleased(file);
    }

    @Test void defaultCloseRefusesDirtyDataAndMetadataWithoutChangingFrontend() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("dirty");
        FakeFile file = project.file("/dirty", true);
        GhidraProjectController controller = new GhidraProjectController(host);
        assertThrows(IllegalStateException.class, () -> controller.closeProject(false, false));
        file.changed = false;
        project.metadataDirty = true;
        assertThrows(IllegalStateException.class, () -> controller.closeProject(false, false));
        assertSame(project, host.current);
        assertSame(project.project, host.displayed);
        assertFalse(project.closed);
        assertReleased(file);
    }

    @Test void closeWithSaveSavesDataThenClosesAndAlreadyClosedIsIdempotent() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("close");
        FakeFile file = project.file("/dirty", true);
        project.metadataDirty = true;
        GhidraProjectController controller = new GhidraProjectController(host);
        var result = controller.closeProject(true, false);
        assertEquals("closed", result.get("status"));
        assertEquals(1, result.get("saved_files"));
        assertEquals(false, result.get("discarded_changes"));
        assertTrue(project.closed);
        assertNull(host.current);
        assertNull(host.displayed);
        assertEquals(1, host.lastOpenedClears);
        assertReleased(file);
        assertEquals("already_closed", controller.closeProject(false, false).get("status"));
    }

    @Test void unsavedToolConfigAndPluginDataRequireExplicitDiscardEvenWithSave() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("plugins");
        FakeFile file = project.file("/dirty", true);
        GhidraProjectController controller = new GhidraProjectController(host);
        host.configDirty = true;
        assertThrows(IllegalStateException.class, () -> controller.closeProject(true, false));
        assertEquals(0, file.saves);
        host.configDirty = false;
        host.pluginDirty = true;
        assertThrows(IllegalStateException.class, () -> controller.closeProject(true, false));
        assertFalse(project.closed);
        assertSame(project.project, host.displayed);
        int metadataSaves = project.metadataSaves;
        host.configDirty = true;
        var discarded = controller.closeProject(false, true);
        assertEquals(true, discarded.get("discarded_changes"));
        assertEquals(0, discarded.get("saved_files"));
        assertEquals(metadataSaves, project.metadataSaves);
        assertTrue(project.closed);
        assertReleased(file);
    }

    @Test void discardStillRefusesRunningCommandsAndBusyObjectsAndCleansPartialLocks() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("busy");
        FakeFile first = project.file("/first", true);
        FakeFile second = project.file("/busy", true);
        GhidraProjectController controller = new GhidraProjectController(host);
        host.busy = true;
        assertThrows(IllegalStateException.class, () -> controller.closeProject(false, true));
        assertEquals(0, first.locks);
        host.busy = false;
        second.allowLock = false;
        var error = assertThrows(IllegalStateException.class, () -> controller.closeProject(false, true));
        assertTrue(error.getMessage().contains("/busy"));
        assertEquals(1, first.unlocks);
        assertEquals(0, second.unlocks);
        assertTrue(first.consumers.isEmpty());
        assertTrue(second.consumers.isEmpty());
        assertFalse(project.closed);
        assertSame(project, host.current);
        assertThrows(IllegalArgumentException.class, () -> controller.closeProject(true, true));
    }

    @Test void uiProjectSwitchDuringSaveCannotSaveOrCloseReplacement() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject original = host.activate("original");
        FakeFile first = original.file("/first", true);
        FakeFile second = original.file("/second", true);
        FakeProject replacement = new FakeProject(host, "replacement");
        first.afterSave = () -> { host.current = replacement; host.displayed = replacement.project; };
        IOException error = assertThrows(IOException.class,
            () -> new GhidraProjectController(host).closeProject(true, false));
        assertTrue(error.getMessage().contains("active Ghidra project changed"));
        assertEquals(0, second.saves);
        assertEquals(0, original.metadataSaves);
        assertEquals(0, replacement.metadataSaves);
        assertFalse(replacement.closed);
        assertSame(replacement.project, host.displayed);
        assertReleased(first, second);
    }

    @Test void existingSnapshotLockIsRefusedWithoutTakingAnotherLockOrWaiting() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("snapshot");
        FakeFile file = project.file("/snapshot", false);
        file.hostLocked = true;
        assertThrows(IllegalStateException.class,
            () -> new GhidraProjectController(host).closeProject(false, true));
        assertEquals(0, file.locks);
        assertReleased(file);
        file.hostLocked = false;
        file.busy = true;
        assertThrows(IllegalStateException.class,
            () -> new GhidraProjectController(host).saveProject());
        assertEquals(0, file.locks);
        assertReleased(file);
    }

    @Test void uiOpeningAnotherFileDuringSaveRefusesCloseWithoutDroppingEitherFile() throws Exception {
        FakeHost host = new FakeHost();
        FakeProject project = host.activate("new-data");
        FakeFile first = project.file("/first", true);
        first.afterSave = () -> project.file("/newly-opened", false);
        var error = assertThrows(IllegalStateException.class,
            () -> new GhidraProjectController(host).closeProject(true, false));
        assertTrue(error.getMessage().contains("Open project data changed"));
        assertFalse(project.closed);
        assertSame(project, host.current);
        assertSame(project.project, host.displayed);
        assertEquals(2, project.openFiles.size());
        assertReleased(first);
    }

    private static void assertReleased(FakeFile... files) {
        for (FakeFile file : files) {
            assertEquals(file.locks, file.unlocks, file.path);
            assertTrue(file.consumers.isEmpty(), file.path);
        }
    }

    private final class FakeHost implements GhidraProjectController.Host {
        FakeProject current, lastCreated;
        Project displayed;
        Object repository;
        int creates, opens, lastOpenedClears;
        boolean onEdt, busy, configDirty, pluginDirty, remember, restore, resetOwner;
        Runnable attachFailure;
        final ProjectManager manager = proxy(ProjectManager.class, (object, method, args) -> {
            assertTrue(onEdt, method.getName());
            return switch (method.getName()) {
                case "getActiveProject" -> current == null ? null : current.project;
                case "projectExists" -> false;
                case "createProject" -> {
                    creates++;
                    repository = args[1];
                    remember = (boolean) args[2];
                    lastCreated = new FakeProject(this, ((ProjectLocator) args[0]).getName());
                    lastCreated.makeFiles();
                    current = lastCreated;
                    yield current.project;
                }
                case "openProject" -> {
                    opens++;
                    restore = (boolean) args[1];
                    resetOwner = (boolean) args[2];
                    current = new FakeProject(this, ((ProjectLocator) args[0]).getName());
                    yield current.project;
                }
                case "setLastOpenedProject" -> { assertNull(args[0]); lastOpenedClears++; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        FakeProject activate(String name) throws IOException {
            current = new FakeProject(this, name);
            current.makeFiles();
            displayed = current.project;
            return current;
        }
        @Override public <T> T onEdt(GhidraProjectController.CheckedSupplier<T> action) throws Exception {
            boolean previous = onEdt;
            onEdt = true;
            try { return action.get(); } finally { onEdt = previous; }
        }
        @Override public ProjectManager projectManager() { assertTrue(onEdt); return manager; }
        @Override public void setActiveProject(Project project) {
            assertTrue(onEdt);
            if (project != null && attachFailure != null) attachFailure.run();
            displayed = project;
        }
        @Override public boolean hasRunningCommands(Project project) { assertTrue(onEdt); return busy; }
        @Override public boolean hasUnsavedToolConfiguration(Project project) { assertTrue(onEdt); return configDirty; }
        @Override public boolean hasUnsavedPluginData(Project project) { assertTrue(onEdt); return pluginDirty; }
    }

    private final class FakeProject {
        final FakeHost host;
        final ProjectLocator locator;
        final List<DomainFile> openFiles = new ArrayList<>();
        SaveState checkpoint;
        int metadataSaves;
        boolean closed, metadataDirty, swallowMetadataWrite;
        final Project project;
        private Project createProxy() { return proxy(Project.class, (object, method, args) -> switch (method.getName()) {
            case "getName" -> locator.getName();
            case "getProjectLocator" -> locator;
            case "isClosed" -> closed;
            case "hasChanged" -> { assertTrue(host.onEdt); yield metadataDirty; }
            case "getOpenData" -> { assertTrue(host.onEdt); yield List.copyOf(openFiles); }
            case "setSaveableData" -> { assertTrue(host.onEdt); checkpoint = (SaveState) args[1]; yield null; }
            case "getSaveableData" -> { assertTrue(host.onEdt); yield checkpoint; }
            case "save" -> {
                assertTrue(host.onEdt);
                metadataSaves++;
                metadataDirty = false;
                if (!swallowMetadataWrite) Files.writeString(stateFile(), "<PROJECT><PROJECT_DATA_XML_NAME NAME=\""
                    + GhidraProjectController.SAVE_CHECKPOINT_KEY + "\">"
                    + XmlUtilities.toString(checkpoint.saveToXml()) + "</PROJECT_DATA_XML_NAME></PROJECT>");
                yield null;
            }
            case "close" -> {
                assertTrue(host.onEdt);
                closed = true;
                if (host.current == this) host.current = null;
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        }); }
        FakeProject(FakeHost host, String name) {
            this.host = host;
            locator = new ProjectLocator(directory.toString(), name);
            project = createProxy();
        }
        void makeFiles() throws IOException {
            Files.createDirectories(locator.getProjectDir().toPath());
            if (!locator.getMarkerFile().exists()) Files.createFile(locator.getMarkerFile().toPath());
        }
        Path stateFile() { return locator.getProjectDir().toPath().resolve("projectState"); }
        FakeFile file(String path, boolean changed) {
            FakeFile file = new FakeFile(host, path, changed);
            openFiles.add(file.file);
            return file;
        }
    }

    private static final class FakeFile {
        final FakeHost host;
        final String path;
        final IdentityHashMap<Object, Boolean> consumers = new IdentityHashMap<>();
        int saves, locks, unlocks;
        boolean changed, saveable = true, allowLock = true, failSave, hostLocked, busy;
        Runnable afterSave;
        final DomainObject object;
        private DomainObject createObjectProxy() { return proxy(DomainObject.class, (proxy, method, args) -> switch (method.getName()) {
            case "isChanged" -> changed;
            case "isLocked" -> hostLocked;
            case "lock" -> { if (allowLock) locks++; yield allowLock; }
            case "unlock" -> { unlocks++; yield null; }
            case "release" -> { assertNotNull(consumers.remove(args[0])); yield null; }
            default -> throw new UnsupportedOperationException(method.getName());
        }); }
        final DomainFile file;
        private DomainFile createFileProxy() { return proxy(DomainFile.class, (proxy, method, args) -> switch (method.getName()) {
            case "getPathname" -> path;
            case "isChanged" -> changed;
            case "isBusy" -> busy;
            case "canSave" -> saveable;
            case "getOpenedDomainObject" -> { consumers.put(args[0], true); yield object; }
            case "save" -> {
                assertFalse(host.onEdt, "DomainFile.save must run off EDT");
                assertEquals(locks, unlocks + 1, "Save requires a retained domain lock");
                if (failSave) throw new IOException("Cannot save " + path);
                saves++;
                changed = false;
                if (afterSave != null) afterSave.run();
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        }); }
        FakeFile(FakeHost host, String path, boolean changed) {
            this.host = host;
            this.path = path;
            this.changed = changed;
            object = createObjectProxy();
            file = createFileProxy();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> object == args[0];
                    case "hashCode" -> System.identityHashCode(object);
                    case "toString" -> type.getSimpleName() + " fixture";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            return handler.invoke(object, method, args);
        });
    }
}
