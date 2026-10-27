package remix.myplayer.ui.screen.playing

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlin.math.ceil
import kotlin.math.min

/** 队列中每一行的高度 */
private val QUEUE_ROW_HEIGHT = 50.dp

/**
 * 计算每页容纳的歌曲数量。
 *
 * 每页比一屏能显示的行数多几行，这样：
 * - 左右滑动用于翻页（浏览整个队列）；
 * - 页内上下滑动用于查看当前页剩余的歌曲。
 */
private fun computePageSize(viewportHeight: Dp): Int {
  val visibleRows = (viewportHeight / QUEUE_ROW_HEIGHT).toInt()
  return (visibleRows + 3).coerceIn(6, 20)
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

/**
 * 播放队列面板。
 *
 * 显示在播放页右侧（竖屏为歌词页），横向翻页 + 页内纵向滚动，
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

  Column(
    modifier = modifier
      .fillMaxSize()
  ) {
    // 标题栏：队列数量 + 重新随机 + 关闭
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(44.dp)
        .padding(start = 16.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextPrimary(
        stringResource(R.string.play_queue, songs.size),
        fontSize = 16.sp,
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

    // 队列主体：横向翻页，页内纵向滚动
    BoxWithConstraints(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
    ) {
      val pageSize = remember(maxHeight) { computePageSize(maxHeight) }
      val pageCount = remember(songs.size, pageSize) {
        if (songs.isEmpty()) {
          1
        } else {
          ceil(songs.size / pageSize.toDouble()).toInt().coerceAtLeast(1)
        }
      }
      val pagerState = rememberPagerState(pageCount = { pageCount })

      HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
      ) { page ->
        val start = (page * pageSize).coerceAtMost(songs.size)
        val end = min(start + pageSize, songs.size)
        val pageSongs = songs.subList(start, end)
        val listState = rememberLazyListState()

        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize()
        ) {
          itemsIndexed(pageSongs, key = { _, song -> song.id }) { localIndex, song ->
            PlayQueueItem(
              song = song,
              isCurrent = song.id == playbackState.song.id,
              onClick = {
                sendLocalBroadcast(
                  makeCmdIntent(Command.PLAY_AT).putExtra(EXTRA_POSITION, start + localIndex)
                )
                onClose()
              },
              onDelete = { playbackVM.removeFromQueue(song.id) }
            )
          }
        }

        // 打开队列时定位到当前播放的歌曲
        val currentIndexInPage = pageSongs.indexOfFirst { it.id == playbackState.song.id }
        LaunchedEffect(page, pagerState.currentPage, currentIndexInPage) {
          if (page == pagerState.currentPage && currentIndexInPage >= 0) {
            listState.scrollToItem(currentIndexInPage)
          }
        }
      }

      // 页码指示
      if (pageCount > 1) {
        TextSecondary(
          text = stringResource(R.string.play_queue_page, pagerState.currentPage + 1, pageCount),
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 6.dp),
          fontSize = 11.sp
        )
      }
    }
  }
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
        .padding(horizontal = 16.dp)
        .weight(1f)
    ) {
      if (!song.valid()) {
        TextPrimary(stringResource(R.string.song_lose_effect))
      } else {
        TextPrimary(
          song.title,
          color = if (isCurrent) theme.secondary else theme.textPrimary
        )
        TextSecondary(song.artist)
      }
    }

    if (song.valid()) {
      CenterInBox(
        modifier = Modifier
          .clickWithRipple { onDelete() }
          .padding(8.dp)
      ) {
        Image(
          painter = painterResource(R.drawable.icon_playqueue_delete),
          contentDescription = "PlayQueueDelete"
        )
      }
    }
  }
}
