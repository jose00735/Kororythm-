package com.koro.kororythm.ui.bulk

/**
 * Hidden shortcut: a short sequence of hardware keys pressed within [windowMs]
 * (Volume Up, Down, Up in under 2 seconds opens bulk edit). Keys still do their usual job.
 */
class KeySequence(private val pattern: List<Int>, private val windowMs: Long = 2000) {
    private val recent = ArrayDeque<Pair<Int, Long>>()

    /** Feed one key press; true when it completes the sequence. */
    fun press(key: Int, atMs: Long): Boolean {
        recent.addLast(key to atMs)
        while (recent.size > pattern.size) recent.removeFirst()
        val hit = recent.size == pattern.size &&
            recent.map { it.first } == pattern &&
            recent.last().second - recent.first().second <= windowMs
        if (hit) recent.clear()
        return hit
    }
}
