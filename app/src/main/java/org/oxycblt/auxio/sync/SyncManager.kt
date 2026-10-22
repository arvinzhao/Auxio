/*
 * Copyright (c) 2026 Auxio Project
 * SyncManager.kt is part of Auxio.
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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Components
import timber.log.Timber as L

/**
 * Coordinates the MusicBox (PC) playlist synchronization feature.
 *
 * Owns the embedded [SyncServer] and the mDNS registration, and implements the incremental
 * sync logic: the PC sends a playlist manifest, this device computes which files are missing,
 * receives them over HTTP, and finally writes the playlist into the local music library.
 *
 * Pairing requests are surfaced through [pairRequests] for confirmation by an activity.
 */
@Singleton
class SyncManager
@Inject
constructor(@ApplicationContext private val context: Context, private val repository: MusicRepository) :
    SyncServer.Handler {
    private val store: SyncStore by lazy { SyncStore(context) }
    private var server: SyncServer? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    /** The playlist currently being synchronized, as set by the last manifest. */
    @Volatile private var sessionPlaylistId: String? = null

    private val _pairRequests = MutableStateFlow<PairRequest?>(null)

    /** The pending [PairRequest], or null if there is nothing to confirm. */
    val pairRequests: StateFlow<PairRequest?> = _pairRequests.asStateFlow()

    /** A pairing request from a PC that awaits user confirmation. */
    class PairRequest
    internal constructor(
        val deviceId: String,
        val deviceName: String,
        internal val deferred: CompletableDeferred<Boolean>,
    ) {
        /** Resolve the request with the user's [decision]. */
        fun complete(decision: Boolean): Boolean = deferred.complete(decision)
    }

    /** Resolve the given pairing [request] with the user's [decision]. */
    fun resolve(request: PairRequest, decision: Boolean) {
        request.complete(decision)
    }

    /**
     * Start the sync server and register the mDNS service. Does nothing if already started.
     */
    @Synchronized
    fun start() {
        if (server != null) {
            return
        }
        val instance = SyncServer(this)
        try {
            instance.start(SOCKET_TIMEOUT_MS, true)
        } catch (e: Exception) {
            L.e(e, "Failed to start the sync server")
            return
        }
        server = instance
        L.i("Sync server listening on port ${instance.listeningPort}")
        registerNsd(instance.listeningPort)
    }

    private fun registerNsd(port: Int) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            L.w("NsdManager is unavailable, MusicBox discovery will not work")
            return
        }
        val info =
            NsdServiceInfo().apply {
                serviceName = "${store.deviceName} (Auxio)"
                serviceType = SERVICE_TYPE
                setPort(port)
                try {
                    setAttribute("deviceId", store.deviceId)
                    setAttribute("deviceName", sanitizeTxt(store.deviceName))
                    setAttribute("protocol", PROTOCOL.toString())
                    setAttribute("appVersion", sanitizeTxt(BuildConfig.VERSION_NAME))
                } catch (e: Exception) {
                    L.w(e, "Failed to attach sync TXT records")
                }
            }
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    L.i("Sync service registered: ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    L.w("Sync service registration failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
        registrationListener = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            L.e(e, "Failed to register the sync service")
        }
    }

    /**
     * Re-announce the mDNS service so that PCs can (re-)discover this device, and return a
     * human-readable "ip:port" summary of the sync endpoint for manual fallback entry.
     */
    suspend fun rebroadcast(): String =
        withContext(Dispatchers.IO) {
            val nsd =
                context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val listener = registrationListener
            if (nsd != null && listener != null) {
                try {
                    nsd.unregisterService(listener)
                } catch (e: Exception) {
                    L.w(e, "Failed to unregister the sync service before rebroadcasting")
                }
                // Unregistration is asynchronous; give the daemon a moment before re-registering.
                delay(400)
            }
            val port = server?.listeningPort
            if (port != null) {
                registerNsd(port)
            }
            "${localIpAddress()}:${port ?: -1}"
        }

    private fun localIpAddress(): String =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
            ?: "unknown"

    // --- SyncServer.Handler ---

    override fun onPair(
        deviceId: String,
        deviceName: String,
        appVersion: String,
        protocol: Int
    ): JSONObject {
        if (protocol != PROTOCOL) {
            throw SyncServer.SyncException(NanoHTTPD.Response.Status.BAD_REQUEST, "protocol_mismatch")
        }
        // Already-paired devices are re-issued their token without any confirmation.
        store.getToken(deviceId)?.let {
            return JSONObject().put("token", it).put("deviceName", store.deviceName)
        }
        // A new device requires explicit user confirmation. If an older request is still
        // pending, reject it so its connection does not hang forever.
        _pairRequests.value?.complete(false)
        val request = PairRequest(deviceId, deviceName, CompletableDeferred())
        _pairRequests.value = request
        val accepted = runBlocking { request.deferred.await() }
        if (_pairRequests.value === request) {
            _pairRequests.value = null
        }
        if (!accepted) {
            L.i("Rejected pairing request from $deviceName ($deviceId)")
            throw SyncServer.SyncException(NanoHTTPD.Response.Status.FORBIDDEN, "rejected")
        }
        val token = UUID.randomUUID().toString()
        store.putToken(deviceId, token)
        L.i("Paired with $deviceName ($deviceId)")
        return JSONObject().put("token", token).put("deviceName", store.deviceName)
    }

    override fun onManifest(token: String, body: JSONObject): JSONObject {
        val playlist =
            body.optJSONObject("playlist")
                ?: throw SyncServer.SyncException(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "missing_playlist")
        val playlistId =
            playlist.optString("playlistId").takeIf { it.isNotEmpty() }
                ?: throw SyncServer.SyncException(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "missing_playlist_id")
        val name = playlist.optString("name").takeIf { it.isNotEmpty() } ?: "MusicBox"
        val tracks = playlist.optJSONArray("tracks") ?: JSONArray()

        store.putPlaylistName(playlistId, name)
        sessionPlaylistId = playlistId

        // If the library has not been loaded yet (e.g. the app was just started), kick off a
        // load so that the incremental comparison and the later commit have something to
        // match against. Never cancel an indexing run that is already in progress.
        if (repository.library == null && repository.indexingState == null) {
            repository.requestIndex(true)
        }

        // Incremental comparison happens here: anything the library does not currently index
        // has to be pushed again by the PC.
        val knownPaths = HashSet<String>()
        repository.library?.let { library ->
            for (song in library.songs) {
                knownPaths.add(song.path.components.unixString)
            }
        }
        val missingFileIds = JSONArray()
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val fileId = track.optString("fileId")
            if (fileId.isEmpty()) {
                continue
            }
            val record = store.findFileRecord(fileId)
            if (record == null || record.relPath !in knownPaths) {
                missingFileIds.put(fileId)
            }
        }
        // Covers are received but discarded in v1; embedded tag art is used instead.
        return JSONObject()
            .put("missingFileIds", missingFileIds)
            .put("missingCoverHashes", JSONArray())
            .put("removedTrackIds", JSONArray())
    }

    override fun onFile(
        token: String,
        fileId: String,
        fileName: String?,
        mimeType: String?,
        contentLength: Long,
        input: InputStream,
    ): JSONObject {
        if (fileId.startsWith("cover:")) {
            // v1: covers are not persisted, but the payload still has to be drained.
            input.readBytes()
            return JSONObject().put("received", true)
        }
        val playlistName =
            sessionPlaylistId?.let { store.findPlaylistName(it) } ?: "MusicBox"
        val name = sanitizeName(fileName ?: "track-$fileId")
        val mime = normalizeAudioMime(mimeType, name)
        val record = store.findFileRecord(fileId)
        val (uri, relPath) = writeAudioFile(record, name, mime, playlistName, contentLength, input)
        store.putFileRecord(
            SyncStore.FileRecord(
                fileId = fileId,
                uri = uri,
                relPath = relPath,
                name = name,
                size = contentLength.coerceAtLeast(0)))
        L.i("Received $fileId -> $relPath")
        return JSONObject().put("received", true)
    }

    override fun onCommit(token: String, body: JSONObject): JSONObject {
        val playlistId =
            body.optString("playlistId").takeIf { it.isNotEmpty() }
                ?: throw SyncServer.SyncException(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "missing_playlist_id")
        val name =
            store.findPlaylistName(playlistId)
                ?: throw SyncServer.SyncException(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "unknown_playlist")
        val finalTrackIds = body.optJSONArray("finalTrackIds") ?: JSONArray()

        val songs = runBlocking { resolveSongs(finalTrackIds) }
        // resolveSongs triggers indexing as needed; wait until the library is actually loaded
        // before mutating playlists (the indexing may take a while on large libraries).
        val library =
            runBlocking { awaitLibrary() }
                ?: throw SyncServer.SyncException(
                    NanoHTTPD.Response.Status.CONFLICT, "library_not_ready")

        val result = JSONObject()
        runBlocking {
            val existing = library.findPlaylistByName(name)
            if (existing != null) {
                repository.rewritePlaylist(existing, songs)
                result
                    .put("added", 0)
                    .put("updated", songs.size)
                    .put("removed", (existing.songs.size - songs.size).coerceAtLeast(0))
            } else {
                repository.createPlaylist(name, songs)
                result.put("added", songs.size).put("updated", 0).put("removed", 0)
            }
        }
        sessionPlaylistId = null
        L.i("Committed playlist '$name': $result")
        return result
    }

    override fun onAbort(token: String, body: JSONObject): JSONObject {
        sessionPlaylistId = null
        L.i("Sync session aborted")
        return JSONObject().put("aborted", true)
    }

    override fun isTokenValid(token: String): Boolean = store.allTokens().containsValue(token)

    // --- Internals ---

    /**
     * Resolve the given file IDs to [Song]s in order, triggering a re-index and waiting for it
     * if some received files are not in the library yet.
     *
     * The PC may commit with the full final track list while only pushing the missing files, so
     * IDs without a received file record are skipped instead of failing the whole commit. The
     * commit only fails when nothing at all could be resolved.
     */
    private suspend fun resolveSongs(trackIds: JSONArray): List<Song> {
        val ordered = ArrayList<String>(trackIds.length())
        for (i in 0 until trackIds.length()) {
            val id = trackIds.optString(i)
            if (id.isNotEmpty()) {
                ordered.add(id)
            }
        }
        var matched = resolve(ordered)
        // Only wait for indexing on ids that actually have a received file record; the PC may
        // commit with the full playlist while only the missing files were pushed, and waiting
        // for ids that were never sent would stall the request for the entire poll budget.
        val waiting =
            ordered.mapIndexed { idx, id -> matched[idx] == null && store.findFileRecord(id) != null }
        if (waiting.any { it }) {
            // Freshly received files are not indexed yet; force a full re-index and poll the
            // library until the missing songs show up.
            repository.requestIndex(false)
            var polls = 0
            fun stillWaiting() =
                ordered.mapIndexed { idx, id ->
                    matched[idx] == null && store.findFileRecord(id) != null
                }
            while (stillWaiting().any { it } && polls < COMMIT_POLLS) {
                delay(COMMIT_POLL_INTERVAL_MS)
                matched = resolve(ordered)
                polls++
            }
        }
        val resolved = matched.filterNotNull()
        if (ordered.isNotEmpty() && resolved.isEmpty()) {
            throw SyncServer.SyncException(
                NanoHTTPD.Response.Status.CONFLICT, "tracks_not_indexed")
        }
        if (resolved.size < ordered.size) {
            L.w("Commit skipped ${ordered.size - resolved.size} unresolved track ids")
        }
        return resolved
    }

    private fun resolve(trackIds: List<String>): List<Song?> {
        val library = repository.library ?: return List(trackIds.size) { null }
        val byPath = HashMap<String, Song>()
        for (song in library.songs) {
            byPath[song.path.components.unixString] = song
        }
        return trackIds.map { id -> store.findFileRecord(id)?.let { byPath[it.relPath] } }
    }

    /**
     * Wait for the music library to become available. If no indexing has been kicked off yet
     * (e.g. an empty playlist was committed), start one. Returns null if the library did not
     * load within the polling budget.
     */
    private suspend fun awaitLibrary(): org.oxycblt.musikr.Library? {
        if (repository.library == null) {
            if (repository.indexingState == null) {
                repository.requestIndex(true)
            }
            var polls = 0
            while (repository.library == null && polls < COMMIT_POLLS) {
                delay(COMMIT_POLL_INTERVAL_MS)
                polls++
            }
        }
        return repository.library
    }

    /**
     * Write the streamed audio file to storage, overwriting a previous copy of the same file ID
     * if one still exists. Returns the content URI and the volume-relative path of the file.
     */
    private fun writeAudioFile(
        record: SyncStore.FileRecord?,
        name: String,
        mime: String,
        playlistName: String,
        contentLength: Long,
        input: InputStream,
    ): Pair<String, String> {
        val resolver = context.contentResolver
        val directory = "$SYNC_DIR_BASE/${sanitizeName(playlistName)}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (record != null) {
                // Fast path: overwrite the file previously received for this ID.
                try {
                    resolver.openOutputStream(Uri.parse(record.uri), "w")?.use { output ->
                        copyExactly(input, output, contentLength)
                    }
                    return record.uri to record.relPath
                } catch (e: Exception) {
                    L.w(e, "Overwriting ${record.relPath} failed, inserting a new file")
                }
            }
            val values =
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, directory)
                    if (contentLength > 0) {
                        put(MediaStore.Audio.Media.SIZE, contentLength)
                    }
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            val collection =
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri =
                resolver.insert(collection, values)
                    ?: throw SyncServer.SyncException(
                        NanoHTTPD.Response.Status.INTERNAL_ERROR, "insert_failed")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    copyExactly(input, output, contentLength)
                }
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (ignored: Exception) {}
                throw SyncServer.SyncException(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR, "write_failed")
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            try {
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                L.w(e, "Failed to un-pend $uri")
            }
            // MediaStore may have deduplicated the display name, so resolve the actual path.
            var relPath = "$directory/$name"
            try {
                resolver
                    .query(
                        uri,
                        arrayOf(
                            MediaStore.Audio.Media.RELATIVE_PATH,
                            MediaStore.Audio.Media.DISPLAY_NAME),
                        null,
                        null,
                        null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val relative = cursor.getString(0)?.trim('/', '\\')
                            val displayName = cursor.getString(1)
                            if (relative != null && displayName != null) {
                                relPath = "$relative/$displayName"
                            }
                        }
                    }
            } catch (e: Exception) {
                L.w(e, "Failed to resolve the path of $uri")
            }
            return uri.toString() to relPath
        } else {
            // Below Android Q, MediaStore writes require WRITE_EXTERNAL_STORAGE; fall back to
            // the app-private music directory. Files stored here will not match indexed media
            // paths, so commits are expected to fail on such devices.
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), directory)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, name)
            try {
                file.outputStream().use { output -> copyExactly(input, output, contentLength) }
            } catch (e: Exception) {
                throw SyncServer.SyncException(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR, "write_failed")
            }
            return Uri.fromFile(file).toString() to "$directory/$name"
        }
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, contentLength: Long) {
        if (contentLength < 0) {
            input.copyTo(output)
            return
        }
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = contentLength
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) {
                throw SyncServer.SyncException(NanoHTTPD.Response.Status.BAD_REQUEST, "short_read")
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().takeIf { it.isNotEmpty() } ?: "track"

    /**
     * Resolve the MIME type to use when inserting a received audio file into MediaStore.
     * The audio collection rejects anything outside the audio family, but PCs commonly send
     * generic MIME types (e.g. application/octet-stream) for raw streams, so the type is
     * normalized (falling back to the file extension) before insertion.
     */
    private fun normalizeAudioMime(raw: String?, name: String): String {
        val lower = raw?.lowercase()?.trim()
        if (lower != null && lower.startsWith("audio/")) {
            return lower
        }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/x-ape"
            "aif", "aiff" -> "audio/aiff"
            else -> "audio/mpeg"
        }
    }

    private fun sanitizeTxt(value: String): String = value.filter { it.code in 32..126 }.take(64)

    private companion object {
        /** The mDNS service type MusicBox browses for, as defined by the sync protocol. */
        const val SERVICE_TYPE = "_musicbox-sync._tcp"
        /** The protocol version this implementation speaks. */
        const val PROTOCOL = 1
        /** The read timeout for sync connections, generous to accommodate large files. */
        const val SOCKET_TIMEOUT_MS = 30_000
        /**
         * How long to wait for the indexer to make received files/library visible before giving
         * up. Full re-indexing of large libraries can easily exceed a minute, so this is set
         * generously (120 polls x 750ms = 90s).
         */
        const val COMMIT_POLLS = 120
        const val COMMIT_POLL_INTERVAL_MS = 750L
        const val SYNC_DIR_BASE = "Music/MusicBox"
        const val BUFFER_SIZE = 64 * 1024
    }
}
