package spirelink.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** fieldOrNull must survive the subclass-field-on-superclass mistake that killed the game loop. */
public class FieldKitTest {

    private static class Parent {
        private String inherited = "p";
    }

    private static class Child extends Parent {
        private String own = "c";
    }

    @Test
    public void readsSubclassField() {
        assertEquals("c", FieldKit.fieldOrNull(new Child(), "own"));
    }

    @Test
    public void readsInheritedField() {
        assertEquals("p", FieldKit.fieldOrNull(new Child(), "inherited"));
    }

    @Test
    public void missingFieldIsNullNotThrow() {
        assertNull(FieldKit.fieldOrNull(new Child(), "nope"));
        assertNull(FieldKit.fieldOrNull(null, "own"));
    }
}
