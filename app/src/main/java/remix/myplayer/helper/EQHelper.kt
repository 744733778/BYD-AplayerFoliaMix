package remix.myplayer.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.widget.Toast
import dagger.hilt.android.EntryPointAccessors
import remix.myplayer.App
import remix.myplayer.R
import remix.myplayer.data.prefs.SettingPrefsEntryPoint
import remix.myplayer.data.prefs.delegate
import remix.myplayer.service.MusicServiceRemote
import remix.myplayer.util.Util.isIntentAvailable
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * created by Remix on 2019-05-06
 */
object EQHelper {

  /**
   * 均衡器预设：用常见频段锚点(Hz) + 相对 dB 描述，应用时会按设备实际频段中心频率
   * 映射到最近的频段，因此可适配不同设备/不同频段数量。每个预设都带 bass 低音增强值，
   * 保证切换预设时低音滑条也会同步（避免“低音增强”预设拉高后切其他预设不回落）。
   */
  data class EqPreset(
    val id: String,
    val nameRes: Int,
    val points: List<Pair<Int, Float>>,
    val bass: Int = 0
  )

  const val EQ_PRESET_CUSTOM = "custom"

  val EQ_PRESETS = listOf(
    EqPreset(
      "flat",
      R.string.eq_preset_flat,
      emptyList(),
      bass = 0
    ),
    EqPreset(
      "rock",
      R.string.eq_preset_rock,
      listOf(60 to 5f, 250 to 3f, 1000 to -1f, 4000 to 4f, 12000 to 5f),
      bass = 300
    ),
    EqPreset(
      "pop",
      R.string.eq_preset_pop,
      listOf(60 to -2f, 250 to 1f, 1000 to 3f, 4000 to 1f, 12000 to -1f),
      bass = 150
    ),
    EqPreset(
      "classical",
      R.string.eq_preset_classical,
      listOf(60 to 4f, 250 to 1f, 1000 to 0f, 4000 to 2f, 12000 to 3f),
      bass = 0
    ),
    EqPreset(
      "jazz",
      R.string.eq_preset_jazz,
      listOf(60 to 3f, 250 to 2f, 1000 to -1f, 4000 to 2f, 12000 to 3f),
      bass = 120
    ),
    EqPreset(
      "vocal",
      R.string.eq_preset_vocal,
      listOf(60 to -3f, 250 to 0f, 1000 to 4f, 4000 to 4f, 12000 to 1f),
      bass = 0
    ),
    EqPreset(
      "bass",
      R.string.eq_preset_bass,
      listOf(60 to 7f, 250 to 4f, 1000 to -1f, 4000 to -1f, 12000 to -2f),
      bass = 600
    ),
    EqPreset(
      "treble",
      R.string.eq_preset_treble,
      listOf(60 to -2f, 250 to -1f, 1000 to 1f, 4000 to 4f, 12000 to 6f),
      bass = 0
    )
  )

  /** 将预设按设备实际频段中心频率(Hz)映射到每段 dB 增益，并 clamp 到设备允许范围。 */
  private fun presetBandLevels(
    preset: EqPreset,
    centerFreqsHz: List<Int>,
    minLevel: Int,
    maxLevel: Int
  ): List<Int> {
    if (preset.points.isEmpty()) return List(centerFreqsHz.size) { 0 }
    return centerFreqsHz.map { f ->
      val nearest = preset.points.minByOrNull { (fa, _) -> abs(fa - f) } ?: (0 to 0f)
      (nearest.second * 100).roundToInt().coerceIn(minLevel, maxLevel)
    }
  }

  /** 应用某个预设：写入各频段增益与低音增强，并记录当前预设 id。 */
  fun applyEqPreset(preset: EqPreset) {
    val centerFreqsHz = (0 until bandNumber).map { getCenterFreq(it) / 1000 }
    val levels = presetBandLevels(preset, centerFreqsHz, minLevel.toInt(), maxLevel.toInt())
    levels.forEachIndexed { i, lvl -> setBandLevel(i, lvl) }
    if (isBassBoostEnabled) {
      bassBoostStrength = preset.bass
    }
    settingPrefs.eqPresetId = preset.id
  }

  fun getEqPresetId(): String = settingPrefs.eqPresetId

  fun setCustomPreset() {
    settingPrefs.eqPresetId = EQ_PRESET_CUSTOM
  }
  private val settingPrefs = EntryPointAccessors.fromApplication(
    App.context,
    SettingPrefsEntryPoint::class.java
  ).settingPrefs()

  private var equalizer: Equalizer? = null
  private var bassBoost: BassBoost? = null
  private var virtualizer: Virtualizer? = null

