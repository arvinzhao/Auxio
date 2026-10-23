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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Coordinates the MusicBox (PC) playlist synchronization feature.
 *
 * Owns the embedded [SyncServer] and the mDNS registration. Sync protocol v2 is built around a
 * single shared HTML snapshot document ([PlaylistSnapshot]) that both devices maintain a copy
 * of: it contains every playlist and song entry, and the copy with the highest revision wins.
 * This device regenerates its copy whenever the user edits a playlist, accepts pushed copies
 * from the PC, and serves its copy for the PC to pull. Audio files for tracks missing on this
 * device are still pushed incrementally by the PC before the pushed snapshot is applied.
 *
 * Pairing requests are surfaced through [pairRequests] for confirmation by an activity.
 */
@Singleton
class SyncManager
@Inject
constructor(@ApplicationContext private val context: Context, private val repository: MusicRepository) :
    SyncServer.Handler, MusicRepository.UpdateListener {
    private val store: SyncStore by lazy { SyncStore(context) }
    private var server: SyncServer? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes snapshot regeneration and import so they never interleave. */
    private val snapshotLock = Any()

    /** True while a pushed snapshot is being imported; suppresses snapshot regeneration. */
    @Volatile private var importing = false

    /** In-memory cache of the current snapshot, avoiding repeated HTML parsing. */
    @Volatile private var cachedSnapshot: PlaylistSnapshot? = null

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
        // Listen for playlist edits so the shared snapshot document stays current.
        repository.addUpdateListener(this)
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

    // --- MusicRepository.UpdateListener ---

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (!changes.userLibrary || importing) {
            return
        }
        // Playlist edits (create/rename/delete/add/rewrite) invalidate the shared snapshot.
        scope.launch { regenerateSnapshot() }
    }

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

    override fun onSnapshotRevision(token: String): JSONObject {
        val snapshot = currentSnapshot()
        return JSONObject()
            .put("revision", snapshot?.revision ?: 0)
            .put("updatedBy", snapshot?.updatedBy ?: "unknown")
            .put("playlistCount", snapshot?.playlists?.size ?: 0)
            .put("trackCount", snapshot?.trackCount ?: 0)
    }

    override fun onSnapshotPull(token: String): JSONObject {
        val snapshot =
            currentSnapshot()
                ?: throw SyncServer.SyncException(
                    NanoHTTPD.Response.Status.CONFLICT, "no_snapshot")
        return JSONObject()
            .put("revision", snapshot.revision)
            .put("updatedBy", snapshot.updatedBy)
            .put("html", snapshot.encode())
    }

    override fun onSnapshotPush(token: String, html: String, deferImport: Boolean): JSONObject {
        val incoming =
            PlaylistSnapshot.decode(html)
                ?: throw SyncServer.SyncException(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "invalid_snapshot")
        if (incoming.revision < store.snapshotRevision) {
            // The local document is newer than what the PC is pushing; it should pull instead.
            throw SyncServer.SyncException(
                NanoHTTPD.Response.Status.CONFLICT, "stale_snapshot")
        }

        synchronized(snapshotLock) {
            store.writePendingSnapshot(html)

            // If the library has not been loaded yet (e.g. the app was just started), kick off a
            // load so that the diff and the later apply have something to match against. Never
            // cancel an indexing run that is already in progress.
            if (repository.library == null && repository.indexingState == null) {
                repository.requestIndex(true)
            }

            // Incremental comparison happens here: anything the library does not currently
            // index has to be pushed again by the PC. Without a loaded library the received
            // file records are trusted instead of re-pushing everything.
            val library = repository.library
            val knownPaths = HashSet<String>()
            if (library != null) {
                for (song in library.songs) {
                    knownPaths.add(song.path.components.unixString)
                }
            }
            val missingFileIds = JSONArray()
            for (playlist in incoming.playlists) {
                for (track in playlist.tracks) {
                    val fileId = track.fileId
                    if (fileId.isEmpty() || fileId.startsWith(LOCAL_FILE_ID_PREFIX)) {
                        continue
                    }
                    val record = store.findFileRecord(fileId)
                    if (record == null || (library != null && record.relPath !in knownPaths)) {
                        missingFileIds.put(fileId)
                    }
                }
            }

            val applied = !deferImport
            if (applied) {
                applyPendingSnapshot()
            }
            L.i(
                "Accepted pushed snapshot rev ${incoming.revision} " +
                    "(deferred=$deferImport, missing=${missingFileIds.length()})")
            return JSONObject()
                .put("missingFileIds", missingFileIds)
                .put("applied", applied)
        }
    }

    override fun onSnapshotApply(token: String): JSONObject = applyPendingSnapshot()

    override fun onFile(
        token: String,
        fileId: String,
        fileName: String?,
        mimeType: String?,
        contentLength: Long,
        input: InputStream,
    ): JSONObject {
        if (fileId.startsWith("cover:")) {
            // Covers are not persisted; the payload still has to be drained.
            input.readBytes()
            return JSONObject().put("received", true)
        }
        val name = sanitizeName(fileName ?: "track-$fileId")
        val mime = normalizeAudioMime(mimeType, name)
        val record = store.findFileRecord(fileId)
        val (uri, relPath) = writeAudioFile(record, name, mime, contentLength, input)
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

    override fun onAbort(token: String, body: JSONObject): JSONObject {
        store.clearPendingSnapshot()
        L.i("Sync session aborted")
        return JSONObject().put("aborted", true)
    }

    override fun isTokenValid(token: String): Boolean = store.allTokens().containsValue(token)

    // --- Snapshot management ---

    /** The current snapshot, lazily loading (and caching) the stored HTML document. */
    private fun currentSnapshot(): PlaylistSnapshot? {
        cachedSnapshot?.let { return it }
        val html = store.readSnapshot() ?: return null
        return PlaylistSnapshot.decode(html)?.also { cachedSnapshot = it }
    }

    /**
     * Regenerate the shared snapshot document from the current library state, bumping the
     * revision only when the playlist data actually changed. Runs on library changes that were
     * caused by the user rather than by a sync import.
     */
    private suspend fun regenerateSnapshot() {
        val library = repository.library ?: return
        synchronized(snapshotLock) {
            if (importing) {
                return
            }
            val playlists = buildSnapshotPlaylists(library)
            val current = currentSnapshot()
            if (current != null && samePlaylistData(current.playlists, playlists)) {
                return
            }
            val snapshot =
                PlaylistSnapshot(System.currentTimeMillis(), store.deviceName, playlists)
            store.writeSnapshot(snapshot.encode(), snapshot.revision, snapshot.updatedBy)
            cachedSnapshot = snapshot
            L.i(
                "Regenerated snapshot rev ${snapshot.revision}: " +
                    "${playlists.size} playlists, ${snapshot.trackCount} tracks")
        }
    }

    /**
     * Import the previously stored pending snapshot into the local music library, creating,
     * renaming, rewriting and deleting playlists so that they mirror the shared document.
     */
    private fun applyPendingSnapshot(): JSONObject =
        // runBlocking keeps the server thread synchronous while the monitor is held for the
        // whole import; suspend calls live inside the runBlocking lambda, not the critical
        // section itself.
        synchronized(snapshotLock) { runBlocking { applyPendingSnapshotInternal() } }

    private suspend fun applyPendingSnapshotInternal(): JSONObject {
        val empty =
            JSONObject()
                .put("added", 0)
                .put("updated", 0)
                .put("removed", 0)
                .put("skipped", 0)
        val pendingHtml = store.readPendingSnapshot() ?: return empty
        val snapshot =
            PlaylistSnapshot.decode(pendingHtml)
                ?: run {
                    store.clearPendingSnapshot()
                    throw SyncServer.SyncException(
                        NanoHTTPD.Response.Status.BAD_REQUEST, "invalid_snapshot")
                }
        // resolveSongs triggers indexing as needed; wait until the library is actually loaded
        // before mutating playlists (the indexing may take a while on large libraries).
        awaitLibrary()
            ?: throw SyncServer.SyncException(
                NanoHTTPD.Response.Status.CONFLICT, "library_not_ready")

        importing = true
        try {
            var added = 0
            var updated = 0
            var removed = 0
            var skipped = 0
            val seenIds = HashSet<String>()
            for (playlist in snapshot.playlists) {
                seenIds.add(playlist.playlistId)
                val fileIds = playlist.tracks.map { it.fileId }.filter { it.isNotEmpty() }
                val songs = resolveSongs(fileIds)
                skipped += fileIds.size - songs.size
                val existing =
                    findBoundPlaylist(playlist.playlistId)
                        ?: repository.library?.findPlaylistByName(playlist.name)
                if (existing != null) {
                    if (existing.name.raw != playlist.name) {
                        repository.renamePlaylist(existing, playlist.name)
                    }
                    repository.rewritePlaylist(existing, songs)
                    store.bindPlaylist(playlist.playlistId, existing.uid.toString())
                    updated++
                } else {
                    repository.createPlaylist(playlist.name, songs)
                    val created = repository.library?.findPlaylistByName(playlist.name)
                    if (created != null) {
                        store.bindPlaylist(playlist.playlistId, created.uid.toString())
                    }
                    added++
                }
            }
            // Playlists previously shared but no longer present in the document were deleted
            // on the other device; mirror the deletion locally.
            for ((playlistId, uidString) in store.allPlaylistBindings()) {
                if (playlistId in seenIds) {
                    continue
                }
                val uid = Music.UID.fromString(uidString)
                val playlist = uid?.let { repository.library?.findPlaylist(it) }
                if (playlist != null) {
                    repository.deletePlaylist(playlist)
                    removed++
                }
                store.unbindPlaylistId(playlistId)
            }
            // The imported document is now the local source of truth.
            store.writeSnapshot(pendingHtml, snapshot.revision, snapshot.updatedBy)
            cachedSnapshot = snapshot
            L.i(
                "Applied snapshot rev ${snapshot.revision}: added=$added updated=$updated " +
                    "removed=$removed skipped=$skipped")
            return JSONObject()
                .put("added", added)
                .put("updated", updated)
                .put("removed", removed)
                .put("skipped", skipped)
        } finally {
            importing = false
        }
    }

    /** Find the local [org.oxycblt.musikr.Playlist] bound to the given cross-device ID. */
    private fun findBoundPlaylist(playlistId: String): org.oxycblt.musikr.Playlist? {
        val uidString = store.uidForPlaylistId(playlistId) ?: return null
        val uid = Music.UID.fromString(uidString) ?: return null
        return repository.library?.findPlaylist(uid)
    }

    /**
     * Build snapshot playlists from the library. Every playlist is assigned a stable
     * cross-device ID (created on first use); songs received from the PC map back to their
     * original file IDs, while local songs use `local:<uid>` identities.
     */
    private fun buildSnapshotPlaylists(library: Library): List<SnapshotPlaylist> {
        val fileIdByPath = HashMap<String, String>()
        for (record in store.allFileRecords()) {
            fileIdByPath[record.relPath] = record.fileId
        }
        return library.playlists.map { playlist ->
            val playlistId = store.playlistIdForUid(playlist.uid.toString())
            val tracks =
                playlist.songs.map { song ->
                    val path = song.path.components.unixString
                    SnapshotTrack(
                        fileId = fileIdByPath[path] ?: "$LOCAL_FILE_ID_PREFIX${song.uid}",
                        title = song.name.raw,
                        artist =
                            song.artists.firstOrNull()?.name?.resolve(context)
                                ?: context.getString(R.string.def_artist),
                        album = song.album.name.resolve(context),
                        durationSec = song.durationMs / 1000,
                        fileSize = song.size,
                        fileName = path.substringAfterLast('/'),
                    )
                }
            SnapshotPlaylist(playlistId, playlist.name.raw, System.currentTimeMillis(), tracks)
        }
    }

    /** Whether two snapshot playlist lists describe the same data (ignoring timestamps). */
    private fun samePlaylistData(
        a: List<SnapshotPlaylist>,
        b: List<SnapshotPlaylist>,
    ): Boolean {
        if (a.size != b.size) {
            return false
        }
        return a.zip(b).all { (left, right) ->
            left.playlistId == right.playlistId &&
                left.name == right.name &&
                left.tracks.size == right.tracks.size &&
                left.tracks.zip(right.tracks).all { (l, r) -> l.fileId == r.fileId }
        }
    }

    // --- Internals ---

    /**
     * Resolve the given file IDs to [Song]s in order, triggering a re-index and waiting for it
     * if some received files are not in the library yet.
     *
     * IDs without a received file record (or whose file no longer resolves) are skipped instead
     * of failing the whole import; the import only fails when nothing at all could be resolved.
     */
    private suspend fun resolveSongs(trackIds: List<String>): List<Song> {
        var matched = resolve(trackIds)
        // Only wait for indexing on ids that actually have a received file record; the PC may
        // reference songs that never were pushed, and waiting for those would stall the
        // request for the entire poll budget.
        fun stillWaiting() =
            trackIds.mapIndexed { idx, id -> matched[idx] == null && store.findFileRecord(id) != null }
        if (stillWaiting().any { it }) {
            // Freshly received files are not indexed yet; force a full re-index and poll the
            // library until the missing songs show up.
            repository.requestIndex(false)
            var polls = 0
            while (stillWaiting().any { it } && polls < COMMIT_POLLS) {
                delay(COMMIT_POLL_INTERVAL_MS)
                matched = resolve(trackIds)
                polls++
            }
        }
        val resolved = matched.filterNotNull()
        if (resolved.size < trackIds.size) {
            L.w("Import skipped ${trackIds.size - resolved.size} unresolved track ids")
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
     * (e.g. an empty snapshot was applied), start one. Returns null if the library did not
     * load within the polling budget.
     */
    private suspend fun awaitLibrary(): Library? {
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
        contentLength: Long,
        input: InputStream,
    ): Pair<String, String> {
        val resolver = context.contentResolver
        val directory = SYNC_DIR_BASE
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
        const val PROTOCOL = PlaylistSnapshot.PROTOCOL
        /** Prefix used for file IDs of songs that originate on this device. */
        const val LOCAL_FILE_ID_PREFIX = "local:"
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
