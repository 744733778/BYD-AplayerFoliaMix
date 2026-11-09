package remix.myplayer.service.playback

import android.content.Context
import com.bumptech.glide.Glide
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.lyric.LyricSearcher
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放满 [TRIGGER_POSITION_MS] 后，预载下一曲的歌词 / 封面 / 响度，
 * 让自然切歌时不必再临时读文件、解析歌词、扫描响度，从而减少切歌瞬间的卡顿。
 *
 * 设计要点：
 * - 自带 1s 轮询，不依赖亮屏进度推送（updateProgressTicker 在熄屏时不跑），
 *   也不依赖「断点播放」开关（PlaybackProgressSaver 只在开关打开时启动）。
 * - 每首歌只触发一次；切歌自动重置，60s 前手动切歌仍走原有即时加载路径，不会更慢。
 * - 三项预载彼此隔离、全部静默兜底：任一项失败不影响播放，也不影响其余两项。
 * - 全部在后台协程执行，不占用主线程与播放线程。
 *
 * 预载之所以能生效，是因为三处消费点本身都带缓存：
 * - 歌词：[LyricSearcher.getLyricsAndOffset] 会把解析结果写入缓存文件，
 *   切歌时 [remix.myplayer.lyric.LyricManager.updateLyrics] 直接命中。
 * - 封面：Glide 以 Song 为 key 缓存位图，预载尺寸与全屏歌词页一致以保证缓存键相同。
 * - 响度：[LoudnessGainStore.analyzeIfNeeded] 命中已存库结果时直接返回，不重复扫描。
 */
@Singleton
class NextSongPrefetcher @Inject constructor(
  @param:ApplicationContext
  private val context: Context,
  private val lyricSearcher: LyricSearcher,
  private val loudnessGainStore: LoudnessGainStore,
  private val settingPrefs: SettingPrefs,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var tickerJob: Job? = null
  private var prefetchJob: Job? = null

  /** 上一次轮询看到的歌曲标识，用于检测切歌 */
  @Volatile
  private var lastSongKey: String? = null

  /** 已经为哪首歌触发过预载，保证每曲只触发一次 */
  @Volatile
  private var prefetchedKey: String? = null

  /**
   * 启动轮询。三个 provider 都是轻量的即时读取（不做事、不切线程），由调用方保证
   * playback 未初始化时返回安全值。
   */
  fun start(
    positionProvider: () -> Long,
    songProvider: () -> Song?,
    nextProvider: () -> Song?,
  ) {
    if (tickerJob?.isActive == true) return
    tickerJob = scope.launch {
      while (isActive) {
        try {
          val song = songProvider()
          val key = keyOf(song)
          if (key != lastSongKey) {
            // 切歌：重置本曲预载状态，并取消上一曲尚未完成的预载
            lastSongKey = key
            prefetchedKey = null
            prefetchJob?.cancel()
          }
          if (song != null && key != null && song.valid() &&
            prefetchedKey != key && positionProvider() >= TRIGGER_POSITION_MS
          ) {
            val next = nextProvider()
            if (next != null && next.valid() && keyOf(next) != key) {
              // 先置位再启动，避免 tick 期间重复触发
              prefetchedKey = key
              prefetchJob = scope.launch { prefetch(next) }
            }
          }
        } catch (t: Throwable) {
          Timber.tag(TAG).w(t, "prefetch tick failed")
        }
        delay(CHECK_INTERVAL_MS)
      }
    }
  }

  fun stop() {
    tickerJob?.cancel()
    prefetchJob?.cancel()
    tickerJob = null
    prefetchJob = null
    lastSongKey = null
    prefetchedKey = null
  }

  private suspend fun prefetch(next: Song) {
    Timber.tag(TAG).d("prefetch next song: ${next.title}")

    // 1) 歌词：解析并落缓存，切歌时 updateLyrics 直接命中
    runCatching { lyricSearcher.getLyricsAndOffset(next, null) }
      .onFailure { Timber.tag(TAG).w(it, "prefetch lyrics failed") }

    // 2) 封面：预热 Glide 位图缓存，尺寸与全屏歌词页一致
    runCatching {
      Glide.with(context)
        .asBitmap()
        .load(next)
        .submit(COVER_SIZE, COVER_SIZE)
        .get(COVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }.onFailure { Timber.tag(TAG).w(it, "prefetch cover failed") }

    // 3) 响度：仅本地歌曲且开启自动分析时才有意义；已缓存的结果会自动跳过
    if (next.isLocal() && settingPrefs.replayGainEnabled &&
      settingPrefs.loudnessAutoAnalyzeEnabled
    ) {
      runCatching { loudnessGainStore.analyzeIfNeeded(next) }
        .onFailure { Timber.tag(TAG).w(it, "prefetch loudness failed") }
    }
  }

  /** Song.Local / Remote 不是 data class，用稳定字段做标识避免依赖引用相等 */
  private fun keyOf(song: Song?): String? = song?.let { "${it.id}:${it.data}" }

  private companion object {
    const val TAG = "NextSongPrefetcher"

    /** 播放满 1 分钟即认为进入稳定收听，可以放心占用后台 IO */
    const val TRIGGER_POSITION_MS = 60_000L
    const val CHECK_INTERVAL_MS = 1_000L

    /** 与 FullscreenLyricScreen 的封面请求保持一致 */
    const val COVER_SIZE = 256
    const val COVER_TIMEOUT_SECONDS = 3L
  }
}
