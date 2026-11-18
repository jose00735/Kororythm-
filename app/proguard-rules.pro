# Media3 ships its own consumer rules.

# SwitchingAudioSink forwards AudioSink calls by method name through a dynamic proxy,
# so the interface's method names must survive minification.
-keep interface androidx.media3.exoplayer.audio.AudioSink { *; }
