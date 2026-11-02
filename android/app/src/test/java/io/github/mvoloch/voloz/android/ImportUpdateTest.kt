package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.dictation.AudioImport
import io.github.mvoloch.voloz.android.net.Provider
import io.github.mvoloch.voloz.android.net.ProviderPresets
import io.github.mvoloch.voloz.android.net.UpdateCheck
import io.github.mvoloch.voloz.android.ui.screens.moveDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportUpdateTest {
    @Test
    fun importedFilesGetAnUploadNameTheProvidersRead() {
        assertEquals("audio.ogg" to "audio/ogg", AudioImport.uploadName("audio/ogg", "PTT-20261030-WA0001.opus"))
        assertEquals("audio.ogg" to "audio/ogg", AudioImport.uploadName(null, "PTT-20261030-WA0001.opus"))
        assertEquals("audio.m4a" to "audio/mp4", AudioImport.uploadName("audio/mp4", null))
        assertEquals("audio.mp3" to "audio/mpeg", AudioImport.uploadName("audio/mpeg", "x.mp3"))
        assertEquals("audio.wav" to "audio/wav", AudioImport.uploadName("audio/x-wav", null))
        assertNull(AudioImport.uploadName("audio/amr", "recording.amr"))
        assertNull(AudioImport.uploadName(null, null))
    }

    @Test
    fun versionsCompareByNumbers() {
        assertTrue(UpdateCheck.isNewer("0.2.10", "0.2.9"))
        assertTrue(UpdateCheck.isNewer("0.3.0", "0.2.2"))
        assertFalse(UpdateCheck.isNewer("0.2.2", "0.2.2"))
        assertFalse(UpdateCheck.isNewer("0.2.1", "0.2.2"))
        assertTrue(UpdateCheck.isNewer("0.2.0", "0.1.0-prototype"))
    }

    @Test
    fun theNewestAndroidReleaseIsFound() {
        val listing = """[
            {"tag_name":"lite-v0.8.16","html_url":"https://x/lite","draft":false},
            {"tag_name":"android-v0.3.0","html_url":"https://x/a030","draft":false},
            {"tag_name":"android-v0.4.0","html_url":"https://x/a040","draft":true},
            {"tag_name":"android-v0.2.2","html_url":"https://x/a022","draft":false}
        ]"""
        assertEquals(UpdateCheck.Release("0.3.0", "https://x/a030"), UpdateCheck.newest(listing, "0.2.2"))
        assertNull(UpdateCheck.newest(listing, "0.3.0"))
    }

    @Test
    fun movingDownUsesTheProvider() {
        val a = Provider(ProviderPresets.GROQ, "a")
        val b = Provider(ProviderPresets.OPENAI, "b")
        assertEquals(listOf(b, a), moveDown(listOf(a, b), a))
        assertEquals(listOf(a, b), moveDown(listOf(a, b), b))
    }
}
