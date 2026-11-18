package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GovInfoFieldsTest {
    private val html = """
        <html><body>
        <!-- field-start:head --><h3>§2254. State custody; remedies in Federal courts</h3><!-- field-end:head -->
        <!-- field-start:statute -->
        <p>(a) The Supreme Court, a Justice thereof, a circuit judge, or a district court shall entertain an application.</p>
        <p>(b)(1) An application ... shall not be granted unless</p>
        <!-- field-end:statute -->
        <!-- field-start:notes --><p>Editorial note</p><!-- field-end:notes -->
        </body></html>
    """.trimIndent()

    @Test fun `extracts only the requested field blocks in order`() {
        val out = govInfoFields(html, setOf("head", "statute"))
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals("§2254. State custody; remedies in Federal courts", lines.first())
        assertTrue(out.contains("(a) The Supreme Court"))
        assertTrue(out.contains("(b)(1) An application"))
        assertTrue(!out.contains("Editorial note"))
    }

    @Test fun `missing fields yield empty text`() {
        assertEquals("", govInfoFields("<p>nothing</p>", setOf("statute")))
    }
}
