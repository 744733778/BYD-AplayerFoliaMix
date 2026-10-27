package remix.myplayer.ui.widget.lyric.folia

import remix.myplayer.lyric.LyricLine
import remix.myplayer.lyric.PerWordLyricLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 特效歌词引擎（Folia 风格，排版 / 动画 / 计时核心）。
 *
 * 本类不依赖 android.graphics，只做数学与状态推进：
 *  - [TextMetrics] 由宿主 View 提供（Canvas 路径用 TextPaint 实现）；
 *  - 每帧产出一份不可变 [FrameState] 快照，Canvas 渲染路径与 GLES 渲染路径共用，
 *    保证两条管线视觉完全一致；
 *  - 排版只在歌词 / 尺寸 / 字号变化时计算一次；播放进度按墙钟外推。
 *
 * 视觉规范对齐 Folia（作者原实现的算法在此原样移植，未改参数）：
 *  - 三行轨道：当前行锚定容器 50% 高度、整行水平居中；上下文行按距离缩小、降透明度并加模糊；
 *  - 逐词扫光：未唱词暗色底字，唱到的词以软边前沿推进填充；
 *  - 双层光晕：0.28 / 0.65 倍字号，包络「词时长 × 1.18 升到峰值，max(行尾, 词尾 + 1.05s) 消散」；
 *  - 行进出场：y / 缩放用弹簧，透明度 / 模糊用 cubic-bezier(0.32,0.72,0,1) 计时渐变。
 */

// ============================================================================================
// 对外数据结构
// ============================================================================================

/**
 * 特效模式（folia 真实模式子集，共 6 种）。
 * 当前全部由 Canvas 2D 原生渲染——车机零黑屏风险、无 GL 依赖。
 * 标签 [label] 直接展示在设置页的模式切换 UI（[EffectMode.entries] 自动派生）。
 */
enum class EffectMode(val label: String) {
  /** 流光：逐字扫光卡拉OK（folia 默认款 classic/luminous）。 */
  CLASSIC("流光"),

  /** 绘光：字形点亮 + 四芒星闪点 + 崩解漂散（folia lumiere）。 */
  LUMIERE("绘光"),

  /** 镜台：3D 空间内歌词行飞行 + 台面倒影（folia diorama）。 */
  DIORAMA("镜台"),

  /** 浮名：超大字号大字报 + 虚拟相机弹簧穿行，未唱字带雾感（folia fume）。 */
  FUME("浮名"),

  /** 心象：语义排版 + 整行呼吸光晕 + 横向扫光光束 + 逐词升腾入场（folia cadenza）。 */
  CADENZA("心象"),

  /** 商籁：胶片式镂空巨字 + 诗稿边框 + 信箱黑边 + 暗角（folia sonnet）。 */
  SONNET("商籁"),
  ;

  /** 是否必须走 GLES 管线。当前 6 种模式均为 Canvas 原生渲染，恒为 false。 */
  val requiresGl: Boolean
    get() = false
}

/** 配色（ARGB）。由 Compose 层随主题注入。 */
data class Palette(
  val active: Int = 0xFF698CF6.toInt(),
  val activeSecondary: Int = 0xFF9AB4FF.toInt(),
  val sung: Int = 0xFFFFFFFF.toInt(),
  val unsung: Int = 0xFF8C8C96.toInt(),
  val translation: Int = 0xFF6E6E78.toInt(),
  val bgTop: Int = 0xFF0E1018.toInt(),
  val bgBottom: Int = 0xFF1B1E2E.toInt(),
  val particle: Int = 0xFF7FA0FF.toInt(),
)

/** 文字测量抽象，避免引擎直接依赖 android.graphics。 */
interface TextMetrics {
  var textSize: Float
  fun measure(text: String): Float
  fun measure(text: String, start: Int, end: Int): Float
  val ascent: Float
  val descent: Float
}

