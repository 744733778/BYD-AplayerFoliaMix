package remix.myplayer.ui.screen.lyric

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import remix.myplayer.R
import remix.myplayer.data.prefs.LyricPrefs
import remix.myplayer.lyric.LyricLine
import remix.myplayer.service.Command
import remix.myplayer.service.playback.MusicStateSource
import remix.myplayer.ui.nav.ExtraRestorePlayingScreen
import remix.myplayer.ui.nav.LocalNavController
import remix.myplayer.ui.nav.RouteLyricMatch
import remix.myplayer.ui.widget.lyric.folia.EffectMode
import remix.myplayer.ui.widget.lyric.folia.FoliaLyricCanvasView
import remix.myplayer.ui.widget.lyric.folia.Palette
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.util.MusicUtil.makeCmdIntent
import remix.myplayer.util.Util.sendLocalBroadcast
import remix.myplayer.viewmodel.PlayingScreenValue
import remix.myplayer.viewmodel.mainViewModel
import remix.myplayer.viewmodel.playbackViewModel
import remix.myplayer.viewmodel.settingViewModel
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement

/**
 * 全屏歌词页：Canvas 渲染（[FoliaLyricCanvasView]，Folia 风格逐字扫光）。
 * 右下角一个主按钮，点击弹出控制栏：上一曲 / 播放暂停 / 下一曲 / 特效切换 / 联网匹配。
 * 从播放页进入时（[ExtraRestorePlayingScreen]），返回键先恢复播放页浮窗再出栈。
 */
