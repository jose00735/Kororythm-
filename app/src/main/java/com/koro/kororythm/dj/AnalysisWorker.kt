package com.koro.kororythm.dj

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.koro.kororythm.library.Library
import java.nio.ByteOrder

/**
 * Background analysis: decodes each library track once with Android's own decoders and stores
 * its tempo (BPM) and loudness gain. Runs after imports and from the DJ screen.
 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = Library.dao
        var done = 0
        while (!isStopped) {
            val batch = dao.unanalyzed(20)
            if (batch.isEmpty()) break
            for (t in batch) {
                if (isStopped) return Result.success()
                val (bpm, gain) = runCatching { analyze(applicationContext, Uri.parse(t.uri)) }.getOrDefault(null to null)
                dao.setAnalysis(t.id, bpm, gain)
                done++
                setProgress(workDataOf("done" to done))
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "koro-analysis"
        private const val MAX_SECONDS = 8 * 60

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }

        /** Decodes up to 8 minutes of a track and returns (BPM, gain dB). */
        fun analyze(context: Context, uri: Uri): Pair<Float?, Float?> {
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                ex.setDataSource(context, uri, null)
                val track = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null to null
                val fmt = ex.getTrackFormat(track)
                ex.selectTrack(track)
                val mime = fmt.getString(MediaFormat.KEY_MIME)!!
                var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val c = MediaCodec.createDecoderByType(mime).also { codec = it }
                c.configure(fmt, null, null, 0)
                c.start()
                var analyzer = TrackAnalyzer(rate, ch)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var frames = 0L
                var floats = FloatArray(0)
                while (true) {
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = c.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0 || frames > MAX_SECONDS.toLong() * rate) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = c.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                            if (frames == 0L) analyzer = TrackAnalyzer(rate, ch)
                        }
                        o >= 0 -> {
                            val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val samples = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 else info.size / 2
                            if (floats.size < samples) floats = FloatArray(samples)
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) for (k in 0 until samples) floats[k] = buf.getFloat()
                            else for (k in 0 until samples) floats[k] = buf.getShort() / 32768f
                            analyzer.feed(floats, samples / ch)
                            frames += samples / ch
                            c.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                return analyzer.bpm() to analyzer.gainDb()?.toFloat()
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                ex.release()
            }
        }
    }
}
