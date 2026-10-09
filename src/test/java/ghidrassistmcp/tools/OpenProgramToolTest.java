package ghidrassistmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.List;
import ghidra.framework.model.DomainFile;
import org.junit.jupiter.api.Test;

class OpenProgramToolTest {
    @Test void duplicateNamesRequireFullProjectPaths() {
        DomainFile first = file("/version1/app.exe");
        DomainFile second = file("/version2/app.exe");
        List<DomainFile> project = List.of(first, second);
        assertThrows(IllegalArgumentException.class, () -> OpenProgramTool.findFile(project, "app.exe"));
        assertSame(second, OpenProgramTool.findFile(project, "/version2/app.exe"));
        assertThrows(IllegalArgumentException.class, () -> OpenProgramTool.findFile(project, "app"));
    }

    @Test void exactNameWinsOverPartialAndMissingDoesNotFallBack() {
        DomainFile exact = file("/app");
        DomainFile longer = file("/app.exe");
        assertSame(exact, OpenProgramTool.findFile(List.of(longer, exact), "app"));
        assertNull(OpenProgramTool.findFile(List.of(exact, longer), "other"));
    }

    private DomainFile file(String path) {
        return (DomainFile) Proxy.newProxyInstance(DomainFile.class.getClassLoader(),
            new Class<?>[]{DomainFile.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getPathname" -> path;
                case "getName" -> path.substring(path.lastIndexOf('/') + 1);
                case "toString" -> path;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
