package remix.myplayer.ui.screen.lyric

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import remix.myplayer.service.playback.MusicStateSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import remix.myplayer.R
import remix.myplayer.glide.UriFetcherEntryPoint
import remix.myplayer.lyric.LyricLine
import remix.myplayer.lyric.manual.ManualLyricCandidate
import remix.myplayer.lyric.manual.LyricManualSearcher
import remix.myplayer.ui.nav.LocalNavController
import remix.myplayer.ui.nav.MessageNotifier
import remix.myplayer.ui.theme.LocalTheme
import remix.myplayer.ui.widget.common.CommonAppBar
import remix.myplayer.ui.widget.common.TextPrimary
import remix.myplayer.ui.widget.common.TextSecondary
import remix.myplayer.viewmodel.playbackViewModel
import remix.myplayer.viewmodel.settingViewModel
import java.util.Locale

/**
 * 歌词匹配二级窗口：多源 Provider（网易云/酷狗/QQ）、自动回退取词、
 * 手动跨平台搜索（可改关键词、切换来源）。
 * 布局对齐 folia OnlineLyricMatchModal：左侧候选列表，右侧选中歌词预览，底部确认。
 */
@Composable
fun LyricMatchScreen() {
  val nav = LocalNavController.current
  val scope = rememberCoroutineScope()
  val settingVM = settingViewModel
  val playbackVM = playbackViewModel
  val searcher = settingVM.manualSearcher
  val lyricManager = settingVM.lyricManager
  val context = LocalContext.current
  val uriFetcher = remember {
    EntryPointAccessors.fromApplication(context, UriFetcherEntryPoint::class.java).uriFetcher()
  }

  val playbackState by playbackVM.playbackUiState.collectAsStateWithLifecycle()
  val progress by MusicStateSource.progressState.collectAsStateWithLifecycle()
  val song = playbackState.song

  val defaultKey = remember(song.id) {
    listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" - ")
  }
  var keyword by remember(song.id) { mutableStateOf(defaultKey) }
  var filter by remember { mutableStateOf(LyricManualSearcher.SourceFilter.ALL) }
  var candidates by remember { mutableStateOf<List<ManualLyricCandidate>>(emptyList()) }
  var loading by remember { mutableStateOf(false) }
  var applying by remember { mutableStateOf(false) }
  // 选中候选（folia 逻辑：单击选中，搜索完自动预选第一条）
  var selected by remember { mutableStateOf<ManualLyricCandidate?>(null) }
  // 预览歌词（按候选缓存，避免来回切换重复请求）
  val previewCache = remember { mutableMapOf<ManualLyricCandidate, List<LyricLine>>() }
  var previewLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
  var previewLoading by remember { mutableStateOf(false) }

  fun runSearch() {
    if (keyword.isBlank()) return
    loading = true
    scope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching { searcher.search(song, keyword.trim(), filter) }
          .onFailure { MessageNotifier.show(R.string.lyric_search_failed) }
          .getOrDefault(emptyList())
      }
      candidates = result
      selected = result.firstOrNull()
      loading = false
    }
  }

  LaunchedEffect(song.id) { runSearch() }

  // 选中变化 -> 按需取词预览（folia：预览请求与确认请求相互独立）
  LaunchedEffect(selected) {
    val c = selected
    if (c == null) {
      previewLines = emptyList()
      return@LaunchedEffect
    }
    previewCache[c]?.let {
      previewLines = it
      return@LaunchedEffect
    }
    previewLoading = true
    val lines = withContext(Dispatchers.IO) { searcher.fetchPreview(c) }
    previewCache[c] = lines
    previewLines = lines
    previewLoading = false
  }

  fun apply() {
    val c = selected ?: return
    applying = true
    scope.launch {
      val ok = withContext(Dispatchers.IO) {
        runCatching { searcher.fetchWithFallback(candidates, c) }
          .onFailure { MessageNotifier.show(R.string.lyric_fetch_failed) }
          .getOrNull()
      }
      if (ok != null) {
        lyricManager.applyManualLyrics(song, ok.first)
        // 同步匹配对应封面：优先用所选候选自带封面，否则按歌曲信息回退搜索
        val coverUrl = runCatching {
          val preferred = searcher.coverUrlFromCandidate(c)
          if (!preferred.isNullOrEmpty()) {
            uriFetcher.resolveCoverUrl(song, preferred)
          } else {
            uriFetcher.resolveCoverUrl(song, null)
          }
        }.getOrNull()
        if (!coverUrl.isNullOrEmpty()) {
          runCatching { uriFetcher.saveCustomCover(song, coverUrl) }
        }
        nav.popBackStack()
      }
      applying = false
    }
  }

  Column(
    modifier = Modifier.fillMaxSize()
  ) {
    CommonAppBar(
      title = stringResource(R.string.lyric_match_online),
      onBack = { nav.popBackStack() },
      actions = emptyList()
    )

    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // ============ 左：来源 + 搜索框 + 候选列表（匹配度列整体靠左） ============
      Column(
        modifier = Modifier
          .weight(0.58f)
          .fillMaxHeight()
      ) {
        // 顶部：搜索框（右端放搜索按钮）+ 应用按钮（位于搜索按钮右侧，始终可见）
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            label = { Text(stringResource(R.string.lyric_match_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              cursorColor = Color.White,
              focusedLabelColor = Color.White.copy(alpha = 0.7f),
              unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
              focusedBorderColor = Color.White.copy(alpha = 0.6f),
              unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            trailingIcon = {
              IconButton(onClick = { runSearch() }) {
                Icon(
                  painter = painterResource(R.drawable.ic_search_white_24dp),
                  contentDescription = null,
                  tint = Color.White
                )
              }
            }
          )
          Spacer(Modifier.width(8.dp))
          ApplyButton(
            enabled = selected != null && !applying,
            loading = applying,
            onClick = { apply() }
          )
        }

        Spacer(Modifier.height(8.dp))

        // 来源切换
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          LyricManualSearcher.SourceFilter.entries.forEach { f ->
            SourceChip(
              label = stringResource(f.labelRes),
              selected = filter == f,
              onClick = {
                filter = f
                runSearch()
              }
            )
          }
        }

        Spacer(Modifier.height(8.dp))

        when {
          loading || applying -> Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.Center
          ) { CircularProgressIndicator() }

          candidates.isEmpty() -> TextSecondary(
            stringResource(R.string.no_lrc),
            modifier = Modifier.padding(24.dp)
          )

          else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(candidates) { c ->
              CandidateRow(
                c = c,
                selected = c == selected,
                onClick = { selected = c }
              )
            }
          }
        }
      }

      // ============ 右：选中歌词预览 ============
      PreviewPanel(
        modifier = Modifier
          .weight(0.42f)
          .fillMaxHeight(),
        candidate = selected,
        lines = previewLines,
        loading = previewLoading,
        position = progress.position
      )
    }
  }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
  val bg = if (selected) LocalTheme.current.primary else LocalTheme.current.select
  Text(
    text = label,
    color = if (selected) androidx.compose.ui.graphics.Color.White else LocalTheme.current.textPrimary,
    fontSize = 13.sp,
    modifier = Modifier
      .clip(RoundedCornerShape(50))
      .background(bg)
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp)
  )
}

