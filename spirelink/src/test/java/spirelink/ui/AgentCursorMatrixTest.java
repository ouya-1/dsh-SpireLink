package spirelink.ui;

import com.badlogic.gdx.math.Matrix4;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The fish restores whatever batch transform it found, so the "is this a leftover matrix?" test has
 * to be exact: a false positive would rotate the pointer, a false negative would leave someone
 * else's transform applied to the game for the rest of the frame.
 *
 * <p>Matrices are filled by hand instead of through rotation/translation helpers: the desktop
 * libgdx build implements those in native code, and a plain JUnit run has no natives loaded.</p>
 */
public class AgentCursorMatrixTest {

    @Test
    public void freshMatrixIsIdentity() {
        assertTrue(AgentCursor.isIdentity(new Matrix4()));
    }

    @Test
    public void nullIsTreatedAsIdentity() {
        assertTrue(AgentCursor.isIdentity(null));
    }

    @Test
    public void rotatedTextMatrixIsNotIdentity() {
        // What FontHelper leaves behind for a 30° rotated caption: off-diagonal terms, unit scale.
        Matrix4 m = new Matrix4();
        m.val[0] = 0.8660254f;
        m.val[1] = 0.5f;
        m.val[4] = -0.5f;
        m.val[5] = 0.8660254f;
        assertFalse(AgentCursor.isIdentity(m));
    }

    @Test
    public void translatedMatrixIsNotIdentity() {
        // A stray camera offset would shift the whole frame the same way it shifts the fish.
        Matrix4 m = new Matrix4();
        m.val[12] = 64f;
        m.val[13] = -9554f;
        assertFalse(AgentCursor.isIdentity(m));
    }

    @Test
    public void scaledMatrixIsNotIdentity() {
        Matrix4 m = new Matrix4();
        m.val[0] = 2f;
        m.val[5] = 2f;
        assertFalse(AgentCursor.isIdentity(m));
    }

    @Test
    public void barelyPerturbedMatrixIsStillNoise() {
        Matrix4 m = new Matrix4();
        m.val[12] = 1.0e-6f;
        assertTrue(AgentCursor.isIdentity(m));
    }
}