  private val bandLevels = ArrayList<Short>()

  var bandNumber: Short = 0
  var maxLevel: Short = 0
  var minLevel: Short = 0

  var enable = false

  //  var systemSessionOpen = false
//  var builtIdSessionOpen = false
  var builtEqualizerInit = false
  private var currentAudioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

  val isBassBoostEnabled: Boolean
    get() = enable && bassBoost?.strengthSupported == true

  var bassBoostStrength: Int
    get() = settingPrefs.bassBoostStrength
    set(strength) {
      settingPrefs.bassBoostStrength = strength
      if (isBassBoostEnabled) {
        tryRun({
          bassBoost?.setStrength(strength.toShort())
        }, {
          releaseBassBoost()
        })
      }
    }

//  val isVirtualizerEnabled: Boolean
//    get() = enable && virtualizer?.strengthSupported == true
//
//  var virtualizerStrength: Int
//    get() = SPUtil.getValue(App.getContext(), NAME, VIRTUALIZER_STRENGTH, 0)
//    set(strength) {
//      SPUtil.putValue(App.getContext(), NAME, VIRTUALIZER_STRENGTH, strength)
//      if (isVirtualizerEnabled) {
//        virtualizer?.setStrength(strength.toShort())
//      }
//    }

  fun init(context: Context, sessionId: Int, force: Boolean = false): Boolean {
    Timber.v("init, audioSessionId: $sessionId")

    if (!isValidAudioSessionId(sessionId)) {
      return false
    }

    //系统均衡器不可用 才初始化内置的
    if (isSystemEqualizerAvailable(context)) {
      return false
    }

    //是否启用音效设置
    enable = settingPrefs.enableEq
    // 不需要初始化
    if (!enable && !force) {
      builtEqualizerInit = false
      return builtEqualizerInit
    }

    Timber.v("init start")

    tryRun({
      releaseEqualizer()
      equalizer = Equalizer(0, sessionId)
      equalizer?.also { equalizer ->
        equalizer.enabled = enable

        //得到当前Equalizer引擎所支持的控制频率的标签数目。
        bandNumber = equalizer.numberOfBands

        //最小范围
        minLevel = equalizer.bandLevelRange[0]
        //最大范围
        maxLevel = equalizer.bandLevelRange[1]

        //得到当前Equalizer引擎所支持的控制频率的标签数目。
        bandNumber = equalizer.numberOfBands

        //得到之前存储的每个频率的db值
        bandLevels.clear()
        for (i in 0 until bandNumber) {
          var bandLevel by settingPrefs.sp.delegate("band$i", 0)
          bandLevels.add(bandLevel.toShort())
        }

        Timber.v("init finish")

        builtEqualizerInit = true
      }
    }, {
      equalizer = null
      builtEqualizerInit = false
      Timber.v("init failed")
    })

    return builtEqualizerInit
  }

  fun open(context: Context, audioSessionId: Int) {
    Timber.v("open, audioSessionId: $audioSessionId")

    if (!isValidAudioSessionId(audioSessionId)) {
      return
    }

    if (isSystemEqualizerAvailable(context)) {
      openSystemAudioEffectSession(context, audioSessionId)
    } else {
      Timber.v("open built-in")
      //是否启用音效设置
      enable = settingPrefs.enableEq
      if (!enable) {
        return
      }
      tryRun({
        //EQ
        equalizer?.release()

        equalizer = Equalizer(0, audioSessionId)
        equalizer?.also { equalizer ->
          equalizer.enabled = enable

          //得到之前存储的每个频率的db值
          for (i in 0 until bandNumber) {
            if (enable) {
              equalizer.setBandLevel(i.toShort(), bandLevels[i])
            }
          }
        }
      }, {
        releaseEqualizer()
      })

      tryRun({
        //低音增强
        bassBoost?.release()
        bassBoost = BassBoost(0, audioSessionId)
        bassBoost?.also { bassBoost ->
          bassBoost.enabled = enable && bassBoost.strengthSupported
          if (bassBoost.enabled) {
            bassBoost.setStrength(bassBoostStrength.toShort())
          }
        }
      }, {
        releaseBassBoost()
      })


      //环绕声
//      virtualizer?.enabled = false
//      virtualizer?.canVirtualize()
//
//      virtualizer = Virtualizer(0, audioSessionId)
//      virtualizer?.also { virtualizer ->
//        try {
//          virtualizer.enabled = enable && virtualizer.strengthSupported
//          if (virtualizer.enabled) {
//            virtualizer.setStrength(virtualizerStrength.toShort())
//          }
//        } catch (e: Exception) {
//          Timber.w(e)
//        }
//      }

      Timber.v("min: $minLevel max: $maxLevel bandNumber: $bandNumber")
    }
  }

