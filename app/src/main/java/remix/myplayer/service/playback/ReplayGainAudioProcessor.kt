package remix.myplayer.service.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Applies ReplayGain to PCM audio in the float/16-bit domain.
 *
 * The gain ramps smoothly towards the target to avoid clicks when switching
 * tracks, and the output is limited to full scale (optionally pre-limited by
 * the stored peak to preserve headroom without clipping).
 */
@OptIn(UnstableApi::class)
class ReplayGainAudioProcessor : BaseAudioProcessor() {

  @Volatile
  private var targetGainLinear = 1f

  private var currentGainLinear = 1f
  private var rampAlpha = 0f

  /**
   * Set the gain to apply. Pass null (or 0 dB) to disable.
   *
   * mp3gain-style safety: by default this processor only ever attenuates the
   * signal (never boosts it) and keeps 1 dB of headroom for the downstream EQ /
   * crossfade chain. Boosting quiet recordings raises their noise floor and,
   * after later gain stages, hard-clips against full scale — that is exactly the
   * "sandy" distortion this strategy avoids. Set [allowBoost] to opt back into
   * full ReplayGain alignment (the user then accepts the quality trade-off).
   *
   * @param gainDb ReplayGain value in dB
   * @param peak Stored peak (0..1) of the source
   * @param peakProtection Whether to prevent clipping using the stored peak
   * @param allowBoost Permit gains above 0 dB. Off by default.
   */
  fun setGain(
    gainDb: Float?,
    peak: Float? = null,
    peakProtection: Boolean = true,
    allowBoost: Boolean = false
  ) {
    if (gainDb == null) {
      targetGainLinear = 1f
      return
    }
    val gainLinear = exp(gainDb * ln(10f) / 20f)
    // A missing / invalid peak must never be interpreted as permission to boost.
    val peakCeiling = if (peak != null && peak.isFinite() && peak > 0f) {
      1f / peak
    } else {
      Float.POSITIVE_INFINITY
    }
    // 10^(-1/20) ~= 0.891: -1 dBFS ceiling leaves room for EQ / crossfade.
    val headroomCeiling = if (allowBoost) Float.POSITIVE_INFINITY else 0.89125094f
    val ceiling = if (peakProtection) min(peakCeiling, headroomCeiling) else headroomCeiling
    targetGainLinear = min(gainLinear, ceiling).coerceAtLeast(0f)
  }

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    return when (inputAudioFormat.encoding) {
      C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
        // ~20ms time constant for click-free gain changes
        rampAlpha = 1f - exp(-1f / (0.02f * inputAudioFormat.sampleRate))
        inputAudioFormat
      }
      else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val output = replaceOutputBuffer(inputBuffer.remaining())
    when (inputAudioFormat.encoding) {
      C.ENCODING_PCM_FLOAT -> {
        while (inputBuffer.hasRemaining()) {
          val sample = inputBuffer.float * nextGain()
          output.putFloat(sample.coerceIn(-1f, 1f))
        }
      }
      C.ENCODING_PCM_16BIT -> {
        while (inputBuffer.hasRemaining()) {
          val sample = inputBuffer.short * nextGain()
          output.putShort(sample.coerceIn(-32768f, 32767f).toInt().toShort())
        }
      }
      else -> throw IllegalStateException("Unexpected encoding: ${inputAudioFormat.encoding}")
    }
    output.flip()
  }

  override fun onReset() {
    currentGainLinear = 1f
    rampAlpha = 0f
  }

  private fun nextGain(): Float {
    val target = targetGainLinear
    val current = currentGainLinear
    if (current == target) {
      return current
    }
    val next = if (rampAlpha > 0f) current + (target - current) * rampAlpha else target
    if (abs(target - next) < 1e-6f) {
      currentGainLinear = target
      return target
    }
    currentGainLinear = next
    return next
  }
}
