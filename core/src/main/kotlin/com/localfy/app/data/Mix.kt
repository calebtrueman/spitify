package com.localfy.app.data

data class Mix(
    val key: String,
    val title: String,
    val description: String,
    val songs: List<Song>,
    /** Which Home shelf it belongs to. */
    val section: MixSection = MixSection.MadeForYou,
    val style: CoverStyle = CoverStyle.Collage,
    /** ARGB colour for the generated cover. */
    val accent: Long = 0xFF1ED760,
    /** "Because you listen to ..." - shown on the playlist page. */
    val why: String? = null,
    val refresh: String? = null,
) {
    val cover: Song get() = songs.first()
}

enum class MixSection(val title: String) {
    MadeForYou("Made for you"), Discover("Discover"), YourMixes("Your mixes"), Throwbacks("Throwbacks & favourites")
}

enum class CoverStyle { Collage, Bold }
