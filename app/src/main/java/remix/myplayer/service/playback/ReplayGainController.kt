package remix.myplayer.service.playback

import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import remix.myplayer.data.model.audio.ReplayGain
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.helper.AudioTagFile
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads ReplayGain tags and applies the resulting gain through the
 * [ReplayGainAudioProcessor] shared with the ExoPlayer audio sink.
 */
@OptIn(UnstableApi::class)
@Singleton
class ReplayGainController @Inject constructor(
  private val settingPrefs: SettingPrefs,
  private val loudnessGainStore: LoudnessGainStore,
) {

  val processor = ReplayGainAudioProcessor()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val cache = LruCache<String, ReplayGain>(512)

  private var requestId = 0L

  /**
   * Compute and apply the ReplayGain for [song]. Called on track transitions
   * and when the ReplayGain settings change.
   *
   * Resolution order for the per-track gain:
   * 1. ReplayGain tags read from the file (track/album gain + peak).
   * 2. If absent and auto-analysis is enabled, the on-device measured loudness
   *    ([LoudnessGainStore]) — computed on the fly if not yet cached.
   * 3. Otherwise the configured default gain for untagged audio.
   */
  fun applyFor(song: Song?) {
    val request = ++requestId
    if (song !is Song.Local || !settingPrefs.replayGainEnabled) {
      processor.setGain(null)
      return
    }
    cache.get(song.data)?.let {
      applyGain(it)
      return
    }
    processor.setGain(null)
    scope.launch {
      val effective = withContext(Dispatchers.IO) {
        computeEffectiveGain(song)
      }
      // cache the resolved result to avoid re-reading the file / re-analyzing on every transition
      cache.put(song.data, effective)
      if (request == requestId && settingPrefs.replayGainEnabled) {
        applyGain(effective)
      }
    }
  }

  private suspend fun computeEffectiveGain(song: Song): ReplayGain {
    val tag = runCatching { AudioTagFile.readReplayGain(File(song.data)) }.getOrNull()
    val hasTag = tag != null && (tag.trackGainDb != null || tag.albumGainDb != null)
    if (hasTag) return tag!!

    if (!settingPrefs.loudnessAutoAnalyzeEnabled) return ReplayGain()

    // No usable tag: fall back to the on-device measured loudness (mp3gain-style).
    val computed = runCatching { loudnessGainStore.analyzeIfNeeded(song) }.getOrNull()
    if (computed != null) {
      return ReplayGain(
        trackGainDb = computed.gainDb,
        albumGainDb = null,
        trackPeak = computed.peak,
        albumPeak = null,
      )
    }
    return ReplayGain()
  }

  private fun applyGain(gain: ReplayGain?) {
    val preferAlbumGain = settingPrefs.replayGainMode == SettingPrefs.REPLAY_GAIN_MODE_ALBUM
    val selectedAlbumGain = if (preferAlbumGain) {
      gain?.albumGainDb != null
    } else {
      gain?.trackGainDb == null && gain?.albumGainDb != null
    }
    val tagGainDb = if (selectedAlbumGain) gain?.albumGainDb else gain?.trackGainDb
    val gainDb = tagGainDb?.plus(settingPrefs.replayGainPreampDb)
      ?: settingPrefs.replayGainMissingGainDb
    val peak = if (selectedAlbumGain) gain?.albumPeak else gain?.trackPeak
    processor.setGain(gainDb, peak, settingPrefs.replayGainPeakProtection)
  }
}
