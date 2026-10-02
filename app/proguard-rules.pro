# Room and Media3 ship their own consumer rules; nothing app-specific is reflected on.

# DefaultRenderersFactory instantiates the FFmpeg renderer reflectively.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }

# Jaudiotagger's desktop image branch is never used: FileTags selects AndroidArtwork.
-dontwarn java.awt.image.BufferedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.stream.ImageInputStream
