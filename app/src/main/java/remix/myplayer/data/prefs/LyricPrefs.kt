package remix.myplayer.data.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import remix.myplayer.data.model.misc.LyricOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通用的歌词配置，桌面歌词的配置在DesktopLyricPrefs
 * @see remix.myplayer.data.prefs.DesktopLyricPrefs
 */
@Singleton
class LyricPrefs @Inject constructor(
  @ApplicationContext context: Context
) : AbstractPref(context, "Lyric") {

  // 默认的歌词搜索顺序
  val generalLyricOrderList: List<LyricOrder>
    get() = try {
      Json.decodeFromString<List<LyricOrder>>(generalLyricOrder)
    } catch (ignore: Exception) {
      defaultLyricOrderList
    }

  var generalLyricOrder by PrefsDelegate(
    sp,
    KEY_GENERAL_LYRIC_ORDER,
    Json.encodeToString(defaultLyricOrderList)
  )

  var tipShown by PrefsDelegate(sp, KEY_LYRIC_LOCAL_TIP_SHOWN, false)

  var desktopLyricEnabled by PrefsDelegate(sp, KEY_DESKTOP_LYRIC_ENABLED, false)
  var statusBarLyricEnabled by PrefsDelegate(sp, KEY_STATUS_BAR_LYRIC_ENABLED, false)

  var fontScale by PrefsDelegate(sp, KEY_LYRIC_FONT_SCALE, 1.0f)

  var translationEnabled by PrefsDelegate(sp, KEY_TRANSLATION_ENABLED, true)

  /**
   * 全屏歌词特效模式（[remix.myplayer.ui.widget.lyric.folia.EffectMode] 的 name）。
   * 用 name 而非下标持久化，避免模式增删/重排后下标错位。
   * 退出时用的哪个特效，下次打开沿用。
   */
  var lyricEffectName by PrefsDelegate(sp, KEY_LYRIC_EFFECT_NAME, "CLASSIC")

  /**
   * 全屏歌词页的动态背景模式（[remix.myplayer.ui.widget.lyric.folia.LyricBackgroundMode] 的 name）。
   * 同样用 name 持久化，退出时用的哪个背景，下次打开沿用。
   */
  var lyricBackgroundName by PrefsDelegate(sp, KEY_LYRIC_BACKGROUND_NAME, "COVER_BLUR")

  /**
   * 上次退出时所在的「播放相关界面」，用于二次打开时恢复：
   * "none" = 不在播放界面（库/设置等）；"regular" = 常规播放页浮窗；"lyric" = 全屏歌词特效界面。
   */
  var lastPlayingSurface by PrefsDelegate(sp, KEY_LAST_PLAYING_SURFACE, "none")

  /**
   * 清除所有针对单首歌曲的配置
   */
  fun clearUserSave() {
    val keys = sp.all.keys
    val toRemove = keys.filter {
      it.startsWith(KEY_SONG_PREFIX) || it.startsWith(KEY_OFFSET_PREFIX)
    }
    if (toRemove.isEmpty()) return
    sp.edit(commit = true) {
      toRemove.forEach { remove(it) }
    }
  }

  companion object {

    private val defaultLyricOrderList = listOf(
      LyricOrder.Embedded,
      LyricOrder.Local,
      LyricOrder.Kugou,
      LyricOrder.Netease,
      LyricOrder.Qq,
      LyricOrder.Ignore
    )

    // StatusBar
    const val KEY_STATUS_BAR_LYRIC_ENABLED: String = "status_bar_lyric_enabled"

    // Desktop
    const val KEY_DESKTOP_LYRIC_ENABLED = "desktop_lyric_enabled"
    const val KEY_DESKTOP_LYRIC_LOCKED = "desktop_lyric_locked"

    // LyricScreen
    const val KEY_LYRIC_FONT_SCALE = "lyric_font_scale"
    const val KEY_LYRIC_LOCAL_TIP_SHOWN = "lyric_local_tip_shown"
    const val KEY_GENERAL_LYRIC_ORDER = "general_lyric_order"

    const val KEY_TRANSLATION_ENABLED = "translation_enabled"

    const val KEY_LYRIC_EFFECT_NAME = "lyric_effect_name"
    const val KEY_LYRIC_BACKGROUND_NAME = "lyric_background_name"
    const val KEY_LAST_PLAYING_SURFACE = "last_playing_surface"

    /** [lastPlayingSurface] 取值：不在播放相关界面（库 / 设置等）。 */
    const val SURFACE_NONE = "none"

    /** [lastPlayingSurface] 取值：常规播放页（播放浮窗）。 */
    const val SURFACE_REGULAR = "regular"

    /** [lastPlayingSurface] 取值：全屏歌词特效界面。 */
    const val SURFACE_LYRIC = "lyric"

    const val KEY_SONG_PREFIX = "lyric_song_"
    const val KEY_OFFSET_PREFIX = "lyric_offset_"
  }
}