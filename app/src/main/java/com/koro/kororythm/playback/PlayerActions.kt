package com.koro.kororythm.playback

/** Everything the panels can ask the player to do. PlayerViewModel is the real one. */
interface PlayerActions {
    fun playPause()
    fun play()
    fun pause()
    fun stop()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun playAt(index: Int)
    fun toggleShuffle()
    fun cycleRepeat()
    fun removeAt(index: Int)
    fun clear()
    fun setBand(i: Int, db: Float)
    fun setPreamp(db: Float)
    fun toggleEq()
    fun toggleBoost()
    fun applyPreset(name: String)
    fun resetEq()

    /** Replaces the queue with [tracks] and plays from [start] (library "play"). */
    fun playTracks(tracks: List<TrackInfo>, start: Int = 0) {}

    /** Adds [tracks] to the end of the queue (library "add"). */
    fun enqueue(tracks: List<TrackInfo>) {}

    /** Turntable pitch fader: speed 0.92..1.08; with key lock the pitch stays put. */
    fun setTempo(speed: Float, keyLock: Boolean) {}

    /** The tag editor is about to rewrite [uri]: stop reading it if it's playing. */
    fun releaseFile(uri: String) {}

    /** The file was rewritten (and maybe renamed): refresh it in the queue and resume. */
    fun fileRewritten(oldUri: String, info: TrackInfo) {}
}
