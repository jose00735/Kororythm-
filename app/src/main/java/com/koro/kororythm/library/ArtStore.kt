package com.koro.kororythm.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Downscaled (320 px) JPEG copies of cover art in app storage; one file per distinct image. */
object ArtStore {
    fun save(context: Context, bytes: ByteArray): String? = runCatching {
        val dir = File(context.filesDir, "art").apply { mkdirs() }
        val name = TagRules.contentKey(bytes, minOf(bytes.size, 16 * 1024), bytes.size.toLong()).take(16) + ".jpg"
        val out = File(dir, name)
        if (!out.exists()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
        }
        out.absolutePath
    }.getOrNull()
}