  fun close(context: Context, audioSessionId: Int) {
    Timber.v("close")

    if (!isValidAudioSessionId(audioSessionId)) {
      return
    }

    tryRun({
      releaseEqualizer()
    }, {

    })

    tryRun({
      releaseBassBoost()
    }, {

    })

    closeSystemAudioEffectSession(context, audioSessionId)
  }

  fun updateAudioSession(context: Context, audioSessionId: Int) {
    if (!isValidAudioSessionId(audioSessionId)) {
      return
    }
    if (currentAudioSessionId == audioSessionId) {
      return
    }

    if (isValidAudioSessionId(currentAudioSessionId)) {
      close(context, currentAudioSessionId)
    }

    Timber.v("updateAudioSession, from: $currentAudioSessionId to: $audioSessionId")
    init(context, audioSessionId)
    open(context, audioSessionId)
    currentAudioSessionId = audioSessionId
  }

  fun releaseCurrentAudioSession(context: Context) {
    close(context, currentAudioSessionId)
    currentAudioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
  }


  fun getCenterFreq(band: Int): Int {
    return equalizer?.getCenterFreq(band.toShort()) ?: 0
  }

  fun getBandLevel(band: Int): Short {
    return bandLevels[band]
  }

  fun setBandLevel(band: Int, level: Int) {
    tryRun({
      equalizer?.setBandLevel(band.toShort(), level.toShort())
    }, {
      releaseEqualizer()
    })
    bandLevels[band] = level.toShort()
    var bandLevel by settingPrefs.sp.delegate("band${band}", 0)
    bandLevel = level
  }

  fun updateEnable(enable: Boolean) {
    this.enable = enable
    settingPrefs.enableEq = enable

    tryRun({
      equalizer?.enabled = enable
      for (band in 0 until bandNumber) {
        val bandLevel = if (enable) getBandLevel(band) else 0
        equalizer?.setBandLevel(band.toShort(), bandLevel)
      }
    }, {
      releaseEqualizer()
    })

    tryRun({
      bassBoost?.enabled = isBassBoostEnabled
      bassBoost?.setStrength(if (isBassBoostEnabled) bassBoostStrength.toShort() else 0)
    }, {
      releaseBassBoost()
    })

//    try {
//      virtualizer?.enabled = isVirtualizerEnabled
//      virtualizer?.setStrength(if (isVirtualizerEnabled) virtualizerStrength.toShort() else 0)
//    } catch (e: Exception) {
//      Timber.w(e)
//    }

  }

  private fun releaseBassBoost() {
    bassBoost?.release()
    bassBoost = null
  }

  private fun releaseEqualizer() {
    equalizer?.release()
    equalizer = null
  }

  fun reset() {
    for (i in 0 until bandNumber) {
      setBandLevel(i, 0)
    }

    bassBoostStrength = 0
//    virtualizerStrength = 0
  }

  private fun openSystemAudioEffectSession(context: Context, audioSessionId: Int) {
    val audioEffectsIntent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
    audioEffectsIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
    audioEffectsIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
    context.sendBroadcast(audioEffectsIntent)
  }

  private fun closeSystemAudioEffectSession(context: Context, audioSessionId: Int) {
    val audioEffectsIntent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
    audioEffectsIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
    audioEffectsIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
    context.sendBroadcast(audioEffectsIntent)
  }

  /**
   * 启动均衡器
   */
  @JvmStatic
  fun startEqualizer(activity: Activity, navigateToEq: () -> Unit) {
    val sessionId = MusicServiceRemote.getAudioSessionId() ?: return
    if (!isValidAudioSessionId(sessionId)) {
      Toast.makeText(
        activity,
        activity.resources.getString(R.string.no_audio_ID),
        Toast.LENGTH_LONG
      ).show()
      return
    }
    val audioEffectIntent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
    audioEffectIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
    audioEffectIntent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    if (isSystemEqualizerAvailable(activity)) {
      activity.startActivityForResult(audioEffectIntent, REQUEST_EQ)
    } else {
      navigateToEq()
    }
  }

  private fun isSystemEqualizerAvailable(context: Context): Boolean {
    return isIntentAvailable(context, Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL))
  }

  private fun isValidAudioSessionId(sessionId: Int): Boolean {
    return sessionId != AudioEffect.ERROR_BAD_VALUE &&
      sessionId != AudioManager.AUDIO_SESSION_ID_GENERATE
  }

  private fun tryRun(block: () -> Unit, error: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      Timber.w(e)
      error()
    }
  }


  const val REQUEST_EQ = 0


}
