# Room and Media3 ship their own consumer rules; nothing app-specific is reflected on.

# DefaultRenderersFactory instantiates the FFmpeg renderer reflectively.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
