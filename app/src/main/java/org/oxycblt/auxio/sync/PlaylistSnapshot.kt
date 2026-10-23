/*
 * Copyright (c) 2026 Auxio Project
 * PlaylistSnapshot.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * The shared playlist document synchronized between MusicBox (PC) and this device.
 *
 * The document is a single HTML file that acts as the source of truth for all playlist and
 * song information: playlists are rendered as human-readable tables, while the authoritative
 * machine-readable data is embedded in a `application/json` script block. Whichever side last
 * modified the document owns the highest [revision], and the other side imports it wholesale
 * (last-writer-wins at the document level).
 */
data class PlaylistSnapshot(
    /** Last-modified wall clock in epoch milliseconds; the merge key between devices. */
    val revision: Long,
    /** Human-readable name of the device that produced this snapshot. */
    val updatedBy: String,
    val playlists: List<SnapshotPlaylist>,
) {
    val trackCount: Int
        get() = playlists.sumOf { it.tracks.size }

    fun toJson(): JSONObject =
        JSONObject()
            .put("protocol", PROTOCOL)
            .put("revision", revision)
            .put("updatedBy", updatedBy)
            .put(
                "playlists",
                JSONArray().apply {
                    for (playlist in playlists) {
                        put(playlist.toJson())
                    }
                })

    /** Render the snapshot as a self-contained, human-readable HTML document. */
    fun encode(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>MusicBox 共享歌单</title>\n")
        sb.append("<style>\n")
        sb.append("body{font-family:system-ui,sans-serif;max-width:860px;margin:24px auto;padding:0 16px;color:#1f2937}\n")
        sb.append("h1{font-size:1.5rem}h2{font-size:1.15rem;margin-top:32px}\n")
        sb.append(".meta{color:#6b7280;font-size:.9rem}\n")
        sb.append("table{border-collapse:collapse;width:100%;font-size:.9rem}\n")
        sb.append("th,td{border-bottom:1px solid #e5e7eb;padding:6px 8px;text-align:left}\n")
        sb.append("th{color:#6b7280;font-weight:500}\n")
        sb.append("</style>\n</head>\n<body>\n")
        sb.append("<h1>MusicBox 共享歌单</h1>\n")
        sb.append("<p class=\"meta\">更新时间：")
            .append(Format.timestamp(revision))
            .append(" · 更新来源：")
            .append(Format.escape(updatedBy))
            .append(" · 共 ")
            .append(playlists.size)
            .append(" 个歌单 / ")
            .append(trackCount)
            .append(" 首歌曲</p>\n")
        for (playlist in playlists) {
            sb.append("<section class=\"playlist\">\n<h2>")
                .append(Format.escape(playlist.name))
                .append("</h2>\n")
            sb.append("<table>\n<thead><tr><th>#</th><th>歌曲</th><th>艺术家</th><th>专辑</th><th>时长</th></tr></thead>\n<tbody>\n")
            for ((index, track) in playlist.tracks.withIndex()) {
                sb.append("<tr><td>")
                    .append(index + 1)
                    .append("</td><td>")
                    .append(Format.escape(track.title))
                    .append("</td><td>")
                    .append(Format.escape(track.artist))
                    .append("</td><td>")
                    .append(Format.escape(track.album))
                    .append("</td><td>")
                    .append(Format.duration(track.durationSec))
                    .append("</td></tr>\n")
            }
            sb.append("</tbody>\n</table>\n</section>\n")
        }
        sb.append("<script type=\"application/json\" id=\"")
            .append(DATA_ELEMENT_ID)
            .append("\">\n")
            .append(toJson().toString())
            .append("\n</script>\n")
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    companion object {
        const val PROTOCOL = 2
        const val DATA_ELEMENT_ID = "musicbox-sync-data"
        private const val MAX_HTML_BYTES = 64 * 1024 * 1024

        /**
         * Parse a snapshot from its HTML document. Returns null if the document does not
         * contain a valid embedded data block.
         */
        fun decode(html: String): PlaylistSnapshot? {
            if (html.length > MAX_HTML_BYTES) return null
            val json = extractDataJson(html) ?: return null
            return try {
                fromJson(JSONObject(json))
            } catch (e: Exception) {
                null
            }
        }

        /** Extract the raw embedded JSON string, or null if the marker block is absent. */
        fun extractDataJson(html: String): String? {
            val startMarker = "<script type=\"application/json\" id=\"$DATA_ELEMENT_ID\">"
            val start = html.indexOf(startMarker)
            if (start < 0) return null
            val jsonStart = start + startMarker.length
            val end = html.indexOf("</script>", jsonStart)
            if (end < 0) return null
            return html.substring(jsonStart, end).trim()
        }

        fun fromJson(obj: JSONObject): PlaylistSnapshot? {
            if (obj.optInt("protocol") != PROTOCOL) return null
            val revision = obj.optLong("revision", 0)
            val updatedBy = obj.optString("updatedBy", "unknown")
            val playlists = ArrayList<SnapshotPlaylist>()
            val arr = obj.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val playlistObj = arr.optJSONObject(i) ?: continue
                val playlist = SnapshotPlaylist.fromJson(playlistObj) ?: continue
                playlists.add(playlist)
            }
            return PlaylistSnapshot(revision, updatedBy, playlists)
        }
    }
}

