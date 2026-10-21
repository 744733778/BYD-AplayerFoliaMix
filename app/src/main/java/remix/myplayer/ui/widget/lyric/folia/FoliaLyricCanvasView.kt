package remix.myplayer.ui.widget.lyric.folia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import remix.myplayer.lyric.LyricLine
import remix.myplayer.lyric.PerWordLyricLine
import remix.myplayer.lyric.SimpleLyricLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 全屏逐字歌词渲染器（Folia 风格，纯 Canvas 实现）。
 *
 * 视觉规范对齐 Folia 的 Monet/Lattice 歌词：
 *  - 三行轨道：当前行锚定在容器 50% 高度、整行水平居中；上下文行按距离缩小（0.72 × 0.9^(d-1)）、
 *    降透明度并加高斯模糊，形成远近景。
 *  - 逐词扫光：未唱词为暗色底字；唱到的词从左往右以软边前沿推进填充色，
 *    填充内部沿字形有 1.00 → 0.92(68%) → 0.72(100%) 的墨迹渐变；唱完的词整词切换为高亮色。
 *  - 双层光晕：正在唱/刚唱完的词有两圈高斯模糊余晖（0.28/0.65 倍字号），
 *    包络为「词时长 × 1.18 升到峰值，max(行尾, 词尾 + 1.05s) 内消散」。
 *  - 行进出场：y/缩放用弹簧（142/28/0.82 与 150/30/0.78，1/120s 子步长），
 *    透明度/模糊用 cubic-bezier(0.32,0.72,0,1) 计时渐变（0.28s / 0.32s），
 *    进场偏移 ±34px、进场缩放 0.7、退场偏移 ±38px。
 *
 * 工程细节：
 *  - 排版只在歌词/尺寸/字号变化时计算一次；所有行共用一个字号，上下文行靠 scale 缩放。
 *  - 播放进度在帧间按墙钟外推（服务端 100ms 一跳的进度不再造成逐字卡顿）。
 *  - 只在「播放中 / 弹簧未落位 / 光晕未消散」时注册 Choreographer；detach 后停止取帧。
 *  - 普通歌词（无逐字时间）按 CJK 单字、西文整词的权重合成逐词时间轴（对齐 Folia 的做法）。
 */
class FoliaLyricCanvasView(context: Context) : View(context), Choreographer.FrameCallback {

  /** 特效模式。 */
  enum class EffectMode(val label: String) {
    CLASSIC("扫光卡拉OK"),
    GLOW("词弹辉光"),
    LUMIERE("光室点亮"),
  }

  /** 配色（ARGB）。由 Compose 层随主题注入。 */
  data class Palette(
    val active: Int = Color.parseColor("#698CF6"),
    val activeSecondary: Int = Color.parseColor("#9AB4FF"),
    val sung: Int = Color.parseColor("#FFFFFF"),
    val unsung: Int = Color.parseColor("#8C8C96"),
    val translation: Int = Color.parseColor("#6E6E78"),
    val bgTop: Int = Color.parseColor("#0E1018"),
    val bgBottom: Int = Color.parseColor("#1B1E2E"),
    val particle: Int = Color.parseColor("#7FA0FF"),
  )

  // ============================================================================================
  // 数据结构
  // ============================================================================================

