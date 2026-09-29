package app.inwardjourney.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteRangeTest {

    @Test
    fun testValidExplicitRange() {
        val result = parseByteRange("bytes=0-499", 1000L)
        assertTrue(result is ByteRangeResult.Valid)
        val valid = result as ByteRangeResult.Valid
        assertEquals(0L, valid.start)
        assertEquals(499L, valid.end)
        assertEquals(500L, valid.length)
    }

    @Test
    fun testValidOpenEndedRange() {
        val result = parseByteRange("bytes=500-", 1000L)
        assertTrue(result is ByteRangeResult.Valid)
        val valid = result as ByteRangeResult.Valid
        assertEquals(500L, valid.start)
        assertEquals(999L, valid.end)
        assertEquals(500L, valid.length)
    }

    @Test
    fun testValidSuffixRange() {
        val result = parseByteRange("bytes=-500", 1000L)
        assertTrue(result is ByteRangeResult.Valid)
        val valid = result as ByteRangeResult.Valid
        assertEquals(0L, valid.start)
        assertEquals(500L, valid.end)
        assertEquals(501L, valid.length)
    }

    @Test
    fun testValidSingleByteRange() {
        val result = parseByteRange("bytes=0-0", 1000L)
        assertTrue(result is ByteRangeResult.Valid)
        val valid = result as ByteRangeResult.Valid
        assertEquals(0L, valid.start)
        assertEquals(0L, valid.end)
        assertEquals(1L, valid.length)
    }

    @Test
    fun testValidFullFileRange() {
        val result = parseByteRange("bytes=0-999", 1000L)
        assertTrue(result is ByteRangeResult.Valid)
        val valid = result as ByteRangeResult.Valid
        assertEquals(0L, valid.start)
        assertEquals(999L, valid.end)
        assertEquals(1000L, valid.length)
    }

    @Test
    fun testEndClampedToFileSize() {
        val result = parseByteRange("bytes=500-2000", 1000L)
        assertTrue(result is ByteRangeResult.Valid)
        val valid = result as ByteRangeResult.Valid
        assertEquals(500L, valid.start)
        assertEquals(999L, valid.end)
        assertEquals(500L, valid.length)
    }

    @Test
    fun testUnsatisfiableStartPastFileSize() {
        val result = parseByteRange("bytes=1000-1500", 1000L)
        assertEquals(ByteRangeResult.Unsatisfiable, result)
    }

    @Test
    fun testUnsatisfiableStartEqualsFileSize() {
        val result = parseByteRange("bytes=1000-", 1000L)
        assertEquals(ByteRangeResult.Unsatisfiable, result)
    }

    @Test
    fun testUnsatisfiableStartGreaterThanEnd() {
        val result = parseByteRange("bytes=500-200", 1000L)
        assertEquals(ByteRangeResult.Unsatisfiable, result)
    }

    @Test
    fun testInvalidHeaderFormat() {
        assertNull(parseByteRange("items=0-500", 1000L))
        assertNull(parseByteRange("bytes=abc-def", 1000L))
        assertNull(parseByteRange("0-100", 1000L))
        assertNull(parseByteRange("", 1000L))
        assertNull(parseByteRange("bytes=", 1000L))
    }

    @Test
    fun testZeroOrNegativeFileSize() {
        assertNull(parseByteRange("bytes=0-499", 0L))
        assertNull(parseByteRange("bytes=0-499", -10L))
    }
}
