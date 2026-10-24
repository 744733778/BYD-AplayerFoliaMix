package remix.myplayer.service.playback

import remix.myplayer.data.db.room.AppDatabase
import remix.myplayer.data.db.room.entity.LoudnessGain
import remix.myplayer.data.model.audio.Song
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists on-device loudness analysis results ([LoudnessGain]) and serves them to the
 * ReplayGain pipeline. Results are keyed by the song's file path and validated against the
 * file's size / last-modified time so a stale entry is recomputed when the file changes.
 */
@Singleton
class LoudnessGainStore @Inject constructor(
  private val db: AppDatabase,
) {

  /** Return a valid cached result for [song], or null if missing or stale. */
  suspend fun getForSong(song: Song): LoudnessGain? {
    if (song !is Song.Local) return null
    val cached = runCatching { db.loudnessGainDao().get(song.data) }.getOrNull() ?: return null
    if (cached.fileSize != song.size || cached.lastModified != song.dateModified) {
      Timber.d("LoudnessGain stale for ${song.data}, recompute")
      return null
    }
    return cached
  }

  /** Analyze [song] if no valid result exists, persist it, and return it. */
  suspend fun analyzeIfNeeded(song: Song, maxAnalyzeMs: Long = 45_000L): LoudnessGain? {
    if (song !is Song.Local) return null
    val existing = getForSong(song)
    if (existing != null) return existing
    val result = LoudnessAnalyzer.analyze(java.io.File(song.data), maxAnalyzeMs) ?: return null
    val entity = LoudnessGain(
      key = song.data,
      gainDb = result.gainDb,
      peak = result.peak,
      sampleRate = result.sampleRate,
      durationMs = result.durationMs,
      fileSize = song.size,
      lastModified = song.dateModified,
    )
    runCatching { db.loudnessGainDao().insert(entity) }.onFailure {
      Timber.w(it, "Failed to persist LoudnessGain for ${song.data}")
    }
    return entity
  }

  suspend fun count(): Int = runCatching { db.loudnessGainDao().count() }.getOrDefault(0)

  suspend fun clearAll() {
    runCatching { db.loudnessGainDao().deleteAll() }
  }
}