/** 一个词（或合成的伪逐词单元）的运行时状态。 */
data class WordState(
  val index: Int,
  val startMs: Long,
  val endMs: Long,
  /** 已唱完（pos >= endMs）。 */
  val sung: Boolean,
  /** 正在唱（startMs <= pos < endMs）。 */
  val singing: Boolean,
  /** 扫光填充宽度（px）。 */
  val fillPx: Float,
)

/** 一条可见歌词的动画状态快照。 */
data class TrackSnapshot(
  val lineIndex: Int,
  val y: Float,
  val scale: Float,
  val alpha: Float,
  val blurPx: Float,
  val leaving: Boolean,
  val leaveDir: Int,
)

/**
 * 每帧快照：两条渲染路径的唯一数据来源。
 * [layout] 在排版变化时整体替换；替换前不得修改其内部 Token（发布后只读）。
 */
data class FrameState(
  val activeIndex: Int,
  val positionMs: Long,
  val tracks: List<TrackSnapshot>,
  val layout: List<LineLayout>,
  /** 当前行逐词状态（按时间序）。 */
  val activeWords: List<WordState>,
  /** 当前行行尾时间（光晕包络用）。 */
  val activeLineEndMs: Long,
  /** 当前主字号（px），行位图栅化 / 特效半径换算用。 */
  val fontPx: Float,
)

// ============================================================================================
// 内部排版结构（作者原实现的移植）
// ============================================================================================

/** 一个词（或合成的伪逐词单元）：时间轴 + 排版位置 + 逐字宽度表。 */
class Token(
  val content: String,
  val startTime: Long,
  val endTime: Long,
  /** 无逐字数据（整行均分）时为 false，扫光仍可用，只是时间精度低一档。 */
  val timed: Boolean,
) {
  var row = 0
  var x = 0f
  var width = 0f
  /** 逐字前缀宽度表（长度 = 字数 + 1），扫光按字素插值。 */
  lateinit var charOffsets: FloatArray

  /** 唱到的横向宽度（px），对齐 Folia 的 resolveMonetFillWidth。 */
  fun fillWidth(timeMs: Long): Float {
    if (timeMs <= startTime) return 0f
    if (timeMs >= endTime) return width
    val n = charOffsets.size - 1
    if (n <= 0) return width
    val unit = (endTime - startTime).coerceAtLeast(1L).toFloat() / n
    val idx = (((timeMs - startTime) / unit).toInt()).coerceIn(0, n - 1)
    val frac = (((timeMs - startTime) - idx * unit) / unit).coerceIn(0f, 1f)
    return charOffsets[idx] + (charOffsets[idx + 1] - charOffsets[idx]) * frac
  }
}

/** 一行歌词完成排版后的缓存。 */
class LineLayout(
  val line: LyricLine,
  val tokens: List<Token>,
  /** 折行后每一行的完整文本（缓存，避免每帧拼接）。 */
  val rowTexts: Array<String?>,
  val rowCount: Int,
  /** 每一行（折行后）的像素宽度，用于整行水平居中。 */
  val rowWidths: FloatArray,
  /** 每一行的居中偏移：把行内坐标平移到以行中心为原点（= -rowWidths[r] / 2）。 */
  val rowOffsets: FloatArray,
  val rowPitch: Float,
  val baseline0: Float,
  val mainHeight: Float,
  val transHeight: Float,
  val blockHeight: Float,
  val lineEndMs: Long,
)

/** 行轨道：一条可见歌词的动画状态。 */
class Track {
  var index = 0
  var y = 0f
  var vy = 0f
  var scale = 0.7f
  var vs = 0f
  var alpha = 0f
  var blur = 5f
  var fromAlpha = 0f
  var fromBlur = 5f
  var fadeElapsed = 0f
  var lastTargetAlpha = -1f
  var lastTargetBlur = -1f
  var leaving = false
  var leaveDir = 1
  var dead = false
}

// ============================================================================================
// 引擎
// ============================================================================================

class FoliaLyricEngine(private var metrics: TextMetrics) {

