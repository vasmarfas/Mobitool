package com.vasmarfas.card.tools.media

import com.vasmarfas.card.core.MediaException
import com.vasmarfas.card.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskErrorTest {
    @Test
    fun failuresAreShownInTheInterfaceLanguage() = runBlocking {
        assertEquals(getString(Res.string.no_audio_track), errorText(MediaException("no audio stream", Res.string.no_audio_track)))
        assertEquals(getString(Res.string.media_failed), errorText(MediaException("moov atom not found")))
        assertEquals(getString(Res.string.out_of_memory), errorText(OutOfMemoryError("Java heap space")))
        assertEquals("a.png: not readable", errorText(IllegalStateException("a.png: not readable")))
    }
}