@Composable
fun FullscreenLyricScreen() {
  val context = LocalContext.current
  val nav = LocalNavController.current
  val settingVM = settingViewModel
  val playbackVM = playbackViewModel
  val lyricManager = settingVM.lyricManager
  val mainVM = mainViewModel
  val scope = rememberCoroutineScope()

  val playbackState by playbackVM.playbackUiState.collectAsStateWithLifecycle()
  val settingState by settingVM.settingsState.collectAsStateWithLifecycle()
  val song = playbackState.song
  val currentSong by rememberUpdatedState(song)

  // 歌词通过 LyricManager 的响应式流刷新：切歌/联网匹配完成/手动应用都会自动更新，
  // 不再依赖进入页面那一刻的读取，修复“联网匹配后不刷新、需返回重进”的问题。
  val lyricsFromManager by lyricManager.lyricsFlow.collectAsStateWithLifecycle()
  var offset by remember(song.id) { mutableLongStateOf(0L) }
  var panelOpen by remember { mutableStateOf(false) }
  var coverBitmap by remember(song.id) { mutableStateOf<Bitmap?>(null) }
  val lyrics = lyricsFromManager ?: emptyList()
  val effectModes = EffectMode.entries
  // 特效选择持久化：以上次退出时用的特效为准（用 name 存，模式增删不会错位）
  val lyricPrefs = mainVM.lyricPrefs
  val initialEffectIndex = remember {
    EffectMode.entries.indexOfFirst { it.name == lyricPrefs.lyricEffectName }
      .let { if (it < 0) 0 else it }
  }
  var effectIndex by remember { mutableIntStateOf(initialEffectIndex) }
  val effectMode = effectModes[effectIndex % effectModes.size]

  // 特效切换后立刻落盘
  LaunchedEffect(effectMode) {
    lyricPrefs.lyricEffectName = effectMode.name
  }

  // 标记「当前在全屏歌词界面」：播放页浮窗此时是收起的，无法用浮窗状态判断。
  // 退出时把标记清掉，界面状态由播放页浮窗的观察者接管。
  DisposableEffect(Unit) {
    mainVM.onLyricScreen.value = true
    lyricPrefs.lastPlayingSurface = LyricPrefs.SURFACE_LYRIC
    onDispose {
      mainVM.onLyricScreen.value = false
      // 退出时播放页浮窗会恢复展开 → 界面状态回到常规播放页；
      // 若是从这里跳去别的页面（浮窗仍收起），则记为不在播放界面。
      lyricPrefs.lastPlayingSurface =
        if (mainVM.playingScreenState.currentValue == PlayingScreenValue.Expanded)
          LyricPrefs.SURFACE_REGULAR
        else LyricPrefs.SURFACE_NONE
    }
  }

  // 从播放页进入：返回时先恢复播放页浮窗再出栈，避免闪现主界面
  val navEntry by nav.currentBackStackEntryAsState()
  BackHandler {
    scope.launch {
      if (navEntry?.savedStateHandle?.get<Boolean>(ExtraRestorePlayingScreen) == true) {
        mainVM.playingScreenState.snapTo(PlayingScreenValue.Expanded)
      }
      nav.popBackStack()
    }
  }

  LaunchedEffect(song.id) {
    if (!currentSong.valid()) return@LaunchedEffect
    // 触发歌词加载（本地优先 → 无歌词时多源自动联网匹配）。
    // 加载结果由 lyricManager.lyricsFlow 反应式刷新，无需在此等待。
    lyricManager.updateLyrics(currentSong, null)
    offset = lyricManager.offset
  }

  // 封面位图：交给渲染器做模糊背景
  LaunchedEffect(song.id, song.data) {
    if (!currentSong.valid()) return@LaunchedEffect
    coverBitmap = withContext(Dispatchers.IO) {
      runCatching {
        Glide.with(context)
          .asBitmap()
          .load(currentSong)
          .submit(256, 256)
          .get()
      }.getOrNull()
    }
  }

  val bg = LocalTheme.current.mainBackground
  val palette = Palette(
    active = LocalTheme.current.primary.toArgb(),
    activeSecondary = LocalTheme.current.secondary.toArgb(),
    sung = LocalTheme.current.textPrimary.toArgb(),
    unsung = LocalTheme.current.textSecondary.toArgb(),
    translation = LocalTheme.current.textSecondary.toArgb(),
    bgTop = bg.toArgb(),
    bgBottom = darken(bg.toArgb(), 0.18f),
    particle = LocalTheme.current.primary.toArgb(),
  )

  val iconTint = LocalTheme.current.textPrimary
  val hint = if (lyrics.isEmpty()) "正在联网匹配歌词…" else ""
  val bgPanel = Color.Black.copy(alpha = 0.45f)

  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      factory = { ctx ->
        FoliaLyricCanvasView(ctx).apply {
          // 屏显位置带歌词偏移，seek 时要把它减回去
          onSeek = { playbackVM.setProgress(it - offset) }
        }
      },
      update = { view ->
        view.lyrics = lyrics
        view.fontScale = settingState.lyric.fontScale
        view.effectMode = effectMode
        view.hintText = hint
        view.coverBitmap = coverBitmap
        view.positionProvider = {
          MusicStateSource.currentProgressState.position + offset
        }
        view.playingProvider = { playbackState.isPlaying }
        view.palette = palette
        view.onPlaybackStateChanged()
      },
      modifier = Modifier.fillMaxSize()
    )

    // 控制栏（展开时显示在底部）：上一曲 / 播放暂停 / 下一曲 / 特效 / 匹配
    if (panelOpen) {
      Row(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 48.dp)
          .clip(RoundedCornerShape(50))
          .background(bgPanel)
          .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        CtrlIcon(
          iconRes = R.drawable.play_btn_pre,
          tint = iconTint,
          onClick = { sendLocalBroadcast(makeCmdIntent(Command.SKIP_TO_PREVIOUS)) }
        )
        CtrlIcon(
          iconRes = if (playbackState.isPlaying) R.drawable.ic_pause_black_24dp else R.drawable.ic_play_arrow_black_24dp,
          tint = iconTint,
          size = 30.dp,
          onClick = { sendLocalBroadcast(makeCmdIntent(Command.PLAY_PAUSE)) }
        )
        CtrlIcon(
          iconRes = R.drawable.play_btn_next,
          tint = iconTint,
          onClick = { sendLocalBroadcast(makeCmdIntent(Command.SKIP_TO_NEXT)) }
        )
        CtrlIcon(
          iconRes = R.drawable.ic_palette_24dp,
          tint = iconTint,
          label = effectMode.label,
          onClick = { effectIndex = (effectIndex + 1) % effectModes.size }
        )
        CtrlIcon(
          iconRes = R.drawable.ic_search_white_24dp,
          tint = iconTint,
          onClick = { nav.navigate(RouteLyricMatch) }
        )
      }
    }

    // 左下角：封面小图标 + 歌名（参考 folia；不显示歌手，且整体上移避开车机状态栏）
    if (song.valid()) {
      Column(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(start = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
      ) {
        val cover = coverBitmap
        if (cover != null && !cover.isRecycled) {
          Image(
            bitmap = cover.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .size(56.dp)
              .clip(RoundedCornerShape(10.dp))
          )
        } else {
          Box(
            Modifier
              .size(56.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(bgPanel)
          )
        }
        Text(
          text = song.title,
          color = iconTint,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }

    // 右下角主按钮：切换控制栏（透明背景，避免黑色圆按键；整体上移避开车机状态栏）
    IconButton(
      onClick = { panelOpen = !panelOpen },
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(bottom = 48.dp, end = 16.dp)
        .size(52.dp)
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_more_vert_white_24dp),
        contentDescription = null,
        tint = iconTint
      )
    }
  }
}

/** 把颜色按比例调暗（用于背景渐变底端，增强层次）。 */
private fun darken(color: Int, fraction: Float): Int {
  val f = 1f - fraction.coerceIn(0f, 1f)
  return android.graphics.Color.argb(
    android.graphics.Color.alpha(color),
    (android.graphics.Color.red(color) * f).toInt(),
    (android.graphics.Color.green(color) * f).toInt(),
    (android.graphics.Color.blue(color) * f).toInt()
  )
}

@Composable
private fun CtrlIcon(
  iconRes: Int,
  tint: Color,
  size: androidx.compose.ui.unit.Dp = 24.dp,
  label: String? = null,
  onClick: () -> Unit
) {
  IconButton(
    onClick = onClick,
    modifier = Modifier.size(if (label != null) 64.dp else 48.dp)
  ) {
    if (label != null) {
      androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
          painter = painterResource(iconRes),
          contentDescription = null,
          tint = tint,
          modifier = Modifier.size(20.dp)
        )
        Text(label, color = tint, fontSize = 9.sp)
      }
    } else {
      Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size)
      )
    }
  }
}
