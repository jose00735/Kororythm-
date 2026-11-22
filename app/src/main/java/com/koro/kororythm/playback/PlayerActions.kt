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

    /** Replaces the queue with [tracks] and plays from [start] (library "play"); [source] names the list. */
    fun playTracks(tracks: List<TrackInfo>, start: Int = 0, source: String? = null) {}

    /**
     * Queues [tracks] on the fly. [next] = play right after the current song (after any songs
     * already queued that way, so they keep the order you picked them); otherwise at the very end.
     */
    fun enqueue(tracks: List<TrackInfo>, next: Boolean = false) {}

    /** Turntable pitch fader: speed 0.92..1.08; with key lock the pitch stays put. */
    fun setTempo(speed: Float, keyLock: Boolean) {}

    /** The tag editor is about to rewrite [uri]: stop reading it if it's playing. */
    fun releaseFile(uri: String) {}

    /** The file was rewritten (and maybe renamed): refresh it in the queue and resume. */
    fun fileRewritten(oldUri: String, info: TrackInfo) {}

    /** The file is gone (deleted or missing): take it out of the queue. */
    fun fileRemoved(uri: String) {}
}
