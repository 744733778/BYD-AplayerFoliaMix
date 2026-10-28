package remix.myplayer.ui.widget.lyric.folia

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 全屏歌词页的动态背景模式。
 *
 * 名称沿用 Folia（folia-major-0.7.20）的中文文案：
 * - [COVER_BLUR]：封面高斯模糊（原实现，保留）；
 * - [MONET]「莫奈」：Folia 的 Monet 背景（图像洗色 + 叠加层 + AE 式漂移）；
 * - [NOMAND]「漫游」：Folia 的 Nomand 背景（默认 dithering 像素画着色器）；
 * - [SORA]「空」：Folia 的 Sora 星空背景。
 *
 * 全部走 Canvas 2D，零 GLES 依赖（车机兼容性铁律）。
 */
enum class LyricBackgroundMode(val label: String) {
  /** 封面高斯模糊（原实现）。 */
  COVER_BLUR("封面"),

  /** 莫奈：图像洗色 + 叠加层 + 缓慢漂移。 */
  MONET("莫奈"),

  /** 漫游：像素画抖动 + 缓慢漂移。 */
  NOMAND("漫游"),

  /** 空：星空粒子场。 */
  SORA("空"),
}

/**
 * 全屏歌词页的动态背景渲染器（纯 Canvas 2D）。
 *
 * 与 [FoliaLyricCanvasView] 的关系：View 只负责把「模式 / 封面 / 主题色 / 时间」喂进来，
 * 具体绘制全部在本类完成。像素级处理（莫奈洗色、漫游抖动）放在后台线程，
 * 结果按 generation 丢弃过期产物，避免切歌 / 换模式时闪旧图。
 */
