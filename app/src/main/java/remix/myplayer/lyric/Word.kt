package remix.myplayer.lyric

import kotlinx.serialization.Serializable

@Serializable
data class Word(
  val time: Long, // in ms，词起始
  val content: String,
  /** 词结束时间（ms）。仅 KRC 等带精确起止的来源提供；null 表示未知，按词间距退化。 */
  val end: Long? = null,
)
