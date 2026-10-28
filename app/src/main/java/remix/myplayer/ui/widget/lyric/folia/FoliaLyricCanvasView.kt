package remix.myplayer.ui.widget.lyric.folia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.GestureDetectorCompat
import remix.myplayer.lyric.LyricLine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ============================================================================================
// 文件级类型与常量（与 InnerCanvasView 同文件，private 可见）
// ============================================================================================
/** 镜台透视：焦距越小收敛越猛（1.0 + |d|×1.45 → 相邻行只剩 41%）。 */
private const val DIORAMA_FOCAL = 1.0f
private const val DIORAMA_DEPTH = 1.45f

/**
 * 特效歌词渲染器（Folia 风格，纯 Canvas 2D 管线）。
 *
 * 本类是「薄封装」：全部排版 / 动画 / 计时逻辑在 [FoliaLyricEngine]（无 android.graphics 依赖），
 * 渲染路径直接消费引擎产出的 [FrameState] 快照。
 *
 * - 6 种模式（CLASSIC 流光 / LUMIERE 绘光 / DIORAMA 镜台 / FUME 浮名 / CADENZA 心象 /
 *   SONNET 商籁）全部由 Canvas 2D 原生绘制（流光扫光 / 绘光 / 镜台透视 / 浮名字雾 /
 *   心象升腾 / 商籁镂空），零 GLES 依赖、车机零黑屏；
 * - 公开 API：lyrics / fontScale / effectMode / hintText / coverBitmap /
 *   positionProvider / playingProvider / palette / onSeek / onPlaybackStateChanged；
 * - 播放进度按墙钟外推（服务端 100ms 一跳）；只在「播放中 / 弹簧未落位 / 光晕未熄 /
 *   粒子未散尽」时注册 Choreographer。
 *
 * 类型说明：EffectMode / Palette 为引擎的顶层类型（[EffectMode]、[Palette]）。
 */
class FoliaLyricCanvasView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), Choreographer.FrameCallback {

  // ============================================================================================
  // 公开 API（与作者原版一致）
  // ============================================================================================

  /** 配色（ARGB）。由 Compose 层随主题注入。 */
  var palette: Palette = Palette()
    set(value) {
      if (field == value) return
      field = value
      backgroundRenderer.palette = value
      updateLyricPalette()
      invalidateColors()
    }

  /**
   * 歌词实际使用的配色。
   *
   * 与 [palette] 分开：背景是「画出来」的（封面模糊 / 莫奈洗色 / 漫游色阶），
   * 歌词又恰好可能来自同一张封面（「主色调取自封面」）——两者同源时颜色极近，歌词会糊掉。
   * 这里按背景的**等效色**做 WCAG 对比度校正（见 [LyricContrast]），
   * 只动歌词颜色的明度，**不动背景用色**，避免「改歌词色 → 背景变 → 再改色」的循环。
   */
  private var lyricPalette: Palette = Palette()

  /** 动态背景模式（封面模糊 / 莫奈 / 漫游 / 空）。 */
  var backgroundMode: LyricBackgroundMode = LyricBackgroundMode.COVER_BLUR
    set(value) {
      if (field == value) return
      field = value
      backgroundRenderer.mode = value
      invalidate()
      ensureCallback()
    }

  var fontScale: Float = 1f
    set(value) {
      if (field == value) return
      field = value
      engine.fontScale = value
      invalidate()
      ensureCallback()
    }

  var lyrics: List<LyricLine> = emptyList()
    set(value) {
      if (field === value) return
      field = value
      engine.lyrics = value
      invalidate()
      ensureCallback()
    }

  var effectMode: EffectMode = EffectMode.CLASSIC
    set(value) {
      if (field == value) return
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
      backgroundRenderer.setCover(value)
      invalidate()
    }

  var positionProvider: (() -> Long)? = null
  var playingProvider: (() -> Boolean)? = null
  var onSeek: ((Long) -> Unit)? = null

  /**
   * 当前实际生效的模式。
   * 全部 6 种模式均由 Canvas 2D 原生渲染，直接按所选模式渲染，保证车机始终有完整特效。
   */
  fun effectiveMode(): EffectMode = effectMode

  // ============================================================================================
  // 内部状态
  // ============================================================================================

  private val engine = FoliaLyricEngine(TextPaintMetrics())
  private val canvasView = InnerCanvasView(context)

  private var callbackRegistered = false
  private var lastFrameNs = 0L

  /** 背景渲染器（莫奈 / 漫游 / 空 + 原封面模糊）。 */
  private val backgroundRenderer = FoliaBackgroundRenderer(
    // 后台线程回调，用 postInvalidate 而不是 invalidate（跨线程 invalidate 不安全）
    requestRender = { postInvalidate() },
    density = resources.displayMetrics.density
  )

