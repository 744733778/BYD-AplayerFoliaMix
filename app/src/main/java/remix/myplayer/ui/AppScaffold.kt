package remix.myplayer.ui

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import remix.myplayer.data.prefs.LyricPrefs
import remix.myplayer.ui.nav.ExtraRestorePlayingScreen
import remix.myplayer.ui.nav.LocalNavController
import remix.myplayer.ui.nav.RouteFullscreenLyric
import remix.myplayer.ui.nav.RouteHome
import remix.myplayer.ui.screen.playing.PlayingPanel
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.viewmodel.PlayingScreenValue
import remix.myplayer.viewmodel.mainViewModel
import remix.myplayer.viewmodel.playbackViewModel
import kotlin.math.roundToInt

// 启动恢复：等「歌曲就绪」或「用户自己操作」的上限。
// 车机上恢复播放队列（Room + MediaStore + ExoPlayer prepare）常常要几秒，
// 给足时间；超时后保守处理，不误伤持久化意图。
private const val RESTORE_WAIT_TIMEOUT_MS = 8_000L

// 等播放页浮窗锚点就位的上限
private const val ANCHOR_READY_TIMEOUT_MS = 3_000L

/** 启动恢复期间观察到的状态快照 */
private data class RestoreOutcome(
  /** 歌曲是否已真正就绪（服务已恢复队列并 prepare 完成） */
  val songReady: Boolean = false,
  /** 用户是否已经自己展开了播放页 */
  val userExpandedPlaying: Boolean = false
)

