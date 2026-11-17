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
}
