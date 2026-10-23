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
import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * Persistent storage backing the MusicBox playlist sync feature.
 *
 * Stores this device's identity, paired device tokens, received file locations, the shared
 * playlist snapshot document and the mapping between cross-device playlist IDs and local
 * playlist UIDs. Small values are serialized into a single [SharedPreferences] using
 * [JSONObject]; the (potentially large) HTML snapshot documents live in the app's external
 * files directory.
 */
class SyncStore(context: Context) {
    private val context: Context = context.applicationContext
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

    // --- Playlist identity mapping ---

    /**
     * The cross-device playlist ID bound to the given local playlist [uid] string, creating
     * and persisting a fresh binding if none exists yet.
     */
    fun playlistIdForUid(uid: String): String {
        val obj = JSONObject(prefs.getString(KEY_PLAYLIST_IDS, null) ?: "{}")
        return obj.optString(uid).takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also {
                obj.put(uid, it)
                prefs.edit().putString(KEY_PLAYLIST_IDS, obj.toString()).apply()
            }
    }

    /** The local playlist UID string bound to the given [playlistId], or null. */
    fun uidForPlaylistId(playlistId: String): String? {
        val obj = JSONObject(prefs.getString(KEY_PLAYLIST_BINDINGS, null) ?: "{}")
        return obj.optString(playlistId).takeIf { it.isNotEmpty() }
    }

    /** Bind a cross-device [playlistId] to a local playlist [uid] string. */
    fun bindPlaylist(playlistId: String, uid: String) {
        val bindings = JSONObject(prefs.getString(KEY_PLAYLIST_BINDINGS, null) ?: "{}")
        bindings.put(playlistId, uid)
        prefs.edit().putString(KEY_PLAYLIST_BINDINGS, bindings.toString()).apply()
        // Maintain the reverse map as well.
        val ids = JSONObject(prefs.getString(KEY_PLAYLIST_IDS, null) ?: "{}")
        if (ids.optString(uid) != playlistId) {
            ids.put(uid, playlistId)
            prefs.edit().putString(KEY_PLAYLIST_IDS, ids.toString()).apply()
        }
    }

    /** Drop the binding for the given [playlistId] (e.g. the playlist was deleted). */
    fun unbindPlaylistId(playlistId: String) {
        val bindings = JSONObject(prefs.getString(KEY_PLAYLIST_BINDINGS, null) ?: "{}")
        val uid = bindings.optString(playlistId).takeIf { it.isNotEmpty() }
        bindings.remove(playlistId)
        prefs.edit().putString(KEY_PLAYLIST_BINDINGS, bindings.toString()).apply()
        if (uid != null) {
            val ids = JSONObject(prefs.getString(KEY_PLAYLIST_IDS, null) ?: "{}")
            ids.remove(uid)
            prefs.edit().putString(KEY_PLAYLIST_IDS, ids.toString()).apply()
        }
    }

    /** All playlistId → local UID string bindings currently known. */
    fun allPlaylistBindings(): Map<String, String> {
        val obj = JSONObject(prefs.getString(KEY_PLAYLIST_BINDINGS, null) ?: "{}")
        val out = mutableMapOf<String, String>()
        for (key in obj.keys()) {
            out[key] = obj.optString(key)
        }
        return out
    }

    /** All fileId → [FileRecord] mappings currently known. */
    fun allFileRecords(): List<FileRecord> {
        val obj = files()
        val out = ArrayList<FileRecord>(obj.length())
        for (key in obj.keys()) {
            val record = obj.optJSONObject(key) ?: continue
            out.add(
                FileRecord(
                    fileId = key,
                    uri = record.optString(KEY_URI),
                    relPath = record.optString(KEY_REL_PATH),
                    name = record.optString(KEY_NAME),
                    size = record.optLong(KEY_SIZE),
                ))
        }
        return out
    }

    // --- Shared playlist snapshot document ---

    /** The revision of the current snapshot document, or 0 if there is none. */
    var snapshotRevision: Long
        get() = prefs.getLong(KEY_SNAPSHOT_REVISION, 0)
        private set(value) {
            prefs.edit().putLong(KEY_SNAPSHOT_REVISION, value).apply()
        }

    /** The name of the device that produced the current snapshot. */
    var snapshotUpdatedBy: String
        get() = prefs.getString(KEY_SNAPSHOT_UPDATED_BY, null) ?: "unknown"
        private set(value) {
            prefs.edit().putString(KEY_SNAPSHOT_UPDATED_BY, value).apply()
        }

    /** The current shared snapshot HTML document, or null if none was received/generated. */
    fun readSnapshot(): String? = readSnapshotFile(SNAPSHOT_FILE)

    /**
     * Persist the given [html] as the current snapshot document, recording its [revision] and
     * the producing device's [updatedBy] name.
     */
    fun writeSnapshot(html: String, revision: Long, updatedBy: String) {
        writeSnapshotFile(SNAPSHOT_FILE, html)
        snapshotRevision = revision
        snapshotUpdatedBy = updatedBy
        clearPendingSnapshot()
    }

    /**
     * Store an incoming snapshot that still awaits audio file transfer before it can be
     * imported ("pending"); it replaces the current snapshot only after being applied.
     */
    fun writePendingSnapshot(html: String) {
        writeSnapshotFile(SNAPSHOT_PENDING_FILE, html)
    }

    /** The pending snapshot HTML, or null if there is nothing awaiting import. */
    fun readPendingSnapshot(): String? = readSnapshotFile(SNAPSHOT_PENDING_FILE)

    /** Discard any pending (not yet applied) snapshot. */
    fun clearPendingSnapshot() {
        snapshotFile(SNAPSHOT_PENDING_FILE).delete()
    }

    private fun snapshotFile(name: String): File =
        File(context.getExternalFilesDir(null), name)

    private fun readSnapshotFile(name: String): String? =
        try {
            val file = snapshotFile(name)
            if (file.isFile) file.readText() else null
        } catch (e: Exception) {
            null
        }

    private fun writeSnapshotFile(name: String, html: String) {
        try {
            snapshotFile(name).writeText(html)
        } catch (e: Exception) {
            // Storage failures leave the previous document in place; the next sync retries.
        }
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
        const val KEY_PLAYLIST_IDS = "playlistIdsByUid"
        const val KEY_PLAYLIST_BINDINGS = "uidsByPlaylistId"
        const val KEY_SNAPSHOT_REVISION = "snapshotRevision"
        const val KEY_SNAPSHOT_UPDATED_BY = "snapshotUpdatedBy"
        const val KEY_URI = "uri"
        const val KEY_REL_PATH = "relPath"
        const val KEY_NAME = "name"
        const val KEY_SIZE = "size"
        const val SNAPSHOT_FILE = "musicbox-playlists.html"
        const val SNAPSHOT_PENDING_FILE = "musicbox-playlists.pending.html"
    }
}
