/*
 * Copyright (c) 2026 Auxio Project
 * SyncServer.kt is part of Auxio.
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

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.URLDecoder
import org.json.JSONObject
import timber.log.Timber as L

/**
 * The embedded HTTP server that MusicBox (PC) synchronizes playlists with.
 *
 * Implements the endpoints defined by the `localsend-playlist-sync` protocol v2, which is
 * built around a single shared HTML snapshot document (see [PlaylistSnapshot]):
 * `/sync/snapshot/revision`, `/sync/snapshot/pull`, `/sync/snapshot/push`, `/sync/snapshot/apply`,
 * `/sync/file` and `/sync/abort`. All sync endpoints require the Bearer token issued during
 * pairing.
 *
 * Request bodies are read straight off [NanoHTTPD.IHTTPSession.getInputStream] so that large
 * audio uploads can be streamed without temporary files.
 */
class SyncServer(private val handler: Handler, port: Int = 0) :
    NanoHTTPD(null as String?, port) {
    /** Business logic callbacks, implemented by [SyncManager]. */
    interface Handler {
        /**
         * Handle a pairing request.
         *
         * @return the response JSON containing the (possibly already issued) token.
         * @throws SyncException with status 403 if the user rejected the pairing.
         */
        fun onPair(
            deviceId: String,
            deviceName: String,
            appVersion: String,
            protocol: Int
        ): JSONObject

        /** Report the revision of the locally stored snapshot (cheap poll endpoint). */
        fun onSnapshotRevision(token: String): JSONObject

        /** Return the current snapshot document from this device. */
        fun onSnapshotPull(token: String): JSONObject

        /**
         * Receive a pushed snapshot document. When [deferImport] is set, the document is only
         * stored and the response reports which audio files are still missing; otherwise it is
         * imported immediately.
         */
        fun onSnapshotPush(token: String, html: String, deferImport: Boolean): JSONObject

        /** Import the previously stored pending snapshot into the local library. */
        fun onSnapshotApply(token: String): JSONObject

        /**
         * Receive a pushed file (audio or cover) streamed from [input].
         *
         * @param contentLength the number of bytes to consume from [input], or -1 if unknown.
         */
        fun onFile(
            token: String,
            fileId: String,
            fileName: String?,
            mimeType: String?,
            contentLength: Long,
            input: InputStream,
        ): JSONObject

        /** Abort any ongoing synchronization session. */
        fun onAbort(token: String, body: JSONObject): JSONObject

        /** Whether the given Bearer [token] was issued to a paired device. */
        fun isTokenValid(token: String): Boolean
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri?.trimEnd('/') ?: ""
        return try {
            if (session.method != Method.POST) {
                jsonResponse(Response.Status.METHOD_NOT_ALLOWED, error("method_not_allowed"))
            } else {
                when (path) {
                    "/pair" -> jsonResponse(Response.Status.OK, handlePair(session))
                    "/sync/snapshot/revision" ->
                        authed(session) {
                            jsonResponse(Response.Status.OK, handler.onSnapshotRevision(it))
                        }
                    "/sync/snapshot/pull" ->
                        authed(session) {
                            jsonResponse(Response.Status.OK, handler.onSnapshotPull(it))
                        }
                    "/sync/snapshot/push" -> {
                        val (token, body) = authedWithBody(session)
                        val html =
                            body.optString("html").takeIf { it.isNotEmpty() }
                                ?: fail(Response.Status.BAD_REQUEST, "missing_html")
                        val deferImport = body.optBoolean("deferImport", false)
                        jsonResponse(Response.Status.OK, handler.onSnapshotPush(token, html, deferImport))
                    }
                    "/sync/snapshot/apply" ->
                        authed(session) {
                            jsonResponse(Response.Status.OK, handler.onSnapshotApply(it))
                        }
                    "/sync/file" -> jsonResponse(Response.Status.OK, handleFile(session))
                    "/sync/abort" ->
                        authed(session) {
                            jsonResponse(Response.Status.OK, handler.onAbort(it, readJson(session)))
                        }
                    else -> jsonResponse(Response.Status.NOT_FOUND, error("not_found"))
                }
            }
        } catch (e: SyncException) {
            L.i("Sync request failed: ${e.code}")
            jsonResponse(e.status, error(e.code))
        } catch (e: Exception) {
            L.e(e, "Sync server error")
            jsonResponse(Response.Status.INTERNAL_ERROR, error("internal_error"))
        }
    }

    private fun handlePair(session: IHTTPSession): JSONObject {
        val body = readJson(session)
        val deviceId =
            body.optString("deviceId").takeIf { it.isNotEmpty() }
                ?: fail(Response.Status.BAD_REQUEST, "missing_device_id")
        val deviceName = body.optString("deviceName").takeIf { it.isNotEmpty() } ?: "MusicBox"
        val appVersion = body.optString("appVersion")
        val protocol = body.optInt("protocol", 0)
        return handler.onPair(deviceId, deviceName, appVersion, protocol)
    }

    private fun handleFile(session: IHTTPSession): JSONObject {
        val token = requireToken(session)
        val fileId =
            session.headers[HEADER_FILE_ID] ?: fail(Response.Status.BAD_REQUEST, "missing_file_id")
        val fileName =
            session.headers[HEADER_FILE_NAME]?.let {
                try {
                    URLDecoder.decode(it, "UTF-8")
                } catch (e: Exception) {
                    it
                }
            }
        val mimeType = session.headers["content-type"]
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
        return handler.onFile(token, fileId, fileName, mimeType, contentLength, session.inputStream)
    }

    private fun authed(session: IHTTPSession, block: (String) -> Response): Response =
        block(requireToken(session))

    /** Authenticate and read the JSON body in one step (for endpoints that need both). */
    private fun authedWithBody(session: IHTTPSession): Pair<String, JSONObject> {
        val token = requireToken(session)
        return token to readJson(session)
    }

    private fun requireToken(session: IHTTPSession): String {
        val authorization =
            session.headers["authorization"] ?: fail(Response.Status.UNAUTHORIZED, "unauthorized")
        val token =
            authorization.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
                ?: fail(Response.Status.UNAUTHORIZED, "unauthorized")
        if (!handler.isTokenValid(token)) {
            fail(Response.Status.UNAUTHORIZED, "unauthorized")
        }
        return token
    }

    private fun readJson(session: IHTTPSession): JSONObject {
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length < 0 || length > MAX_JSON_BYTES) {
            fail(Response.Status.BAD_REQUEST, "invalid_content_length")
        }
        val bytes = ByteArray(length.toInt())
        var offset = 0
        val input = session.inputStream
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) break
            offset += read
        }
        return try {
            JSONObject(String(bytes, 0, offset, Charsets.UTF_8))
        } catch (e: Exception) {
            fail(Response.Status.BAD_REQUEST, "invalid_json")
        }
    }

    private fun jsonResponse(status: Response.Status, body: JSONObject) =
        newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(code: String) = JSONObject().put("error", code)

    private fun fail(status: Response.Status, code: String): Nothing =
        throw SyncException(status, code)

    /** An error that maps directly onto an HTTP response. */
    class SyncException(val status: Response.Status, val code: String) : Exception(code)

    private companion object {
        private const val HEADER_FILE_ID = "x-file-id"
        private const val HEADER_FILE_NAME = "x-file-name"
        // Snapshot documents embed every playlist and track of the whole library, so the
        // JSON body limit has to accommodate large libraries.
        private const val MAX_JSON_BYTES = 64 * 1024 * 1024
    }
}
