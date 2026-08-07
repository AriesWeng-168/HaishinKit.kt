package com.haishinkit.rtmp

import android.net.Uri

internal class RtmpUri(
    private val uri: Uri,
) {
    val streamName: String
        get() {
            // obslive patch: keep the query string with the stream name. Relay/CDN publish
            // credentials ride on the stream key as `name?user=u&pass=p` (HaishinKit.swift
            // semantics). Uri.pathSegments drops the query, so publishing lost the
            // credentials and the server rejected the stream (reproduced on mediamtx).
            val name = uri.pathSegments.last().toString()
            val query = uri.query
            return if (query.isNullOrEmpty()) name else "$name?$query"
        }

    val tcUrl: String
        get() {
            // obslive patch: tcUrl must NOT carry the query (it belongs to the stream name).
            val path = uri.pathSegments.first().toString()
            return uri.buildUpon().path(path).clearQuery().build().toString()
        }

    override fun toString() = uri.toString()
}