class FoliaBackgroundRenderer(
  /** 请求宿主重绘（View 侧转成 invalidate）。 */
  private val requestRender: () -> Unit,
  /** 屏幕密度（漫游的像素块尺寸按 dp 换算）。 */
  private val density: Float,
) {

  // ==========================================================================================
  // 对外状态
  // ==========================================================================================

  /** 当前背景模式。 */
  var mode: LyricBackgroundMode = LyricBackgroundMode.COVER_BLUR
    set(value) {
      if (field == value) return
      field = value
      // 合成步骤变了，等效背景色要重算（位图产物不变，无需重建）
      worker.execute { updateBackdrop() }
      requestRender()
    }

  /** 主题配色（洗色 / 抖动 / 星点都从这里取色）。 */
  var palette: Palette = Palette()
    set(value) {
      field = value
      scheduleRebuild()
      requestRender()
    }

  /** 该模式是否需要持续取帧（漂移 / 闪烁）。 */
  val animated: Boolean
    get() = mode != LyricBackgroundMode.COVER_BLUR

  /**
   * 歌词所在区域的**等效背景色**（ARGB，不透明）。
   *
   * 背景是画出来的（封面模糊 / 莫奈洗色 / 漫游色阶），没有一个现成的「背景色」可用，
   * 这里按各模式实际的合成步骤反推出歌词背后的颜色，供歌词对比度校正使用。
   * 见 [FoliaLyricCanvasView] 与 [readableAgainst]。
   */
  @Volatile
  var backdropColor: Int = 0
    private set

  /** 背景等效色发生变化（换封面 / 换模式 / 换主题色）时回调，供 View 侧重算歌词配色。 */
  @Volatile
  var onBackdropChanged: (() -> Unit)? = null

  // ==========================================================================================
  // 内部状态
  // ==========================================================================================

  private var cover: Bitmap? = null
  private var viewW = 0
  private var viewH = 0

  /** 莫奈的处理结果（已烘焙叠加层）。 */
  @Volatile
  private var monetBitmap: Bitmap? = null

  /** 漫游的处理结果（网格分辨率的像素画，放大时用最近邻保持硬边）。 */
  @Volatile
  private var ditherBitmap: Bitmap? = null

  /** 封面模糊用的小图（缩到极小再放大 = 廉价高斯模糊）。 */
  @Volatile
  private var blurCover: Bitmap? = null

  /** 星空粒子（确定性生成，与帧无关）。 */
  private val stars: Array<Star> = Array(STAR_COUNT) { Star.of(it) }

  @Volatile
  private var generation = 0

  private val worker = Executors.newSingleThreadExecutor { r ->
    Thread(r, "folia-background").apply {
      isDaemon = true
      priority = Thread.MIN_PRIORITY
    }
  }

  // ==========================================================================================
  // 输入
  // ==========================================================================================

  /** 换封面（歌切了）。 */
  fun setCover(bitmap: Bitmap?) {
    cover = bitmap
    scheduleRebuild()
  }

  /** 视口尺寸变化（旋转 / 车机分辨率变化）。 */
  fun onSizeChanged(width: Int, height: Int) {
    if (width == viewW && height == viewH) return
    viewW = width
    viewH = height
    scheduleRebuild()
  }

  // ==========================================================================================
  // 绘制
  // ==========================================================================================

  /**
   * 绘制背景。
   *
   * @param timeSec 动画时钟（秒）；漂移 / 闪烁都由它驱动。
   */
  fun draw(canvas: Canvas, width: Int, height: Int, timeSec: Float) {
    when (mode) {
      LyricBackgroundMode.COVER_BLUR -> drawCoverBlur(canvas, width, height)
      LyricBackgroundMode.MONET -> drawMonet(canvas, width, height, timeSec)
      LyricBackgroundMode.NOMAND -> drawNomand(canvas, width, height, timeSec)
      LyricBackgroundMode.SORA -> drawSora(canvas, width, height, timeSec)
    }
  }

  // ------------------------------------------------------------------------------------------
  // 封面模糊（原有实现）
  // ------------------------------------------------------------------------------------------

  private fun drawCoverBlur(canvas: Canvas, width: Int, height: Int) {
    val tiny = blurCover
    if (tiny != null && !tiny.isRecycled) {
      val scale = max(width.toFloat() / tiny.width, height.toFloat() / tiny.height)
      val dw = tiny.width * scale
      val dh = tiny.height * scale
      coverPaint.alpha = 255
      coverDst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
      canvas.drawBitmap(tiny, null, coverDst, coverPaint)
    }
    if (bgGradient == null || bgGradientHeight != height) {
      bgGradient = LinearGradient(
        0f, 0f, 0f, height.toFloat(),
        scrim(palette.bgTop, COVER_BLUR_SCRIM_TOP), scrim(palette.bgBottom, COVER_BLUR_SCRIM_BOTTOM),
        Shader.TileMode.CLAMP
      )
      bgGradientHeight = height
    }
    scrimPaint.shader = bgGradient
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
  }

  // ------------------------------------------------------------------------------------------
  // 莫奈（Folia Monet）
  // ------------------------------------------------------------------------------------------

  private fun drawMonet(canvas: Canvas, width: Int, height: Int, timeSec: Float) {
    val bmp = monetBitmap
    if (bmp == null || bmp.isRecycled) {
      drawCoverBlur(canvas, width, height)
      return
    }
    // AE 式漂移：噪声驱动的平移 + 呼吸缩放（见 Folia monetBackgroundDrift.ts）
    val drift = driftTransform(timeSec, MONET_DRIFT_STRENGTH)
    canvas.save()
    canvas.translate(
      width / 2f + width * drift.dxPercent / 100f,
      height / 2f + height * drift.dyPercent / 100f
    )
    canvas.scale(drift.scale, drift.scale)
    canvas.drawBitmap(bmp, null, RectF(-width / 2f, -height / 2f, width / 2f, height / 2f), coverPaint)
    canvas.restore()

    // 可读性渐变（压在图上，保证歌词可读）
    if (readabilityGradient == null || readabilityWidth != width || readabilityHeight != height) {
      readabilityGradient = LinearGradient(
        0f, 0f, width.toFloat(), 0f,
        intArrayOf(
          scrim(palette.bgTop, MONET_READABILITY_TOP_LEFT),
          scrim(palette.bgTop, MONET_READABILITY_TOP_LEFT_HIGH),
          scrim(palette.bgBottom, MONET_READABILITY_BOTTOM_RIGHT),
          scrim(palette.bgBottom, MONET_READABILITY_BOTTOM_RIGHT_HIGH),
        ),
        floatArrayOf(0f, 0.34f, 0.70f, 1f),
        Shader.TileMode.CLAMP
      )
      readabilityWidth = width
      readabilityHeight = height
    }
    scrimPaint.shader = readabilityGradient
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
  }

  // ------------------------------------------------------------------------------------------
  // 漫游（Folia Nomand / Paper dithering 着色器）
  // ------------------------------------------------------------------------------------------

  private fun drawNomand(canvas: Canvas, width: Int, height: Int, timeSec: Float) {
    val bmp = ditherBitmap
    if (bmp == null || bmp.isRecycled) {
      drawCoverBlur(canvas, width, height)
      return
    }
    val drift = driftTransform(timeSec, NOMAND_DRIFT_STRENGTH)
    canvas.save()
    canvas.translate(
      width / 2f + width * drift.dxPercent / 100f,
      height / 2f + height * drift.dyPercent / 100f
    )
    canvas.scale(drift.scale, drift.scale)
    // 最近邻放大：像素画的硬边不能被插值糊掉
    pixelPaint.isFilterBitmap = false
    canvas.drawBitmap(bmp, null, RectF(-width / 2f, -height / 2f, width / 2f, height / 2f), pixelPaint)
    canvas.restore()

    // 主题底色压一层（Folia overlayEnabled=true, overlayOpacity=0.35）
    scrimPaint.shader = null
    scrimPaint.color = scrim(palette.bgBottom, NOMAND_BASE_SCRIM)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

    // 上下方向的柔和压暗，保证歌词可读
    if (nomandScrim == null || nomandScrimHeight != height) {
      nomandScrim = LinearGradient(
        0f, 0f, 0f, height.toFloat(),
        scrim(palette.bgTop, NOMAND_SCRIM_TOP), scrim(palette.bgBottom, NOMAND_SCRIM_BOTTOM),
        Shader.TileMode.CLAMP
      )
      nomandScrimHeight = height
    }
    scrimPaint.shader = nomandScrim
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
  }

  // ------------------------------------------------------------------------------------------
  // 空（Folia Sora 星空）
  // ------------------------------------------------------------------------------------------

  private fun drawSora(canvas: Canvas, width: Int, height: Int, timeSec: Float) {
    // 夜空底（Folia 夜间模式是纯黑，这里带一点主题底色）
    canvas.drawColor(palette.bgBottom)

    val aspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
    val margin = 0.05f
    val laneWidth = aspect + margin * 2f

    starPaint.style = Paint.Style.FILL
    starPaint.isAntiAlias = true

    for (star in stars) {
      // x：横向漂移 + 环绕（fract）
      var x = (star.x0 + timeSec * star.speed) % 1f
      if (x < 0f) x += 1f
      x = x * laneWidth - (aspect * 0.5f + margin)
      // y：竖向摆动
      val wave = sin(timeSec * 0.3f + star.yRand * 6.283f) * 0.005f
      val ndcX = x / (aspect * 0.5f)
      val ndcY = (star.y + wave) / 0.5f
      val px = (ndcX * 0.5f + 0.5f) * width
      val py = (0.5f - ndcY * 0.5f) * height
      if (px < -32f || px > width + 32f) continue

      // 闪烁
      val blink = 0.3f + 0.7f * sin(timeSec * (1f + star.blinkRand * 2.5f) + star.x0 * 6.283f)
      val radius = max(2f, star.size * height * 3.5f)

      val color = if (star.isAccent) palette.activeSecondary else palette.active
      // 外圈柔光
      starPaint.color = Color.argb((70 * blink).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
      canvas.drawCircle(px, py, radius * 1.7f, starPaint)
      // 核心
      starPaint.color = Color.argb((235 * blink).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
      canvas.drawCircle(px, py, radius * 0.55f, starPaint)
    }
  }

  // ==========================================================================================
  // 后台处理
  // ==========================================================================================

  private fun scheduleRebuild() {
    val g = ++generation
    val src = cover
    val p = palette
    val w = viewW
    val h = viewH
    worker.execute {
      val blur = src?.let { makeTiny(it) }
      val monet = src?.let { buildMonet(it, p) }
      val dither = if (src != null && w > 0 && h > 0) buildDither(src, p, w, h) else null
      if (g != generation) return@execute
      blurCover = blur
      monetBitmap = monet
      ditherBitmap = dither
      updateBackdrop()
      requestRender()
    }
  }

  // ------------------------------------------------------------------------------------------
  // 等效背景色（供歌词对比度校正使用）
  // ------------------------------------------------------------------------------------------

  /**
   * 按当前模式的**真实合成步骤**反推歌词背后的等效背景色。
   *
   * 各模式都是「图 + 若干层纱罩」的叠加，等效色 = 把纱罩按同样的 alpha 叠到图上。
   * 纱罩的 alpha 直接复用绘制时的常量，保证与画面完全一致。
   */
  private fun updateBackdrop() {
    val p = palette
    val base = when (mode) {
      LyricBackgroundMode.COVER_BLUR -> averageColor(blurCover) ?: p.bgBottom
      LyricBackgroundMode.MONET -> averageColor(monetBitmap) ?: averageColor(blurCover) ?: p.bgBottom
      LyricBackgroundMode.NOMAND -> averageColor(ditherBitmap) ?: averageColor(blurCover) ?: p.bgBottom
      LyricBackgroundMode.SORA -> p.bgBottom
    }
    val result = when (mode) {
      // 上下渐变纱罩：顶部 bgTop@62% → 底部 bgBottom@78%，取中部（歌词所在区）
      LyricBackgroundMode.COVER_BLUR -> {
        val t = 0.5f
        val alpha = COVER_BLUR_SCRIM_TOP + (COVER_BLUR_SCRIM_BOTTOM - COVER_BLUR_SCRIM_TOP) * t
        over(base, lerpColor(p.bgTop, p.bgBottom, t), alpha)
      }
      // 横向可读性渐变：左侧 bgTop@18~32% → 右侧 bgBottom@66~82%，取中段
      LyricBackgroundMode.MONET -> {
        val t = 0.5f
        val alpha = MONET_READABILITY_TOP_LEFT_HIGH +
          (MONET_READABILITY_BOTTOM_RIGHT - MONET_READABILITY_TOP_LEFT_HIGH) * t
        over(base, lerpColor(p.bgTop, p.bgBottom, t), alpha)
      }
      // 漫游：先压一层主题底色，再叠上下压暗
      LyricBackgroundMode.NOMAND -> {
        val withBase = over(base, p.bgBottom, NOMAND_BASE_SCRIM)
        val t = 0.5f
        val alpha = NOMAND_SCRIM_TOP + (NOMAND_SCRIM_BOTTOM - NOMAND_SCRIM_TOP) * t
        over(withBase, lerpColor(p.bgTop, p.bgBottom, t), alpha)
      }
      // 星空：纯主题底色
      LyricBackgroundMode.SORA -> base
    }
    if (result == backdropColor) return
    backdropColor = result
    onBackdropChanged?.invoke()
  }

  /** 取位图平均色（等效背景色只需要大致色调，不必逐像素精确）。 */
  private fun averageColor(bitmap: Bitmap?): Int? {
    if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
    val stepX = max(1, bitmap.width / 16)
    val stepY = max(1, bitmap.height / 16)
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    var y = 0
    while (y < bitmap.height) {
      var x = 0
      while (x < bitmap.width) {
        val c = bitmap.getPixel(x, y)
        r += Color.red(c)
        g += Color.green(c)
        b += Color.blue(c)
        n++
        x += stepX
      }
      y += stepY
    }
    if (n == 0L) return null
    return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
  }

  /** 把不透明色 [top] 以 [alpha] 叠在 [base] 上。 */
  private fun over(base: Int, top: Int, alpha: Float): Int {
    val a = alpha.coerceIn(0f, 1f)
    fun mix(f: Int, b: Int) = (b + (f - b) * a).roundToInt().coerceIn(0, 255)
    return Color.rgb(
      mix(Color.red(top), Color.red(base)),
      mix(Color.green(top), Color.green(base)),
      mix(Color.blue(top), Color.blue(base))
    )
  }

  /** 缩到极小再放大 = 廉价高斯模糊。 */
  private fun makeTiny(src: Bitmap): Bitmap {
    val w = TINY_SIZE
    val h = max(1, (src.height.toLong() * w / max(1, src.width)).toInt())
    return Bitmap.createScaledBitmap(src, w, h, true)
  }

  /**
   * 莫奈：Canvas 2D 图像洗色 + 叠加层（照搬 Folia monetBackgroundPipeline.ts）。
   *
   * 步骤：铺底色 → 画封面（裁切填满）→ 模糊 → 灰度/饱和/洗色 → 叠加渐变/辉光/纹理。
   */
  private fun buildMonet(src: Bitmap, p: Palette): Bitmap {
    val w = MONET_WIDTH
    val h = MONET_HEIGHT
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(p.bgTop)

    // 画封面（居中裁切填满）
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    canvas.drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)

    // 模糊：先缩到极小再放大（Folia 用 ctx.filter blur，Android 无等价 API）
    val tiny = Bitmap.createScaledBitmap(out, MONET_BLUR_TINY, MONET_BLUR_TINY, true)
    canvas.drawBitmap(tiny, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
    tiny.recycle()

    // 后处理：饱和度 + 色调洗色（Folia 默认 grayscale=0 / saturation=1.05 / wash=0.34）
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
      val c = pixels[i]
      var r = Color.red(c)
      var g = Color.green(c)
      var b = Color.blue(c)
      val luminance = luminanceOf(r, g, b)

      // 饱和度
      val sat = MONET_SATURATION
      if (abs(sat - 1f) >= 0.001f) {
        val sl = luminanceOf(r, g, b)
        r = (sl + (r - sl) * sat).roundToInt().coerceIn(0, 255)
        g = (sl + (g - sl) * sat).roundToInt().coerceIn(0, 255)
        b = (sl + (b - sl) * sat).roundToInt().coerceIn(0, 255)
      }

      // 色调洗色
      val wash = MONET_WASH
      if (wash > 0f) {
        val washColor = resolveWashColor(
          (luminance / 255f).coerceIn(0f, 1f),
          p.bgTop, p.activeSecondary, p.active
        )
        r = mix(r, Color.red(washColor), wash).roundToInt().coerceIn(0, 255)
        g = mix(g, Color.green(washColor), wash).roundToInt().coerceIn(0, 255)
        b = mix(b, Color.blue(washColor), wash).roundToInt().coerceIn(0, 255)
      }
      pixels[i] = Color.rgb(r, g, b)
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)

    // 叠加层：斜向渐变 + 左侧辉光 + 右侧纱罩 + 竖向纹理（Folia paintMonetOverlay）
    val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val overlay = MONET_OVERLAY_OPACITY
    val gradient = LinearGradient(
      0f, 0f, w.toFloat(), h.toFloat(),
      intArrayOf(
        colorWithAlpha(p.activeSecondary, 0.08f + overlay * 0.62f),
        colorWithAlpha(p.bgTop, 0.16f + overlay * 0.58f),
        colorWithAlpha(p.active, 0.06f + overlay * 0.36f),
      ),
      floatArrayOf(0f, 0.44f, 1f),
      Shader.TileMode.CLAMP
    )
    overlayPaint.shader = gradient
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)

    val bloom = RadialGradient(
      w * 0.18f, h * 0.34f, w * 0.55f,
      colorWithAlpha(p.activeSecondary, overlay * 0.42f),
      colorWithAlpha(p.bgTop, 0f),
      Shader.TileMode.CLAMP
    )
    overlayPaint.shader = bloom
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)

    val veil = LinearGradient(
      w * 0.45f, 0f, w.toFloat(), 0f,
      colorWithAlpha(p.bgTop, 0f),
      colorWithAlpha(p.bgTop, 0.18f + overlay * 0.38f),
      Shader.TileMode.CLAMP
    )
    overlayPaint.shader = veil
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)

    // 竖向纹理（18 条）
    overlayPaint.shader = null
    overlayPaint.color = colorWithAlpha(p.bgTop, 0.1f + overlay * 0.08f)
    for (i in 0 until 18) {
      val x = (i * 127) % w
      val y = ((i * 211) % h) - 40
      canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + h * 0.28f, overlayPaint)
    }
    return out
  }

  /**
   * 漫游：Bayer 8×8 有序抖动 + 色阶量化（Folia Nomand 的 dithering 着色器）。
   *
   * 生成网格分辨率的小图，放大时用最近邻保持像素画的硬边。
   */
  private fun buildDither(src: Bitmap, p: Palette, width: Int, height: Int): Bitmap? {
    // 像素块按 dp 换算，但设上限：高密度屏上块太大会让画面过于粗糙、丢掉封面细节
    val cell = (NOMAND_CELL_DP * density).roundToInt().coerceIn(2, NOMAND_CELL_MAX_PX)
    val cols = ceil(width / cell.toFloat()).toInt().coerceAtLeast(1)
    val rows = ceil(height / cell.toFloat()).toInt().coerceAtLeast(1)

    // 先把封面裁切缩到网格尺寸
    val small = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
    Canvas(small).drawBitmap(
      src, null, RectF(0f, 0f, cols.toFloat(), rows.toFloat()),
      Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    )

    val pixels = IntArray(cols * rows)
    small.getPixels(pixels, 0, cols, 0, 0, cols, rows)

    // 色阶：底色 → 强调色 → 高光（Folia: colorBack / colorFront / colorHighlight）
    val steps = NOMAND_COLOR_STEPS
    val ramp = IntArray(steps) { i ->
      when {
        i == 0 -> p.bgTop
        i == steps - 1 -> lerpColor(p.activeSecondary, p.active, 0.55f)
        else -> lerpColor(p.bgTop, p.activeSecondary, (i.toFloat() / (steps - 1)).coerceIn(0f, 1f))
      }
    }

    val out = IntArray(cols * rows)
    for (y in 0 until rows) {
      for (x in 0 until cols) {
        val idx = y * cols + x
        val c = pixels[idx]
        val lum = (luminanceOf(Color.red(c), Color.green(c), Color.blue(c)) / 255f).coerceIn(0f, 1f)
        val threshold = BAYER8[(y % 8) * 8 + (x % 8)] / 64f
        var level = floor(lum * (steps - 1) + threshold).toInt()
        level = level.coerceIn(0, steps - 1)
        out[idx] = ramp[level]
      }
    }

    val result = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, cols, 0, 0, cols, rows)
    small.recycle()
    return result
  }

  // ==========================================================================================
  // 漂移（照搬 Folia monetBackgroundDrift.ts 的噪声模型）
  // ==========================================================================================

  private class Drift(val dxPercent: Float, val dyPercent: Float, val scale: Float)

  /**
   * AE 式漂移：周期值噪声驱动平移 + 呼吸缩放。
   *
   * 与 Folia 的实现等价——那边把噪声采样成关键帧交给合成器播放，这边直接按时间连续求值，
   * 视觉上同样是「每隔几秒 wiggle 一下」。
   */
  private fun driftTransform(timeSec: Float, strength: Float): Drift {
    val t = timeSec / DRIFT_LOOP_SECONDS * DRIFT_LATTICE_POINTS
    val travel = DRIFT_MAX_TRAVEL_PERCENT * strength
    val minScale = 1f + (travel / 100f) * 2f + DRIFT_OVERSCAN_MARGIN
    val breath = DRIFT_BREATH_AMPLITUDE * strength

    val nx = periodicFbm(t, DRIFT_SEED_X)
    val ny = periodicFbm(t, DRIFT_SEED_Y)
    val ns = periodicFbm(t, DRIFT_SEED_SCALE)

    return Drift(
      dxPercent = nx * travel,
      dyPercent = ny * travel,
      scale = minScale + breath * ((ns + 1f) / 2f)
    )
  }

  private fun periodicFbm(t: Float, seed: Float): Float {
    val base = periodicNoise(t, DRIFT_LATTICE_POINTS, seed)
    val detail = periodicNoise(t * 2f, DRIFT_LATTICE_POINTS * 2, seed + 17.31f)
    return (base * 0.68f + detail * 0.32f) * 2f - 1f
  }

  private fun periodicNoise(t: Float, points: Int, seed: Float): Float {
    val cell = floor(t).toInt()
    val from = hash(wrap(cell, points), seed)
    val to = hash(wrap(cell + 1, points), seed)
    val s = t - cell
    val smooth = s * s * (3f - 2f * s)
    return from + (to - from) * smooth
  }

  private fun hash(lattice: Int, seed: Float): Float {
    val v = sin(lattice * 127.1f + seed * 311.7f) * 43758.5453f
    return v - floor(v)
  }

  private fun wrap(value: Int, modulus: Int): Int {
    val m = value % modulus
    return if (m < 0) m + modulus else m
  }

  // ==========================================================================================
  // 颜色工具
  // ==========================================================================================

  private fun luminanceOf(r: Int, g: Int, b: Int): Float =
    0.2126f * r + 0.7152f * g + 0.0722f * b

  private fun mix(from: Int, to: Int, amount: Float): Float =
    from + (to - from) * amount

  private fun colorWithAlpha(color: Int, alpha: Float): Int =
    Color.argb((255 * alpha.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

  private fun lerpColor(from: Int, to: Int, amount: Float): Int {
    val a = amount.coerceIn(0f, 1f)
    return Color.rgb(
      mix(Color.red(from), Color.red(to), a).roundToInt().coerceIn(0, 255),
      mix(Color.green(from), Color.green(to), a).roundToInt().coerceIn(0, 255),
      mix(Color.blue(from), Color.blue(to), a).roundToInt().coerceIn(0, 255)
    )
  }

  /** 主题背景色加不透明度当纱罩。 */
  private fun scrim(color: Int, alpha: Float): Int = colorWithAlpha(color, alpha)

  /**
   * 莫奈的洗色取色（Folia resolveWashColor）。
   *
   * 暗部往「阴影色 + 强调色」走，亮部往「强调色 + 高光色」走。
   */
  private fun resolveWashColor(
    normalizedLuminance: Float,
    background: Int,
    accent: Int,
    primary: Int,
  ): Int {
    val backgroundLuminance = luminanceOf(Color.red(background), Color.green(background), Color.blue(background))
    val primaryLuminance = luminanceOf(Color.red(primary), Color.green(primary), Color.blue(primary))
    val shadow: Int
    val highlight: Int
    if (backgroundLuminance <= primaryLuminance) {
      shadow = background
      highlight = primary
    } else {
      shadow = primary
      highlight = background
    }
    if (normalizedLuminance <= 0.55f) {
      return lerpColor(shadow, accent, normalizedLuminance / 0.55f)
    }
    return lerpColor(accent, highlight, (normalizedLuminance - 0.55f) / 0.45f)
  }

  // ==========================================================================================
  // 画笔 / 渐变缓存
  // ==========================================================================================

  private val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val pixelPaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val coverDst = RectF()

  private var bgGradient: LinearGradient? = null
  private var bgGradientHeight = -1
  private var readabilityGradient: LinearGradient? = null
  private var readabilityWidth = -1
  private var readabilityHeight = -1
  private var nomandScrim: LinearGradient? = null
  private var nomandScrimHeight = -1

  // ==========================================================================================
  // 星空粒子
  // ==========================================================================================

  private class Star(
    val x0: Float,
    val y: Float,
    val yRand: Float,
    val speed: Float,
    val size: Float,
    val blinkRand: Float,
    val isAccent: Boolean,
  ) {
    companion object {
          /** 照搬 Folia Sora 顶点着色器的 hash2 与随机量。 */
          fun of(index: Int): Star {
            val seed = index * 123.456f
            val speedRand = hash2(seed, 1.1f)
            val sizeRand = hash2(seed, 2.2f)
            val yRand = hash2(seed, 3.3f)
            val xRand = hash2(seed, 4.4f)
            val blinkRand = hash2(seed, 5.5f)
            val colorTypeRand = hash2(seed, 6.6f)
            return Star(
              x0 = xRand,
              y = -0.45f + yRand * 0.9f,
              yRand = yRand,
              speed = 0.008f + speedRand * 0.024f,
              size = 0.0006f + 0.0032f * sizeRand.pow(3.8f),
              blinkRand = blinkRand,
              isAccent = colorTypeRand > 0.90f
            )
          }

          private fun hash2(x: Float, y: Float): Float {
            val v = sin(x * 127.1f + y * 311.7f) * 43758.5453123f
            return v - floor(v)
          }
        }
  }

  companion object {
    /** Bayer 8×8 有序抖动矩阵。 */
    private val BAYER8 = intArrayOf(
      0, 32, 8, 40, 2, 34, 10, 42,
      48, 16, 56, 24, 50, 18, 58, 26,
      12, 44, 4, 36, 14, 46, 6, 38,
      60, 28, 52, 20, 62, 30, 54, 22,
      3, 35, 11, 43, 1, 33, 9, 41,
      51, 19, 59, 27, 49, 17, 57, 25,
      15, 47, 7, 39, 13, 45, 5, 37,
      63, 31, 55, 23, 61, 29, 53, 21
    )

    private const val STAR_COUNT = 150
    private const val TINY_SIZE = 24

    // 纱罩不透明度（绘制与「等效背景色」反推共用同一份常量，保证两者永远一致）
    /** 封面模糊：上下渐变纱罩。 */
    private const val COVER_BLUR_SCRIM_TOP = 0.62f
    private const val COVER_BLUR_SCRIM_BOTTOM = 0.78f

    /** 莫奈：横向可读性渐变。 */
    private const val MONET_READABILITY_TOP_LEFT = 0.18f
    private const val MONET_READABILITY_TOP_LEFT_HIGH = 0.32f
    private const val MONET_READABILITY_BOTTOM_RIGHT = 0.66f
    private const val MONET_READABILITY_BOTTOM_RIGHT_HIGH = 0.82f

    /** 漫游：主题底色纱罩 + 上下压暗。 */
    private const val NOMAND_BASE_SCRIM = 0.35f
    private const val NOMAND_SCRIM_TOP = 0.30f
    private const val NOMAND_SCRIM_BOTTOM = 0.55f

    // 莫奈
    private const val MONET_WIDTH = 640
    private const val MONET_HEIGHT = 360
    private const val MONET_BLUR_TINY = 24
    private const val MONET_SATURATION = 1.05f
    private const val MONET_WASH = 0.34f
    private const val MONET_OVERLAY_OPACITY = 0.74f
    private const val MONET_DRIFT_STRENGTH = 0.5f

    // 漫游
    private const val NOMAND_CELL_DP = 3f
    private const val NOMAND_CELL_MAX_PX = 6
    private const val NOMAND_COLOR_STEPS = 4
    private const val NOMAND_DRIFT_STRENGTH = 0.32f

    // 漂移（Folia monetBackgroundDrift.ts）
    private const val DRIFT_LOOP_SECONDS = 240f
    private const val DRIFT_LATTICE_POINTS = 76
    private const val DRIFT_MAX_TRAVEL_PERCENT = 3.4f
    private const val DRIFT_BREATH_AMPLITUDE = 0.035f
    private const val DRIFT_OVERSCAN_MARGIN = 0.01f
    private const val DRIFT_SEED_X = 12.9898f
    private const val DRIFT_SEED_Y = 78.233f
    private const val DRIFT_SEED_SCALE = 39.425f
  }
}
