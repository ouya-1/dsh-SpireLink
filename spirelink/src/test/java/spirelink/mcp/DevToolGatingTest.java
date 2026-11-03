package spirelink.mcp;

import org.junit.Test;
import spirelink.util.TestMode;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Dev tools must not leak into production: test_mode=false means they are not even listed. */
public class DevToolGatingTest {

    @Test
    public void productionHidesDevTools() {
        TestMode.setEnabled(false);
        assertFalse(ToolCatalog.devToolsEnabled());
        assertTrue(ToolCatalog.devToolNames().isEmpty());
    }

    @Test
    public void testModeExposesDevEvent() {
        TestMode.setEnabled(true);
        assertTrue(ToolCatalog.devToolsEnabled());
        assertTrue(ToolCatalog.devToolNames().contains(ToolCatalog.TOOL_DEV_EVENT));
        TestMode.setEnabled(false);
    }
}
