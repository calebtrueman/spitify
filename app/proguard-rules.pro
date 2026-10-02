# Room and Media3 ship their own consumer rules; nothing app-specific is reflected on.

# DefaultRenderersFactory instantiates the FFmpeg renderer reflectively.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }

# Jaudiotagger's desktop image branch is never used: FileTags selects AndroidArtwork.
-dontwarn java.awt.image.BufferedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.stream.ImageInputStream

# Nostr's generated bindings pass callbacks and structures through JNA by name.
-keep class org.nostrdevkit.sdk.** { *; }
-keep class com.sun.jna.** { *; }
# JNA's AWT helpers are desktop-only and are never used by the Nostr bindings.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
