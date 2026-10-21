package remix.myplayer.lyric.manual

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remix.myplayer.R
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.model.misc.LyricOrder
import remix.myplayer.lyric.LrcParser
import remix.myplayer.lyric.LyricLine
import remix.myplayer.lyric.provider.SearchScorer
import remix.myplayer.request.kugou.KuGouClient
import remix.myplayer.request.kugou.KuGouSong
import remix.myplayer.request.netease.NetEaseClient
import remix.myplayer.request.netease.NetEaseSong
import remix.myplayer.request.qq.QQClient
import remix.myplayer.request.qq.QQSong
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手动联网匹配歌词的统一候选条目。
 * [raw] 持有各平台原始歌曲对象（NetEaseSong / KuGouSong / QQSong），由本类解包。
 */
data class ManualLyricCandidate(
  val providerId: String,
  val displayName: String,
  val title: String,
  val artist: String,
  val album: String?,
  val durationMs: Long?,
  val score: Float,
  val raw: Any,
) {
  companion object {
    const val PROVIDER_NETEASE = "Netease"
    const val PROVIDER_KUGOU = "Kugou"
    const val PROVIDER_QQ = "Qq"
  }
}

/**
 * 手动联网匹配歌词：跨平台（网易云 / 酷狗 / QQ）搜索、按相似度评分排序，
 * 选中后自动回退（候选无词则依次尝试同站下一条、再尝试其它平台）。
 */
