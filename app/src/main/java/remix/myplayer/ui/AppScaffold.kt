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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import remix.myplayer.data.prefs.LyricPrefs
import remix.myplayer.ui.nav.ExtraRestorePlayingScreen
import remix.myplayer.ui.nav.LocalNavController
import remix.myplayer.ui.nav.RouteFullscreenLyric
import remix.myplayer.ui.screen.playing.PlayingPanel
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.viewmodel.PlayingScreenValue
import remix.myplayer.viewmodel.mainViewModel
import remix.myplayer.viewmodel.playbackViewModel
import kotlin.math.roundToInt

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
    // 等 NavHost 与浮窗锚点就绪
    delay(400)
    val surface = restoreSurface
    val hasSong = playbackVM.playbackUiState.value.song.valid()
    if (surface != LyricPrefs.SURFACE_NONE && hasSong) {
      if (surface == LyricPrefs.SURFACE_LYRIC) {
        // 回到全屏歌词特效界面（该界面本身全屏，浮窗保持收起）
        runCatching {
          nav.navigate(RouteFullscreenLyric)
          // 标记返回时恢复播放页浮窗，避免直接退回主界面
          nav.getBackStackEntry(RouteFullscreenLyric)
            .savedStateHandle[ExtraRestorePlayingScreen] = true
        }
      } else {
        // 回到常规播放页：等浮窗锚点就位再展开
        snapshotFlow { playingScreenState.anchors.hasPositionFor(PlayingScreenValue.Expanded) }
          .filter { it }
          .first()
        playingScreenState.snapTo(PlayingScreenValue.Expanded)
      }
    } else {
      // 不恢复：把界面状态校正为当前实际所在
      lyricPrefs.lastPlayingSurface = LyricPrefs.SURFACE_NONE
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
