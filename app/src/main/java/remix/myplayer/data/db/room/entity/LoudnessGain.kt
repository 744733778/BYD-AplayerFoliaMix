package remix.myplayer.data.db.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-track loudness analysis result, computed on-device (mp3gain / ReplayGain style).
 *
 * The [key] is the song's file path. [fileSize] and [lastModified] are stored so a cached
 * result can be invalidated when the underlying file changes. [gainDb] is the track gain
 * (dB) that brings the song to the target loudness; [peak] is the measured peak (0..1) used
 * for clipping protection.
 */
@Entity(tableName = LoudnessGain.TABLE_NAME)
data class LoudnessGain(
  @PrimaryKey
  val key: String,
  val gainDb: Float,
  val peak: Float,
  val sampleRate: Int,
  val durationMs: Long,
  val fileSize: Long,
  val lastModified: Long,
  val updateTime: Long = System.currentTimeMillis()
) {
  companion object {
    const val TABLE_NAME = "LoudnessGain"
  }
}
