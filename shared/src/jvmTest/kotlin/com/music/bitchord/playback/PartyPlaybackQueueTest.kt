package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class PartyPlaybackQueueTest {
    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT) =
        Song(id, id, "Artist", null, queueTier = tier)

    @Test
    fun `party selection uses only selected and following collection tracks`() {
        val source = QueueSource("Summer Mix", PlaybackSourceType.BROWSE, "playlist-1")
        val selected = song("selected")
        val following = listOf(song("same-id"), song("same-id"))

        val queue = QueueTimeline.buildPartyPlaybackQueue(
            tappedSong = selected,
            source = source,
            followingSongs = following,
        )

        assertEquals(listOf("selected", "same-id", "same-id"), queue.map(Song::videoId))
        assertEquals(QueueTier.CONTEXT, queue[0].queueTier)
        assertEquals(listOf(QueueTier.USER_QUEUE, QueueTier.USER_QUEUE), queue.drop(1).map(Song::queueTier))
        assertEquals(source.title, queue[0].playbackSource)
        assertEquals(source.type, queue[0].playbackSourceType)
        assertEquals(source.id, queue[0].playbackSourceId)
        queue.drop(1).forEach { followingSong ->
            assertEquals(source.title, followingSong.playbackSource)
            assertEquals(source.type, followingSong.playbackSourceType)
            assertEquals(source.id, followingSong.playbackSourceId)
        }
        assertNotNull(queue[1].queueEntryId)
        assertNotNull(queue[2].queueEntryId)
        assertNotEquals(queue[1].queueEntryId, queue[2].queueEntryId)
    }

    @Test
    fun `party one off starts fresh while normal one off preserves manual queue`() {
        val selected = song("selected")
        val existing = song("manual", QueueTier.USER_QUEUE)
        val source = QueueSource("Home", PlaybackSourceType.HOME)

        val partyQueue = QueueTimeline.buildPartyPlaybackQueue(selected, source)
        assertEquals(listOf("selected"), partyQueue.map(Song::videoId))

        val oneOff = QueueTimeline.buildOneOffQueue(
            currentTimeline = listOf(song("playing"), existing),
            currentIndex = 0,
            tappedSong = selected,
            source = source,
        )
        assertEquals(listOf("selected", "manual"), oneOff.map(Song::videoId))
    }

    @Test
    fun `local content and file entries are skipped from following collection`() {
        val queue = QueueTimeline.buildPartyPlaybackQueue(
            tappedSong = song("selected"),
            source = QueueSource("Album", PlaybackSourceType.BROWSE, "album-1"),
            followingSongs = listOf(
                song("content://media/1"),
                song("file:///music/local.mp3"),
                song("remote"),
            ),
        )

        assertEquals(listOf("selected", "remote"), queue.map(Song::videoId))
    }
}