  // ---- 外部可绑定的数据 ----
  var lyrics: List<LyricLine> = emptyList()
    set(value) {
      if (field === value) return
      field = value
      needsLayout = true
      tracks.clear()
    }

  var fontScale: Float = 1f
    set(value) {
      if (field == value) return
      field = value
      needsLayout = true
    }

  /** 屏幕密度（dp→px 换算用）。 */
  var density: Float = 3f

  /** sp→px 系数（displayMetrics.scaledDensity），仅字号类 sp 换算用。 */
  var spUnit: Float = 3f

  /** 视口尺寸（px）。 */
  var viewW = 0
    set(value) {
      if (field == value) return
      field = value
      needsLayout = true
    }
  var viewH = 0
    set(value) {
      if (field == value) return
      field = value
      needsLayout = true
    }

  var positionProvider: (() -> Long)? = null
  var playingProvider: (() -> Boolean)? = null

  // ---- 内部状态 ----
  private var layout: List<LineLayout> = emptyList()
  private var needsLayout = true
  private val tracks = ArrayList<Track>()
  var activeIndex = 0
    private set

  /** 最近一次外推位置（渲染路径每帧读取）。 */
  var lastPositionMs = 0L
    private set

  // 进度平滑（服务端 100ms 一跳，这里按墙钟外推）
  private var lastProvidedPos = Long.MIN_VALUE
  private var basePos = 0L
  private var basePosNs = 0L

  /** 最近一帧是否仍有动画在推进（弹簧未落位 / 淡入淡出未结束 / 特效尾巴未熄）。 */
  var frameAnimating = false
    private set

  // ------------------------------------------------------------------------------------------
  // 排版
  // ------------------------------------------------------------------------------------------

  private fun sp(value: Float): Float = value * spUnit

  fun ensureLayout() {
    if (!needsLayout || viewW == 0 || viewH == 0) return
    needsLayout = false

    val fontPx = min(sp(46f * fontScale), viewW * MAX_FONT_TO_WIDTH).coerceAtLeast(sp(18f))
    metrics.textSize = fontPx
    val padX = fontPx * 0.6f
    val maxWidth = viewW - padX * 2f
    val fmAscent = metrics.ascent
    val fmDescent = metrics.descent
    val rowPitch = fontPx * LINE_HEIGHT
    val baseline0 = rowPitch / 2f - (fmAscent + fmDescent) / 2f
    val transGap = fontPx * 0.3f
    val transLineH = fontPx * TRANS_TO_FONT * 1.35f

    val result = ArrayList<LineLayout>(lyrics.size)
    for ((i, line) in lyrics.withIndex()) {
      val nextTime = lyrics.getOrNull(i + 1)?.time ?: Long.MAX_VALUE
      val lineEndMs = if (nextTime != Long.MAX_VALUE && nextTime > line.time) nextTime
      else {
        val wordEnd = (line as? PerWordLyricLine)?.words
          ?.maxOfOrNull { w -> max(w.time, w.end ?: w.time) } ?: line.time
        max(wordEnd, line.time + DEFAULT_LINE_MS)
      }

      val tokens = buildTokens(line, lineEndMs)
      var totalWidth = 0f
      for (t in tokens) {
        t.width = metrics.measure(t.content)
        totalWidth += t.width
      }

      // 折行（按真实像素宽度，只在词边界断行）
      var rowCount = 1
      if (totalWidth > maxWidth && tokens.isNotEmpty()) {
        var acc = 0f
        var row = 0
        for (t in tokens) {
          if (acc > 0f && acc + t.width > maxWidth) {
            row++
            acc = 0f
          }
          t.row = row
          acc += t.width
          if (row + 1 > rowCount) rowCount = row + 1
        }
      } else {
        for (t in tokens) t.row = 0
      }

      // 行内 x（左对齐）
      var x = 0f
      var curRow = -1
      for (t in tokens) {
        if (t.row != curRow) {
          curRow = t.row
          x = 0f
        }
        t.x = x
        x += t.width
      }

      // 逐字宽度表（扫光用）
      for (t in tokens) {
        val chars = t.content.length
        val offsets = FloatArray(chars + 1)
        for (k in 1 until chars) {
          offsets[k] = metrics.measure(t.content, 0, k)
        }
        offsets[chars] = t.width
        t.charOffsets = offsets
      }

      val rowTexts = arrayOfNulls<String>(rowCount)
      if (tokens.isEmpty()) {
        rowTexts[0] = line.content
      } else {
        val sb = StringBuilder()
        for (r in 0 until rowCount) {
          sb.setLength(0)
          for (t in tokens) {
            if (t.row == r) sb.append(t.content)
          }
          rowTexts[r] = sb.toString()
        }
      }

      val rowWidths = FloatArray(rowCount)
      for (t in tokens) rowWidths[t.row] += t.width
      val rowOffsets = FloatArray(rowCount) { -rowWidths[it] / 2f }

      val hasTrans = !line.translation.isNullOrEmpty()
      val mainHeight = rowPitch * rowCount
      val transHeight = if (hasTrans) transGap + transLineH else 0f

      result.add(
        LineLayout(
          line = line,
          tokens = tokens,
          rowTexts = rowTexts,
          rowCount = rowCount,
          rowWidths = rowWidths,
          rowOffsets = rowOffsets,
          rowPitch = rowPitch,
          baseline0 = baseline0,
          mainHeight = mainHeight,
          transHeight = transHeight,
          blockHeight = mainHeight + transHeight,
          lineEndMs = lineEndMs
        )
      )
    }
    layout = result
    tracks.clear()
    activeIndex = 0
  }