  /** 背景动画时钟（秒）。由帧循环推进，仅在动画型背景下有效。 */
  private var backgroundClock = 0f

  private val gestureDetector = GestureDetectorCompat(context, object :
    GestureDetector.SimpleOnGestureListener() {
    override fun onSingleTapUp(e: MotionEvent): Boolean {
      engine.seekTarget(e.y)?.let { onSeek?.invoke(it) }
      ensureCallback()
      return true
    }
  })

  init {
    clipChildren = false
    addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    engine.density = resources.displayMetrics.density
    engine.spUnit = resources.displayMetrics.scaledDensity
    engine.positionProvider = { positionProvider?.invoke() ?: 0L }
    engine.playingProvider = { playingProvider?.invoke() ?: false }
    setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }
    // 背景等效色在后台线程算出，回调 post 回主线程再刷新歌词配色
    backgroundRenderer.onBackdropChanged = { post { updateLyricPalette() } }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    ensureCallback()
  }

  override fun onDetachedFromWindow() {
    callbackRegistered = false
    super.onDetachedFromWindow()
  }

  // ============================================================================================
  // 帧循环
  // ============================================================================================

  private fun ensureCallback() {
    if (!callbackRegistered && isAttachedToWindow) {
      callbackRegistered = true
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  override fun doFrame(frameTimeNanos: Long) {
    callbackRegistered = false
    if (!isAttachedToWindow) return

    val dt = if (lastFrameNs == 0L) 0.016f
    else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
    lastFrameNs = frameTimeNanos

    val playing = playingProvider?.invoke() ?: false

    // 背景动画时钟（漂移 / 星空闪烁）。暂停时冻结（车机省电，与 Folia 的 paused 语义一致）。
    if (backgroundRenderer.animated && playing) {
      backgroundClock += dt
    }

    val f = engine.stepFrame(dt)
    lastFrame = f

    canvasView.frame = f
    canvasView.invalidate()

    // 歌词引擎空闲时，若背景需要动画（莫奈漂移 / 漫游漂移 / 星空闪烁）仍要继续取帧
    if (engine.isBusy() || (backgroundRenderer.animated && playing)) ensureCallback()
  }

  @Volatile
  private var lastFrame: FrameState? = null

  fun onPlaybackStateChanged() {
    ensureCallback()
  }

  private fun invalidateColors() {
    canvasView.invalidateColors()
    invalidate()
  }

  /**
   * 按背景等效色重算歌词配色。
   *
   * 背景等效色在后台线程算出（换封面 / 换背景模式 / 换主题色都会变），
   * 所以回调统一 post 回主线程再更新。
   */
  private fun updateLyricPalette() {
    val backdrop = backgroundRenderer.backdropColor
    val corrected = if (backdrop == 0) palette else palette.readableAgainst(backdrop)
    if (corrected == lyricPalette) return
    lyricPalette = corrected
    invalidateColors()
  }

  // ============================================================================================
  // 工具
  // ============================================================================================

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    engine.viewW = w
    engine.viewH = h
    backgroundRenderer.onSizeChanged(w, h)
  }

  /** 文字测量适配器：引擎的 [TextMetrics] 由 textPaint 实现（Canvas 路径用）。 */
  private class TextPaintMetrics : TextMetrics {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      isFakeBoldText = true
    }

    override var textSize: Float
      get() = paint.textSize
      set(value) {
        paint.textSize = value
      }

    override fun measure(text: String): Float = paint.measureText(text)
    override fun measure(text: String, start: Int, end: Int): Float =
      paint.measureText(text, start, end)

    override val ascent: Float get() = paint.fontMetrics.ascent
    override val descent: Float get() = paint.fontMetrics.descent
  }

  companion object {
  }

  // ============================================================================================
  // Canvas 渲染路径：作者原实现的完整移植（数据来自引擎 FrameState）
  // ============================================================================================

  private inner class InnerCanvasView(context: Context) : View(context) {

    private var viewW = 0
    private var viewH = 0

    // ---- 画笔（与原实现一致）----
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
    private val blurFilters = HashMap<Int, BlurMaskFilter>()
    /** 心象（CADENZA）四芒星闪点。 */
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
    }

    // ---- 镜台（DIORAMA）：四点透视 + 台面倒影 ----
    private val dioramaMatrix = android.graphics.Matrix()
    private val dioramaSrc = FloatArray(8)
    private val dioramaDst = FloatArray(8)
    private val floorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      isFakeBoldText = true
    }

    // ---- 心象（CADENZA）/ 商籁（SONNET）辅助画笔 ----
    /** 扫光光束遮罩：重绘字作 alpha 掩膜，配合 SRC_ATOP 把光束限制在字上。 */
    private val maskPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    /** 横向扫光光束（SRC_ATOP 限制在字上）。 */
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    /** 商籁诗稿细边框。 */
    private val sonnetFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 1.4f
    }
    /** 商籁信箱黑边。 */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 商籁暗角（按屏幕尺寸缓存，onSizeChanged 重建）。 */
    private var vignetteShader: RadialGradient? = null

    @Volatile
    var frame: FrameState? = null

    private val density: Float
      get() = resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
      super.onSizeChanged(w, h, oldw, oldh)
      viewW = w
      viewH = h
      // 商籁暗角：中心透明、四角渐暗（半径取对角线一半）
      val cx = w / 2f
      val cy = h / 2f
      val rad = kotlin.math.hypot(w.toDouble(), h.toDouble()) / 2.0
      vignetteShader = RadialGradient(
        cx, cy, rad.toFloat() * 0.62f,
        intArrayOf(0x00000000, 0x00000000, 0x66000000.toInt(), 0xB3000000.toInt()),
        floatArrayOf(0f, 0.55f, 0.82f, 1f), Shader.TileMode.CLAMP
      )
    }

    fun invalidateColors() {
      invalidate()
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val f = frame ?: run {
        drawBackground(canvas)
        drawHint(canvas)
        return
      }
      drawBackground(canvas)

      if (f.layout.isEmpty()) {
        drawHint(canvas)
        return
      }

      // 字号同步（引擎 ensureLayout 只设置测量用字号，这里同步到绘制画笔）
      val fontPx = f.fontPx
      if (abs(textPaint.textSize - fontPx) > 0.01f) {
        textPaint.textSize = fontPx
        glowPaint.textSize = fontPx
        transPaint.textSize = fontPx * FoliaLyricEngine.TRANS_TO_FONT
        hintPaint.textSize = fontPx * 0.5f
      }

      // 绘制顺序：退场轨道 → 上下文行（由远及近）→ 当前行
      val mode = effectiveMode()
      val diorama = mode == EffectMode.DIORAMA
      // 镜台焦点（车头视角的地平线）：视口中偏下，随时间缓慢呼吸（相机推进感）
      val nowSec = engine.lastPositionMs / 1000f
      val focusY = viewH * 0.58f + sin(nowSec * 0.42f) * viewH * 0.012f
      val ordered = f.tracks.sortedWith(
        compareBy({ it.leaving }, { if (it.lineIndex == f.activeIndex) 2 else -abs(it.lineIndex - f.activeIndex) })
      )
      // 镜台：先在台面上铺一层当前行的镜像倒影（文字之前绘制）
      if (diorama) drawDioramaFloor(canvas, f)
      for (t in ordered) {
        val l = f.layout.getOrNull(t.lineIndex) ?: continue
        if (t.alpha <= 0.005f) continue
        val isActive = t.lineIndex == f.activeIndex && !t.leaving
        canvas.save()
        if (diorama) {
          applyDioramaTransform(canvas, l, t, f.activeIndex, focusY, nowSec)
        } else if (mode == EffectMode.FUME) {
          // 浮名：当前行放大成主角，上下文行缩到很小（大字报 + 相机穿行感）
          val s = if (isActive) t.scale * 1.5f else t.scale * 0.55f
          canvas.translate(viewW / 2f, t.y)
          canvas.scale(s, s)
        } else {
          // 以视口水平中心为原点（配合各行的 rowOffsets 实现整行居中）
          canvas.translate(viewW / 2f, t.y)
          canvas.scale(t.scale, t.scale)
        }
        if (isActive) {
          drawActiveLine(canvas, l, f.positionMs, t.alpha, t)
        } else {
          val di = abs(t.lineIndex - f.activeIndex)
          // 透视会把行等比例缩小，模糊半径若不放大，远处反而显得更清晰
          val boost = if (diorama) {
            val persp = DIORAMA_FOCAL / (DIORAMA_FOCAL + abs(t.lineIndex - f.activeIndex) * DIORAMA_DEPTH)
            (1f / persp).coerceIn(1f, 3f)
          } else 1f
          drawContextLine(canvas, l, t.alpha, di, boost)
        }
        canvas.restore()
      }
      // 商籁：胶片信箱黑边 + 暗角（屏幕级叠加，盖在歌词之上）
      if (mode == EffectMode.SONNET) drawSonnetLetterbox(canvas)
    }

    // ============================================================================================
    // 镜台飞行（DIORAMA）：真四点透视（梯形）+ 焦点收敛 + 台面倒影
    // ============================================================================================

    /**
     * 把当前 canvas 变换到该行的「立体书」位置。
     *
     * 用 [android.graphics.Matrix.setPolyToPoly] 做四点映射，源矩形是行内坐标
     * （x ∈ [-行宽/2, +行宽/2]、y ∈ [0, blockHeight]），目标是屏幕上的梯形：
     * - 透视缩放 persp = focal / (focal + |d|·depth)，相邻行只剩 41%，纵深差异一眼可见；
     * - 行中心向焦点（地平线）收敛，越远越贴近焦点；
     * - 近边更宽、远边更窄，形成「斜贴在台面上」的立体倾斜（不是简单缩放）。
     */
    private fun applyDioramaTransform(
      canvas: Canvas,
      l: LineLayout,
      t: TrackSnapshot,
      activeIndex: Int,
      focusY: Float,
      nowSec: Float,
    ) {
      val d = (t.lineIndex - activeIndex).toFloat()
      val persp = DIORAMA_FOCAL / (DIORAMA_FOCAL + abs(d) * DIORAMA_DEPTH)
      val maxW = (l.rowWidths.maxOrNull() ?: 0f)
      if (maxW <= 0f) return
      val halfW = maxW / 2f

      // 行中心向焦点收敛，但只收 22%：若按 persp 全额收敛，远处行会全部塌到焦点上叠成一片。
      // 缩放给足（相邻行 41%），位置少收（78%~100%），既有纵深又不重叠。
      val rowCy = t.y + l.blockHeight * 0.5f
      val converge = 0.78f + 0.22f * persp
      val cy = focusY + (rowCy - focusY) * converge
      // 各行为不同相位的轻微横向漂移，强化空间层次
      val drift = sin(nowSec * 0.55f + t.lineIndex * 1.9f) * maxW * 0.014f * persp
      val cx = viewW / 2f + drift

      val w = halfW * t.scale * persp
      val h = l.blockHeight * t.scale * persp
      val top = cy - h / 2f
      val bottom = cy + h / 2f

      // 梯形倾斜：d>0（尚未唱，在焦点下方）下边更近→更宽；d<0 反之
      val k = when {
        d > 0.01f -> 0.24f
        d < -0.01f -> -0.24f
        else -> 0f
      }
      val topHalf = w * (1f - k)
      val bottomHalf = w * (1f + k)

      dioramaSrc[0] = -halfW; dioramaSrc[1] = 0f
      dioramaSrc[2] = halfW; dioramaSrc[3] = 0f
      dioramaSrc[4] = -halfW; dioramaSrc[5] = l.blockHeight
      dioramaSrc[6] = halfW; dioramaSrc[7] = l.blockHeight

      dioramaDst[0] = cx - topHalf; dioramaDst[1] = top
      dioramaDst[2] = cx + topHalf; dioramaDst[3] = top
      dioramaDst[4] = cx - bottomHalf; dioramaDst[5] = bottom
      dioramaDst[6] = cx + bottomHalf; dioramaDst[7] = bottom

      dioramaMatrix.reset()
      if (!dioramaMatrix.setPolyToPoly(dioramaSrc, 0, dioramaDst, 0, 4)) {
        // 退化（极端缩放下四点共线）：回退成普通缩放+居中，绝不画丢
        canvas.translate(cx, cy)
        canvas.scale(t.scale * persp, t.scale * persp)
        canvas.translate(0f, -l.blockHeight * 0.5f)
        return
      }
      canvas.concat(dioramaMatrix)
    }

    /** 台面倒影：把当前行上下翻转、压扁 42%，贴在行下方并模糊淡出。 */
    private fun drawDioramaFloor(canvas: Canvas, f: FrameState) {
      val track = f.tracks.firstOrNull { it.lineIndex == f.activeIndex && !it.leaving } ?: return
      if (track.alpha <= 0.02f) return
      val l = f.layout.getOrNull(f.activeIndex) ?: return
      val fontPx = f.fontPx
      val maxW = (l.rowWidths.maxOrNull() ?: 0f)
      if (maxW <= 0f) return

      val h = l.blockHeight
      val mirrorTop = track.y + l.blockHeight * track.scale + fontPx * 0.30f
      floorPaint.textSize = fontPx
      floorPaint.shader = null
      floorPaint.color = blend(lyricPalette.active, lyricPalette.sung, 0.35f)
      floorPaint.alpha = (track.alpha * 52).toInt().coerceIn(0, 255)
      floorPaint.maskFilter = blurFilterFor(fontPx * 0.09f)

      canvas.save()
      canvas.translate(viewW / 2f, mirrorTop)
      canvas.scale(track.scale, -track.scale * 0.42f)
      canvas.translate(0f, -h)
      for (r in 0 until l.rowCount) {
        val txt = l.rowTexts.getOrNull(r) ?: continue
        canvas.drawText(txt, l.rowOffsets[r], l.baseline0 + r * l.rowPitch, floorPaint)
      }
      canvas.restore()
      floorPaint.maskFilter = null
    }

    private fun drawHint(canvas: Canvas) {
      if (hintText.isEmpty()) return
      hintPaint.color = lyricPalette.unsung
      hintPaint.alpha = 180
      canvas.drawText(hintText, viewW / 2f, viewH / 2f, hintPaint)
    }

    private fun drawBackground(canvas: Canvas) {
      backgroundRenderer.draw(canvas, viewW, viewH, backgroundClock)
    }

    /** 上下文行：整行一次画完 + 按距离模糊/缩放。[blurBoost] 补偿透视缩小后模糊视觉变弱。 */
    private fun drawContextLine(canvas: Canvas, l: LineLayout, alpha: Float, d: Int, blurBoost: Float = 1f) {
      val color = blend(lyricPalette.unsung, lyricPalette.active, 0.22f)
      textPaint.shader = null
      textPaint.color = color
      textPaint.alpha = (alpha * 255 * 0.9f).toInt().coerceIn(0, 255)
      textPaint.maskFilter = blurFilterFor(toneBlurPx(max(d, 1)) * blurBoost)
      for (r in 0 until l.rowCount) {
        val text = l.rowTexts.getOrNull(r) ?: continue
        canvas.drawText(text, l.rowOffsets[r], l.baseline0 + r * l.rowPitch, textPaint)
      }
      textPaint.maskFilter = null
    }

    /** 当前行：按模式分发的扫光 + 光晕 + 译文绘制。 */
    private fun drawActiveLine(canvas: Canvas, l: LineLayout, pos: Long, trackAlpha: Float, t: TrackSnapshot) {
      val fontPx = textPaint.textSize
      val tokens = l.tokens

      if (tokens.isEmpty()) {
        textPaint.shader = null
        textPaint.color = lyricPalette.sung
        textPaint.alpha = (trackAlpha * 255).toInt().coerceIn(0, 255)
        textPaint.maskFilter = null
        for (r in 0 until l.rowCount) {
          val text = l.rowTexts.getOrNull(r) ?: continue
          canvas.drawText(text, l.rowOffsets[r], l.baseline0 + r * l.rowPitch, textPaint)
        }
        drawTranslation(canvas, l, trackAlpha)
        return
      }

      // 模式分发：流光(默认扫光) / 绘光 / 镜台 / 浮名 / 心象 / 商籁
      when (effectiveMode()) {
        EffectMode.LUMIERE, EffectMode.DIORAMA -> {
          drawLumiere(canvas, l, pos, trackAlpha)
          drawTranslation(canvas, l, trackAlpha)
          return
        }
        EffectMode.FUME -> {
          drawFume(canvas, l, pos, trackAlpha)
          drawTranslation(canvas, l, trackAlpha)
          return
        }
        EffectMode.CADENZA -> {
          drawCadenza(canvas, l, pos, trackAlpha)
          drawTranslation(canvas, l, trackAlpha)
          return
        }
        EffectMode.SONNET -> {
          drawSonnet(canvas, l, pos, trackAlpha)
          drawTranslation(canvas, l, trackAlpha)
          return
        }
        else -> {}
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
      val baseColor = lyricPalette.unsung
      val wordColor = blend(lyricPalette.active, lyricPalette.sung, 0.25f)
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
        textPaint.alpha = (trackAlpha * 255 * FoliaLyricEngine.BASE_ALPHA).toInt().coerceIn(0, 255)
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
      val glowColor = lyricPalette.activeSecondary

      glowPaint.shader = null
      glowPaint.maskFilter = null

      for ((i, t) in tokens.withIndex()) {
        if (t.width <= 0f) continue
        val g = engine.glowEnvelope(pos, t, l.lineEndMs)
        if (g <= 0.02f) continue
        // 还没唱的词不发光；正在唱与已唱的词按包络发光
        if (pos <= t.startTime) continue

        val color = blend(lyricPalette.active, glowColor, g)
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
    // 浮名 FUME（folia fume）—— 超大主角字 + 雾感未唱字 + 整行呼吸光晕
    // （几何放大在 onDraw：当前行 ×1.5、上下文行 ×0.55；此处只负责文字本身）
    // ============================================================================================
    private fun drawFume(canvas: Canvas, l: LineLayout, pos: Long, trackAlpha: Float) {
      val fontPx = textPaint.textSize
      val baseColor = lyricPalette.unsung
      val wordColor = blend(lyricPalette.active, lyricPalette.sung, 0.32f)
      // 整行柔光（英雄字的光晕）
      drawGlow(canvas, l, l.tokens, pos, trackAlpha)
      textPaint.maskFilter = null
      // 未唱字带「字雾」模糊；已唱/正在唱清晰高亮
      val fog = blurFilterFor(fontPx * 0.11f)
      for (tk in l.tokens) {
        if (tk.width <= 0f) continue
        val off = l.rowOffsets[tk.row]
        val baseline = l.baseline0 + tk.row * l.rowPitch
        val sung = pos >= tk.endTime
        val singing = pos >= tk.startTime && !sung
        if (!sung && !singing) {
          textPaint.color = baseColor
          textPaint.alpha = (trackAlpha * 255 * FoliaLyricEngine.BASE_ALPHA).toInt().coerceIn(0, 255)
          textPaint.maskFilter = fog
          canvas.drawText(tk.content, tk.x + off, baseline, textPaint)
        } else {
          val k = if (sung) 1f else
            ((pos - tk.startTime).toFloat() / (tk.endTime - tk.startTime).coerceAtLeast(1L)).coerceIn(0f, 1f)
          textPaint.color = blend(baseColor, wordColor, k)
          textPaint.alpha = (trackAlpha * 255).toInt().coerceIn(0, 255)
          textPaint.maskFilter = null
          canvas.drawText(tk.content, tk.x + off, baseline, textPaint)
        }
      }
      textPaint.maskFilter = null
    }

    // ============================================================================================
    // 特效 3：Lumiere lyricWindow（folia lumiere）—— 字形点亮 + 四芒星闪点 + 崩解漂散，无填充
    // ============================================================================================

    /** Lumiere 光室：未唱字是冷色半亮刻字，唱到的字被点亮并爆出星形闪点，唱过的字崩解漂散。 */
    private fun drawLumiere(canvas: Canvas, l: LineLayout, pos: Long, trackAlpha: Float) {
      val fontPx = textPaint.textSize
      val fm = textPaint.fontMetrics
      val centerY = (fm.ascent + fm.descent) / 2f
      val cold = blend(lyricPalette.unsung, lyricPalette.active, 0.21f)   // illum≈0.6 → 0.35*0.6
      val hot = blend(lyricPalette.active, lyricPalette.sung, 0.5f)
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
          else -> 0.85f + engine.seedRand(i, 5) * 0.35f
        }
        val dy = when {
          i == keyIdx -> 0f
          isPunct -> fontPx * 0.32f * (if (engine.seedRand(i, 6) > 0.5f) 1f else -1f)
          else -> fontPx * 0.2f * (if (engine.seedRand(i, 7) > 0.5f) 1f else -1f)
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
          val lit = engine.smooth01((pos - charStart).toFloat() / max(charDur, 300L).toFloat())
          val flash = engine.flashEnv(pos, charStart)
          val heat = (0.6f * 1.3f + flash * 0.2f).coerceIn(0f, 1f)
          val revealed = min(1f, 0.35f + 0.6f * 0.45f)   // 未唱字被光场照出的冷色半亮

          // 崩解漂散：唱完后漂起 + 随机横向 + 淡出（约 1.6s 消失）
          var dissolve = 0f
          var ddx = 0f
          var ddy = 0f
          var rot = 0f
          if (lit >= 1f) {
            val age = ((pos - t.endTime) / 1000f).coerceAtLeast(0f)
            dissolve = engine.smooth01(age / 1.6f)
            val drift = age * age.coerceAtLeast(0f)
            ddy = -fontPx * 0.42f * drift
            ddx = fontPx * 0.22f * drift * (if (engine.seedRand(i * 31 + k, 8) > 0.5f) 1f else -1f)
            rot = (if (engine.seedRand(i * 17 + k, 9) > 0.5f) 1f else -1f) * 14f * drift
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
            glowPaint.color = lyricPalette.active
            glowPaint.alpha = (lit * (0.10f + 0.25f * flash) * (1f - dissolve) * trackAlpha * 255)
              .toInt().coerceIn(0, 255)
            glowPaint.setShadowLayer(
              fontPx * 0.8f, 0f, 0f,
              withAlpha(lyricPalette.activeSecondary, (lit * 160).toInt().coerceIn(0, 255))
            )
            canvas.save()
            canvas.translate(ccx + ddx, cy + ddy)
            canvas.scale(wScale, wScale)
            canvas.drawText(t.content, k, k + 1, -cw / 2f, centerY, glowPaint)
            canvas.restore()
          }

          // 四芒星闪点：唱到的一瞬
          if (flash > 0.08f) {
            val sx = ccx + ddx + fontPx * 0.22f * (engine.seedRand(i * 13 + k, 10) - 0.5f)
            val sy = cy + ddy + fontPx * 0.22f * (engine.seedRand(i * 29 + k, 11) - 0.5f) - fontPx * 0.25f
            val len = fontPx * 0.5f * (0.8f + 0.5f * flash)
            val starAlpha = (flash * 0.55f * trackAlpha * 255).toInt().coerceIn(0, 255)
            starPaint.color = blend(lyricPalette.active, Color.WHITE, 0.5f)
            starPaint.alpha = starAlpha
            starPaint.strokeWidth = fontPx * 0.045f
            val angle = engine.seedRand(i * 7 + k, 12) * 90f + (1f - flash) * 30f
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

    private fun drawTranslation(canvas: Canvas, l: LineLayout, trackAlpha: Float) {
      val trans = l.line.translation ?: return
      transPaint.color = lyricPalette.translation
      transPaint.alpha = (trackAlpha * 255 * FoliaLyricEngine.TRANSLATION_ALPHA).toInt().coerceIn(0, 255)
      transPaint.shader = null
      transPaint.maskFilter = null
      val tw = transPaint.measureText(trans)
      val fm = transPaint.fontMetrics
      val transBaseline = transPaint.textSize / 2f - (fm.ascent + fm.descent) / 2f
      canvas.drawText(trans, -tw / 2f, l.mainHeight + transPaint.textSize * 0.3f + transBaseline, transPaint)
    }

    private fun blurFilterFor(radiusPx: Float): BlurMaskFilter? {
      if (radiusPx < 0.5f) return null
      val key = radiusPx.toInt()
      return blurFilters.getOrPut(key) {
        BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
      }
    }

    private fun toneBlurPx(d: Int): Float = when {
      d == 0 -> 0f
      d > 0 -> if (d == 1) 0.7f else 1.8f + 0.8f * (d - 2)
      else -> 1.1f + 0.7f * (-d - 1)
    }.let { it * density }

    private fun isCjk(c: Char): Boolean {
      val block = Character.UnicodeBlock.of(c) ?: return false
      return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
    }

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

    // ============================================================================================
    // 心象 CADENZA（folia cadenza）—— 逐词升腾入场 + 整行呼吸光晕 + 横向扫光光束（限在字上）
    // ============================================================================================
    private fun drawCadenza(canvas: Canvas, l: LineLayout, pos: Long, trackAlpha: Float) {
      val fontPx = textPaint.textSize
      val baseColor = lyricPalette.unsung
      val wordColor = blend(lyricPalette.active, lyricPalette.sung, 0.30f)
      val maxW = (l.rowWidths.maxOrNull() ?: 0f)
      if (maxW <= 0f) return

      textPaint.maskFilter = null
      // 1) 底色字 + 逐词「升腾入场」（开唱后 ~420ms 从下落 0.42 字号落到原位）
      for (tk in l.tokens) {
        if (tk.width <= 0f) continue
        val off = l.rowOffsets[tk.row]
        val baseline0 = l.baseline0 + tk.row * l.rowPitch
        val sung = pos >= tk.endTime
        val singing = pos >= tk.startTime && !sung
        val rise = smooth01(((pos - tk.startTime) / 420f).coerceIn(0f, 1f))
        val dy = (1f - rise) * fontPx * 0.42f
        val a = if (sung || singing) (0.45f + 0.55f * rise) else 0.34f
        val k = if (sung) 1f else if (singing)
          ((pos - tk.startTime).toFloat() / (tk.endTime - tk.startTime).coerceAtLeast(1L)).coerceIn(0f, 1f) else 0f
        textPaint.color = if (sung || singing) blend(baseColor, wordColor, k) else baseColor
        textPaint.alpha = (trackAlpha * 255 * a).toInt().coerceIn(0, 255)
        canvas.drawText(tk.content, tk.x + off, baseline0 + dy, textPaint)
      }

      // 2) 整行呼吸光晕（随播放时间轻微脉动）
      val breath = 0.8f + 0.2f * (0.5f + 0.5f * sin(pos / 1000f * 2.2f))
      drawGlow(canvas, l, l.tokens, pos, trackAlpha * breath)

      // 3) 横向扫光光束：重绘字作遮罩 + SRC_ATOP 限制光束只在字上
      val nowSec = pos / 1000f
      val period = 3.4f
      val bandW = maxW * 0.34f
      val phase = (nowSec % period) / period
      val bx = -maxW / 2f - bandW + (maxW + bandW * 2f) * phase
      val top = -fontPx * 1.4f
      val bot = l.blockHeight + fontPx * 0.5f
      val pad = fontPx
      val layer = canvas.saveLayer(RectF(-maxW / 2f - pad, top, maxW / 2f + pad, bot), null)
      maskPaint.textSize = fontPx
      maskPaint.typeface = textPaint.typeface
      maskPaint.style = Paint.Style.FILL
      maskPaint.maskFilter = null
      maskPaint.shader = null
      maskPaint.color = Color.WHITE
      for (tk in l.tokens) {
        if (tk.width <= 0f) continue
        val off = l.rowOffsets[tk.row]
        canvas.drawText(tk.content, tk.x + off, l.baseline0 + tk.row * l.rowPitch, maskPaint)
      }
      beamPaint.shader = LinearGradient(
        bx, 0f, bx + bandW, 0f,
        intArrayOf(0x00FFFFFF, 0x4DFFFFFF, 0xBFFFFFFF.toInt(), 0x4DFFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP
      )
      beamPaint.alpha = (trackAlpha * 230).toInt().coerceIn(0, 255)
      canvas.drawRect(-maxW / 2f - pad, top, maxW / 2f + pad, bot, beamPaint)
      beamPaint.shader = null
      canvas.restoreToCount(layer)
    }

    // ============================================================================================
    // 商籁 SONNET（folia sonnet）—— 胶片式镂空巨字 + 诗稿细边框 + 信箱黑边 + 暗角
    // （几何在 onDraw 维持原尺寸；信箱黑边/暗角为屏幕级叠加，见 drawSonnetLetterbox）
    // ============================================================================================
    private fun drawSonnet(canvas: Canvas, l: LineLayout, pos: Long, trackAlpha: Float) {
      val fontPx = textPaint.textSize
      val baseColor = lyricPalette.unsung
      val wordColor = blend(lyricPalette.active, lyricPalette.sung, 0.25f)
      textPaint.maskFilter = null
      // 诗稿细边框（围绕当前行包围盒）
      var minX = Float.MAX_VALUE
      var maxX = -Float.MAX_VALUE
      for (r in 0 until l.rowCount) {
        val w = l.rowWidths.getOrNull(r) ?: continue
        minX = min(minX, l.rowOffsets[r])
        maxX = max(maxX, l.rowOffsets[r] + w)
      }
      if (maxX > minX) {
        sonnetFramePaint.color = withAlpha(Color.WHITE, (trackAlpha * 64).toInt().coerceIn(0, 255))
        canvas.drawRect(
          minX - fontPx * 0.45f, -fontPx * 0.3f,
          maxX + fontPx * 0.45f, l.blockHeight + fontPx * 0.3f, sonnetFramePaint
        )
      }
      // 逐词：未唱镂空描边，已唱实心 + 细描边
      for (tk in l.tokens) {
        if (tk.width <= 0f) continue
        val off = l.rowOffsets[tk.row]
        val baseline = l.baseline0 + tk.row * l.rowPitch
        val sung = pos >= tk.endTime
        val singing = pos >= tk.startTime && !sung
        val frac = when {
          sung -> 1f
          singing -> ((pos - tk.startTime).toFloat() / (tk.endTime - tk.startTime).coerceAtLeast(1L)).coerceIn(0f, 1f)
          else -> 0f
        }
        if (frac <= 0f) {
          textPaint.style = Paint.Style.STROKE
          textPaint.strokeWidth = fontPx * 0.038f
          textPaint.color = baseColor
          textPaint.alpha = (trackAlpha * 255 * 0.5f).toInt().coerceIn(0, 255)
          canvas.drawText(tk.content, tk.x + off, baseline, textPaint)
        } else {
          textPaint.style = Paint.Style.FILL
          textPaint.color = blend(baseColor, wordColor, frac)
          textPaint.alpha = (trackAlpha * 255).toInt().coerceIn(0, 255)
          canvas.drawText(tk.content, tk.x + off, baseline, textPaint)
          textPaint.style = Paint.Style.STROKE
          textPaint.strokeWidth = fontPx * 0.02f
          textPaint.color = Color.WHITE
          textPaint.alpha = (trackAlpha * 110f * frac).toInt().coerceIn(0, 255)
          canvas.drawText(tk.content, tk.x + off, baseline, textPaint)
        }
      }
      textPaint.style = Paint.Style.FILL
      textPaint.strokeWidth = 0f
    }

    /** 商籁信箱黑边 + 暗角（屏幕级，盖在歌词之上）。 */
    private fun drawSonnetLetterbox(canvas: Canvas) {
      val barH = viewH * 0.11f
      barPaint.color = Color.BLACK
      barPaint.alpha = 235
      canvas.drawRect(0f, 0f, viewW.toFloat(), barH, barPaint)
      canvas.drawRect(0f, viewH - barH, viewW.toFloat(), viewH.toFloat(), barPaint)
      val vg = vignetteShader
      if (vg != null) {
        barPaint.shader = vg
        barPaint.alpha = 200
        canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), barPaint)
        barPaint.shader = null
      }
    }

    /** 平滑 0→1（缓入缓出），folia 常用。 */
    private fun smooth01(x: Float): Float {
      val t = x.coerceIn(0f, 1f)
      return t * t * (3f - 2f * t)
    }
  }
}
