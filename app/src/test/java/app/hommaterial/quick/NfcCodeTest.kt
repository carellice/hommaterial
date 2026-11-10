package app.hommaterial.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NfcCodeTest {
    @Test
    fun codeIsShortAndStable() {
        val id = "SKILL_" + "eyJza2lsbElkIjoiYW16bjEuYXNrLnNraWxsLjEyMzQ1Njc4OTAiLCJzdGFnZSI6ImxpdmUifQ".repeat(3)
        assertEquals(10, nfcCode(id).length)
        assertEquals(nfcCode(id), nfcCode(id))
        assertNotEquals(nfcCode(id), nfcCode(id + "x"))
        // sha256("abc") starts with ba7816bf8f.
        assertEquals("ba7816bf8f", nfcCode("abc"))
    }
}
