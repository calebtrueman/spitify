package com.localfy.app.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/** Where Android plays [Song] from: its stream/episode URI, else its MediaStore entry. */
val Song.uri: Uri get() = sourceUri?.let(Uri::parse) ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

/** Album URI; MediaStore can produce a thumbnail for it (used for notification / lock-screen art). */
val Song.albumArtUri: Uri get() = artUrl?.let(Uri::parse) ?: ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