  /** 一个词（或合成的伪逐词单元）：时间轴 + 排版位置 + 逐字宽度表。 */
  private class Token(
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

  private class LineLayout(
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

  /** 行轨道：一条可见歌词的动画状态（对齐 Folia 的 LineTrack）。 */
  private class Track {
    var index = 0
    var y = 0f
    var vy = 0f
    var scale = 0.7f
    var vs = 0f
    var alpha = 0f
    var blur = ENTER_BLUR
    var fromAlpha = 0f
    var fromBlur = ENTER_BLUR
    var fadeElapsed = 0f
    var lastTargetAlpha = -1f
    var lastTargetBlur = -1f
    var leaving = false
    var leaveDir = 1
    var dead = false
  }

  // ---- 外部可绑定的数据 ----
  var palette: Palette = Palette()
    set(value) {
      field = value
      invalidateColors()
    }
  var fontScale: Float = 1f
    set(value) {
      if (field == value) return
      field = value
      needsLayout = true
      invalidate()
      ensureCallback()
    }
  var lyrics: List<LyricLine> = emptyList()
    set(value) {
      field = value
      needsLayout = true
      tracks.clear()
      invalidate()
      ensureCallback()
    }
  var effectMode: EffectMode = EffectMode.CLASSIC
    set(value) {
      field = value
      invalidate()
      ensureCallback()
    }

  /** 无歌词时的提示文字（加载中/无歌词）。 */
  var hintText: String = ""
    set(value) {
      field = value
      invalidate()
    }

  /** 封面位图：用作全屏背景（缩到极小再放大 = 廉价高斯模糊）。 */
  var coverBitmap: Bitmap? = null
    set(value) {
      if (field === value) return
      field = value
      blurCover = value?.let { makeTiny(it) }
      invalidate()
    }

  var positionProvider: (() -> Long)? = null
  var playingProvider: (() -> Boolean)? = null
  var onSeek: ((Long) -> Unit)? = null

  // ---- 内部状态 ----
  private var layout: List<LineLayout> = emptyList()
  private var needsLayout = true
  private var viewW = 0
  private var viewH = 0

  private var activeIndex = 0
  private val tracks = ArrayList<Track>()
  private var frameAnimating = false
  private var lastFrameNs = 0L

  // 进度平滑（服务端 100ms 一跳，这里按墙钟外推）
  private var lastProvidedPos = Long.MIN_VALUE
  private var basePos = 0L
  private var basePosNs = 0L

  // ---- 画笔 ----
  private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isFakeBoldText = true
  }
  private val glowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isFakeBoldText = true
  }
  private val transPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
  private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
  }
  private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
  }
  private val coverDst = android.graphics.RectF()
  private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private var bgGradient: LinearGradient? = null
  private var blurCover: Bitmap? = null
  private val blurFilters = HashMap<Int, BlurMaskFilter>()

  private val gestureDetector = GestureDetectorCompat(context, object :
    GestureDetector.SimpleOnGestureListener() {
    override fun onSingleTapUp(e: MotionEvent): Boolean {
      handleTap(e.y)
      return true
    }
  })

  init {
    setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    viewW = w
    viewH = h
    needsLayout = true
    bgGradient = null
  }

  private fun invalidateColors() {
    bgGradient = null
    invalidate()
  }

  private fun sp(value: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics
  )

  private val density: Float
    get() = resources.displayMetrics.density

  /** 极度缩小封面 → 再放大绘制即为平滑模糊，零 RenderEffect 依赖。 */
  private fun makeTiny(src: Bitmap): Bitmap {
    val target = max(24, min(viewW, 96) / 8)
    val w = target
    val h = max(1, (src.height.toLong() * w / max(1, src.width)).toInt())
    return Bitmap.createScaledBitmap(src, w, h, true)
  }

  // ============================================================================================
  // 布局
  // ============================================================================================

  private fun ensureLayout() {
    if (!needsLayout || viewW == 0 || viewH == 0) return
    needsLayout = false

    val fontPx = min(sp(46f * fontScale), viewW * MAX_FONT_TO_WIDTH)
      .coerceAtLeast(sp(18f))
    textPaint.textSize = fontPx
    glowPaint.textSize = fontPx
    transPaint.textSize = fontPx * TRANS_TO_FONT
    hintPaint.textSize = fontPx * 0.5f

    val padX = fontPx * 0.6f
    val maxWidth = viewW - padX * 2f
    val fm = textPaint.fontMetrics
    val rowPitch = fontPx * LINE_HEIGHT
    val baseline0 = rowPitch / 2f - (fm.ascent + fm.descent) / 2f
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
        t.width = textPaint.measureText(t.content)
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
          offsets[k] = textPaint.measureText(t.content, 0, k)
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

    data class Raw(val sb: StringBuilder, var weight: Float)

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

  // ============================================================================================
  // 时间推进
  // ============================================================================================

  private fun isPlaying(): Boolean = playingProvider?.invoke() ?: false

  /** 帧间外推的播放位置：provider 每 100ms 跳一跳，这里按墙钟补齐。 */
  private fun samplePosition(): Long {
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

  // ============================================================================================
  // 行轨道（弹簧 + 计时渐变）
  // ============================================================================================

  private fun focusY(): Float = viewH * FOCUS_Y

  private fun toneScale(d: Int): Float =
    if (d == 0) 1f else max(0.68f, 0.72f * 0.9f.pow(d - 1))

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

  private fun syncTracks() {
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
      val gap = max(sp(12f), textPaint.textSize * GAP_TO_FONT)
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

  private fun stepTracks(dt: Float) {
    frameAnimating = false
    if (layout.isEmpty()) return
    val anchorTop = focusY() - layout[activeIndex].blockHeight / 2f
    val activeH = layout[activeIndex].blockHeight
    val gap = max(sp(12f), textPaint.textSize * GAP_TO_FONT)

    for (t in tracks) {
      val l = layout.getOrNull(t.index)
      if (l == null) {
        t.dead = true
        continue
      }
      val d = if (t.leaving) t.leaveDir else t.index - activeIndex
      val absD = abs(d)
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

  // ============================================================================================
  // 绘制
  // ============================================================================================

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    ensureLayout()
    drawBackground(canvas)

    if (layout.isEmpty()) {
      if (hintText.isNotEmpty()) {
        hintPaint.color = palette.unsung
        hintPaint.alpha = 180
        canvas.drawText(hintText, viewW / 2f, viewH / 2f, hintPaint)
      }
      return
    }

    val pos = samplePosition()
    updateActiveIndex(pos)
    syncTracks()

    val fontPx = textPaint.textSize

    // 绘制顺序：退场轨道 → 上下文行（由远及近）→ 当前行
    val ordered = tracks.sortedWith(
      compareBy({ it.leaving }, { if (it.index == activeIndex) 2 else -abs(it.index - activeIndex) })
    )
    for (t in ordered) {
      val l = layout.getOrNull(t.index) ?: continue
      if (t.alpha <= 0.005f) continue
      val isActive = t.index == activeIndex && !t.leaving
      canvas.save()
      // 以视口水平中心为原点（配合各行的 rowOffsets 实现整行居中）
      canvas.translate(viewW / 2f, t.y)
      canvas.scale(t.scale, t.scale)
      if (isActive) {
        drawActiveLine(canvas, l, pos, t.alpha)
      } else {
        drawContextLine(canvas, l, t.alpha, abs(t.index - activeIndex))
      }
      canvas.restore()
    }
  }

  private fun drawBackground(canvas: Canvas) {
    val tiny = blurCover
    if (tiny != null && !tiny.isRecycled) {
      val scale = max(viewW.toFloat() / tiny.width, viewH.toFloat() / tiny.height)
      val dw = tiny.width * scale
      val dh = tiny.height * scale
      coverPaint.alpha = 255
      coverDst.set((viewW - dw) / 2f, (viewH - dh) / 2f, (viewW + dw) / 2f, (viewH + dh) / 2f)
      canvas.drawBitmap(tiny, null, coverDst, coverPaint)
    }
    if (bgGradient == null) {
      bgGradient = LinearGradient(
        0f, 0f, 0f, viewH.toFloat(),
        scrim(palette.bgTop, 0.62f), scrim(palette.bgBottom, 0.78f),
        Shader.TileMode.CLAMP
      )
    }
    scrimPaint.shader = bgGradient
    canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), scrimPaint)
  }

  /** 主题背景色加不透明度当纱罩，保证模糊封面上的文字可读。 */
  private fun scrim(color: Int, alpha: Float): Int =
    Color.argb((255 * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))

  /** 上下文行：整行一次画完 + 按距离模糊/缩放。 */
  private fun drawContextLine(canvas: Canvas, l: LineLayout, alpha: Float, d: Int) {
    val color = blend(palette.unsung, palette.active, 0.22f)
    textPaint.shader = null
    textPaint.color = color
    textPaint.alpha = (alpha * 255 * 0.9f).toInt().coerceIn(0, 255)
    textPaint.maskFilter = blurFilterFor(toneBlurPx(max(d, 1)))
    for (r in 0 until l.rowCount) {
      val text = l.rowTexts.getOrNull(r) ?: continue
      canvas.drawText(text, l.rowOffsets[r], l.baseline0 + r * l.rowPitch, textPaint)
    }
    textPaint.maskFilter = null
  }

  /** 当前行：扫光 + 光晕 + 译文。 */
  private fun drawActiveLine(canvas: Canvas, l: LineLayout, pos: Long, trackAlpha: Float) {
    val fontPx = textPaint.textSize
    val tokens = l.tokens

    if (tokens.isEmpty()) {
      textPaint.shader = null
      textPaint.color = palette.sung
      textPaint.alpha = (trackAlpha * 255).toInt().coerceIn(0, 255)
      textPaint.maskFilter = null
      for (r in 0 until l.rowCount) {
        val text = l.rowTexts.getOrNull(r) ?: continue
        canvas.drawText(text, l.rowOffsets[r], l.baseline0 + r * l.rowPitch, textPaint)
      }
      drawTranslation(canvas, l, trackAlpha)
      return
    }

    // 模式分发：扫光 / 词弹辉光 / 光室点亮
    when (effectMode) {
      EffectMode.GLOW -> {
        drawGlowWords(canvas, l, pos, trackAlpha)
        drawTranslation(canvas, l, trackAlpha)
        return
      }
      EffectMode.LUMIERE -> {
        drawLumiere(canvas, l, pos, trackAlpha)
        drawTranslation(canvas, l, trackAlpha)
        return
      }
      EffectMode.CLASSIC -> {}
    }

    // 每个词的状态：0=未唱 1=正在唱 2=已唱完
    var singingIdx = -1
    var singingProgress = 0f
    val sung = BooleanArray(tokens.size)
    for (i in tokens.indices) {
      val t = tokens[i]
      if (pos >= t.endTime) {
        sung[i] = true
      } else if (pos >= t.startTime) {
        singingIdx = i
        singingProgress = ((pos - t.startTime).toFloat() /
          (t.endTime - t.startTime).coerceAtLeast(1L)).coerceIn(0f, 1f)
      }
    }

    // 1) 光晕层（画在文字底下）：正在唱 + 尾焰未熄的词
    drawGlow(canvas, l, tokens, pos, trackAlpha)

    // 2) 文字层
    val baseColor = palette.unsung
    val wordColor = blend(palette.active, palette.sung, 0.25f)
    textPaint.maskFilter = null

    for (i in tokens.indices) {
      val t = tokens[i]
      if (t.width <= 0f) continue
      val baseline = l.baseline0 + t.row * l.rowPitch
      val off = l.rowOffsets[t.row]

      if (sung[i]) {
        // 已唱完：整词高亮
        textPaint.shader = null
        textPaint.color = wordColor
        textPaint.alpha = (trackAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawText(t.content, t.x + off, baseline, textPaint)
        continue
      }

      // 未唱部分：暗色底字
      textPaint.shader = null
      textPaint.color = baseColor
      textPaint.alpha = (trackAlpha * 255 * BASE_ALPHA).toInt().coerceIn(0, 255)
      canvas.drawText(t.content, t.x + off, baseline, textPaint)

      if (i == singingIdx && singingProgress > 0f) {
        // 正在唱：软边扫光填充（clip + 沿字形 alpha 渐变）
        drawSweepFill(canvas, t, baseline, pos, singingProgress, baseColor, wordColor, trackAlpha, fontPx, off)
      }
    }

    drawTranslation(canvas, l, trackAlpha)
  }

  /**
   * 扫光填充：对正在唱的词，裁到软边前沿，再用一条横向渐变着色。
   * 渐变 = 「前沿软边遮罩 (1→0.92→0) × 沿字形墨迹渐变 (1→0.92@68%→0.72)」，
   * 断点取两组线性段的并集逐点相乘，与 Folia shader 的 mask*gradient 等价。
   */
  private fun drawSweepFill(
    canvas: Canvas,
    t: Token,
    baseline: Float,
    pos: Long,
    progress: Float,
    baseColor: Int,
    wordColor: Int,
    trackAlpha: Float,
    fontPx: Float,
    off: Float
  ) {
    val ox = t.x + off
    val w = t.width
    val fill = t.fillWidth(pos)
    val soft = (fontPx * 0.45f).coerceIn(6f * density, 16f * density)
    val front = fill + soft * progress

    // 颜色随词进度从底色过渡到高亮色
    val fillRgb = blend(baseColor, wordColor, progress)

    // 断点（词内相对 px）：字形渐变 {0, 0.68w, w} ∪ 软边 {front-soft, front-0.55soft, front}
    val stops = FloatArray(7)
    var n = 0
    fun add(v: Float) {
      val c = v.coerceIn(0f, w)
      for (k in 0 until n) {
        if (abs(stops[k] - c) < 0.5f) return
      }
      stops[n++] = c
    }
    add(0f)
    add(0.68f * w)
    add(w)
    add(front - soft)
    add(front - soft * 0.55f)
    add(front)
    // 排序（插入序可能乱）
    for (a in 1 until n) {
      val key = stops[a]
      var b = a - 1
      while (b >= 0 && stops[b] > key) {
        stops[b + 1] = stops[b]
        b--
      }
      stops[b + 1] = key
    }

    fun maskAlpha(x: Float): Float = when {
      x <= front - soft -> 1f
      x <= front - soft * 0.55f -> 1f - 0.08f * ((x - (front - soft)) / max(0.5f, soft * 0.45f))
      x < front -> 0.92f * (1f - (x - (front - soft * 0.55f)) / max(0.5f, soft * 0.55f))
      else -> 0f
    }

    fun glyphAlpha(x: Float): Float =
      if (x < 0.68f * w) 1f - 0.08f * (x / max(0.5f, 0.68f * w))
      else 0.92f - 0.20f * ((x - 0.68f * w) / max(0.5f, 0.32f * w))

    val colors = IntArray(n)
    val positions = FloatArray(n)
    for (k in 0 until n) {
      val x = stops[k]
      val a = (maskAlpha(x) * glyphAlpha(x)).coerceIn(0f, 1f)
      colors[k] = withAlpha(fillRgb, (a * 255).toInt())
      positions[k] = x / w
    }
    positions[n - 1] = max(positions[n - 1], positions[max(0, n - 2)] + 0.001f)
    positions[0] = 0f

    textPaint.shader = LinearGradient(
      ox, 0f, ox + w, 0f,
      colors, positions, Shader.TileMode.CLAMP
    )
    textPaint.color = Color.WHITE
    textPaint.alpha = (trackAlpha * 255).toInt().coerceIn(0, 255)

    canvas.save()
    canvas.clipRect(
      ox, baseline + textPaint.fontMetrics.ascent - fontPx * 0.15f,
      ox + front, baseline + textPaint.fontMetrics.descent + fontPx * 0.1f
    )
    canvas.drawText(t.content, ox, baseline, textPaint)
    canvas.restore()
    textPaint.shader = null
  }

  /** 双层高斯光晕：升到峰值 = 词时长 × 1.18，消散到 max(行尾, 词尾 + 1.05s)。 */
  private fun drawGlow(
    canvas: Canvas,
    l: LineLayout,
    tokens: List<Token>,
    pos: Long,
    trackAlpha: Float
  ) {
    val fontPx = textPaint.textSize
    val r1 = fontPx * 0.28f
    val r2 = fontPx * 0.65f
    val maxAlpha = 0.88f
    val glowColor = palette.activeSecondary

    glowPaint.shader = null
    glowPaint.maskFilter = null

    for ((i, t) in tokens.withIndex()) {
      if (t.width <= 0f) continue
      val g = glowEnvelope(pos, t, l.lineEndMs)
      if (g <= 0.02f) continue
      // 还没唱的词不发光；正在唱与已唱的词按包络发光
      if (pos <= t.startTime) continue

      val color = blend(palette.active, glowColor, g)
      val alpha = (g * maxAlpha * trackAlpha * 255).toInt().coerceIn(0, 255)
      val baseline = l.baseline0 + t.row * l.rowPitch
      val ox = t.x + l.rowOffsets[t.row]

      glowPaint.color = color
      glowPaint.alpha = alpha
      glowPaint.setShadowLayer(r1, 0f, 0f, withAlpha(glowColor, (g * maxAlpha * 220).toInt()))
      canvas.drawText(t.content, ox, baseline, glowPaint)
      glowPaint.setShadowLayer(r2, 0f, 0f, withAlpha(glowColor, (g * maxAlpha * 130).toInt()))
      canvas.drawText(t.content, ox, baseline, glowPaint)
    }
    glowPaint.clearShadowLayer()
  }

  // ============================================================================================
  // 特效 2：GlowWord（folia classic/partita）—— 整词弹入 + 逐字辉光脉冲，无扫光填充
  // ============================================================================================

  /**
   * spring(stiffness=200, damping=20) 的阶跃响应：ωn=√200≈14.1，ζ≈0.707，ωd≈10。
   * age<0 返回 0；稳态 1，带轻微过冲。
   */
  private fun springResp(age: Float): Float {
    if (age <= 0f) return 0f
    val e = exp(-10f * age)
    return 1f - e * (kotlin.math.cos(10f * age) + 0.707f * sin(10f * age))
  }

  /** 确定性伪随机（稳定种子，逐帧一致）。 */
  private fun seedRand(i: Int, salt: Int): Float {
    val x = sin(i * 127.1f + salt * 311.7f) * 43758.5453f
    return x - kotlin.math.floor(x)
  }

  /** GlowWord 正文：waiting 暗淡模糊 → active 弹入变亮 → passed 淡回底色并微旋转漂移。 */
  private fun drawGlowWords(canvas: Canvas, l: LineLayout, pos: Long, trackAlpha: Float) {
    val fontPx = textPaint.textSize
    val fm = textPaint.fontMetrics
    val centerY = (fm.ascent + fm.descent) / 2f
    val baseColor = palette.unsung
    val activeColor = blend(palette.active, palette.sung, 0.35f)
    val waitBlur = blurFilterFor(fontPx * 0.18f)
    // 行级呼吸浮动（folia classic：7s 周期，约 0.025 字号）
    val breath = sin(lastFrameNs / 1_000_000_000f * (2f * Math.PI.toFloat() / 7f)) * fontPx * 0.025f

    for ((i, t) in l.tokens.withIndex()) {
      if (t.width <= 0f) continue
      val durMs = (t.endTime - t.startTime).coerceAtLeast(1L)
      val passed = pos >= t.endTime
      val active = pos >= t.startTime && !passed
      val off = l.rowOffsets[t.row]

      // 词级变换：弹入缩放 + passed 微旋转
      val age = (pos - t.startTime) / 1000f
      val pop = springResp(age)
      val scale = 0.5f + 0.5f * pop
      val rotDeg = if (passed) {
        val dir = if (seedRand(i, 3) > 0.5f) 1f else -1f
        (10f + seedRand(i, 4) * 12.5f) * dir *
          (((pos - t.endTime) / 5000f).coerceIn(0f, 1f))
      } else 0f

      val cx = t.x + t.width / 2f + off
      val by = l.baseline0 + t.row * l.rowPitch + breath
      val wordColor = when {
        active -> blend(baseColor, activeColor, ((pos - t.startTime).toFloat() / durMs).coerceIn(0f, 1f))
        passed -> blend(activeColor, baseColor, ((pos - t.endTime).toFloat() / 800f).coerceIn(0f, 1f))
        else -> baseColor
      }

      canvas.save()
      canvas.translate(cx, by + centerY)
      if (rotDeg != 0f) canvas.rotate(rotDeg)
      canvas.scale(scale, scale)
      canvas.translate(-cx, -(by + centerY))

      // 正文
      textPaint.shader = null
      textPaint.color = wordColor
      textPaint.alpha = (trackAlpha * (if (passed) 255 * 0.82f else 255f)).toInt().coerceIn(0, 255)
      textPaint.maskFilter = if (!active && !passed) waitBlur else null
      canvas.drawText(t.content, t.x + off, by, textPaint)
      textPaint.maskFilter = null
      canvas.restore()

      // 辉光层：逐字脉冲，峰值曲线 times[0,0.3,1]、时长 = 字时长×6（快速起、慢速落）
      if (!active && !passed) continue
      val n = t.charOffsets.size - 1
      for (k in 0 until n) {
        val ccx = t.x + (t.charOffsets[k] + t.charOffsets[k + 1]) / 2f + off
        val charFrac = (t.charOffsets[k] / max(0.5f, t.width)).coerceIn(0f, 1f)
        val charStart = t.startTime + (durMs * charFrac).toLong()
        val charDur = (durMs * ((t.charOffsets[k + 1] - t.charOffsets[k]) / max(0.5f, t.width)))
          .toLong().coerceAtLeast(80L)
        val gAge = (pos - charStart) / 1000f
        if (gAge <= 0f) continue
        val gDur = (charDur / 1000f * 6f).coerceIn(0.6f, 3.0f)
        val frac = (gAge / gDur).coerceIn(0f, 1f)
        val peak = if (frac < 0.3f) {
          val p = frac / 0.3f
          p * p * (3f - 2f * p)
        } else {
          val p = (frac - 0.3f) / 0.7f
          1f - p * p * (3f - 2f * p)
        }
        if (peak <= 0.02f) continue
        glowPaint.shader = null
        glowPaint.maskFilter = null
        glowPaint.color = activeColor
        glowPaint.alpha = (peak * trackAlpha * 255).toInt().coerceIn(0, 255)
        glowPaint.setShadowLayer(
          fontPx * 0.35f, 0f, 0f,
          withAlpha(palette.activeSecondary, (peak * 200).toInt().coerceIn(0, 255))
        )
        canvas.drawText(t.content, k, k + 1, ccx - (t.charOffsets[k + 1] - t.charOffsets[k]) / 2f, by, glowPaint)
        glowPaint.setShadowLayer(
          fontPx * 0.7f, 0f, 0f,
          withAlpha(palette.activeSecondary, (peak * 120).toInt().coerceIn(0, 255))
        )
        canvas.drawText(t.content, k, k + 1, ccx - (t.charOffsets[k + 1] - t.charOffsets[k]) / 2f, by, glowPaint)
      }
    }
    glowPaint.clearShadowLayer()
  }

  // ============================================================================================
  // 特效 3：Lumiere lyricWindow（folia lumiere）—— 字形点亮 + 四芒星闪点 + 崩解漂散，无填充
  // ============================================================================================

  /** smoothstep 01。 */
  private fun smooth01(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
  }

  /** Lumiere 闪点包络：0.15s 攻击上升，之后 exp 衰减（decay 0.45s）。 */
  private fun flashEnv(posMs: Long, startMs: Long, durMs: Long): Float {
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

  /** Lumiere 光室：未唱字是冷色半亮刻字，唱到的字被点亮并爆出星形闪点，唱过的字崩解漂散。 */
  private fun drawLumiere(canvas: Canvas, l: LineLayout, pos: Long, trackAlpha: Float) {
    val fontPx = textPaint.textSize
    val fm = textPaint.fontMetrics
    val centerY = (fm.ascent + fm.descent) / 2f
    val cold = blend(palette.unsung, palette.active, 0.21f)   // illum≈0.6 → 0.35*0.6
    val hot = blend(palette.active, palette.sung, 0.5f)
    // 关键词 = 最长的实词 token
    val keyIdx = l.tokens.indices.maxByOrNull { l.tokens[it].width } ?: -1

    for ((i, t) in l.tokens.withIndex()) {
      if (t.width <= 0f) continue
      val durMs = (t.endTime - t.startTime).coerceAtLeast(1L)
      val off = l.rowOffsets[t.row]

      // 词级字号错落：重点词 1.45，标点 0.62，其余 0.85+rng*0.35；垂直错位
      val isPunct = t.content.none { it.isLetterOrDigit() || isCjk(it) }
      val wScale = when {
        i == keyIdx -> 1.45f
        isPunct -> 0.62f
        else -> 0.85f + seedRand(i, 5) * 0.35f
      }
      val dy = when {
        i == keyIdx -> 0f
        isPunct -> fontPx * 0.32f * (if (seedRand(i, 6) > 0.5f) 1f else -1f)
        else -> fontPx * 0.2f * (if (seedRand(i, 7) > 0.5f) 1f else -1f)
      }

      val n = t.charOffsets.size - 1
      for (k in 0 until n) {
        val cw = t.charOffsets[k + 1] - t.charOffsets[k]
        if (cw <= 0f) continue
        val ccx = t.x + (t.charOffsets[k] + t.charOffsets[k + 1]) / 2f + off
        val by = l.baseline0 + t.row * l.rowPitch + dy
        val cy = by + centerY

        val charFrac = (t.charOffsets[k] / max(0.5f, t.width)).coerceIn(0f, 1f)
        val charStart = t.startTime + (durMs * charFrac).toLong()
        val charDur = (durMs * (cw / max(0.5f, t.width))).toLong().coerceAtLeast(80L)

        // 点亮进度 + 闪点（闪点随 age 从字起点指数衰减，覆盖「唱到的一瞬」）
        val lit = smooth01((pos - charStart).toFloat() / max(charDur, 300L).toFloat())
        val flash = flashEnv(pos, charStart, charDur)
        val heat = (0.6f * 1.3f + flash * 0.2f).coerceIn(0f, 1f)
        val revealed = min(1f, 0.35f + 0.6f * 0.45f)   // 未唱字被光场照出的冷色半亮

        // 崩解漂散：唱完后漂起 + 随机横向 + 淡出（约 1.6s 消失）
        var dissolve = 0f
        var ddx = 0f
        var ddy = 0f
        var rot = 0f
        if (lit >= 1f) {
          val age = ((pos - t.endTime) / 1000f).coerceAtLeast(0f)
          dissolve = smooth01(age / 1.6f)
          val drift = age * age.coerceAtLeast(0f)
          ddy = -fontPx * 0.42f * drift
          ddx = fontPx * 0.22f * drift * (if (seedRand(i * 31 + k, 8) > 0.5f) 1f else -1f)
          rot = (if (seedRand(i * 17 + k, 9) > 0.5f) 1f else -1f) * 14f * drift
        }

        canvas.save()
        canvas.translate(ccx + ddx, cy + ddy)
        canvas.rotate(rot)
        canvas.scale(wScale, wScale)

        // 字色：未亮 = 冷色半亮；亮 = 热色
        val tint = blend(cold, hot, lit)
        val bodyAlpha = ((revealed * (1f - lit) + lit * min(1f, 0.55f + 0.4f * heat)) *
          (1f - dissolve) * trackAlpha * 255).toInt().coerceIn(0, 255)
        textPaint.shader = null
        textPaint.color = tint
        textPaint.alpha = bodyAlpha
        textPaint.maskFilter = null
        canvas.drawText(t.content, k, k + 1, -cw / 2f, centerY, textPaint)
        canvas.restore()

        // 光晕：点亮后的柔光
        if (lit > 0.05f && dissolve < 0.95f) {
          glowPaint.shader = null
          glowPaint.maskFilter = null
          glowPaint.color = palette.active
          glowPaint.alpha = (lit * (0.10f + 0.25f * flash) * (1f - dissolve) * trackAlpha * 255)
            .toInt().coerceIn(0, 255)
          glowPaint.setShadowLayer(
            fontPx * 0.8f, 0f, 0f,
            withAlpha(palette.activeSecondary, (lit * 160).toInt().coerceIn(0, 255))
          )
          canvas.save()
          canvas.translate(ccx + ddx, cy + ddy)
          canvas.scale(wScale, wScale)
          canvas.drawText(t.content, k, k + 1, -cw / 2f, centerY, glowPaint)
          canvas.restore()
        }

        // 四芒星闪点：唱到的一瞬
        if (flash > 0.08f) {
          val sx = ccx + ddx + fontPx * 0.22f * (seedRand(i * 13 + k, 10) - 0.5f)
          val sy = cy + ddy + fontPx * 0.22f * (seedRand(i * 29 + k, 11) - 0.5f) - fontPx * 0.25f
          val len = fontPx * 0.5f * (0.8f + 0.5f * flash)
          val starAlpha = (flash * 0.55f * trackAlpha * 255).toInt().coerceIn(0, 255)
          starPaint.color = blend(palette.active, Color.WHITE, 0.5f)
          starPaint.alpha = starAlpha
          starPaint.strokeWidth = fontPx * 0.045f
          val angle = seedRand(i * 7 + k, 12) * 90f + (1f - flash) * 30f
          canvas.save()
          canvas.translate(sx, sy)
          canvas.rotate(angle)
          canvas.drawLine(-len, 0f, len, 0f, starPaint)
          canvas.drawLine(0f, -len * 0.55f, 0f, len * 0.55f, starPaint)
          canvas.restore()
        }
      }
    }
    glowPaint.clearShadowLayer()
  }

  private fun glowEnvelope(pos: Long, t: Token, lineEnd: Long): Float {    if (pos <= t.startTime) return 0f
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

  private fun drawTranslation(canvas: Canvas, l: LineLayout, trackAlpha: Float) {
    val trans = l.line.translation ?: return
    transPaint.color = palette.translation
    transPaint.alpha = (trackAlpha * 255 * TRANSLATION_ALPHA).toInt().coerceIn(0, 255)
    transPaint.shader = null
    transPaint.maskFilter = null
    val tw = transPaint.measureText(trans)
    val fm = transPaint.fontMetrics
    val transBaseline = transPaint.textSize / 2f - (fm.ascent + fm.descent) / 2f
    canvas.drawText(trans, -tw / 2f, l.mainHeight + fontPxOf(transPaint) * 0.3f + transBaseline, transPaint)
  }

  private fun fontPxOf(p: Paint): Float = p.textSize

  private fun blurFilterFor(radiusPx: Float): BlurMaskFilter? {
    if (radiusPx < 0.5f) return null
    val key = radiusPx.toInt()
    return blurFilters.getOrPut(key) {
      BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
    }
  }

  private fun handleTap(y: Float) {
    // 命中测试：找被点中的轨道，取离当前行最近的一个
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
    val target = best ?: return
    onSeek?.invoke(layout[target.index].line.time)
    ensureCallback()
  }

  // ============================================================================================
  // 帧循环：只在需要时注册，detach 后停止
  // ============================================================================================

  private fun ensureCallback() {
    if (!callbackRegistered && isAttachedToWindow) {
      callbackRegistered = true
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  private var callbackRegistered = false

  override fun doFrame(frameTimeNanos: Long) {
    callbackRegistered = false
    if (!isAttachedToWindow) return

    val dt = if (lastFrameNs == 0L) 0.016f
    else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
    lastFrameNs = frameTimeNanos

    ensureLayout()
    val pos = samplePosition()
    updateActiveIndex(pos)
    syncTracks()
    stepTracks(dt)
    invalidate()

    val needMore = isPlaying() || frameAnimating || effectBusy(pos)
    if (needMore) ensureCallback()
  }

  /** 当前行还在唱 / 光晕尾巴未熄 → 继续取帧；暂停且静止时完全停摆。 */
  private fun effectBusy(pos: Long): Boolean {
    val l = layout.getOrNull(activeIndex) ?: return false
    return pos >= l.line.time - 200L && pos <= l.lineEndMs + GLOW_TAIL_MS
  }

  fun onPlaybackStateChanged() {
    ensureCallback()
  }

  override fun onDetachedFromWindow() {
    // 不再重新注册；挂起中的一次回调会因 isAttachedToWindow=false 直接返回
    callbackRegistered = false
    super.onDetachedFromWindow()
  }

  // ============================================================================================
  // 工具
  // ============================================================================================

  private fun blend(a: Int, b: Int, f: Float): Int {
    val k = f.coerceIn(0f, 1f)
    return Color.argb(
      Color.alpha(a),
      (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt().coerceIn(0, 255),
      (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt().coerceIn(0, 255),
      (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt().coerceIn(0, 255)
    )
  }

  private fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

  private fun Float.pow(n: Int): Float = Math.pow(this.toDouble(), n.toDouble()).toFloat()

  companion object {
    private const val FOCUS_Y = 0.5f
    private const val TRACK_WINDOW = 2
    private const val LINE_HEIGHT = 1.18f
    private const val MAX_FONT_TO_WIDTH = 0.09f
    private const val TRANS_TO_FONT = 0.5f
    private const val GAP_TO_FONT = 0.49f
    private const val BASE_ALPHA = 0.42f
    private const val TRANSLATION_ALPHA = 0.68f

    private const val ENTER_OFFSET_PX = 34f
    private const val EXIT_OFFSET_PX = 38f
    private const val ENTER_SCALE = 0.7f
    private const val ENTER_BLUR = 5f
    private const val FADE_ALPHA_SEC = 0.28f
    private const val FADE_BLUR_SEC = 0.32f

    private const val GLOW_TAIL_MS = 1050L
    private const val DEFAULT_LINE_MS = 4000L
  }
}
