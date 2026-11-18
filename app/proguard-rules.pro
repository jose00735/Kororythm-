# Media3 ships its own consumer rules.

# SwitchingAudioSink forwards AudioSink calls by method name through a dynamic proxy,
# so the interface's method names must survive minification.
-keep interface androidx.media3.exoplayer.audio.AudioSink { *; }

# youtubedl-android: VideoInfo/VideoFormat are filled by Jackson via reflection
-keep class com.yausername.** { *; }
-keepclassmembers class * { @com.fasterxml.jackson.annotation.* <fields>; @com.fasterxml.jackson.annotation.* <init>(...); }
-keep class com.fasterxml.jackson.** { *; }
# commons-compress unpacks the bundled Python/FFmpeg; its zip extra fields are created by reflection
-keep class org.apache.commons.compress.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn java.beans.**