  /**
   * 把一行歌词拆成带时间的 token。
   * PerWordLyricLine 直接用词；SimpleLyricLine 按 CJK 单字 / 西文整词的权重
   * 把行时长的 90% 均分（对齐 Folia 的 buildTimedWords）。
   */
  private fun buildTokens(line: LyricLine, lineEndMs: Long): List<Token> {
    val perWord = line as? PerWordLyricLine
    if (perWord != null) {
      val words = perWord.words
      val tokens = ArrayList<Token>(words.size)
      for ((i, w) in words.withIndex()) {
        val start = w.time
        val end = w.end?.takeIf { it > start }
          ?: (words.getOrNull(i + 1)?.time?.takeIf { it > start } ?: lineEndMs)
        if (w.content.isEmpty()) continue
        tokens.add(Token(w.content, start, max(start, end), true))
      }
      return tokens
    }

    // 无逐字数据：合成伪逐词时间轴
    val content = line.content
    if (content.isEmpty()) return emptyList()

    class Raw(val sb: StringBuilder, var weight: Float)

    val raws = ArrayList<Raw>()
    fun appendToLast(text: String, weight: Float) {
      if (raws.isEmpty()) {
        raws.add(Raw(StringBuilder(text), weight))
      } else {
        raws.last().sb.append(text)
      }
    }

    var i = 0
    while (i < content.length) {
      val c = content[i]
      when {
        isCjk(c) -> raws.add(Raw(StringBuilder().append(c), 1f))
        c.isLetterOrDigit() || c == '\'' || c == '’' -> {
          val start = i
          while (i < content.length &&
            (content[i].isLetterOrDigit() || content[i] == '\'' || content[i] == '’') &&
            !isCjk(content[i])
          ) i++
          val word = content.substring(start, i)
          raws.add(Raw(StringBuilder(word), 1f + 0.15f * word.length))
          continue
        }
        else -> appendToLast(c.toString(), 0f)
      }
      i++
    }

    val span = (lineEndMs - line.time).coerceAtLeast(1L)
    val activeDuration = span * 0.9
    val totalWeight = raws.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.001f)
    var perWeightMs = activeDuration / totalWeight
    // 单词最少 50ms，超出总时长则整体线性压缩
    val rawSum = raws.sumOf { (max(50.0, (it.weight * perWeightMs).toDouble())).toDouble() }
    if (rawSum > activeDuration && rawSum > 0) {
      perWeightMs *= activeDuration / rawSum
    }

