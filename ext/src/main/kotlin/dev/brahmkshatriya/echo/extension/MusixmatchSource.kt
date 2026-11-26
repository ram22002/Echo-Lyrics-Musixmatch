package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * Musixmatch through the desktop web API.
 *
 * IMPORTANT: without a valid `usertoken` the keyless path returns decoy lyrics (verified during
 * the audit: two unrelated songs produced the same placeholder text). The extension therefore
 * refuses to return anything without a token instead of shipping fake lyrics.
 */
internal class MusixmatchSource : LyricsSource {

    private val throttle = Throttle(500)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val token = token(settings)
        val query = track.query()
        if (query.title.isBlank() || query.artist.isBlank()) return emptyList()
        throttle.await()
        val url = "$BASE" + "track.search?q_track=" + Http.encode(track.title) +
            "&q_artist=" + Http.encode(query.artist) +
            "&page_size=10&s_track_rating=desc&app_id=" + APP_ID + "&format=json" +
            "&usertoken=" + Http.encode(token)
        val body = runCatching { Http.get(url, HEADERS) }.getOrNull() ?: return emptyList()
        val tracks = runCatching {
            Http.json(body).obj()?.obj("message")?.obj("body")?.arr("track_list").objects()
        }.getOrNull() ?: return emptyList()

        return tracks.mapNotNull { entry ->
            val item = entry.obj("track") ?: return@mapNotNull null
            val id = item.long("track_id") ?: return@mapNotNull null
            val title = item.str("track_name") ?: return@mapNotNull null
            val artist = item.str("artist_name").orEmpty()
            val album = item.str("album_name").orEmpty()
            val duration = item.long("track_length") ?: 0L
            Triple(id, Candidate(title, artist, album, duration), item)
        }.filter { Match.accept(query, it.second) }
            .sortedByDescending { Match.score(query, it.second) }
            .take(6)
            .map { (id, candidate, _) ->
                Lyrics(
                    id = "musixmatch:$id",
                    title = candidate.title,
                    subtitle = candidate.artist + " • Musixmatch",
                    lyrics = null,
                    extras = mapOf("id" to id.toString()),
                )
            }
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val token = token(settings)
        val id = lyrics.extras["id"] ?: throw LyricsException("Musixmatch candidate is missing its track id")
        throttle.await()
        val url = "$BASE" + "macro.subtitles.get?format=json&namespace=lyrics_synched" +
            "&track_id=" + Http.encode(id) + "&f_subtitle_length=0" +
            "&app_id=" + APP_ID + "&usertoken=" + Http.encode(token)
        val root = Http.json(Http.get(url, HEADERS)).obj()
            ?: throw LyricsException("Empty Musixmatch response")
        val macro = root.obj("message")?.obj("body")?.obj("macro_calls")
            ?: throw LyricsException("Unexpected Musixmatch payload")

        val subtitle = macro.obj("track.subtitles.get")
            ?.obj("message")?.obj("body")?.arr("subtitle_list").objects()
            ?.firstOrNull()?.obj("subtitle")?.str("subtitle_body")
        if (!subtitle.isNullOrBlank()) {
            val lines = Lrc.parse(subtitle)
            if (lines.isNotEmpty()) return lyrics.copy(lyrics = timed(lines, false))
        }

        val plainBody = macro.obj("track.lyrics.get")
            ?.obj("message")?.obj("body")?.obj("lyrics")?.str("lyrics_body")
        if (!plainBody.isNullOrBlank()) {
            val lines = Lrc.parsePlain(plainBody)
            if (lines.isNotEmpty()) return lyrics.copy(lyrics = plain(lines.joinToString("\n")))
        }
        throw LyricsException("Musixmatch has no lyrics for this track")
    }

    private fun token(settings: Settings?): String {
        val value = settings?.getString(KEY_TOKEN)?.trim().orEmpty()
        if (value.isEmpty()) {
            throw LyricsException(
                "Musixmatch needs a user token. The keyless endpoint returns decoy lyrics, " +
                    "so this extension will not return any. Enter a token in the extension settings.",
            )
        }
        return value
    }

    companion object {
        const val KEY_TOKEN = "usertoken"
        private const val APP_ID = "web-desktop-app-v1.0"
        private const val BASE = "https://apic-desktop.musixmatch.com/ws/1.1/"
        private val HEADERS = mapOf("Cookie" to "x-mxm-token-guid=")

        fun settings(): List<Setting> = listOf(
            SettingTextInput(
                title = "Musixmatch user token",
                key = KEY_TOKEN,
                summary = "Required. Without it Musixmatch serves placeholder lyrics.",
                defaultValue = "",
            ),
        )
    }
}
