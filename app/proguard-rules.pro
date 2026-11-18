# Media3 ships its own consumer rules.

# SwitchingAudioSink forwards AudioSink calls by method name through a dynamic proxy,
# so the interface's method names must survive minification.
-keep interface androidx.media3.exoplayer.audio.AudioSink { *; }

# youtubedl-android: VideoInfo/VideoFormat are filled by Jackson via reflection
-keep class com.yausername.** { *; }
-keepclassmembers class * { @com.fasterxml.jackson.annotation.* <fields>; @com.fasterxml.jackson.annotation.* <init>(...); }
-keep class com.fasterxml.jackson.databind.ObjectMapper { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn org.apache.commons.compress.**
-dontwarn java.beans.**
