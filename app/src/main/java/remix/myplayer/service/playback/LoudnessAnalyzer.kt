package remix.myplayer.service.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device loudness analyzer, in the spirit of mp3gain / ReplayGain.
 *
 * Each track is decoded to PCM and passed through a K-weighting filter (BS.1770-style:
 * a 38 Hz high-pass followed by a +4 dB high-shelf around 1.5 kHz). The filtered signal's
 * RMS is measured in short windows; the 95th percentile window is taken as the track's
 * representative loudness (this ignores silent intros/outros and pauses). The per-track
 * gain that brings every song to a common target loudness is then derived, together with
 * the measured peak for clipping protection.
 *
 * The same filter is applied to every track, so the resulting gains are mutually consistent
 * and normalize the perceived playback volume across the whole library.
 */
object LoudnessAnalyzer {

  /** Target loudness as a K-weighted RMS level (dBFS). Typical material lands near 0 dB gain. */
  private const val TARGET_RMS_DB = -18f

  /** Window length for RMS measurement. */
  private const val WINDOW_MS = 400

  /** Percentile of window RMS used as the track's representative loudness. */
  private const val PERCENTILE = 0.95f

  private const val GAIN_MIN_DB = -20f
  private const val GAIN_MAX_DB = 20f

  private const val TIMEOUT_US = 10_000L

  data class LoudnessResult(
    val gainDb: Float,
    val peak: Float,
    val sampleRate: Int,
    val durationMs: Long,
  )

  /**
   * Analyze [file] and return its loudness result, or null on failure.
   *
   * @param maxAnalyzeMs When set, analysis stops after this much audio has been decoded
   *   (used for fast first-play analysis). The full track is analyzed when left at the default.
   */
  suspend fun analyze(file: File, maxAnalyzeMs: Long = Long.MAX_VALUE): LoudnessResult? =
    withContext(Dispatchers.IO) {
      if (!file.exists() || !file.canRead()) return@withContext null
      val extractor = MediaExtractor()
      var codec: MediaCodec? = null
      try {
        extractor.setDataSource(file.absolutePath)
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
          val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
          if (mime != null && mime.startsWith("audio/")) {
            trackIndex = i
            break
          }
        }
        if (trackIndex < 0) return@withContext null
        extractor.selectTrack(trackIndex)
        val inFormat = extractor.getTrackFormat(trackIndex)
        val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        decoder.configure(inFormat, null, null, 0)
        decoder.start()

        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) {
          inFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
          -1L
        }

        val highPass = highPass(sampleRate, 38f, 0.5f)
        val highShelf = highShelf(sampleRate, 1500f, 4f, 0.707f)
        val windowFrames = max((sampleRate * WINDOW_MS / 1000f).toInt(), 1)
        val bytesPerFrame = channels * 2

        val rmsList = ArrayList<Float>()
        val acc = Accumulator()
        var elapsedFrames = 0L

        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()

        while (!outputDone) {
          if (!inputDone) {
            val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
              val inBuf = decoder.getInputBuffer(inIdx)!!
              // Stop feeding once we've analyzed enough audio.
              if (elapsedFrames * 1000L / sampleRate >= maxAnalyzeMs) {
                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
              } else {
                val size = extractor.readSampleData(inBuf, 0)
                if (size <= 0) {
                  decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                  inputDone = true
                } else {
                  val pts = extractor.sampleTime
                  decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                  extractor.advance()
                }
              }
            }
          }

