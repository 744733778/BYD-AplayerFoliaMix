package remix.myplayer.ui.theme

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import remix.myplayer.data.model.audio.Song
import remix.myplayer.service.playback.MusicStateSource
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「主色调 / 强调色取自封面」的取色管线。
 *
 * 监听当前播放歌曲的变化 → 拉取封面位图 → 提取配色 → 写回运行时主题。
 * 只有用户开启了对应的开关时才会真正取色，平时几乎零开销。
 */
@Singleton
class CoverThemeExtractor @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val themeController: ThemeController
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private var watchJob: Job? = null

  // 取色令牌：切歌或手动刷新时自增，丢弃过期的取色结果
  private var token: Int = 0

  fun start() {
    if (watchJob != null) {
      return
    }
    watchJob = scope.launch {
      MusicStateSource.playbackUiState
        .map { it.song }
        .distinctUntilChangedBy { it.id }
        .collect { song ->
          applyFor(song)
        }
    }
  }

  /**
   * 开关状态变化后立即用当前歌曲重新取色，无需等到下一次切歌。
   */
  fun refresh() {
    scope.launch {
      applyFor(MusicStateSource.playbackUiState.value.song)
    }
  }

  private suspend fun applyFor(song: Song) {
    if (!themeController.primaryFromCover && !themeController.secondaryFromCover) {
      return
    }
    if (!song.valid()) {
      return
    }
    val currentToken = ++token
    val bitmap = loadCover(song)
    if (bitmap == null || currentToken != token) {
      return
    }
    val colors = withContext(Dispatchers.Default) {
      runCatching { CoverColorPalette.extract(bitmap) }.getOrNull()
    }
    if (colors == null || currentToken != token) {
      return
    }
    Timber.v("apply cover colors: ${colors.primary} / ${colors.secondary}")
    themeController.applyPrimaryFromCover(colors.primary)
    themeController.applySecondaryFromCover(colors.secondary)
  }

  private suspend fun loadCover(song: Song): Bitmap? {
    // Glide 的请求构建需在主线程，等待结果放到 IO 线程，避免阻塞主线程
    val future = withContext(Dispatchers.Main.immediate) {
      runCatching {
        Glide.with(context)
          .asBitmap()
          .load(song)
          .centerCrop()
          .submit(COVER_SIZE, COVER_SIZE)
      }.getOrNull()
    } ?: return null
    return withContext(Dispatchers.IO) {
      runCatching { future.get() }.getOrNull()
    }
  }

  companion object {
    private const val COVER_SIZE = 128
  }
}
