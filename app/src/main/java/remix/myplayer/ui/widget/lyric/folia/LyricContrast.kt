package remix.myplayer.ui.widget.lyric.folia

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * 歌词与背景的对比度守卫（WCAG 2.1 相对亮度公式）。
 *
 * 动态歌词页的背景是**从封面动态生成的**（封面模糊 / 莫奈洗色 / 漫游色阶），
 * 而歌词的高亮色又可能来自主色调——「主色调取自封面」正是如此。
 * 两者同源时颜色会非常接近，歌词就糊在背景里。
 *
 * 这里按 WCAG 标准度量对比度，不达标时**只微调明度**（保留色相与饱和度），
 * 沿「远离背景」的方向做**最小必要调整**——既解决看不清，又不破坏原有配色观感。
 *
 * 关键：判断对比度时用的是**文字实际绘制出来的合成色**（前景按绘制 alpha 压在背景上），
 * 而不是前景原色。未唱底字只画 42% 不透明度，用原色算会严重高估可读性。
 */
object LyricContrast {

  /** 相对亮度（WCAG 2.1）。 */
  fun relativeLuminance(color: Int): Double {
    val r = linearize(Color.red(color) / 255.0)
    val g = linearize(Color.green(color) / 255.0)
    val b = linearize(Color.blue(color) / 255.0)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
  }

  /** 对比度（1.0 ~ 21.0）。 */
  fun contrastRatio(fg: Int, bg: Int): Double {
    val l1 = relativeLuminance(fg)
    val l2 = relativeLuminance(bg)
    val hi = maxOf(l1, l2)
    val lo = minOf(l1, l2)
    return (hi + 0.05) / (lo + 0.05)
  }

  /**
   * 把前景色按 [alpha] 合成到背景上，得到**屏幕上真正看到的颜色**。
   * （sRGB 空间的线性近似，足够判断可读性。）
   */
  fun composite(fg: Int, bg: Int, alpha: Float): Int {
    val a = alpha.coerceIn(0f, 1f)
    fun mix(f: Int, b: Int) = (b + (f - b) * a).toInt().coerceIn(0, 255)
    return Color.rgb(
      mix(Color.red(fg), Color.red(bg)),
      mix(Color.green(fg), Color.green(bg)),
      mix(Color.blue(fg), Color.blue(bg))
    )
  }

  /**
   * 保证 [fg] 以 [alpha] 不透明度画在 [bg] 上时，合成结果的对比度不低于 [minRatio]。
   *
   * 做法：转到 HSL，只动明度 L，**保留色相 H 与饱和度 S**，
   * 沿「远离背景」的方向做**最小必要调整**。
   *
   * 优先方向：文字合成色比背景亮 → 往更亮推；比背景暗 → 往更暗推。
   * 若该方向推到极限仍不达标（例如亮背景 + 浅色字），再试反方向，取能达标者；
   * 两侧都不达标时取对比度更高的一侧（尽力而为）。
   *
   * @param fg 前景（歌词）色，ARGB，alpha 保持不变
   * @param bg 背景色，ARGB
   * @param minRatio 目标对比度（针对合成后的实际观感）
   * @param alpha 文字绘制时的实际不透明度
   */
  fun ensureContrast(fg: Int, bg: Int, minRatio: Double, alpha: Float = 1f): Int {
    val composited = composite(fg, bg, alpha)
    if (contrastRatio(composited, bg) >= minRatio) {
      return fg
    }
    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(Color.red(fg), Color.green(fg), Color.blue(fg), hsl)
    val fgAlpha = Color.alpha(fg)

    val bgLum = relativeLuminance(bg)
    val compositedLum = relativeLuminance(composited)
    // 文字比背景亮 → 往亮处推；比背景暗 → 往暗处推
    val towardLight = compositedLum >= bgLum

    val primary = searchLightness(hsl, bg, minRatio, alpha, towardLight)
    var best = primary.first
    var bestRatio = primary.second
    if (bestRatio < minRatio) {
      // 主方向到极限仍不达标 → 试反方向
      val opposite = searchLightness(hsl, bg, minRatio, alpha, !towardLight)
      if (opposite.second > bestRatio) {
        best = opposite.first
        bestRatio = opposite.second
      }
    }
    val rgb = ColorUtils.HSLToColor(best)
    return Color.argb(fgAlpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
  }

  /**
   * 在明度轴上二分搜索：找**最小必要**的明度调整量使合成对比度达标。
   *
   * @param towardLight true 只往亮处找（[L,1]），false 只往暗处找（[0,L]）
   * @return 调整后的 HSL 与其实际达到的对比度
   */
  private fun searchLightness(
    hsl: FloatArray,
    bg: Int,
    minRatio: Double,
    alpha: Float,
    towardLight: Boolean,
  ): Pair<FloatArray, Double> {
    val probe = hsl.copyOf()
    val origin = hsl[2]
    val extreme = if (towardLight) 1f else 0f

    fun ratioAt(lightness: Float): Double {
      probe[2] = lightness
      return contrastRatio(composite(ColorUtils.HSLToColor(probe), bg, alpha), bg)
    }

    // 极端值都达不到 → 取极端（无解时的最优解）
    if (ratioAt(extreme) < minRatio) {
      probe[2] = extreme
      return probe to ratioAt(extreme)
    }
    // 二分：区间 [origin, extreme]
    var lo = origin
    var hi = extreme
    repeat(18) {
      val mid = (lo + hi) / 2f
      if (ratioAt(mid) >= minRatio) {
        hi = mid
      } else {
        lo = mid
      }
    }
    probe[2] = hi
    return probe to ratioAt(hi)
  }

  private fun linearize(channel: Double): Double =
    if (channel <= 0.03928) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
}

/**
 * 生成「保证可读」的歌词配色。
 *
 * 只动**歌词用色**（已唱 / 未唱 / 高亮 / 译文 / 光晕），不动背景相关色
 * （[Palette.bgTop] / [Palette.bgBottom]）——背景是画出来的，歌词叠在背景上，
 * 两者必须解耦，否则会出现「为了看清歌词把背景也改了」的循环。
 *
 * 各角色的目标对比度与实际绘制 alpha 不同：
 * - 已唱主字 / 高亮色：全不透明绘制，最需要看清 → 目标最高；
 * - 未唱底字：只画 42% 不透明度，天然对比低 → 目标放宽；
 * - 译文 / 光晕：装饰性 → 目标最低。
 *
 * @param backdrop 背景代表色（ARGB）
 */
fun Palette.readableAgainst(backdrop: Int): Palette {
  if (backdrop == 0) return this
  return copy(
    active = LyricContrast.ensureContrast(active, backdrop, 3.2, 1f),
    activeSecondary = LyricContrast.ensureContrast(activeSecondary, backdrop, 2.2, 0.6f),
    sung = LyricContrast.ensureContrast(sung, backdrop, 3.2, 1f),
    unsung = LyricContrast.ensureContrast(unsung, backdrop, 1.7, 0.45f),
    translation = LyricContrast.ensureContrast(translation, backdrop, 1.9, 0.7f),
  )
}
