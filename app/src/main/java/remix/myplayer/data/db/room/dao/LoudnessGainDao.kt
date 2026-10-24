package remix.myplayer.data.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import remix.myplayer.data.db.room.entity.LoudnessGain

@Dao
interface LoudnessGainDao {

  @Query("SELECT * FROM LoudnessGain WHERE `key` = :key")
  suspend fun get(key: String): LoudnessGain?

  @Query("SELECT COUNT(*) FROM LoudnessGain")
  suspend fun count(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(gain: LoudnessGain)

  @Query("DELETE FROM LoudnessGain WHERE `key` = :key")
  suspend fun delete(key: String)

  @Query("DELETE FROM LoudnessGain")
  suspend fun deleteAll()
}