@Singleton
class LyricManualSearcher @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val netEaseClient: NetEaseClient,
  private val kuGouClient: KuGouClient,
  private val qqClient: QQClient,
) {

  enum class SourceFilter(val id: String, val order: Int) {
    ALL("all", 0),
    NETEASE(ManualLyricCandidate.PROVIDER_NETEASE, 1),
    KUGOU(ManualLyricCandidate.PROVIDER_KUGOU, 2),
    QQ(ManualLyricCandidate.PROVIDER_QQ, 3);

    val labelRes: Int
      get() = when (this) {
        ALL -> R.string.lyric_source_all
        NETEASE -> LyricOrder.Netease.stringRes
        KUGOU -> LyricOrder.Kugou.stringRes
        QQ -> LyricOrder.Qq.stringRes
      }

    companion object {
      fun fromId(id: String?): SourceFilter = entries.firstOrNull { it.id == id } ?: ALL
    }
  }

  /**
   * 跨平台搜索，返回按评分降序排列的候选。单平台失败不影响其它平台。
   */
  suspend fun search(
    target: Song,
    keyword: String,
    filter: SourceFilter,
  ): List<ManualLyricCandidate> = withContext(Dispatchers.IO) {
    if (keyword.isBlank()) return@withContext emptyList()
    val out = ArrayList<ManualLyricCandidate>()

    if (filter == SourceFilter.ALL || filter == SourceFilter.NETEASE) {
      runCatching { netEaseClient.searchSongList(keyword) }
        .onFailure { Timber.w(it, "netease search failed") }
        .getOrNull()
        .orEmpty()
        .forEach { song ->
          val artist = song.ar?.joinToString(", ") { it.name ?: "" } ?: ""
          out += score(target, ManualLyricCandidate.PROVIDER_NETEASE, song.name ?: "",
            artist, song.al?.name, song.dt, song)
        }
    }

    if (filter == SourceFilter.ALL || filter == SourceFilter.KUGOU) {
      runCatching { kuGouClient.searchSongList(keyword) }
        .onFailure { Timber.w(it, "kugou search failed") }
        .getOrNull()
        .orEmpty()
        .forEach { song ->
          out += score(target, ManualLyricCandidate.PROVIDER_KUGOU, song.title,
            song.artists.joinToString(", "), song.album, song.durationMs, song)
        }
    }

    if (filter == SourceFilter.ALL || filter == SourceFilter.QQ) {
      runCatching { qqClient.searchSongList(keyword) }
        .onFailure { Timber.w(it, "qq search failed") }
        .getOrNull()
        .orEmpty()
        .forEach { song ->
          out += score(target, ManualLyricCandidate.PROVIDER_QQ, song.title,
            song.artist.joinToString(", "), song.album, song.duration, song)
        }
    }

    out.sortedByDescending { it.score }
  }

  private fun score(
    target: Song,
    providerId: String,
    title: String,
    artist: String,
    album: String?,
    durationMs: Long?,
    raw: Any,
  ): ManualLyricCandidate {
    val result = SearchScorer.calculateSongScore(target, title, artist, album, durationMs)
    return ManualLyricCandidate(
      providerId = providerId,
      displayName = providerName(providerId),
      title = title,
      artist = artist,
      album = album,
      durationMs = durationMs,
      score = result.score,
      raw = raw,
    )
  }

  private fun providerName(id: String): String = when (id) {
    ManualLyricCandidate.PROVIDER_NETEASE -> context.getString(LyricOrder.Netease.stringRes)
    ManualLyricCandidate.PROVIDER_KUGOU -> context.getString(LyricOrder.Kugou.stringRes)
    ManualLyricCandidate.PROVIDER_QQ -> context.getString(LyricOrder.Qq.stringRes)
    else -> id
  }

  /**
   * 从候选提取自带封面 URL（目前网易云候选直接带专辑图；QQ/酷狗需回退到按歌曲搜索）。
   */
  fun coverUrlFromCandidate(candidate: ManualLyricCandidate): String? {
    if (candidate.providerId == ManualLyricCandidate.PROVIDER_NETEASE) {
      return (candidate.raw as? NetEaseSong)?.al?.picUrl
    }
    return null
  }

  /**
   * 选中候选后取词：先试该候选，为空则在同一批候选里自动回退，
   * 直到拿到非空歌词。返回解析后的歌词行与实际命中的 providerId。
   */
  suspend fun fetchWithFallback(
    orderedCandidates: List<ManualLyricCandidate>,
    preferred: ManualLyricCandidate,
  ): Pair<ArrayList<LyricLine>, String> = withContext(Dispatchers.IO) {
    // 先放首选，再补其余候选（同站优先，最后其它站），去重
    val chain = linkedSetOf<ManualLyricCandidate>()
    chain += preferred
    val sameSource = orderedCandidates.filter { it.providerId == preferred.providerId && it != preferred }
    val otherSource = orderedCandidates.filter { it.providerId != preferred.providerId }
    chain += sameSource
    chain += otherSource

    for (candidate in chain) {
      val parsed = runCatching { fetchRaw(candidate) }
        .onFailure { Timber.w(it, "fetch lyric failed: ${candidate.title}") }
        .getOrNull()
      if (!parsed.isNullOrEmpty()) {
        return@withContext parsed to candidate.providerId
      }
    }
    throw IllegalStateException("no lyric available after fallback")
  }

  /**
   * 预览：仅取该候选的歌词（不回退），失败或为空返回空列表。
   */
  suspend fun fetchPreview(
    candidate: ManualLyricCandidate,
  ): List<LyricLine> = withContext(Dispatchers.IO) {
    runCatching { fetchRaw(candidate) }
      .onFailure { Timber.w(it, "preview lyric failed: ${candidate.title}") }
      .getOrNull()
      .orEmpty()
  }

  private fun fetchRaw(candidate: ManualLyricCandidate): ArrayList<LyricLine>? {
    val (main, translation) = when (candidate.providerId) {
      ManualLyricCandidate.PROVIDER_NETEASE ->
        netEaseClient.getLyrics(candidate.raw as NetEaseSong)
      ManualLyricCandidate.PROVIDER_KUGOU ->
        kuGouClient.getLyrics(candidate.raw as KuGouSong)
      ManualLyricCandidate.PROVIDER_QQ ->
        qqClient.getLyrics(candidate.raw as QQSong)
      else -> return null
    }
    if (main.isNullOrBlank()) return null
    val combined = if (!translation.isNullOrBlank()) {
      main.trimEnd() + "\n" + translation.trimStart()
    } else {
      main
    }
    val lines = LrcParser.parse(combined)
    return lines.ifEmpty { null }
  }
}