@Composable
fun AppScaffold(content: @Composable () -> Unit) {
  val mainVM = mainViewModel
  val scope = rememberCoroutineScope()
  var screenHeight by remember { mutableFloatStateOf(10000f) }
  val playingScreenState = mainVM.playingScreenState
  var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
  val isVisible by remember {
    derivedStateOf {
      playingScreenState.progress(PlayingScreenValue.Hidden, PlayingScreenValue.Expanded) > 0.01f
    }
  }

  val nav = LocalNavController.current
  val lyricPrefs = mainVM.lyricPrefs
  // playbackViewModel 是 @Composable 访问器，必须在组合期取出后，才能在协程里用
  val playbackVM = playbackViewModel
  // 上次退出时所在的播放相关界面。必须在组合期先抓取：
  // 下面的观察者会立刻把「当前」界面写回，不能等到那时再读。
  val restoreSurface = remember { lyricPrefs.lastPlayingSurface }

  // 启动恢复：上次退出时若停在播放页 / 歌词特效页，且当前有歌在播，就自动回到那个界面。
  LaunchedEffect(Unit) {
    val surface = restoreSurface
    if (surface == LyricPrefs.SURFACE_NONE) {
      return@LaunchedEffect
    }
    // 不能用「固定延时后拍一次快照」判定有没有歌：
    // MusicStateSource 是进程级单例，进程被杀后 song 为 EMPTY_SONG；
    // 歌曲要等服务侧恢复队列（Room）→ setPlaylist → player.prepare() → onPrepare
    // → pushPlaybackUiState 才变有效，车机上常常是秒级，固定 400ms 必然来不及，
    // 于是误判「无歌」→ 不恢复界面，并把持久化意图清成 none（下次也不再恢复）。
    // 这正是「上次修过又复现」的根因：不是代码丢了，是判定太早 + 误把意图抹掉。
    //
    // 现在改成「等歌曲真正就绪」，并与「用户自己操作」赛跑：
    //   - 歌曲就绪且用户没动过 → 恢复上次所在界面
    //   - 用户已自己展开播放页 → 用户接管，只记录状态
    //   - 超时仍无歌 → 无法确认，保留持久化意图（绝不写 none），下次启动再试
    val outcome = withTimeoutOrNull(RESTORE_WAIT_TIMEOUT_MS) {
      combine(
        playbackVM.playbackUiState,
        snapshotFlow { playingScreenState.currentValue }
      ) { uiState, screenValue ->
        RestoreOutcome(
          songReady = uiState.song.valid(),
          userExpandedPlaying = screenValue == PlayingScreenValue.Expanded
        )
      }.first { it.songReady || it.userExpandedPlaying }
    }
    // 用户已经自己展开播放页 → 不再自动恢复，只把界面状态记为常规播放页
    if (outcome != null && outcome.userExpandedPlaying) {
      lyricPrefs.lastPlayingSurface = LyricPrefs.SURFACE_REGULAR
      return@LaunchedEffect
    }
    if (outcome == null || !outcome.songReady) {
      // 超时 / 没有歌曲：无法确认到底有没有歌，保留持久化意图，不抹掉，下次启动再试。
      // （这里绝不能写 SURFACE_NONE，否则恢复意图被永久抹掉）
      return@LaunchedEffect
    }
    // 歌曲已就绪 → 现在界面状态是明确的，可以安全地校正/恢复。
    // 仍在首页时才恢复，避免把播放页浮窗盖在设置/详情页上面。
    // route 为 null 说明 NavHost 还没建立好（启动初期），按首页处理。
    val route = nav.currentBackStackEntry?.destination?.route
    if (route != null && route != RouteHome) {
      // 已离开首页 → 用户自己在别的页面，把持久化值校正为当前实际状态
      if (!mainVM.onLyricScreen.value) {
        lyricPrefs.lastPlayingSurface = LyricPrefs.SURFACE_NONE
      }
      return@LaunchedEffect
    }
    if (surface == LyricPrefs.SURFACE_LYRIC) {
      // 回到全屏歌词特效界面（该界面本身全屏，浮窗保持收起）
      // 持久化由该界面自己写（DisposableEffect 里写 SURFACE_LYRIC），此处不重复写
      runCatching {
        nav.navigate(RouteFullscreenLyric)
        // 标记返回时恢复播放页浮窗，避免直接退回主界面
        nav.getBackStackEntry(RouteFullscreenLyric)
          .savedStateHandle[ExtraRestorePlayingScreen] = true
      }
    } else {
      // 回到常规播放页：等浮窗锚点就位再展开（加超时，避免锚点不就位时永久挂起）
      val anchored = withTimeoutOrNull(ANCHOR_READY_TIMEOUT_MS) {
        snapshotFlow { playingScreenState.anchors.hasPositionFor(PlayingScreenValue.Expanded) }
          .filter { it }
          .first()
        true
      } ?: false
      if (anchored) {
        // snapTo 会让 currentValue 变成 Expanded，下面的持久化观察者会自动写 SURFACE_REGULAR
        playingScreenState.snapTo(PlayingScreenValue.Expanded)
      } else {
        lyricPrefs.lastPlayingSurface = LyricPrefs.SURFACE_NONE
      }
    }
  }

  // 持久化「当前所在界面」：浮窗展开 = 常规播放页，收起 = 不在播放界面。
  // 全屏歌词界面由该界面自己写 lyric，这里跳过，
  // 否则会被浮窗的收起状态误写成 none（进该界面时浮窗正是收起的）。
  LaunchedEffect(Unit) {
    snapshotFlow { playingScreenState.currentValue }
      .drop(1)
      .collect { value ->
        if (mainVM.onLyricScreen.value) return@collect
        lyricPrefs.lastPlayingSurface =
          if (value == PlayingScreenValue.Expanded) LyricPrefs.SURFACE_REGULAR
          else LyricPrefs.SURFACE_NONE
      }
  }

  fun calculateRenderedOffset(): Float {
    val currentOffset = playingScreenState.offset.takeIf { !it.isNaN() } ?: screenHeight
    return currentOffset + (screenHeight - currentOffset) * predictiveBackProgress
  }

  fun calculateRenderedProgress(renderedOffset: Float = calculateRenderedOffset()): Float {
    return (1f - renderedOffset / screenHeight).coerceIn(0f, 1f)
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(LocalTheme.current.mainBackground)
      .onSizeChanged { size ->
        val newHeight = size.height.toFloat()
        if (screenHeight != newHeight) {
          screenHeight = newHeight
          playingScreenState.updateAnchors(
            DraggableAnchors {
              PlayingScreenValue.Hidden at newHeight
              PlayingScreenValue.Expanded at 0f
            },
            newTarget = playingScreenState.currentValue
          )
        }
      }
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .graphicsLayer {
          val renderedProgress = calculateRenderedProgress()
          translationY = -size.height * 0.2f * renderedProgress
          alpha = 1f - 0.5f * renderedProgress
        }) {
      content()
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .offset {
          val renderedOffset = calculateRenderedOffset()
          IntOffset(0, renderedOffset.roundToInt())
        }
        .anchoredDraggable(
          state = playingScreenState,
          orientation = Orientation.Vertical,
          enabled = isVisible,
          flingBehavior = AnchoredDraggableDefaults.flingBehavior(
            state = playingScreenState,
            animationSpec = spring(
              dampingRatio = Spring.DampingRatioLowBouncy,
              stiffness = Spring.StiffnessMediumLow
            )
          )
        )
        .graphicsLayer {
          val renderedProgress = calculateRenderedProgress()
          alpha = FastOutSlowInEasing.transform(renderedProgress)
          clip = renderedProgress > 0f
        }
    ) {
      PredictiveBackHandler(
        enabled = isVisible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
      ) { progress ->
        try {
          progress.collect { backEvent: BackEventCompat ->
            predictiveBackProgress = backEvent.progress
          }
          playingScreenState.animateTo(PlayingScreenValue.Hidden)
          predictiveBackProgress = 0f
        } catch (_: CancellationException) {
          predictiveBackProgress = 0f
        }
      }

      BackHandler(enabled = isVisible && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        scope.launch {
          playingScreenState.animateTo(PlayingScreenValue.Hidden)
        }
      }

      PlayingPanel(isVisible)
    }
  }
}
