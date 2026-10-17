# Keep JNI methods
-keep class io.github.assrender.AssDirectBridge { *; }

# Public API used by integrating apps
-keep class io.github.assrender.AssHandler { *; }
-keep class io.github.assrender.AssRenderersFactory { *; }
-keep class io.github.assrender.AssExtractorsFactory { *; }
-keep class io.github.assrender.SubtitleOverlayView { *; }

# AssMatroskaExtractor reads MatroskaExtractor's private extractorOutput field
# by reflection to wrap it; renaming or removing it would silently disable ASS.
-keepclassmembers class androidx.media3.extractor.mkv.MatroskaExtractor {
    androidx.media3.extractor.ExtractorOutput extractorOutput;
}
