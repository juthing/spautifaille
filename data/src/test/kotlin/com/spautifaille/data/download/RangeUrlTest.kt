package com.spautifaille.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RangeUrlTest {

    @Test
    fun appendsRangeAndRequestNumberToExistingQuery() {
        val url = "https://rr1.googlevideo.com/videoplayback?expire=1&sig=a%3D%3D&c=VISIONOS"
        assertEquals("$url&range=0-1048575&rn=0", RangeUrl.build(url, 0, 1_048_575, 0))
    }

    @Test
    fun addsQuestionMarkWhenThereIsNoQuery() {
        assertEquals("https://h/audio?range=10-19&rn=3", RangeUrl.build("https://h/audio", 10, 19, 3))
    }

    @Test
    fun replacesExistingRangeAndRnKeepingOtherParametersUntouched() {
        val url = "https://h/v?a=1&range=0-9&sig=x%2By%3D&rn=7&c=VISIONOS"
        assertEquals("https://h/v?a=1&sig=x%2By%3D&c=VISIONOS&range=100-199&rn=8", RangeUrl.build(url, 100, 199, 8))
    }

    @Test
    fun supportsSingleByteRanges() {
        assertEquals("https://h/v?range=5-5&rn=0", RangeUrl.build("https://h/v", 5, 5, 0))
    }

    @Test
    fun rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException::class.java) { RangeUrl.build("https://h/v", -1, 5, 0) }
        assertThrows(IllegalArgumentException::class.java) { RangeUrl.build("https://h/v", 10, 9, 0) }
    }

    @Test
    fun readsContentLengthFromClenParameter() {
        assertEquals(4_242_424L, RangeUrl.contentLengthOf("https://h/videoplayback?itag=251&clen=4242424&dur=200"))
        assertEquals(12L, RangeUrl.contentLengthOf("https://h/videoplayback?clen=12"))
        assertNull(RangeUrl.contentLengthOf("https://h/videoplayback?itag=251"))
        assertNull(RangeUrl.contentLengthOf("https://h/videoplayback?xclen=12"))
    }

    @Test
    fun extensionFollowsTheMimeType() {
        assertEquals("webm", DownloadFiles.extensionFor("audio/webm"))
        assertEquals("m4a", DownloadFiles.extensionFor("audio/mp4"))
        assertEquals("webm", DownloadFiles.extensionFor("audio/webm; codecs=\"opus\""))
        assertEquals("audio", DownloadFiles.extensionFor(null))
    }
}
