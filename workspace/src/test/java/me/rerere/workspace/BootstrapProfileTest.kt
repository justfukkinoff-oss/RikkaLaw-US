package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapProfileTest {
    @Test
    fun scriptBatchesAptAndEmitsSteps() {
        val s = BootstrapProfile.script(BootstrapProfile.entries)
        assertTrue(s.startsWith("#!/bin/sh"))
        // one batched install plus a per-package fallback, not dozens of separate calls
        assertTrue(s.contains("install --no-install-recommends \$TODO"))
        assertTrue(s.contains("## STEP \$1/\$N"))
        assertTrue(s.contains(BootstrapProfile.DONE_MARKER))
        assertTrue(s.contains("tee -a"))
    }

    @Test
    fun parseStepReadsLastMarker() {
        val out = "x\n## STEP 1/4 Updating\nfoo\n## STEP 3/4 Installing Python packages: eyecite\nbar"
        assertEquals(Triple(3, 4, "Installing Python packages: eyecite"), BootstrapProfile.parseStep(out))
        assertNull(BootstrapProfile.parseStep("no markers"))
    }
}