/** One playlist within a [PlaylistSnapshot]. */
data class SnapshotPlaylist(
    /** Stable cross-device identity of the playlist. */
    val playlistId: String,
    val name: String,
    val updatedAt: Long,
    val tracks: List<SnapshotTrack>,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("playlistId", playlistId)
            .put("name", name)
            .put("updatedAt", updatedAt)
            .put(
                "tracks",
                JSONArray().apply {
                    for (track in tracks) {
                        put(track.toJson())
                    }
                })

    companion object {
        fun fromJson(obj: JSONObject): SnapshotPlaylist? {
            val playlistId = obj.optString("playlistId").takeIf { it.isNotEmpty() } ?: return null
            val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val updatedAt = obj.optLong("updatedAt", 0)
            val tracks = ArrayList<SnapshotTrack>()
            val arr = obj.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val trackObj = arr.optJSONObject(i) ?: continue
                tracks.add(SnapshotTrack.fromJson(trackObj))
            }
            return SnapshotPlaylist(playlistId, name, updatedAt, tracks)
        }
    }
}

/** One song entry within a [SnapshotPlaylist]. */
data class SnapshotTrack(
    /**
     * Cross-device identity of the audio file. For songs pushed from the PC this is the PC's
     * stable file hash; for songs originating on this device it is `local:<song uid>`.
     */
    val fileId: String,
    val title: String,
    val artist: String,
    val album: String,
    /** Duration in seconds. */
    val durationSec: Long,
    /** File size in bytes. */
    val fileSize: Long,
    val fileName: String,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("fileId", fileId)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("duration", durationSec)
            .put("fileSize", fileSize)
            .put("fileName", fileName)

    companion object {
        fun fromJson(obj: JSONObject): SnapshotTrack =
            SnapshotTrack(
                fileId = obj.optString("fileId"),
                title = obj.optString("title"),
                artist = obj.optString("artist"),
                album = obj.optString("album"),
                durationSec = obj.optLong("duration", 0),
                fileSize = obj.optLong("fileSize", 0),
                fileName = obj.optString("fileName"),
            )
    }
}

/** HTML escaping/formatting helpers used when rendering the snapshot document. */
private object Format {
    fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    fun timestamp(epochMs: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return format.format(java.util.Date(epochMs))
    }

    fun duration(seconds: Long): String {
        if (seconds <= 0) return "-"
        val min = seconds / 60
        val sec = seconds % 60
        return "$min:%02d".format(sec)
    }
}