@Composable
private fun CandidateRow(
  c: ManualLyricCandidate,
  selected: Boolean,
  onClick: () -> Unit
) {
  val bg = if (selected) LocalTheme.current.select else androidx.compose.ui.graphics.Color.Transparent
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(bg)
      .clickable(onClick = onClick)
      .padding(vertical = 10.dp, horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      TextPrimary(
        c.title,
        fontSize = 15.sp,
        maxLine = 1,
        overflow = TextOverflow.Ellipsis
      )
      TextSecondary(
        listOf(c.artist, c.album).filter { !it.isNullOrBlank() }.joinToString(" · "),
        fontSize = 12.sp,
        maxLine = 1
      )
    }
    Spacer(Modifier.width(8.dp))
    // 匹配度：整体向左紧凑排列，右侧留给预览面板
    Column(horizontalAlignment = Alignment.End) {
      TextPrimary("${c.score.toInt()}%", fontSize = 14.sp)
      TextSecondary(c.displayName, fontSize = 11.sp)
      c.durationMs?.let {
        TextSecondary(formatDuration(it), fontSize = 11.sp)
      }
    }
  }
}

@Composable
private fun PreviewPanel(
  modifier: Modifier = Modifier,
  candidate: ManualLyricCandidate?,
  lines: List<LyricLine>,
  loading: Boolean,
  position: Long,
) {
  val theme = LocalTheme.current
  val listState = rememberLazyListState()
  // 当前播放行：用于动态滚动（跟随播放进度）并高亮，便于确认是否匹配正在播放的歌
  val activeIndex = remember(lines, position) {
    var idx = -1
    for (i in lines.indices) {
      if (lines[i].time <= position) idx = i else break
    }
    idx
  }
  LaunchedEffect(activeIndex) {
    if (activeIndex >= 0) {
      val vh = listState.layoutInfo.viewportSize.height
      listState.animateScrollToItem(activeIndex, scrollOffset = if (vh > 0) -(vh / 2) else 0)
    }
  }
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(14.dp))
      .background(theme.select)
      .padding(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // 头部：歌名 · 歌手 · 来源
    if (candidate != null) {
      TextPrimary(
        candidate.title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        maxLine = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
      )
      TextSecondary(
        listOf(candidate.artist, candidate.displayName)
          .filter { !it.isNullOrBlank() }
          .joinToString(" · "),
        fontSize = 12.sp,
        maxLine = 1
      )
      Spacer(Modifier.height(8.dp))
    }
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
      when {
        candidate == null -> TextSecondary(
          stringResource(R.string.no_lrc),
          modifier = Modifier.padding(top = 24.dp)
        )
        loading -> CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        lines.isEmpty() -> TextSecondary(
          stringResource(R.string.no_lrc),
          modifier = Modifier.padding(top = 24.dp)
        )
        else -> LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          itemsIndexed(lines) { index, line ->
            val active = index == activeIndex
            Column(
              modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                line.content.ifBlank { " " },
                color = if (active) theme.primary else theme.textPrimary,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
              )
              line.translation?.takeIf { it.isNotBlank() }?.let {
                TextSecondary(
                  it,
                  fontSize = 11.sp,
                  maxLine = 1,
                  overflow = TextOverflow.Ellipsis,
                  textAlign = TextAlign.Center
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ApplyButton(enabled: Boolean, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val theme = LocalTheme.current
  Text(
    text = if (loading) stringResource(R.string.saveing) else stringResource(R.string.lyric_match_use),
    color = androidx.compose.ui.graphics.Color.White,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    modifier = modifier
      .clip(RoundedCornerShape(50))
      .background(if (enabled) theme.primary else theme.primary.copy(alpha = 0.4f))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 22.dp, vertical = 12.dp)
  )
}

private fun formatDuration(ms: Long): String {
  val totalSec = ms / 1000
  return String.format(
    Locale.getDefault(),
    "%02d:%02d",
    totalSec / 60,
    totalSec % 60
  )
}
