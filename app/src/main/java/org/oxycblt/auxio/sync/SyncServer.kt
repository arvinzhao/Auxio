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
 * The embedded HTTP server that MusicBox (PC) pushes playlists to.
 *
 * Implements the endpoints defined by the `localsend-playlist-sync` protocol:
 * `/pair`, `/sync/manifest`, `/sync/file`, `/sync/commit` and `/sync/abort`. All sync
 * endpoints require the Bearer token issued during pairing.
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

        /** Handle a sync manifest, returning the set of missing files on this device. */
        fun onManifest(token: String, body: JSONObject): JSONObject

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

        /** Commit the synchronized playlist into the local music library. */
        fun onCommit(token: String, body: JSONObject): JSONObject

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
                    "/sync/manifest" ->
                        authed(session) {
                            jsonResponse(Response.Status.OK, handler.onManifest(it, readJson(session)))
                        }
                    "/sync/file" -> jsonResponse(Response.Status.OK, handleFile(session))
                    "/sync/commit" ->
                        authed(session) {
                            jsonResponse(Response.Status.OK, handler.onCommit(it, readJson(session)))
                        }
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
        private const val MAX_JSON_BYTES = 8 * 1024 * 1024
    }
}
