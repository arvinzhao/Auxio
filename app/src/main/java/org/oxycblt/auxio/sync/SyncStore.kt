/*
 * Copyright (c) 2026 Auxio Project
 * SyncStore.kt is part of Auxio.
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

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONObject

/**
 * Persistent storage backing the MusicBox playlist sync feature.
 *
 * Stores this device's identity, paired device tokens, received file locations and the last
 * known names of synchronized playlists. Everything is serialized into a single
 * [SharedPreferences] using [JSONObject], as the data volume is tiny.
 */
class SyncStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** A unique ID for this device that survives restarts. */
    val deviceId: String
        get() =
            prefs.getString(KEY_DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString(KEY_DEVICE_ID, it).apply()
                }

    /** A human-readable name for this device, used for pairing responses and mDNS. */
    val deviceName: String
        get() = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Auxio"

    /** The token issued to the given paired device, or null if never paired. */
    fun getToken(pcDeviceId: String): String? = tokens().optString(pcDeviceId, null)

    /** Persist the [token] issued to the device with ID [pcDeviceId]. */
    fun putToken(pcDeviceId: String, token: String) {
        val obj = tokens()
        obj.put(pcDeviceId, token)
        prefs.edit().putString(KEY_TOKENS, obj.toString()).apply()
    }

    /** All tokens currently issued, mapped from PC device ID to token. */
    fun allTokens(): Map<String, String> {
        val obj = tokens()
        val out = mutableMapOf<String, String>()
        for (key in obj.keys()) {
            out[key] = obj.optString(key)
        }
        return out
    }

    /** The [FileRecord] stored for the given [fileId], or null if it was never received. */
    fun findFileRecord(fileId: String): FileRecord? =
        files().optJSONObject(fileId)?.let {
            FileRecord(
                fileId = fileId,
                uri = it.optString(KEY_URI),
                relPath = it.optString(KEY_REL_PATH),
                name = it.optString(KEY_NAME),
                size = it.optLong(KEY_SIZE),
            )
        }

    /** Persist a received [record]. */
    fun putFileRecord(record: FileRecord) {
        val obj = files()
        obj.put(
            record.fileId,
            JSONObject()
                .put(KEY_URI, record.uri)
                .put(KEY_REL_PATH, record.relPath)
                .put(KEY_NAME, record.name)
                .put(KEY_SIZE, record.size),
        )
        prefs.edit().putString(KEY_FILES, obj.toString()).apply()
    }

    /** The last known playlist name for the given [playlistId], or null. */
    fun findPlaylistName(playlistId: String): String? = playlists().optString(playlistId, null)

    /** Remember [name] as the last synced playlist name for [playlistId]. */
    fun putPlaylistName(playlistId: String, name: String) {
        val obj = playlists()
        obj.put(playlistId, name)
        prefs.edit().putString(KEY_PLAYLISTS, obj.toString()).apply()
    }

    private fun tokens() = JSONObject(prefs.getString(KEY_TOKENS, null) ?: "{}")

    private fun files() = JSONObject(prefs.getString(KEY_FILES, null) ?: "{}")

    private fun playlists() = JSONObject(prefs.getString(KEY_PLAYLISTS, null) ?: "{}")

    /** A file received from MusicBox and its location on this device. */
    data class FileRecord(
        val fileId: String,
        /** The content URI of the file in MediaStore (or a file:// URI below Android Q). */
        val uri: String,
        /** The path of the file relative to the storage volume root, ex. Music/MusicBox/x.mp3. */
        val relPath: String,
        val name: String,
        val size: Long,
    )

    private companion object {
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_TOKENS = "tokens"
        const val KEY_FILES = "files"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_URI = "uri"
        const val KEY_REL_PATH = "relPath"
        const val KEY_NAME = "name"
        const val KEY_SIZE = "size"
    }
}
