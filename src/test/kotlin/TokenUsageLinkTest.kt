package honnyaku

import org.junit.Assert.*
import org.junit.Test
import javax.swing.SwingUtilities

class TokenUsageLinkTest {
    @Test
    fun `usage stays visible before data arrives and after it is cleared`() {
        SwingUtilities.invokeAndWait {
            val link = TokenUsageLink {}
            assertTrue(link.isVisible)
            assertFalse(link.isEnabled)
            assertEquals("Thread tokens: unavailable", link.text)

            link.isEnabled = true
            assertTrue(link.isVisible)
            link.isEnabled = false
            assertTrue(link.isVisible)
        }
    }
}
