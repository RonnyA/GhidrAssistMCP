package ghidrassistmcp.project;

import java.util.Map;

/** Application project lifecycle independent of MCP transport and CodeBrowser views. */
public interface ProjectController {
    Map<String, Object> createProject(String location, String name) throws Exception;
    Map<String, Object> openProject(String location, String name, boolean restoreState) throws Exception;
    Map<String, Object> saveProject() throws Exception;
    Map<String, Object> closeProject(boolean save, boolean discardChanges) throws Exception;
}
