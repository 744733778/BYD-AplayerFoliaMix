package remix.myplayer.ui.screen.playing

import androidx.compose.foundation.Image
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import remix.myplayer.R
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.prefs.SettingPrefs.Companion.MODE_SHUFFLE
import remix.myplayer.service.Command
import remix.myplayer.service.MusicService.Companion.EXTRA_POSITION
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.ui.widget.common.TextPrimary
import remix.myplayer.ui.widget.common.TextSecondary
import remix.myplayer.util.MusicUtil.makeCmdIntent
import remix.myplayer.util.Util.sendLocalBroadcast
import remix.myplayer.util.ext.CenterInBox
import remix.myplayer.util.ext.clickWithRipple
import remix.myplayer.viewmodel.playbackViewModel
import kotlin.math.max

/** 队列中每一行的高度（紧凑排版，一屏可以显示更多歌曲） */
private val QUEUE_ROW_HEIGHT = 42.dp

/** 判定为「翻屏」的最小水平拖动距离 */
private val PAGE_SWIPE_THRESHOLD = 20.dp

/** 翻屏动画时长 */
private val PAGE_SWIPE_DURATION = 280

/**
 * 播放队列面板。
 *
 * 显示在播放页右侧（竖屏为歌词页）：
 * - 上下滑动 = 连续滚动整个播放队列；
 * - 左右滑动 = 整体上下翻一屏（与竖直滚动一屏等价）。
 *
 * 显示顺序与实际播放顺序完全一致（随机模式下即为生成好的随机队列）。
 */
@Composable
internal fun PlayQueuePanel(
  modifier: Modifier = Modifier,
  onClose: () -> Unit,
  onRegenerateShuffle: (() -> Unit)? = null
) {
  val playbackVM = playbackViewModel
  val playbackState by playbackVM.playbackUiState.collectAsStateWithLifecycle()
  val songs by playbackVM.playQueueSongs.collectAsStateWithLifecycle()
  val theme = LocalTheme.current
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current

  Column(
    modifier = modifier
      .fillMaxSize()
  ) {
    // 标题栏：队列数量 + 重新随机 + 关闭
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(40.dp)
        .padding(start = 16.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextPrimary(
        stringResource(R.string.play_queue, songs.size),
        fontSize = 15.sp,
        modifier = Modifier.weight(1f)
      )

      if (onRegenerateShuffle != null) {
        CenterInBox(
          modifier = Modifier
            .clickWithRipple { onRegenerateShuffle() }
            .padding(8.dp)
        ) {
          Image(
            painter = painterResource(R.drawable.ic_shuffle_white_24dp),
            contentDescription = "PlayQueueReshuffle",
            colorFilter = ColorFilter.tint(theme.textSecondary),
            modifier = Modifier.size(20.dp)
          )
        }
      }

      CenterInBox(
        modifier = Modifier
          .clickWithRipple { onClose() }
          .padding(8.dp)
      ) {
        Image(
          painter = painterResource(R.drawable.ic_close_white_24dp),
          contentDescription = "PlayQueueClose",
          colorFilter = ColorFilter.tint(theme.textSecondary),
          modifier = Modifier.size(20.dp)
        )
      }
    }

    // 队列主体：整条队列连续竖向滚动，左右滑动整体翻一屏
    BoxWithConstraints(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
    ) {
      val viewportHeightPx = with(density) { maxHeight.toPx() }
      val rowHeightPx = with(density) { QUEUE_ROW_HEIGHT.toPx() }
      // 一屏能显示多少行，用于计算翻屏的目标位置
      val rowsPerScreen = max(1, (viewportHeightPx / rowHeightPx).toInt())
      val swipeThresholdPx = with(density) { PAGE_SWIPE_THRESHOLD.toPx() }

      // 手势回调里读取最新的一屏行数（屏幕尺寸可能变化）
      val currentRowsPerScreen by rememberUpdatedState(rowsPerScreen)

      var horizontalDrag by remember { mutableFloatStateOf(0f) }

      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectHorizontalDragGestures(
              onDragStart = { horizontalDrag = 0f },
              onHorizontalDrag = { change, dragAmount ->
                change.consume()
                horizontalDrag += dragAmount
              },
              onDragEnd = {
                // 左滑往下翻一屏，右滑往上翻一屏（与竖直滚动一屏等价）
                val direction = when {
                  horizontalDrag <= -swipeThresholdPx -> 1f
                  horizontalDrag >= swipeThresholdPx -> -1f
                  else -> 0f
                }
                if (direction != 0f) {
                  val deltaPx = direction * currentRowsPerScreen * rowHeightPx
                  scope.launch {
                    listState.animateScrollBy(deltaPx, tween(PAGE_SWIPE_DURATION))
                  }
                }
                horizontalDrag = 0f
              },
              onDragCancel = { horizontalDrag = 0f }
            )
          }
      ) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
          PlayQueueItem(
            song = song,
            isCurrent = song.id == playbackState.song.id,
            onClick = {
              sendLocalBroadcast(
                makeCmdIntent(Command.PLAY_AT).putExtra(EXTRA_POSITION, index)
              )
              onClose()
            },
            onDelete = { playbackVM.removeFromQueue(song.id) }
          )
        }
      }
    }
  }

  // 打开队列时定位到当前播放的歌曲
  LaunchedEffect(songs.size) {
    val index = songs.indexOfFirst { it.id == playbackState.song.id }
    if (index >= 0) {
      listState.scrollToItem(index)
    }
  }
}

/**
 * 播放队列面板入口。
 *
 * 随机模式下额外提供「重新随机」按钮，点击后重新生成一份随机队列；
 * 非随机模式下不显示该按钮。
 */
@Composable
internal fun PlayQueuePanelContent(
  modifier: Modifier = Modifier,
  onClose: () -> Unit
) {
  val playbackState by playbackViewModel.playbackUiState.collectAsStateWithLifecycle()
  val isShuffle = playbackState.playMode == MODE_SHUFFLE
  PlayQueuePanel(
    modifier = modifier,
    onClose = onClose,
    onRegenerateShuffle = if (isShuffle) {
      {
        sendLocalBroadcast(makeCmdIntent(Command.REGENERATE_SHUFFLE))
      }
    } else {
      null
    }
  )
}

@Composable
private fun PlayQueueItem(
  song: Song,
  isCurrent: Boolean,
  onClick: () -> Unit,
  onDelete: () -> Unit
) {
  val theme = LocalTheme.current
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .height(QUEUE_ROW_HEIGHT)
      .clickWithRipple(false) { onClick() }
  ) {
    Column(
      verticalArrangement = Arrangement.Center,
      modifier = Modifier
        .padding(start = 16.dp, end = 4.dp)
        .weight(1f)
    ) {
      if (!song.valid()) {
        TextPrimary(stringResource(R.string.song_lose_effect), fontSize = 13.sp)
      } else {
        TextPrimary(
          song.title,
          fontSize = 13.sp,
          color = if (isCurrent) theme.secondary else theme.textPrimary
        )
        TextSecondary(song.artist, fontSize = 11.sp)
      }
    }

    if (song.valid()) {
      CenterInBox(
        modifier = Modifier
          .clickWithRipple { onDelete() }
          .padding(6.dp)
      ) {
        Image(
          painter = painterResource(R.drawable.icon_playqueue_delete),
          contentDescription = "PlayQueueDelete",
          modifier = Modifier.size(18.dp)
        )
      }
    }
  }
}
