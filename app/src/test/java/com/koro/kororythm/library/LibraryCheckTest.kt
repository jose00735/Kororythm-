package com.koro.kororythm.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.FileNotFoundException

/** The refresh check: a file that opens is never "missing" (regression test for 1.3.0). */
class LibraryCheckTest {

    @Test fun openFileIsPresent() {
        var closed = false
        assertNull(LibraryCheck.probe { Closeable { closed = true } })
        assertTrue("handle is closed again", closed)
    }

    @Test fun reasonsForMissingFiles() {
        assertEquals("not found", LibraryCheck.probe { null })
        assertEquals("file not found", LibraryCheck.probe { throw FileNotFoundException("gone") })
        assertEquals("no permission any more", LibraryCheck.probe { throw SecurityException("revoked") })
    }

    @Test fun mostOfTheLibraryMissingIsSuspicious() {
        assertTrue(LibraryCheck.suspicious(112, 112))
        assertTrue(LibraryCheck.suspicious(60, 112))
        assertFalse(LibraryCheck.suspicious(3, 112))
        assertFalse(LibraryCheck.suspicious(4, 6)) // tiny libraries: just report
    }
}
