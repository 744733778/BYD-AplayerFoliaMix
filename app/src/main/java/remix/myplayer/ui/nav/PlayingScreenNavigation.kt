package remix.myplayer.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.gestures.snapTo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import remix.myplayer.viewmodel.PlayingScreenValue
import remix.myplayer.viewmodel.mainViewModel

/**
 * 播放页是覆盖在 NavHost 之上的全屏浮窗，直接 navigate 会被它盖住
 * （表现为「点了没反应，按一下返回才进入目标页面」）。
 *
 * 正确流程：先跳转 → 等目标页面进入 RESUMED（组合完成）→ 收起浮窗；
 * 目标页面出栈销毁后再展开浮窗，回到跳转前的状态。
 * 目标页通过 [ExtraRestorePlayingScreen] 标记是否由播放页进入。
 *
 * EQ / 歌词匹配等入口原来各自内联了这段逻辑，这里统一成一个可组合的导航回调。
 */
@Composable
fun rememberNavigateFromPlayingScreen(): (String) -> Unit {
  val nav = LocalNavController.current
  val mainVM = mainViewModel
  val scope = rememberCoroutineScope()
  return remember(nav, mainVM) {
    { route: String ->
      scope.launch {
        nav.navigate(route)
        val entry = runCatching { nav.getBackStackEntry(route) }.getOrNull() ?: return@launch
        // 标记从播放页跳转，目标页退出时据此判断是否需要恢复浮窗
        entry.savedStateHandle[ExtraRestorePlayingScreen] = true
        entry.lifecycle.currentStateFlow.first {
          it == Lifecycle.State.RESUMED || it == Lifecycle.State.DESTROYED
        }
        if (entry.lifecycle.currentState == Lifecycle.State.DESTROYED) {
          return@launch
        }
        mainVM.playingScreenState.snapTo(PlayingScreenValue.Hidden)
        entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.DESTROYED }
        mainVM.playingScreenState.snapTo(PlayingScreenValue.Expanded)
      }
    }
  }
}