    val tokens = ArrayList<Token>(raws.size)
    var cursor = line.time
    for (r in raws) {
      val dur = max(50.0, (r.weight * perWeightMs).toDouble()).toLong()
      val start = cursor
      val end = min(start + dur, line.time + span)
      tokens.add(Token(r.sb.toString(), start, max(start + 1, end), false))
      cursor = end
    }
    return tokens
  }

  private fun isCjk(c: Char): Boolean {
    val block = Character.UnicodeBlock.of(c) ?: return false
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
      block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
      block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
      block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
      block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
  }

  // ------------------------------------------------------------------------------------------
  // 时间推进
  // ------------------------------------------------------------------------------------------

  private fun isPlaying(): Boolean = playingProvider?.invoke() ?: false

  /** 帧间外推的播放位置：provider 每 100ms 跳一跳，这里按墙钟补齐。 */
  fun samplePosition(): Long {
    val provided = positionProvider?.invoke() ?: 0L
    val now = System.nanoTime()
    if (provided != lastProvidedPos) {
      lastProvidedPos = provided
      basePos = provided
      basePosNs = now
    }
    if (!isPlaying()) return basePos
    return basePos + (now - basePosNs) / 1_000_000L
  }

  private fun updateActiveIndex(position: Long) {
    if (layout.isEmpty()) {
      activeIndex = 0
      return
    }
    var lo = 0
    var hi = layout.size - 1
    var result = 0
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      if (layout[mid].line.time <= position) {
        result = mid
        lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    activeIndex = result
  }

  // ------------------------------------------------------------------------------------------
  // 行轨道（弹簧 + 计时渐变）
  // ------------------------------------------------------------------------------------------

  private fun focusY(): Float = viewH * FOCUS_Y

  private fun toneScale(d: Int): Float =
    if (d == 0) 1f else max(0.68f, 0.72f * pow(0.9f, d - 1))

  private fun toneAlpha(d: Int): Float = when {
    d == 0 -> 1f
    d > 0 -> max(0.36f, 0.72f - 0.18f * (d - 1))
    else -> max(0.28f, 0.52f - 0.12f * (-d - 1))
  }

  private fun toneBlurPx(d: Int): Float = when {
    d == 0 -> 0f
    d > 0 -> if (d == 1) 0.7f else 1.8f + 0.8f * (d - 2)
    else -> 1.1f + 0.7f * (-d - 1)
  }.let { it * density }

  private fun gapPx(fontPx: Float): Float = max(sp(12f), fontPx * GAP_TO_FONT)

  private fun syncTracks(fontPx: Float) {
    if (layout.isEmpty()) {
      tracks.clear()
      return
    }
    val lo = (activeIndex - TRACK_WINDOW).coerceAtLeast(0)
    val hi = (activeIndex + TRACK_WINDOW).coerceAtMost(layout.size - 1)

    for (t in tracks) {
      if (!t.leaving && (t.index !in lo..hi || t.index >= layout.size)) {
        t.leaving = true
        t.leaveDir = if (t.index < activeIndex) -1 else 1
        t.fadeElapsed = 0f
        t.fromAlpha = t.alpha
        t.fromBlur = t.blur
        t.lastTargetAlpha = -1f
      }
    }

    for (i in lo..hi) {
      if (tracks.any { !it.leaving && it.index == i }) continue
      // 快速跳回时，正在退场的同号轨道直接复活，避免和新轨道叠加绘制
      val reviving = tracks.firstOrNull { it.leaving && it.index == i && !it.dead }
      if (reviving != null) {
        reviving.leaving = false
        reviving.fadeElapsed = 0f
        reviving.fromAlpha = reviving.alpha
        reviving.fromBlur = reviving.blur
        reviving.lastTargetAlpha = -1f
        reviving.lastTargetBlur = -1f
        continue
      }
      val t = Track()
      t.index = i
      val d = i - activeIndex
      val blockH = layout[i].blockHeight * toneScale(d)
      val anchorTop = focusY() - layout[activeIndex].blockHeight / 2f
      val gap = gapPx(fontPx)
      t.y = when {
        d == 0 -> anchorTop + ENTER_OFFSET_PX * density
        d < 0 -> anchorTop - gap - blockH - ENTER_OFFSET_PX * density
        else -> anchorTop + layout[activeIndex].blockHeight + gap + ENTER_OFFSET_PX * density
      }
      t.scale = ENTER_SCALE
      t.alpha = 0f
      t.blur = ENTER_BLUR
      t.fromAlpha = 0f
      t.fromBlur = ENTER_BLUR
      t.fadeElapsed = 0f
      tracks.add(t)
    }
  }

  private fun stepTracks(dt: Float, fontPx: Float) {
    frameAnimating = false
    if (layout.isEmpty()) return
    val anchorTop = focusY() - layout[activeIndex].blockHeight / 2f
    val activeH = layout[activeIndex].blockHeight
    val gap = gapPx(fontPx)

    for (t in tracks) {
      val l = layout.getOrNull(t.index)
      if (l == null) {
        t.dead = true
        continue
      }
      val d = if (t.leaving) t.leaveDir else t.index - activeIndex
      val targetScale = if (t.leaving) t.scale else toneScale(d)
      val targetAlpha = if (t.leaving) 0f else toneAlpha(d)
      val targetBlur = if (t.leaving) toneBlurPx(1) else toneBlurPx(d)

      var targetY = when {
        t.leaving -> t.y + if (t.leaveDir < 0) -EXIT_OFFSET_PX * density else EXIT_OFFSET_PX * density
        d == 0 -> anchorTop
        d < 0 -> anchorTop - gap - l.blockHeight * targetScale
        else -> anchorTop + activeH + gap
      }

      val (ny, nvy) = springStep(t.y, t.vy, targetY, dt, 142f, 28f, 0.82f)
      val (ns, nvs) = springStep(t.scale, t.vs, targetScale, dt, 150f, 30f, 0.78f)
      t.y = ny
      t.vy = nvy
      t.scale = ns
      t.vs = nvs

      // 透明度/模糊：目标变化时重启一次计时渐变（cubic-bezier(0.32,0.72,0,1)）
      if (abs(targetAlpha - t.lastTargetAlpha) > 0.01f) {
        t.lastTargetAlpha = targetAlpha
        t.fromAlpha = t.alpha
        t.fadeElapsed = 0f
      }
      if (abs(targetBlur - t.lastTargetBlur) > 0.01f) {
        t.lastTargetBlur = targetBlur
        t.fromBlur = t.blur
      }
      t.fadeElapsed += dt
      t.alpha = t.fromAlpha + (targetAlpha - t.fromAlpha) *
        bezierEase((t.fadeElapsed / FADE_ALPHA_SEC).coerceIn(0f, 1f))
      t.blur = t.fromBlur + (targetBlur - t.fromBlur) *
        bezierEase((t.fadeElapsed / FADE_BLUR_SEC).coerceIn(0f, 1f))

      if (t.leaving && t.fadeElapsed > FADE_ALPHA_SEC && t.alpha < 0.01f) {
        t.dead = true
        continue
      }

      val settled = abs(nvy) < 0.01f && abs(ny - targetY) < 0.5f &&
        abs(nvs) < 0.01f && abs(ns - targetScale) < 0.002f &&
        t.fadeElapsed > FADE_BLUR_SEC && !t.leaving
      if (!settled) frameAnimating = true
    }

    if (tracks.removeAll { it.dead }) {
      frameAnimating = true
    }
  }

  // ------------------------------------------------------------------------------------------
  // 帧推进与快照
  // ------------------------------------------------------------------------------------------

  /**
   * 推进一帧。
   * @param dtSec 距上一帧的秒数
   * @return 本帧快照（两条渲染路径共用）；布局为空时返回 null
   */
  fun stepFrame(dtSec: Float): FrameState? {
    ensureLayout()
    val pos = samplePosition()
    lastPositionMs = pos
    updateActiveIndex(pos)
    val fontPx = metrics.textSize
    syncTracks(fontPx)
    stepTracks(dtSec, fontPx)

    if (layout.isEmpty()) {
      return FrameState(0, pos, emptyList(), emptyList(), emptyList(), 0L, metrics.textSize)
    }

    val snapshots = ArrayList<TrackSnapshot>(tracks.size)
    for (t in tracks) {
      if (t.alpha <= 0.005f) continue
      snapshots.add(
        TrackSnapshot(
          lineIndex = t.index,
          y = t.y,
          scale = t.scale,
          alpha = t.alpha,
          blurPx = t.blur,
          leaving = t.leaving,
          leaveDir = t.leaveDir,
        )
      )
    }

    val active = layout.getOrNull(activeIndex)
    val words = if (active != null) buildWordStates(active, pos) else emptyList()

    return FrameState(
      activeIndex = activeIndex,
      positionMs = pos,
      tracks = snapshots,
      layout = layout,
      activeWords = words,
      activeLineEndMs = active?.lineEndMs ?: 0L,
      fontPx = metrics.textSize,
    )
  }

  private fun buildWordStates(l: LineLayout, pos: Long): List<WordState> {
    val tokens = l.tokens
    if (tokens.isEmpty()) return emptyList()
    val out = ArrayList<WordState>(tokens.size)
    for (i in tokens.indices) {
      val t = tokens[i]
      out.add(
        WordState(
          index = i,
          startMs = t.startTime,
          endMs = t.endTime,
          sung = pos >= t.endTime,
          singing = pos >= t.startTime && pos < t.endTime,
          fillPx = t.fillWidth(pos),
        )
      )
    }
    return out
  }

  /**
   * 当前行还在唱 / 弹簧未落位 / 光晕尾巴未熄 → 需要继续取帧。
   * 暂停时（isPlaying=false）位置不动、画面静止，只等弹簧/淡入淡落位即停摆（车机省电）。
   */
  fun isBusy(pos: Long = lastPositionMs): Boolean {
    if (frameAnimating) return true
    if (!isPlaying()) return false
    val l = layout.getOrNull(activeIndex) ?: return false
    return pos >= l.line.time - 200L && pos <= l.lineEndMs + GLOW_TAIL_MS
  }

  /** 点击命中测试：返回被点中的歌词行开始时间（未命中返回 null）。 */
  fun seekTarget(y: Float): Long? {
    var best: Track? = null
    for (t in tracks) {
      val block = layout.getOrNull(t.index) ?: continue
      val top = t.y
      val bottom = top + block.blockHeight * t.scale
      if (y in top..bottom) {
        if (best == null || abs(t.index - activeIndex) < abs(best.index - activeIndex)) {
          best = t
        }
      }
    }
    val target = best ?: return null
    return layout[target.index].line.time
  }

  /** 当前字号（px）。 */
  fun currentFontPx(): Float = metrics.textSize

  // ------------------------------------------------------------------------------------------
  // 共享数学（两条渲染路径复用，保证视觉一致）
  // ------------------------------------------------------------------------------------------

  /**
   * spring(stiffness=200, damping=20) 的阶跃响应：ωn=√200≈14.1，ζ≈0.707，ωd≈10。
   * age<0 返回 0；稳态 1，带轻微过冲。
   */
  fun springResp(age: Float): Float {
    if (age <= 0f) return 0f
    val e = exp(-10f * age)
    return 1f - e * (cos(10f * age) + 0.707f * sin(10f * age))
  }

  /** 确定性伪随机（稳定种子，逐帧一致）。 */
  fun seedRand(i: Int, salt: Int): Float {
    val x = sin(i * 127.1f + salt * 311.7f) * 43758.5453f
    return x - kotlin.math.floor(x)
  }

  /** smoothstep 01。 */
  fun smooth01(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
  }

  /** Lumiere 闪点包络：0.15s 攻击上升，之后 exp 衰减（decay 0.45s）。 */
  fun flashEnv(posMs: Long, startMs: Long): Float {
    val age = (posMs - startMs) / 1000f
    if (age <= 0f) return 0f
    val attack = 0.15f
    return if (age < attack) {
      val p = age / attack
      p * p * (3f - 2f * p)
    } else {
      exp(-(age - attack) / 0.45f)
    }
  }

  /** 双层高斯光晕包络：升到峰值 = 词时长 × 1.18，消散到 max(行尾, 词尾 + 1.05s)。 */
  fun glowEnvelope(pos: Long, t: Token, lineEnd: Long): Float {
    if (pos <= t.startTime) return 0f
    val rise = ((t.endTime - t.startTime).coerceAtLeast(1L) * 1.18).toLong().coerceAtLeast(1L)
    val peak = t.startTime + rise
    val tail = max(lineEnd, t.endTime + GLOW_TAIL_MS)
    return if (pos <= peak) {
      val p = ((pos - t.startTime).toFloat() / rise).coerceIn(0f, 1f)
      p * p * (3f - 2f * p)
    } else {
      val r = 1f - ((pos - peak).toFloat() / max(180f, (tail - peak).toFloat())).coerceIn(0f, 1f)
      (r * r * (3f - 2f * r)).coerceIn(0f, 1f)
    }
  }

  private fun springStep(
    pos: Float, vel: Float, target: Float, dt: Float,
    stiffness: Float, damping: Float, mass: Float
  ): Pair<Float, Float> {
    var p = pos
    var v = vel
    val steps = ceil(dt * 120f).toInt().coerceIn(1, 12)
    val h = dt / steps
    repeat(steps) {
      val f = -stiffness * (p - target) - damping * v
      v += f / mass * h
      p += v * h
    }
    if (abs(p - target) < 0.01f && abs(v) < 0.01f) {
      p = target
      v = 0f
    }
    return p to v
  }

  private fun bezierEase(x: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    // cubic-bezier(0.32, 0.72, 0, 1)
    var t = x
    repeat(6) {
      val u = 1f - t
      val bx = 3f * u * u * t * 0.32f + t * t * t
      val dx = 3f * u * u * 0.32f - 6f * u * t * 0.32f + 3f * t * t
      if (abs(dx) < 1e-6f) return@repeat
      t = (t - (bx - x) / dx).coerceIn(0f, 1f)
    }
    val u = 1f - t
    return 3f * u * u * t * 0.72f + 3f * u * t * t + t * t * t
  }

  private fun pow(base: Float, exp: Int): Float =
    Math.pow(base.toDouble(), exp.toDouble()).toFloat()

  companion object {
    const val FOCUS_Y = 0.5f
    const val TRACK_WINDOW = 2
    const val LINE_HEIGHT = 1.18f
    const val MAX_FONT_TO_WIDTH = 0.09f
    const val TRANS_TO_FONT = 0.5f
    const val GAP_TO_FONT = 0.49f
    const val BASE_ALPHA = 0.42f
    const val TRANSLATION_ALPHA = 0.68f

    const val ENTER_OFFSET_PX = 34f
    const val EXIT_OFFSET_PX = 38f
    const val ENTER_SCALE = 0.7f
    const val ENTER_BLUR = 5f
    const val FADE_ALPHA_SEC = 0.28f
    const val FADE_BLUR_SEC = 0.32f

    const val GLOW_TAIL_MS = 1050L
    const val DEFAULT_LINE_MS = 4000L
  }
}
