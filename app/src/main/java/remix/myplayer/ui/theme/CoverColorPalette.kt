package remix.myplayer.ui.theme

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.palette.graphics.Palette
import kotlin.math.abs

/**
 * 从封面图中提取主色调与强调色。
 *
 * 主色调优先取 Vibrant 系列，退化顺序为 Muted → DarkMuted → LightMuted → 面积最大的色块；
 * 强调色从封面的其它色块里挑一个与主色调色相差异较大的（>= [MIN_HUE_DISTANCE]），
 * 没有合适色块时由主色调派生一个协调的邻近色。
 */
object CoverColorPalette {

  private const val MIN_HUE_DISTANCE = 20f

  data class Colors(
    val primary: ComposeColor,
    val secondary: ComposeColor
  )

  fun extract(bitmap: Bitmap): Colors? {
    val palette = runCatching { Palette.from(bitmap).generate() }.getOrNull() ?: return null
    val swatches = palette.swatches
    if (swatches.isEmpty()) {
      return null
    }

    val primarySwatch = pickPrimary(palette, swatches)
    val primary = adjust(primarySwatch.rgb)
    val secondary = pickSecondary(palette, swatches, primarySwatch.rgb, primary)
    return Colors(ComposeColor(primary), ComposeColor(secondary))
  }

  private fun pickPrimary(palette: Palette, swatches: List<Palette.Swatch>): Palette.Swatch {
    palette.vibrantSwatch?.let { return it }
    palette.darkVibrantSwatch?.let { return it }
    palette.mutedSwatch?.let { return it }
    palette.darkMutedSwatch?.let { return it }
    palette.lightMutedSwatch?.let { return it }
    palette.lightVibrantSwatch?.let { return it }
    return swatches.maxByOrNull { it.population } ?: swatches.first()
  }

  private fun pickSecondary(
    palette: Palette,
    swatches: List<Palette.Swatch>,
    primaryRgb: Int,
    primary: Int
  ): Int {
    val primaryHue = hueOf(primary)
    val candidates = listOfNotNull(
      palette.mutedSwatch,
      palette.darkVibrantSwatch,
      palette.lightVibrantSwatch,
      palette.darkMutedSwatch,
      palette.lightMutedSwatch,
      palette.vibrantSwatch
    ).filter { it.rgb != primaryRgb }

    // 挑色相差异足够大、且面积较大的色块作为强调色
    val best = candidates
      .filter { hueDistance(hueOf(it.rgb), primaryHue) >= MIN_HUE_DISTANCE }
      .maxByOrNull { it.population }

    if (best != null) {
      return adjust(best.rgb)
    }
    // 封面是单色系：由主色调派生一个邻近色作为强调色
    return deriveNeighbor(primary)
  }

  /**
   * 主色调过白/过黑时做一次明度修正，避免界面文字或图标看不清。
   */
  private fun adjust(color: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    if (hsv[2] > 0.96f) {
      hsv[2] = 0.86f
    }
    if (hsv[2] < 0.06f) {
      hsv[2] = 0.24f
    }
    return Color.HSVToColor(hsv)
  }

  private fun deriveNeighbor(primary: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(primary, hsv)
    hsv[0] = (hsv[0] + 30f) % 360f
    hsv[1] = (hsv[1] * 0.9f).coerceIn(0.25f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.3f, 0.85f)
    return Color.HSVToColor(hsv)
  }

  private fun hueOf(color: Int): Float {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    return hsv[0]
  }

  private fun hueDistance(a: Float, b: Float): Float {
    val diff = abs(a - b)
    return if (diff > 180f) 360f - diff else diff
  }
}
