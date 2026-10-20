package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.listentogether.PartyPlayback
import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.toMediaItem
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow

class PartyAutomaticTransitionTest {

    @Test
    fun `automatic transition waits silently and resumes if party never answers`() = runTest {
        withPartyState(liveParty()) {
            val fake = FakePlayer("next")
            val sync = PartySync(this) { fake.player }
            try {
                sync.onAutomaticTransition()

                assertFalse(fake.playWhenReady)
                assertTrue(ListenTogether.awaitingStart.value)

                advanceTimeBy(200L)
                runCurrent()
                assertFalse(fake.playWhenReady)
                assertTrue(ListenTogether.awaitingStart.value)

                advanceTimeBy(2_000L)
                runCurrent()

                assertTrue(fake.playWhenReady)
                assertEquals(1, fake.playCalls)
                assertFalse(ListenTogether.awaitingStart.value)
            } finally {
                sync.stop()
                ListenTogether.setAwaitingStart(false)
            }
        }
    }

    @Test
    fun `automatic transition leaves playback alone when party cannot schedule it`() = runTest {
        val base = liveParty()
        val cases = listOf(
            "no party" to base.copy(code = null),
            "offline" to base.copy(connection = ListenTogether.Connection.OFFLINE),
            "unsynced clock" to base.copy(clockSynced = false),
            "locked listener" to base.copy(
                hostOnlyControl = true,
                you = PartyMember(memberId = "me", isHost = false),
            ),
            "no party track" to base.copy(playback = base.playback.copy(track = null)),
            "party already on this track" to base.copy(
                playback = base.playback.copy(track = PartyTrack(videoId = "next")),
            ),
        )

        for ((label, state) in cases) {
            withPartyState(state) {
                val fake = FakePlayer("next")
                val sync = PartySync(this) { fake.player }
                try {
                    sync.onAutomaticTransition()
                    assertTrue(label, fake.playWhenReady)
                    assertEquals(label, 0, fake.pauseCalls)
                    assertFalse(label, ListenTogether.awaitingStart.value)
                } finally {
                    sync.stop()
                    ListenTogether.setAwaitingStart(false)
                }
            }
        }
    }

    @Test
    fun `automatic transition does not pause a device file`() = runTest {
        withPartyState(liveParty()) {
            val fileItem = MediaItem.Builder().setMediaId("content://local/song").build()
            val fake = FakePlayer("ignored", item = fileItem)
            val sync = PartySync(this) { fake.player }
            try {
                sync.onAutomaticTransition()
                assertTrue(fake.playWhenReady)
                assertEquals(0, fake.pauseCalls)
                assertFalse(ListenTogether.awaitingStart.value)
            } finally {
                sync.stop()
                ListenTogether.setAwaitingStart(false)
            }
        }
    }

    private suspend fun withPartyState(state: ListenTogether.State, block: suspend () -> Unit) {
        val stateField = ListenTogether::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(ListenTogether) as MutableStateFlow<ListenTogether.State>
        val previous = stateFlow.value
        val previousAwaiting = ListenTogether.awaitingStart.value
        try {
            ListenTogether.setAwaitingStart(false)
            stateFlow.value = state
            block()
        } finally {
            stateFlow.value = previous
            ListenTogether.setAwaitingStart(previousAwaiting)
        }
    }

    private fun liveParty() = ListenTogether.State(
        code = "ABC123",
        connection = ListenTogether.Connection.LIVE,
        clockSynced = true,
        playback = PartyPlayback(
            track = PartyTrack(videoId = "old"),
            isPlaying = true,
        ),
    )

    private class FakePlayer(
        id: String,
        item: MediaItem = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null).toMediaItem(),
    ) {
        var playWhenReady = true
        var pauseCalls = 0
        var playCalls = 0
        private val currentItem = item

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlayWhenReady" -> playWhenReady
                "getCurrentMediaItem" -> currentItem
                "getCurrentMediaItemIndex" -> 0
                "getMediaItemCount" -> 1
                "getMediaItemAt" -> currentItem
                "getCurrentPosition" -> 0L
                "getDuration" -> 180_000L
                "getPlaybackState" -> Player.STATE_READY
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "pause" -> {
                    pauseCalls++
                    playWhenReady = false
                    null
                }
                "play" -> {
                    playCalls++
                    playWhenReady = true
                    null
                }
                else -> null
            }
        } as Player
    }
}
