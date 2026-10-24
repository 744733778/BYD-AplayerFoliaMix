package remix.myplayer.service.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import remix.myplayer.data.model.audio.Song
import remix.myplayer.repo.SongRepository
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class LoudnessScanStatus {
  Idle,
  Scanning,
  Done,
  Cancelled,
}

data class LoudnessScanState(
  val status: LoudnessScanStatus = LoudnessScanStatus.Idle,
  val total: Int = 0,
  val processed: Int = 0,
  val currentTitle: String = "",
  val analyzedThisRun: Int = 0,
  val failedThisRun: Int = 0,
)

/**
 * Drives the on-device loudness scan over the whole library. Re-analyzes only songs that
 * have no valid cached result, so it is resumable: interrupting and re-running just finishes
 * the remaining tracks. Emits progress through [state].
 */
@Singleton
class LoudnessScanManager @Inject constructor(
  private val songRepo: SongRepository,
  private val store: LoudnessGainStore,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _state = MutableStateFlow(LoudnessScanState())
  val state = _state.asStateFlow()

  private val cancelled = AtomicBoolean(false)

  val isScanning: Boolean
    get() = _state.value.status == LoudnessScanStatus.Scanning

  fun scanAll(maxAnalyzeMs: Long = 90_000L) {
    if (isScanning) return
    cancelled.set(false)
    scope.launch {
      try {
        val songs = songRepo.allSongs()
          .filterIsInstance<Song.Local>()
          .filter { it.data.isNotEmpty() && runCatching { File(it.data).canRead() }.getOrDefault(false) }
        val total = songs.size
        _state.value = LoudnessScanState(status = LoudnessScanStatus.Scanning, total = total)
        var processed = 0
        var analyzed = 0
        var failed = 0
        for (song in songs) {
          if (cancelled.get()) {
            _state.value = _state.value.copy(status = LoudnessScanStatus.Cancelled)
            break
          }
          if (store.getForSong(song) != null) {
            processed++
            _state.value = _state.value.copy(processed = processed)
            continue
          }
          _state.value = _state.value.copy(
            processed = processed,
            currentTitle = song.title,
          )
          val result = store.analyzeIfNeeded(song, maxAnalyzeMs)
          if (result != null) analyzed++ else failed++
          processed++
          _state.value = _state.value.copy(
            processed = processed,
            analyzedThisRun = analyzed,
            failedThisRun = failed,
          )
        }
        if (_state.value.status == LoudnessScanStatus.Scanning) {
          _state.value = _state.value.copy(status = LoudnessScanStatus.Done)
        }
      } catch (e: Exception) {
        Timber.w(e, "Loudness scan failed")
        _state.value = _state.value.copy(status = LoudnessScanStatus.Done)
      }
    }
  }

  fun cancel() {
    cancelled.set(true)
  }

  suspend fun analyzedCount(): Int = store.count()

  suspend fun clearAll() {
    store.clearAll()
  }
}