          val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
          when {
            outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
              // No output yet; keep looping.
            }
            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              val outFormat = decoder.outputFormat
              if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
              }
            }
            outIdx >= 0 -> {
              val outBuf = decoder.getOutputBuffer(outIdx)!!
              if (info.size > 0) {
                val frames = processBuffer(
                  outBuf, info.offset, info.size, channels, bytesPerFrame, windowFrames,
                  highPass, highShelf, acc, rmsList,
                )
                elapsedFrames += frames
              }
              decoder.releaseOutputBuffer(outIdx, false)
              if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                outputDone = true
              }
            }
          }
        }

        // Flush any partial trailing window.
        if (acc.windowIndex > 0 && acc.sumSq > 0.0) {
          rmsList.add(sqrt((acc.sumSq / acc.windowIndex).toFloat()))
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        val representativeRms = if (rmsList.isNotEmpty()) {
          percentile(rmsList, PERCENTILE)
        } else {
          0f
        }

        val gainDb = if (representativeRms <= 0f) {
          0f
        } else {
          val measuredDb = 20f * log10(representativeRms)
          (TARGET_RMS_DB - measuredDb).coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)
        }

        val durationMs = if (durationUs > 0) {
          durationUs / 1000
        } else {
          elapsedFrames * 1000L / max(sampleRate, 1)
        }
        LoudnessResult(
          gainDb = gainDb,
          peak = acc.peak.toFloat(),
          sampleRate = sampleRate,
          durationMs = durationMs,
        )
      } catch (e: Exception) {
        Timber.w(e, "LoudnessAnalyzer failed for ${file.absolutePath}")
        null
      } finally {
        runCatching { extractor.release() }
        runCatching { codec?.release() }
      }
    }

  /**
   * Process a block of 16-bit PCM (little-endian, interleaved) through the weighting filters,
   * accumulating windowed RMS into [rmsList] and tracking the raw peak in [acc].
   *
   * @return number of audio frames processed.
   */
  private fun processBuffer(
    buf: ByteBuffer,
    offset: Int,
    size: Int,
    channels: Int,
    bytesPerFrame: Int,
    windowFrames: Int,
    highPass: Biquad,
    highShelf: Biquad,
    acc: Accumulator,
    rmsList: ArrayList<Float>,
  ): Long {
    buf.order(ByteOrder.LITTLE_ENDIAN)
    buf.position(offset)
    buf.limit(offset + size)
    val frameCount = size / bytesPerFrame
    for (f in 0 until frameCount) {
      var mono = 0f
      for (c in 0 until channels) {
        val s = buf.short / 32768f
        val a = abs(s)
        if (a > acc.peak) acc.peak = a.toDouble()
        mono += s
      }
      mono /= channels
      val y = highShelf.process(highPass.process(mono))
      acc.sumSq += y * y
      acc.windowIndex++
      if (acc.windowIndex >= windowFrames) {
        rmsList.add(sqrt((acc.sumSq / windowFrames).toFloat()))
        acc.sumSq = 0.0
        acc.windowIndex = 0
      }
    }
    return frameCount.toLong()
  }

  private fun log10(x: Float): Float = log(x.toDouble(), 10.0).toFloat()

  private fun percentile(values: List<Float>, p: Float): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val idx = min(((sorted.size - 1) * p).roundToInt(), sorted.size - 1)
    return sorted[idx]
  }

  /** RBJ cookbook high-pass biquad, normalized so a0 = 1. */
  private fun highPass(fs: Int, f0: Float, q: Float): Biquad {
    val w0 = 2 * PI * f0 / fs
    val cw = cos(w0)
    val sw = sin(w0)
    val alpha = sw / (2 * q)
    val b0 = (1 + cw) / 2
    val b1 = -(1 + cw)
    val b2 = (1 + cw) / 2
    val a0 = 1 + alpha
    val a1 = -2 * cw
    val a2 = 1 - alpha
    return Biquad(
      (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
      (a1 / a0).toFloat(), (a2 / a0).toFloat(),
    )
  }

  /** RBJ cookbook high-shelf biquad, normalized so a0 = 1. */
  private fun highShelf(fs: Int, f0: Float, gainDb: Float, q: Float): Biquad {
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2 * PI * f0 / fs
    val cw = cos(w0)
    val sw = sin(w0)
    val alpha = sw / (2 * q)
    val sa = 2 * sqrt(a.toDouble()) * alpha
    val b0 = a * ((a + 1) + (a - 1) * cw + sa)
    val b1 = -2 * a * ((a - 1) + (a + 1) * cw)
    val b2 = a * ((a + 1) + (a - 1) * cw - sa)
    val a0 = (a + 1) - (a - 1) * cw + sa
    val a1 = -2 * ((a - 1) - (a + 1) * cw)
    val a2 = (a + 1) - (a - 1) * cw - sa
    return Biquad(
      (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
      (a1 / a0).toFloat(), (a2 / a0).toFloat(),
    )
  }

  /** Mutable accumulator shared between decode windows. */
  private class Accumulator {
    var sumSq = 0.0
    var windowIndex = 0
    var peak = 0.0
  }
}

/**
 * Second-order IIR filter (RBJ cookbook, direct form II transposed, a0 normalized to 1).
 */
class Biquad(
  b0: Float,
  b1: Float,
  b2: Float,
  a1: Float,
  a2: Float,
) {
  private val b0 = b0
  private val b1 = b1
  private val b2 = b2
  private val a1 = a1
  private val a2 = a2
  private var z1 = 0f
  private var z2 = 0f

  fun process(x: Float): Float {
    val y = b0 * x + z1
    z1 = b1 * x - a1 * y + z2
    z2 = b2 * x - a2 * y
    return y
  }
}
