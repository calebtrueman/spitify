package com.localfy.app.data.music

import org.json.JSONObject

/**
 * One queued/finished music download (same fields as the phone's Room entity). On desktop [localUri] is the
 * absolute path of the saved file and [downloadId] is unused (always null): transfers run in-process.
 */
data class MusicDownloadEntity(
    val id: String,
    val trackJson: String,
    val state: String = "queued",
    val downloadId: Long? = null,
    val localUri: String? = null,
    val error: String? = null,
    val quality: String? = null,
    val wifiOnly: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("trackJson", trackJson).put("state", state)
        .put("downloadId", downloadId).put("localUri", localUri).put("error", error).put("quality", quality)
        .put("wifiOnly", wifiOnly).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject): MusicDownloadEntity? = runCatching {
            fun str(key: String) = if (o.has(key) && !o.isNull(key)) o.getString(key) else null
            MusicDownloadEntity(o.getString("id"), o.getString("trackJson"), o.optString("state", "queued"),
                if (o.has("downloadId") && !o.isNull("downloadId")) o.getLong("downloadId") else null,
                str("localUri"), str("error"), str("quality"), o.optBoolean("wifiOnly", true), o.optLong("createdAt", System.currentTimeMillis()))
        }.getOrNull()
    }
}

fun MusicDownloadEntity.track(): OnlineTrack = requireNotNull(Monochrome.parseTrack(JSONObject(trackJson)))
val MusicDownloadEntity.active: Boolean get() = state in listOf("queued", "waiting", "finding", "downloading", "checking")
