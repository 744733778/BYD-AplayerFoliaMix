package remix.myplayer.ui.widget.lyric.folia

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import android.util.SparseArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 画质分级（针对车机 1080p60 与散热降频工况设计）。
 * - HIGH：bloom 三级（half/quarter/eighth）+ 全量粒子；
 * - MEDIUM：bloom 两级 + 粒子减半；
 * - LOW：bloom 一级（half）+ 最少粒子、无流光扰动。
 * 运行时按帧耗时自动升降级（[FrameWatchdog]）。
 */
enum class QualityTier { HIGH, MEDIUM, LOW }

/**
 * 行文字纹理所用的位图由 View 线程提供（白字透明底，四周预留模糊 padding）。
 * 渲染器只在 GL 线程上传，排版不变则复用。
 */
interface FoliaLineRasterizer {
  /** @return 该行（主歌词 + 译文）的位图；null 表示该行不可见/无内容 */
  fun rasterize(lineIndex: Int, fontPx: Float, blurPadPx: Float): Bitmap?
}

/**
 * GL 路径失败时的诊断快照（Route B 排障用）。
 * 真机（尤其 Adreno 车机）无法跑 adb 时，由 View 层弹 5 秒浮层把这些信息显示出来供截图。
 */
data class GlDiagnostic(
  var glRenderer: String = "?",
  var glVersion: String = "?",
  var glVendor: String = "?",
  var surfaceCreated: Boolean = false,
  /** program 名 -> "OK" 或失败日志 */
  val programs: MutableList<Pair<String, String>> = mutableListOf(),
  var glError: Int = 0,
  var framebufferStatus: Int = 0,
  var exception: String = "",
  var glReady: Boolean = false,
  var firstFrameMs: Long = -1L,
  var note: String = "",
) {
  fun toText(): String {
    val sb = StringBuilder()
    sb.append("=== Folia GPU 诊断 ===\n")
    sb.append("渲染器: $glRenderer\n")
    sb.append("版本:   $glVersion\n")
    sb.append("厂商:   $glVendor\n")
    sb.append("surfaceCreated: $surfaceCreated\n")
    sb.append("glReady: $glReady\n")
    sb.append("首帧耗时: ${if (firstFrameMs >= 0) firstFrameMs.toString() + "ms" else "未完成"}\n")
    sb.append("glError: 0x${Integer.toHexString(glError)}\n")
    sb.append("FBO状态: 0x${Integer.toHexString(framebufferStatus)}\n")
    sb.append("programs:\n")
    for ((name, status) in programs) {
      sb.append("  $name: $status\n")
    }
    sb.append("异常: ${if (exception.isNotEmpty()) exception else "无"}\n")
    if (note.isNotEmpty()) sb.append("说明: $note\n")
    return sb.toString()
  }
}

/**
 * Folia 风格特效歌词 GLES 3.x 渲染器。
 *
 * 管线（每帧）：
 *  1. sceneFBO：封面背景（tiny 纹理双线性放大）→ 文字层（base unsung + 逐词 sung 扫光）→ 粒子；
 *  2. brightPass → 多级降采样可分离高斯（bloom 链，级数随画质分级）；
 *  3. composite：scene + bloom（可选流光扰动）+ 主题纱罩 + 暗角 → 默认帧缓冲。
 *
 * 线程模型：View 线程通过 [frame] / [palette] / [mode] / [quality] 发布快照（volatile），
 * GL 线程在 [onDrawFrame] 读取最新值渲染；行位图经 [pendingLines] 队列交给 GL 线程上传。
 */
