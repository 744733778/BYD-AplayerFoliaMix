package remix.myplayer.ui.theme

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import remix.myplayer.data.prefs.ThemePrefs
import remix.myplayer.data.prefs.ThemePrefs.Companion.FOLLOW_SYSTEM
import javax.inject.Inject

val LocalThemeController = compositionLocalOf<ThemeController> {
  error("no controller provide")
}

interface ThemeController {

  val appTheme: AppTheme

  var dark: String

  var black: Boolean

  /** 主色调是否由当前播放歌曲的封面自动生成 */
  val primaryFromCover: Boolean

  /** 强调色是否由当前播放歌曲的封面自动生成 */
  val secondaryFromCover: Boolean

  fun onSystemThemeChanged(uiMode: Int)

  fun setColoredNaviBar(colored: Boolean)

  fun setPrimary(color: Color)

  fun setSecondary(color: Color)

  /**
   * 开关「主色调取自封面」。关闭时立即回落到持久化的手动主色调。
   */
  fun setPrimaryFromCover(enabled: Boolean)

  /**
   * 开关「强调色取自封面」。关闭时立即回落到持久化的手动强调色。
   */
  fun setSecondaryFromCover(enabled: Boolean)

  /**
   * 由封面取色管线写入运行时主色调，不会覆盖用户手动设定并持久化的主色调。
   */
  fun applyPrimaryFromCover(color: Color)

  /**
   * 由封面取色管线写入运行时强调色，不会覆盖用户手动设定并持久化的强调色。
   */
  fun applySecondaryFromCover(color: Color)
}

class ThemeControllerImpl @Inject constructor(private val storage: ThemePrefs) : ThemeController {

  private val _currentTheme: MutableState<AppTheme> = mutableStateOf(
    AppTheme(
      primary = Color(storage.primaryColor),
      secondary = Color(storage.secondaryColor),
      theme = storage.resolveTheme(storage.darkTheme, storage.blackTheme),
      coloredNaviBar = storage.coloredNaviBar
    )
  )

  override val appTheme: AppTheme
    get() = _currentTheme.value

  override var dark: String = FOLLOW_SYSTEM
    get() = storage.darkTheme
    set(value) {
      if (field == value) {
        return
      }
      field = value
      storage.darkTheme = value
      _currentTheme.value = _currentTheme.value.copy(
        theme = storage.resolveTheme(value, storage.blackTheme)
      )
    }

  override var black: Boolean = false
    get() = storage.blackTheme
    set(value) {
      if (field == value) {
        return
      }
      field = value
      storage.blackTheme = value
      _currentTheme.value = _currentTheme.value.copy(
        theme = storage.resolveTheme(storage.darkTheme, value)
      )
    }

  override fun onSystemThemeChanged(uiMode: Int) {
    if (storage.darkTheme != FOLLOW_SYSTEM) {
      return
    }
    val resolved = storage.resolveTheme(storage.darkTheme, storage.blackTheme, uiMode)
    if (_currentTheme.value.theme == resolved) {
      return
    }
    _currentTheme.value = _currentTheme.value.copy(theme = resolved)
  }

  override fun setColoredNaviBar(colored: Boolean) {
    storage.coloredNaviBar = colored
    _currentTheme.value = _currentTheme.value.copy(coloredNaviBar = colored)
  }

  override fun setPrimary(color: Color) {
    storage.primaryColor = color.toArgb()
    // 手动调色优先级最高：同时关闭「主色调取自封面」
    storage.primaryColorFromCover = false
    _currentTheme.value = _currentTheme.value.copy(primary = color)
  }

  override fun setSecondary(color: Color) {
    storage.secondaryColor = color.toArgb()
    storage.secondaryColorFromCover = false
    _currentTheme.value = _currentTheme.value.copy(secondary = color)
  }

  override val primaryFromCover: Boolean
    get() = storage.primaryColorFromCover

  override val secondaryFromCover: Boolean
    get() = storage.secondaryColorFromCover

  override fun setPrimaryFromCover(enabled: Boolean) {
    if (storage.primaryColorFromCover == enabled) {
      return
    }
    storage.primaryColorFromCover = enabled
    if (!enabled) {
      // 关闭时回落到用户手动设置并持久化的主色调
      _currentTheme.value = _currentTheme.value.copy(primary = Color(storage.primaryColor))
    }
  }

  override fun setSecondaryFromCover(enabled: Boolean) {
    if (storage.secondaryColorFromCover == enabled) {
      return
    }
    storage.secondaryColorFromCover = enabled
    if (!enabled) {
      _currentTheme.value = _currentTheme.value.copy(secondary = Color(storage.secondaryColor))
    }
  }

  override fun applyPrimaryFromCover(color: Color) {
    if (!storage.primaryColorFromCover) {
      return
    }
    if (_currentTheme.value.primary == color) {
      return
    }
    _currentTheme.value = _currentTheme.value.copy(primary = color)
  }

  override fun applySecondaryFromCover(color: Color) {
    if (!storage.secondaryColorFromCover) {
      return
    }
    if (_currentTheme.value.secondary == color) {
      return
    }
    _currentTheme.value = _currentTheme.value.copy(secondary = color)
  }

}
