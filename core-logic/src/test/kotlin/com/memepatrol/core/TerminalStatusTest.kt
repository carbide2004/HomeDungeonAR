package com.memepatrol.core

import org.junit.Assert.assertFalse
import org.junit.Test

class TerminalStatusTest {
    @Test
    fun testDefaultStatus() {
        val status = TerminalStatus()
        assertFalse(status.calibrated)
    }
}
