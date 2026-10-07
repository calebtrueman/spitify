package com.localfy.app.data.podcast

import com.localfy.app.data.Song

/** Where a resume point is stored: episodes by episode id ("ep:<id>"), everything else by song id (as on Android). */
val Song.resumeKey: String get() = episodeId?.let { "ep:$it" } ?: id.toString()