internal class FoliaLyricGLRenderer(
  private val rasterizer: FoliaLineRasterizer,
  private val coverProvider: (() -> Bitmap?)?,
) : GLSurfaceView.Renderer {

  // ---- View 线程发布的状态 ----
  @Volatile var frame: FrameState? = null
  @Volatile var palette: Palette = Palette()
  @Volatile var mode: EffectMode = EffectMode.CLASSIC
  @Volatile var quality: QualityTier = QualityTier.HIGH
  @Volatile var viewW: Int = 0
  @Volatile var viewH: Int = 0

  /** 待上传的行位图（View 线程入队，GL 线程消费）。 */
  private val pendingLines = ArrayList<Pair<Int, Bitmap>>()
  private val pendingLock = Any()

  // ---- GL 资源 ----
  private var textProgram = 0
  private var brightProgram = 0
  private var blurProgram = 0
  private var compositeProgram = 0
  private var particleProgram = 0

  private var quadVbo = 0
  private var particleVbo = 0

  private var sceneFbo = 0
  private var sceneTex = 0
  private var sceneWidth = 0
  private var sceneHeight = 0

  /** bloom 级：textures[level] = Pair(texA, texB)，尺寸逐级减半。 */
  private val bloomTex = Array(3) { IntArray(2) }
  private val bloomFbo = Array(3) { IntArray(2) }
  private var bloomLevels = 3
  private var bloomSize = Array(3) { IntArray(2) }

  private var coverTex = 0
  private var coverTexW = 0
  private var coverTexH = 0

  /** lineIndex -> 纹理 id。 */
  private val lineTextures = SparseArray<Int>()
  /** lineIndex -> 位图（GL 线程持有，随纹理一起释放）。 */
  private val lineBitmaps = SparseArray<Bitmap>()

  private val particles = ParticleSystem()
  private var lastFrameNs = 0L
  private var timeSec = 0f

  private val watchdog = FrameWatchdog { tier -> quality = tier }

  /** 正交投影矩阵（2D 文字层，px 坐标系，左上原点）。 */
  private val ortho = FloatArray(16)
  /** drawCover 用的临时矩阵（避免每帧分配）。 */
  private val coverM = FloatArray(16)
  private val coverMvp = FloatArray(16)

  // ---- 黑屏自愈 ----
  /** 渲染持续全黑但确有可见歌词时，由 GL 线程置位，通知 View 回退 Canvas。 */
  @Volatile var needsFallback = false
  /**
   * 画面自检通过：sceneFBO 采样到真实内容。
   * View 只在它为 true 时才把 GL 层顶到前面（alpha=1）并隐藏 Canvas，
   * 否则 Canvas 始终可见——这样即使 GL 出错也绝不会出现黑屏。
   */
  @Volatile var renderHealthy = false
  /** 最近一次自检结果（写入诊断，供真机排障）。 */
  @Volatile var probeLitCells = -1
  @Volatile var probeMaxLum = -1
  private var probeTick = 0
  private var blackRun = 0
  // 从离屏 sceneFBO 读像素（窗口默认帧缓冲在部分驱动上 glReadPixels 返回 0，会误判黑屏）
  private val probePx = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

  private var glReady = false

  // ---- 诊断（供 View 在回退时给出明确原因，避免真机黑屏难以定位）----
  /** onSurfaceCreated 是否被调用过：false 说明 EGL/GLES3 上下文创建失败（GL 线程起不来）。 */
  @Volatile var surfaceCreated = false
  /** 回退类别："" 正常；"egl" 上下文失败；"shader" 着色器编译/链接失败；"black" 渲染持续产黑。 */
  @Volatile var fallbackKind: String = ""
  /** 附带的细节（哪个 program 失败、glError 等）。 */
  @Volatile var fallbackDetail: String = ""

  /** Route B 诊断快照（GL 失败时在真机弹窗显示，供截图排障）。 */
  @Volatile var diagnostic = GlDiagnostic()

  /** 最近一次着色器/链接错误日志（写入 diagnostic.programs）。 */
  private var lastShaderLog = ""
  private var lastLinkLog = ""
  private var surfaceCreatedNs = 0L

  // ============================================================================================
  // 生命周期
  // ============================================================================================

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    surfaceCreated = true
    surfaceCreatedNs = System.nanoTime()
    diagnostic = GlDiagnostic()
    diagnostic.surfaceCreated = true
    try {
      diagnostic.glRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "?"
      diagnostic.glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "?"
      diagnostic.glVendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "?"
    } catch (e: Throwable) {
      Log.e(TAG, "glGetString 失败", e)
    }
    // 透明清屏：探针期间（glReady=false）GL 表面全透明，底层 Canvas 透出，避免黑屏空窗
    GLES30.glClearColor(0f, 0f, 0f, 0f)
    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    try {
      textProgram = buildProgram(FoliaLyricShaders.QUAD_VS, FoliaLyricShaders.TEXT_FS)
      diagnostic.programs.add("text" to (if (textProgram == 0) "FAIL: ${lastShaderLog.ifEmpty { lastLinkLog }}" else "OK"))
      brightProgram = buildProgram(FoliaLyricShaders.FULLSCREEN_VS, FoliaLyricShaders.BRIGHT_FS)
      diagnostic.programs.add("bright" to (if (brightProgram == 0) "FAIL: ${lastShaderLog.ifEmpty { lastLinkLog }}" else "OK"))
      blurProgram = buildProgram(FoliaLyricShaders.FULLSCREEN_VS, FoliaLyricShaders.BLUR_FS)
      diagnostic.programs.add("blur" to (if (blurProgram == 0) "FAIL: ${lastShaderLog.ifEmpty { lastLinkLog }}" else "OK"))
      compositeProgram = buildProgram(FoliaLyricShaders.FULLSCREEN_VS, FoliaLyricShaders.COMPOSITE_FS)
      diagnostic.programs.add("composite" to (if (compositeProgram == 0) "FAIL: ${lastShaderLog.ifEmpty { lastLinkLog }}" else "OK"))
      particleProgram = buildProgram(FoliaLyricShaders.PARTICLE_VS, FoliaLyricShaders.PARTICLE_FS)
      diagnostic.programs.add("particle" to (if (particleProgram == 0) "FAIL: ${lastShaderLog.ifEmpty { lastLinkLog }}" else "OK"))

      // 关键修复：任一 program 编译/链接失败 → 禁止渲染，否则用 program=0 静默黑屏
      if (textProgram == 0 || brightProgram == 0 || blurProgram == 0 || compositeProgram == 0 || particleProgram == 0) {
        val detail = "text=$textProgram bright=$brightProgram blur=$blurProgram composite=$compositeProgram particle=$particleProgram"
        Log.e(TAG, "GL program 编译/链接失败：$detail（shader 错误见上方日志 / diagnostic）")
        glReady = false
        fallbackKind = "shader"
        fallbackDetail = detail
        diagnostic.glReady = false
        return
      }

      // unit quad VBO
      val quad = floatArrayOf(
        0f, 0f, 0f, 0f,
        1f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
      )
      quadVbo = createVbo(quad)

      // 粒子 VBO（每粒子 7 floats：x, y, size, r, g, b, a）
      val particleBuf = ByteBuffer.allocateDirect(ParticleSystem.MAX * 7 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
      particleVbo = createVboEmpty(ParticleSystem.MAX * 7 * 4)
      particles.attachBuffer(particleBuf)

      // 封面纹理
      coverTex = createTexture()

      glReady = true
      diagnostic.glReady = true
    } catch (e: Throwable) {
      // onSurfaceCreated 抛异常（资源创建/驱动异常）→ 记入诊断，绝不静默黑屏
      diagnostic.exception = "${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString().take(600)}"
      diagnostic.glReady = false
      Log.e(TAG, "onSurfaceCreated 异常", e)
      glReady = false
      fallbackKind = "surface"
      fallbackDetail = diagnostic.exception
    }
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    viewW = width
    viewH = height
    GLES30.glViewport(0, 0, width, height)
    ensureSceneTargets(width, height)
    buildOrtho(width, height)
  }

  override fun onDrawFrame(gl: GL10?) {
    if (!glReady) return
    if (diagnostic.firstFrameMs < 0 && surfaceCreatedNs > 0) {
      diagnostic.firstFrameMs = (System.nanoTime() - surfaceCreatedNs) / 1_000_000
    }
    val now = System.nanoTime()
    val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
    lastFrameNs = now
    timeSec += dt
    watchdog.onFrame(dt, now)

    consumePendingLines()
    uploadCoverIfNeeded()

    val f = frame ?: run {
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
      return
    }

    bloomLevels = when (quality) {
      QualityTier.HIGH -> 3
      QualityTier.MEDIUM -> 2
      QualityTier.LOW -> 1
    }

    // ---- 1. scene pass ----
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
    GLES30.glViewport(0, 0, sceneWidth, sceneHeight)
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    drawCover()
    if (mode == EffectMode.DIORAMA) {
      drawDiorama(f)
    } else {
      drawTracks2D(f)
    }
    updateAndDrawParticles(f, dt)

    // ---- 2. bloom 链 ----
    runBloomChain()

    // ---- 3. composite ----
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glViewport(0, 0, viewW, viewH)
    drawComposite()

    // ---- 4. 画面自检（决定是否让 GL 层接管显示） ----
    // 每 45 帧在 sceneFBO 上取 3×3（1/6、1/2、5/6）共 9 个点采样：
    //  · 采到内容（>=1 格非黑）→ renderHealthy = true，View 才把 GL 层顶上来；
    //  · 全黑但确有可见歌词 → 连续 2 次则回退 Canvas。
    // 读离屏 sceneFBO 而不是窗口默认帧缓冲（后者在部分驱动上 glReadPixels 恒返回 0）。
    probeTick++
    if (probeTick >= 45) {
      probeTick = 0
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
      var lit = 0
      var maxLum = 0
      for (gy in 0 until 3) {
        for (gx in 0 until 3) {
          val px = ((sceneWidth * (gx + 0.5f)) / 3f).toInt().coerceIn(0, sceneWidth - 1)
          val py = ((sceneHeight * (gy + 0.5f)) / 3f).toInt().coerceIn(0, sceneHeight - 1)
          probePx.rewind()
          GLES30.glReadPixels(px, py, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, probePx)
          val lum = maxOf(
            probePx.get(0).toInt() and 0xFF,
            probePx.get(1).toInt() and 0xFF,
            probePx.get(2).toInt() and 0xFF
          )
          if (lum > 12) lit++
          if (lum > maxLum) maxLum = lum
        }
      }
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
      probeLitCells = lit
      probeMaxLum = maxLum
      val hasContent = frame?.tracks?.any { it.alpha > 0.05f } == true
      if (lit == 0 && hasContent) {
        blackRun++
        if (blackRun >= 2) {
          val err = GLES30.glGetError()
          Log.e(TAG, "黑屏自愈：sceneFBO 全黑但有可见歌词 → 回退 Canvas。glErr=0x${Integer.toHexString(err)} lineTex=${lineTextures.size()} fontPx=${f.fontPx} sceneFbo=$sceneFbo")
          fallbackKind = "black"
          fallbackDetail = "lineTex=${lineTextures.size()} fontPx=${f.fontPx} sceneFbo=$sceneFbo glErr=0x${Integer.toHexString(err)}"
          needsFallback = true
          return
        }
      } else {
        blackRun = 0
        // 采到内容 + 文字纹理已就绪 → 认为整条链路正常，才允许 View 切到 GL 层。
        // 若文字纹理还没提交（lineTextures 空），保持 Canvas 可见，避免「有背景没歌词」。
        if (lit >= 1 && lineTextures.size() > 0) renderHealthy = true
      }
    }
  }

  // ============================================================================================
  // 资源管理
  // ============================================================================================

  private fun ensureSceneTargets(w: Int, h: Int) {
    if (sceneFbo != 0 && sceneWidth == w && sceneHeight == h) return
    releaseTarget(sceneFbo, sceneTex)
    val (fbo, tex) = createFboTexture(w, h)
    sceneFbo = fbo; sceneTex = tex; sceneWidth = w; sceneHeight = h

    for (i in 0 until 3) {
      releaseTarget(bloomFbo[i][0], bloomTex[i][0])
      releaseTarget(bloomFbo[i][1], bloomTex[i][1])
      val bw = max(1, w shr (i + 1))
      val bh = max(1, h shr (i + 1))
      bloomSize[i][0] = bw; bloomSize[i][1] = bh
      val (fa, ta) = createFboTexture(bw, bh)
      val (fb, tb) = createFboTexture(bw, bh)
      bloomFbo[i][0] = fa; bloomTex[i][0] = ta
      bloomFbo[i][1] = fb; bloomTex[i][1] = tb
    }
    // 初始 clear：未进入 bloom 链的级（bloomLevels < 3 时）被 composite 以权重 0 采样，
    // 未初始化显存可能是 NaN/垃圾，0 × NaN = NaN 会污染整屏——必须先清零。
    for (i in 0 until 3) {
      for (j in 0 until 2) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[i][j])
        GLES30.glViewport(0, 0, bloomSize[i][0], bloomSize[i][1])
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
      }
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glViewport(0, 0, w, h)

    // 一次性完整性校验（实机排障：logcat 过滤 FoliaGLRenderer / diagnostic）
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
    val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    diagnostic.framebufferStatus = st
    if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) {
      Log.e(TAG, "scene FBO incomplete: 0x" + Integer.toHexString(st))
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
  }

  private fun consumePendingLines() {
    // 行失效（View 线程置位）：全部 GPU 资源操作必须在 GL 线程执行
    if (linesDirty) {
      linesDirty = false
      for (i in 0 until lineTextures.size()) {
        GLES30.glDeleteTextures(1, intArrayOf(lineTextures.valueAt(i)), 0)
      }
      for (i in 0 until lineBitmaps.size()) {
        lineBitmaps.valueAt(i)?.recycle()
      }
      lineTextures.clear()
      lineBitmaps.clear()
    }
    synchronized(pendingLock) {
      if (pendingLines.isEmpty()) return
      for ((idx, bmp) in pendingLines) {
        val old = lineTextures.get(idx, 0)
        if (old != 0) {
          GLES30.glDeleteTextures(1, intArrayOf(old), 0)
          lineBitmaps.get(idx)?.recycle()
        }
        val tex = createTexture()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        lineTextures.put(idx, tex)
        lineBitmaps.put(idx, bmp)
      }
      pendingLines.clear()
    }
  }

  /** View 线程调用：提交一行文字的位图（GL 线程异步上传）。 */
  fun submitLine(lineIndex: Int, bitmap: Bitmap) {
    synchronized(pendingLock) { pendingLines.add(lineIndex to bitmap) }
  }

  /** View 线程调用：排版变化时丢弃全部行纹理（GPU 侧在下一帧 GL 线程执行）。 */
  fun invalidateLines() {
    linesDirty = true
  }

  @Volatile
  private var linesDirty = false

  /** 行纹理的模糊 padding（px，栅化行位图时四周留白）。 */
  val blurPadPx: Float
    get() = 14f * densityOf()

  /** GL 上下文是否就绪（探针回退 Canvas 路径用）。 */
  fun isGlReady(): Boolean = glReady

  private var lastAliveParticles = 0

  /** 粒子是否仍在飞（GL 路径持续取帧用）。 */
  fun hasActiveParticles(): Boolean = lastAliveParticles > 0

  private var coverDirty = true
  fun invalidateCover() { coverDirty = true }

  private fun uploadCoverIfNeeded() {
    if (!coverDirty) return
    coverDirty = false
    val bmp = coverProvider?.invoke() ?: return
    if (bmp.isRecycled) return
    coverTexW = bmp.width; coverTexH = bmp.height
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coverTex)
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
  }

  fun release() {
    if (!glReady) return
    releaseTarget(sceneFbo, sceneTex)
    for (i in 0 until 3) {
      releaseTarget(bloomFbo[i][0], bloomTex[i][0])
      releaseTarget(bloomFbo[i][1], bloomTex[i][1])
    }
    for (i in 0 until lineTextures.size()) {
      GLES30.glDeleteTextures(1, intArrayOf(lineTextures.valueAt(i)), 0)
    }
    lineTextures.clear()
    GLES30.glDeleteTextures(1, intArrayOf(coverTex), 0)
    GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
    GLES30.glDeleteBuffers(1, intArrayOf(particleVbo), 0)
    glReady = false
  }

  // ============================================================================================
  // 绘制：背景 / 2D 文字 / 3D / 粒子
  // ============================================================================================

  private fun drawCover() {
    if (coverTex == 0 || coverTexW == 0) return
    GLES30.glUseProgram(textProgram)
    // 关键修复：QUAD_VS 的 aPos 是 unit quad(0..1)，必须先把 quad 缩放到 scene 像素空间，
    // 再乘 ortho 投到 NDC。之前直接 uMVP = ortho(像素空间)，会把整张封面压成左上角 1×1 像素，
    // scene 几乎全空 → 合成后只剩主题纱罩，真机上看起来就是「黑屏」。
    coverM.let { m ->
      android.opengl.Matrix.setIdentityM(m, 0)
      android.opengl.Matrix.scaleM(m, 0, sceneWidth.toFloat(), sceneHeight.toFloat(), 1f)
      android.opengl.Matrix.multiplyMM(coverMvp, 0, ortho, 0, m, 0)
    }
    GLES30.glUniformMatrix4fv(uMvp(textProgram), 1, false, coverMvp, 0)
    // 居中裁剪（center-crop）：保持封面比例，与 Canvas 路径的 cover 行为一致
    val texAspect = coverTexW / coverTexH.toFloat().coerceAtLeast(1f)
    val viewAspect = sceneWidth / sceneHeight.toFloat().coerceAtLeast(1f)
    var u0 = 0f; var v0 = 0f; var u1 = 1f; var v1 = 1f
    if (texAspect > viewAspect) {
      val k = viewAspect / texAspect
      u0 = (1f - k) * 0.5f; u1 = 1f - u0
    } else {
      val k = texAspect / viewAspect
      v0 = (1f - k) * 0.5f; v1 = 1f - v0
    }
    GLES30.glUniform4f(uUvRect(textProgram), u0, v0, u1 - u0, v1 - v0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coverTex)
    GLES30.glUniform1i(uTex(textProgram), 0)
    GLES30.glUniform4f(uUnsung(textProgram), 1f, 1f, 1f, 1f)
    GLES30.glUniform4f(uSung(textProgram), 1f, 1f, 1f, 1f)
    GLES30.glUniform1f(uFillX(textProgram), 0f)
    GLES30.glUniform1f(uEdgePx(textProgram), 0f)
    GLES30.glUniform1f(uLineWidthPx(textProgram), 1f)
    GLES30.glUniform1f(uBlurRadiusPx(textProgram), 0f)
    GLES30.glUniform2f(uTexSize(textProgram), 1f, 1f)
    GLES30.glUniform1f(uAlpha(textProgram), 1f)
    GLES30.glUniform1f(uTextX0(textProgram), 0f)
    GLES30.glUniform1f(uTextW(textProgram), 1f)
    bindQuad()
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
  }

  /** 2D 路径：base unsung 整行 + 逐词 sung 扫光（scissor 逐词裁剪）。 */
  private fun drawTracks2D(f: FrameState) {
    GLES30.glUseProgram(textProgram)
    GLES30.glEnable(GLES30.GL_SCISSOR_TEST)

    // 绘制顺序：退场 → 上下文（远→近）→ 当前行（与 Canvas 路径一致）
    val ordered = f.tracks.sortedWith(
      compareBy({ it.leaving }, { if (it.lineIndex == f.activeIndex) 2 else -abs(it.lineIndex - f.activeIndex) })
    )

    for (t in ordered) {
      val l = f.layout.getOrNull(t.lineIndex) ?: continue
      if (t.alpha <= 0.005f) continue
      val tex = lineTextures.get(t.lineIndex, 0)
      if (tex == 0) continue
      val isActive = t.lineIndex == f.activeIndex && !t.leaving

      // 行 quad 的屏幕几何：整行水平居中，锚点 (viewW/2, t.y)，缩放 t.scale
      val lineW = l.rowWidths.maxOrNull() ?: 0f
      if (lineW <= 0f) continue
      val pad = blurPadPx
      val quadW = lineW + pad * 2f
      val quadH = l.blockHeight + pad * 2f
      val texW = quadW
      val texH = quadH

      // 模型矩阵 M：unit quad (0..1) → 像素空间（围绕文字区中心与顶边定位，
      // 与 Canvas 路径的 translate(viewW/2, t.y) + scale(s, s) 一致）；
      // 最终 gl_Position = ortho * M * pos，ortho 把像素映射到 NDC（shader 内完成）。
      val m = FloatArray(16)
      android.opengl.Matrix.setIdentityM(m, 0)
      android.opengl.Matrix.scaleM(m, 0, quadW * t.scale, quadH * t.scale, 1f)
      // 文字区左缘 (u=pad/quadW) → viewW/2 - lineW*s/2；文字区顶 (v=pad/quadH) → t.y
      m[12] = viewW / 2f - (lineW / 2f + pad) * t.scale
      m[13] = t.y - pad * t.scale
      // 关键修正：uMVP 必须是 ortho × M，否则 quad 落在像素坐标（裁剪空间外）→ 黑屏
      val mvp = FloatArray(16)
      android.opengl.Matrix.multiplyMM(mvp, 0, ortho, 0, m, 0)
      GLES30.glUniformMatrix4fv(uMvp(textProgram), 1, false, mvp, 0)
      // UV 子区：文字区占纹理中央（四周 padding）
      GLES30.glUniform4f(uUvRect(textProgram), 0f, 0f, 1f, 1f)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
      GLES30.glUniform1i(uTex(textProgram), 0)
      GLES30.glUniform2f(uTexSize(textProgram), texW, texH)
      // 墨迹渐变按文字区归一化（与 Canvas 路径的沿字形渐变一致）
      GLES30.glUniform1f(uTextX0(textProgram), pad / texW)
      GLES30.glUniform1f(uTextW(textProgram), lineW / texW)
      GLES30.glUniform1f(uBlurRadiusPx(textProgram), t.blurPx)
      GLES30.glUniform1f(uAlpha(textProgram), t.alpha)

      // base：整行 unsung（绘光模式用 Lumiere 光室色系：未唱冷色刻字）
      val lumiere = mode == EffectMode.LUMIERE
      val cold = blendColor(palette.unsung, palette.active, 0.21f)
      val hot = blendColor(palette.active, palette.sung, 0.5f)
      val unsung = if (isActive) {
        if (lumiere) cold else palette.unsung
      } else blendColor(palette.unsung, palette.active, 0.22f)
      GLES30.glUniform4f(uUnsung(textProgram), r(unsung), g(unsung), b(unsung), if (isActive) FoliaLyricEngine.BASE_ALPHA else 0.9f)
      GLES30.glUniform4f(uSung(textProgram), r(unsung), g(unsung), b(unsung), if (isActive) FoliaLyricEngine.BASE_ALPHA else 0.9f)
      GLES30.glUniform1f(uFillX(textProgram), 0f)
      GLES30.glUniform1f(uEdgePx(textProgram), 0f)
      GLES30.glUniform1f(uLineWidthPx(textProgram), texW)
      bindQuad()
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

      if (!isActive) continue

      // sung：逐词 scissor + 扫光（绘光模式用 Lumiere 热色）
      val sungColor = if (lumiere) hot else blendColor(palette.active, palette.sung, 0.25f)
      GLES30.glUniform4f(uUnsung(textProgram), r(sungColor), g(sungColor), b(sungColor), 1f)
      GLES30.glUniform4f(uSung(textProgram), r(sungColor), g(sungColor), b(sungColor), 1f)
      val edge = (fontPxOf() * 0.45f).coerceIn(6f * densityOf(), 16f * densityOf())
      GLES30.glUniform1f(uEdgePx(textProgram), edge)
      GLES30.glUniform1f(uLineWidthPx(textProgram), texW)

      val words = f.activeWords
      for (wi in words.indices) {
        val w = words[wi]
        val token = l.tokens.getOrNull(w.index) ?: continue
        if (token.width <= 0f) continue
        if (!w.sung && !w.singing) continue
        // scissor 到该词（屏幕 px，左下原点）
        val x0 = viewW / 2f - lineW / 2f + token.x
        val x1 = x0 + token.width
        val yTop = t.y + token.row * l.rowPitch - pad
        val yBottom = yTop + l.rowPitch + pad * 2f
        val sx0 = (x0 * t.scale + viewW / 2f * (1f - t.scale)).toInt()
        val sx1 = (x1 * t.scale + viewW / 2f * (1f - t.scale)).toInt()
        val syTop = (yTop * t.scale + t.y * (1f - t.scale)).toInt()
        val syBottom = (yBottom * t.scale + t.y * (1f - t.scale)).toInt()
        GLES30.glScissor(
          sx0, sceneHeight - syBottom,
          (sx1 - sx0).coerceAtLeast(1), (syBottom - syTop).coerceAtLeast(1)
        )
        // uFillX 为该词填充前沿在整行纹理中的位置
        val fillX = (token.x + w.fillPx + pad) / texW
        GLES30.glUniform1f(uFillX(textProgram), fillX.coerceIn(0f, 1f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
      }
    }
    GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
  }

  /**
   * 镜台飞行：行在 2D 像素空间按深度做伪透视（远处缩小 + 上下退场 + 正弦漂移），
   * 全部经过 ortho 投影，保证落在屏幕内。与 2D 路径共用同一套文字 shader。
   */
  private fun drawDiorama(f: FrameState) {
    GLES30.glUseProgram(textProgram)

    for (t in f.tracks) {
      val l = f.layout.getOrNull(t.lineIndex) ?: continue
      if (t.alpha <= 0.005f) continue
      val tex = lineTextures.get(t.lineIndex, 0)
      if (tex == 0) continue
      val d = (t.lineIndex - f.activeIndex).toFloat()
      val lineW = l.rowWidths.maxOrNull() ?: 0f
      if (lineW <= 0f) continue

      // 伪透视：远处行更小、向后退场（d>0 未来行上移，d<0 过去行下移，与阅读方向相反）
      val zScale = 1f / (1f + abs(d) * 1.45f)
      val drift = sin(timeSec * 0.18f + t.lineIndex * 1.7f) * 22f * zScale
      val yRecede = d * 64f
      val quadW = (lineW + blurPadPx * 2f) * t.scale * zScale
      val quadH = (l.blockHeight + blurPadPx * 2f) * t.scale * zScale
      val cx = viewW / 2f + drift
      val cy = t.y + yRecede

      // 模型矩阵（像素空间）→ 再叠 ortho 投影
      val m = FloatArray(16)
      android.opengl.Matrix.setIdentityM(m, 0)
      android.opengl.Matrix.scaleM(m, 0, quadW, quadH, 1f)
      m[12] = cx - (lineW / 2f + blurPadPx) * t.scale * zScale
      m[13] = cy - blurPadPx * t.scale * zScale
      val mvp = FloatArray(16)
      android.opengl.Matrix.multiplyMM(mvp, 0, ortho, 0, m, 0)

      GLES30.glUniformMatrix4fv(uMvp(textProgram), 1, false, mvp, 0)
      GLES30.glUniform4f(uUvRect(textProgram), 0f, 0f, 1f, 1f)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
      GLES30.glUniform1i(uTex(textProgram), 0)
      val isActive = t.lineIndex == f.activeIndex && !t.leaving
      val unsung = if (isActive) palette.unsung else blendColor(palette.unsung, palette.active, 0.22f)
      val sung = blendColor(palette.active, palette.sung, 0.25f)
      GLES30.glUniform4f(uUnsung(textProgram), r(unsung), g(unsung), b(unsung), if (isActive) FoliaLyricEngine.BASE_ALPHA else 0.55f)
      GLES30.glUniform4f(uSung(textProgram), r(sung), g(sung), b(sung), 1f)
      val dioramaTexW = lineW + blurPadPx * 2f
      GLES30.glUniform1f(uLineWidthPx(textProgram), dioramaTexW)
      GLES30.glUniform1f(uEdgePx(textProgram), 0f)
      GLES30.glUniform2f(uTexSize(textProgram), dioramaTexW, l.blockHeight + blurPadPx * 2f)
      GLES30.glUniform1f(uTextX0(textProgram), blurPadPx / dioramaTexW)
      GLES30.glUniform1f(uTextW(textProgram), lineW / dioramaTexW)
      GLES30.glUniform1f(uBlurRadiusPx(textProgram), if (isActive) 0f else 1.2f * densityOf())
      GLES30.glUniform1f(uAlpha(textProgram), t.alpha)

      if (isActive) {
        val last = f.activeWords.lastOrNull()
        val token = l.tokens.getOrNull(last?.index ?: -1)
        val fillX = if (token != null) (token.x + (last?.fillPx ?: 0f) + blurPadPx) /
          (lineW + blurPadPx * 2f) else 1f
        GLES30.glUniform1f(uFillX(textProgram), fillX.coerceIn(0f, 1f))
      } else {
        GLES30.glUniform1f(uFillX(textProgram), 0f)
      }
      bindQuad()
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
  }

  /** 粒子更新 + 加法混合绘制（写入 sceneFBO，参与 bloom）。 */
  private fun updateAndDrawParticles(f: FrameState, dt: Float) {
    // 词完成事件 → 迸发（仅镜台模式的尘埃粒子持续更新）

    if (mode == EffectMode.DIORAMA) {
      lastAliveParticles = particles.update(dt)
      return
    }
    val count = particles.update(dt)
    lastAliveParticles = count
    if (count <= 0) return

    GLES30.glUseProgram(particleProgram)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE) // 加法
    GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(particleProgram, "uMVP"), 1, false, identityM, 0)
    GLES30.glUniform2f(GLES30.glGetUniformLocation(particleProgram, "uViewport"), viewW.toFloat(), viewH.toFloat())

    val n = particles.fillBuffer()
    if (n <= 0) {
      GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
      return
    }
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, particleVbo)
    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 7 * 4, particles.buffer)
    val stride = 7 * 4
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 8)
    GLES30.glEnableVertexAttribArray(2)
    GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 12)
    GLES30.glEnableVertexAttribArray(3)
    GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 24)
    GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n)
    GLES30.glDisableVertexAttribArray(0)
    GLES30.glDisableVertexAttribArray(1)
    GLES30.glDisableVertexAttribArray(2)
    GLES30.glDisableVertexAttribArray(3)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
  }

  // ============================================================================================
  // 后处理：bloom 链 + 合成
  // ============================================================================================

  private fun runBloomChain() {
    // bright pass：scene → bloom[0].A
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[0][0])
    GLES30.glViewport(0, 0, bloomSize[0][0], bloomSize[0][1])
    fullscreenPass(brightProgram, sceneTex, 0.6f)

    for (level in 0 until bloomLevels) {
      // 级联：本级输入必须来自上一级结果（level 0 = bright pass 输出）。
      // direction=(0,0) 时 blur 的 5-tap 全部落在同一点，权重和 = 1，等价于
      // 「纯双线性复制」——借它完成跨级降采样，避免采样未初始化纹理。
      if (level > 0) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[level][0])
        GLES30.glViewport(0, 0, bloomSize[level][0], bloomSize[level][1])
        fullscreenPass(blurProgram, bloomTex[level - 1][0], 0f, 0f, 0f)
      }
      // H：A → B
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[level][1])
      GLES30.glViewport(0, 0, bloomSize[level][0], bloomSize[level][1])
      fullscreenPass(blurProgram, bloomTex[level][0], 0f,
        dirX = 1f / bloomSize[level][0], dirY = 0f)
      // V：B → A
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[level][0])
      fullscreenPass(blurProgram, bloomTex[level][1], 0f,
        dirX = 0f, dirY = 1f / bloomSize[level][1])
    }
  }

  private fun drawComposite() {
    GLES30.glUseProgram(compositeProgram)
    GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "uScene"), 0)
    GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "uScene"), 0)
    GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "uBloom1"), 1)
    GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "uBloom2"), 2)
    GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "uBloom3"), 3)
    val w1 = if (bloomLevels >= 1) 0.85f else 0f
    val w2 = if (bloomLevels >= 2) 0.55f else 0f
    val w3 = if (bloomLevels >= 3) 0.35f else 0f
    GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "uBloomWeight1"), w1)
    GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "uBloomWeight2"), w2)
    GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "uBloomWeight3"), w3)
    GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "uTime"), timeSec)
    GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "uFlow"), 0f)
    GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "uVignette"), 0.55f)
    GLES30.glUniform4f(GLES30.glGetUniformLocation(compositeProgram, "uScrimTop"),
      r(palette.bgTop), g(palette.bgTop), b(palette.bgTop), 1f)
    GLES30.glUniform4f(GLES30.glGetUniformLocation(compositeProgram, "uScrimBottom"),
      r(palette.bgBottom), g(palette.bgBottom), b(palette.bgBottom), 1f)

    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[0][0])
    // 未启用的 bloom 级别：绑有效纹理（sceneTex）而非未初始化纹理，
    // 避免「0 × NaN = NaN」污染整屏（部分驱动对未初始化纹理返回 NaN）。
    GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (bloomLevels >= 2) bloomTex[1][0] else sceneTex)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (bloomLevels >= 3) bloomTex[2][0] else sceneTex)

    drawFullscreen()
  }

  // ============================================================================================
  // GL 工具
  // ============================================================================================

  private val identityM = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

  private fun bindQuad() {
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
  }

  private fun drawFullscreen() {
    bindQuad()
    // FULLSCREEN_VS 自行把 unit quad 映射到全屏 NDC，无需 MVP
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
  }

  private fun fullscreenPass(program: Int, tex: Int, threshold: Float, dirX: Float = 0f, dirY: Float = 0f) {
    GLES30.glUseProgram(program)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)
    if (program == brightProgram) {
      GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uThreshold"), threshold)
    }
    if (program == blurProgram) {
      GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uDirection"), dirX, dirY)
    }
    bindQuad()
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
  }

  private fun uMvp(p: Int) = GLES30.glGetUniformLocation(p, "uMVP")
  private fun uUvRect(p: Int) = GLES30.glGetUniformLocation(p, "uUvRect")
  private fun uTex(p: Int) = GLES30.glGetUniformLocation(p, "uTex")
  private fun uUnsung(p: Int) = GLES30.glGetUniformLocation(p, "uUnsungColor")
  private fun uSung(p: Int) = GLES30.glGetUniformLocation(p, "uSungColor")
  private fun uFillX(p: Int) = GLES30.glGetUniformLocation(p, "uFillX")
  private fun uEdgePx(p: Int) = GLES30.glGetUniformLocation(p, "uEdgePx")
  private fun uLineWidthPx(p: Int) = GLES30.glGetUniformLocation(p, "uLineWidthPx")
  private fun uBlurRadiusPx(p: Int) = GLES30.glGetUniformLocation(p, "uBlurRadiusPx")
  private fun uTexSize(p: Int) = GLES30.glGetUniformLocation(p, "uTexSize")
  private fun uAlpha(p: Int) = GLES30.glGetUniformLocation(p, "uAlpha")
  private fun uTextX0(p: Int) = GLES30.glGetUniformLocation(p, "uTextX0")
  private fun uTextW(p: Int) = GLES30.glGetUniformLocation(p, "uTextW")

  /**
   * 正交投影：把像素坐标（左上原点，y 向下）映射到 NDC。
   * 注意 y 轴翻转：屏幕像素 y=0（顶）→ NDC +1（顶），与纹理/位图的方向一致，
   * 否则文字/封面会被上下颠倒。
   */
  private fun buildOrtho(w: Int, h: Int) {
    android.opengl.Matrix.orthoM(ortho, 0, 0f, w.toFloat(), h.toFloat(), 0f, -1f, 1f)
  }

  private fun createVbo(data: FloatArray): Int {
    val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    buf.put(data).position(0)
    val ids = IntArray(1)
    GLES30.glGenBuffers(1, ids, 0)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    return ids[0]
  }

  private fun createVboEmpty(sizeBytes: Int): Int {
    val ids = IntArray(1)
    GLES30.glGenBuffers(1, ids, 0)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sizeBytes, null, GLES30.GL_DYNAMIC_DRAW)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    return ids[0]
  }

  private fun createTexture(): Int {
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    return ids[0]
  }

  private fun createFboTexture(w: Int, h: Int): Pair<Int, Int> {
    val tex = createTexture()
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
      GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
    val fbos = IntArray(1)
    GLES30.glGenFramebuffers(1, fbos, 0)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[0])
    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
      GLES30.GL_TEXTURE_2D, tex, 0)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    return fbos[0] to tex
  }

  private fun releaseTarget(fbo: Int, tex: Int) {
    if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
    if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
  }

  private fun buildProgram(vsSrc: String, fsSrc: String): Int {
    val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
    val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
    val p = GLES30.glCreateProgram()
    GLES30.glAttachShader(p, vs)
    GLES30.glAttachShader(p, fs)
    GLES30.glLinkProgram(p)
    val status = IntArray(1)
    GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
    if (status[0] != GLES30.GL_TRUE) {
      lastLinkLog = GLES30.glGetProgramInfoLog(p)
      Log.e(TAG, "link failed: $lastLinkLog")
      GLES30.glDeleteProgram(p)
      return 0
    }
    lastShaderLog = ""
    lastLinkLog = ""
    GLES30.glDeleteShader(vs)
    GLES30.glDeleteShader(fs)
    return p
  }

  private fun compileShader(type: Int, src: String): Int {
    val id = GLES30.glCreateShader(type)
    GLES30.glShaderSource(id, src)
    GLES30.glCompileShader(id)
    val status = IntArray(1)
    GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
    if (status[0] != GLES30.GL_TRUE) {
      lastShaderLog = GLES30.glGetShaderInfoLog(id)
      Log.e(TAG, "shader compile failed: $lastShaderLog")
    }
    return id
  }

  // ---- 颜色 / 尺寸便捷访问 ----
  private fun densityOf(): Float = if (viewW > 0) viewW / 1080f * 3f else 3f
  private fun fontPxOf(): Float = frame?.fontPx?.takeIf { it > 0f } ?: 24f

  private fun r(c: Int) = android.graphics.Color.red(c) / 255f
  private fun g(c: Int) = android.graphics.Color.green(c) / 255f
  private fun b(c: Int) = android.graphics.Color.blue(c) / 255f

  private fun blendColor(a: Int, b: Int, f: Float): Int {
    val k = f.coerceIn(0f, 1f)
    return android.graphics.Color.argb(
      255,
      (android.graphics.Color.red(a) + (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * k).toInt(),
      (android.graphics.Color.green(a) + (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * k).toInt(),
      (android.graphics.Color.blue(a) + (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * k).toInt(),
    )
  }

  companion object {
    private const val TAG = "FoliaGLRenderer"
  }
}

// ============================================================================================
// 粒子系统（CPU 更新，GL_POINTS 加法混合）
// ============================================================================================

internal class ParticleSystem {

  class P {
    var x = 0f; var y = 0f
    var vx = 0f; var vy = 0f
    var life = 0f; var maxLife = 1f
    var size = 4f
    var r = 1f; var g = 1f; var b = 1f
    var gravity = 0f
  }

  private val pool = ArrayList<P>(MAX)
  val buffer: FloatBuffer = ByteBuffer.allocateDirect(MAX * 7 * 4)
    .order(ByteOrder.nativeOrder()).asFloatBuffer()

  fun attachBuffer(buf: FloatBuffer) { /* buffer 已由构造提供 */ }

  /** 一束粒子（词唱完时）。 */
  fun burst(cx: Float, cy: Float, count: Int, color: Int, seed: Int, power: Float) {
    val cr = android.graphics.Color.red(color) / 255f
    val cg = android.graphics.Color.green(color) / 255f
    val cb = android.graphics.Color.blue(color) / 255f
    var spawned = 0
    var attempts = 0
    while (spawned < count && attempts < count * 4) {
      attempts++
      val p = pool.firstOrNull { it.life <= 0f } ?: if (pool.size < MAX) P().also { pool.add(it) } else continue
      val ang = (seedRand(seed * 31 + spawned, 1) * 2f - 1f) * Math.PI.toFloat()
      val sp = power * (0.35f + seedRand(seed * 17 + spawned, 2) * 0.9f)
      p.x = cx; p.y = cy
      p.vx = cos(ang) * sp
      p.vy = sin(ang) * sp - power * 0.25f
      p.maxLife = 0.55f + seedRand(seed * 7 + spawned, 3) * 0.75f
      p.life = p.maxLife
      p.size = 3f + seedRand(seed * 13 + spawned, 4) * 7f
      p.r = cr; p.g = cg; p.b = cb
      p.gravity = power * 0.35f
      spawned++
    }
  }

  /** @return 存活粒子数 */
  fun update(dt: Float): Int {
    var alive = 0
    for (p in pool) {
      if (p.life <= 0f) continue
      p.life -= dt
      if (p.life <= 0f) continue
      p.vy += p.gravity * dt
      p.vx *= (1f - 1.6f * dt)
      p.x += p.vx * dt
      p.y += p.vy * dt
      alive++
    }
    return alive
  }

  /** 把存活粒子写入交错 buffer，返回个数。 */
  fun fillBuffer(): Int {
    var n = 0
    buffer.position(0)
    for (p in pool) {
      if (p.life <= 0f) continue
      if (n >= MAX) break
      val fade = (p.life / p.maxLife).coerceIn(0f, 1f)
      val alpha = fade * fade
      buffer.put(p.x).put(p.y).put(p.size * (0.5f + fade * 0.5f))
        .put(p.r).put(p.g).put(p.b).put(alpha)
      n++
    }
    buffer.position(0)
    return n
  }

  private fun seedRand(i: Int, salt: Int): Float {
    val x = sin(i * 127.1f + salt * 311.7f) * 43758.5453f
    return x - kotlin.math.floor(x)
  }

  companion object {
    const val MAX = 512
  }
}

// ============================================================================================
// 帧耗时看门狗：车机散热降频时自动降画质
// ============================================================================================

internal class FrameWatchdog(private val onTierChange: (QualityTier) -> Unit) {

  private var tier = QualityTier.HIGH
  private var emaMs = 16.6f
  private var overBudgetFrames = 0
  private var underBudgetFrames = 0

  fun onFrame(dtSec: Float, nowNs: Long) {
    val ms = dtSec * 1000f
    emaMs = if (emaMs <= 0f) ms else emaMs * 0.9f + ms * 0.1f
    if (emaMs > 19f) {
      overBudgetFrames++
      underBudgetFrames = 0
    } else if (emaMs < 12f) {
      underBudgetFrames++
      overBudgetFrames = 0
    } else {
      overBudgetFrames = 0
      underBudgetFrames = 0
    }
    if (overBudgetFrames > 40 && tier != QualityTier.LOW) {
      tier = if (tier == QualityTier.HIGH) QualityTier.MEDIUM else QualityTier.LOW
      overBudgetFrames = 0
      onTierChange(tier)
    } else if (underBudgetFrames > 240 && tier != QualityTier.HIGH) {
      tier = if (tier == QualityTier.LOW) QualityTier.MEDIUM else QualityTier.HIGH
      underBudgetFrames = 0
      onTierChange(tier)
    }
  }

  fun currentTier(): QualityTier = tier
}
