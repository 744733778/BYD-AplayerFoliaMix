package remix.myplayer.ui.screen.setting.logic.play

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import remix.myplayer.R
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.service.playback.LoudnessScanManager
import remix.myplayer.service.playback.LoudnessScanStatus
import remix.myplayer.service.playback.loudnessScanManager
import remix.myplayer.ui.dialog.ItemsCallbackSingleChoice
import remix.myplayer.ui.dialog.NormalDialog
import remix.myplayer.ui.dialog.rememberDialogState
import remix.myplayer.ui.nav.LocalNavController
import remix.myplayer.ui.nav.RouteReplayGain
import remix.myplayer.ui.screen.setting.NormalPreference
import remix.myplayer.ui.screen.setting.SwitchPreference
import remix.myplayer.ui.widget.common.LineSlider
import remix.myplayer.ui.widget.common.TextPrimary
import remix.myplayer.ui.widget.common.TextSecondary
import remix.myplayer.ui.widget.common.defaultLineSliderProperties
import remix.myplayer.viewmodel.settingViewModel
import java.util.Locale

@Composable
fun ReplayGainLogic() {
  val settingVM = settingViewModel
  val settingState by settingVM.settingsState.collectAsStateWithLifecycle()
  val nav = LocalNavController.current
  val replayGainMode = settingState.play.replayGainMode
  val enabled = settingState.play.replayGainEnabled
  val summary = if (enabled) {
    stringResource(
      R.string.play_replay_gain_summary,
      stringResource(
        if (replayGainMode == SettingPrefs.REPLAY_GAIN_MODE_ALBUM) {
          R.string.play_replay_gain_mode_album
        } else {
          R.string.play_replay_gain_mode_track
        }
      ),
      stringResource(
        if (settingState.play.replayGainPeakProtection) {
          R.string.play_replay_gain_peak_protection
        } else {
          R.string.play_replay_gain_peak_protection_off
        }
      )
    )
  } else {
    stringResource(R.string.play_replay_gain_disabled)
  }

  NormalPreference(stringResource(R.string.play_replay_gain), summary) {
    nav.navigate(RouteReplayGain)
  }
}

@Composable
fun ReplayGainSettingsLogic() {
  val settingVM = settingViewModel
  val settingState by settingVM.settingsState.collectAsStateWithLifecycle()
  val replayGainMode = settingState.play.replayGainMode
  val enabled = settingState.play.replayGainEnabled
  val replayGainSelection = when {
    !enabled -> 0
    replayGainMode == SettingPrefs.REPLAY_GAIN_MODE_ALBUM -> 2
    else -> 1
  }
  val modeDialogState = rememberDialogState(false)

  NormalPreference(
    title = stringResource(R.string.play_replay_gain_mode),
    content = stringResource(
      when (replayGainSelection) {
        1 -> R.string.play_replay_gain_mode_track_tip
        2 -> R.string.play_replay_gain_mode_album_tip
        else -> R.string.play_replay_gain_disabled_tip
      }
    )
  ) {
    modeDialogState.show()
  }

  NormalDialog(
    dialogState = modeDialogState,
    titleRes = R.string.play_replay_gain_mode,
    itemRes = listOf(
      R.string.play_replay_gain_disabled,
      R.string.play_replay_gain_mode_track,
      R.string.play_replay_gain_mode_album
    ),
    positiveRes = null,
    negativeRes = null,
    itemsCallbackSingleChoice = ItemsCallbackSingleChoice(replayGainSelection) {
      when (it) {
        1 -> settingVM.setReplayGain(true, SettingPrefs.REPLAY_GAIN_MODE_TRACK)
        2 -> settingVM.setReplayGain(true, SettingPrefs.REPLAY_GAIN_MODE_ALBUM)
        else -> settingVM.setReplayGain(false, replayGainMode)
      }
    }
  )

  SwitchPreference(
    title = stringResource(R.string.play_replay_gain_peak_protection),
    content = stringResource(R.string.play_replay_gain_peak_protection_tip),
    checked = settingState.play.replayGainPeakProtection
  ) {
    settingVM.setReplayGainPeakProtection(it)
  }

  ReplayGainGainSlider(
    title = stringResource(R.string.play_replay_gain_preamp),
    content = stringResource(R.string.play_replay_gain_preamp_tip),
    gainDb = settingState.play.replayGainPreampDb,
    onGainChanged = settingVM::setReplayGainPreamp
  )

  ReplayGainGainSlider(
    title = stringResource(R.string.play_replay_gain_missing_gain),
    content = stringResource(R.string.play_replay_gain_missing_gain_tip),
    gainDb = settingState.play.replayGainMissingGainDb,
    onGainChanged = settingVM::setReplayGainMissingGain
  )

  LoudnessAutoAnalyzeLogic()

  LoudnessScanLogic()
}

@Composable
private fun ReplayGainGainSlider(
  title: String,
  content: String,
  gainDb: Float,
  onGainChanged: (Float) -> Unit,
) {
  var sliderGainDb by remember(gainDb) { mutableFloatStateOf(gainDb) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 10.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        TextPrimary(title, modifier = Modifier.padding(bottom = 4.dp), fontSize = 16.sp)
        TextSecondary(content, fontSize = 14.sp, maxLine = Int.MAX_VALUE)
      }
      TextPrimary(formatGainDb(sliderGainDb), fontSize = 16.sp)
    }

    LineSlider(
      modifier = Modifier
        .fillMaxWidth()
        .height(32.dp),
      value = sliderGainDb,
      onValueChange = { sliderGainDb = it },
      onValueChangeFinished = { onGainChanged(sliderGainDb) },
      valueRange = SettingPrefs.REPLAY_GAIN_GAIN_MIN_DB..SettingPrefs.REPLAY_GAIN_GAIN_MAX_DB,
      steps = ((SettingPrefs.REPLAY_GAIN_GAIN_MAX_DB - SettingPrefs.REPLAY_GAIN_GAIN_MIN_DB) /
        SettingPrefs.REPLAY_GAIN_GAIN_STEP_DB).toInt() - 1,
      properties = defaultLineSliderProperties.copy()
    )

    Row(modifier = Modifier.fillMaxWidth()) {
      TextSecondary(formatGainDb(SettingPrefs.REPLAY_GAIN_GAIN_MIN_DB), fontSize = 13.sp)
      Spacer(Modifier.weight(1f))
      TextSecondary("0 dB", fontSize = 13.sp)
      Spacer(Modifier.weight(1f))
      TextSecondary(formatGainDb(SettingPrefs.REPLAY_GAIN_GAIN_MAX_DB), fontSize = 13.sp)
    }
  }
}

/**
 * Direct master switch for loudness normalization, shown in Settings → Playback.
 * It is the same enable flag used by the ReplayGain pipeline (ReplayGain / auto-analysis).
 */
@Composable
fun ReplayGainMasterSwitchLogic() {
  val settingVM = settingViewModel
  val settingState by settingVM.settingsState.collectAsStateWithLifecycle()
  SwitchPreference(
    title = stringResource(R.string.play_loudness_normalize),
    content = stringResource(R.string.play_loudness_normalize_tip),
    checked = settingState.play.replayGainEnabled
  ) {
    settingVM.setReplayGain(it, settingState.play.replayGainMode)
  }
}

@Composable
fun LoudnessAutoAnalyzeLogic() {
  val settingVM = settingViewModel
  val settingState by settingVM.settingsState.collectAsStateWithLifecycle()
  SwitchPreference(
    title = stringResource(R.string.play_loudness_auto_analyze),
    content = stringResource(R.string.play_loudness_auto_analyze_tip),
    checked = settingState.play.loudnessAutoAnalyzeEnabled
  ) {
    settingVM.setLoudnessAutoAnalyze(it)
  }
}

@Composable
fun LoudnessScanLogic() {
  val scope = rememberCoroutineScope()
  val scanManager = remember { loudnessScanManager() }
  val scanState by scanManager.state.collectAsStateWithLifecycle()
  var analyzedCount by remember { mutableStateOf(0) }
  LaunchedEffect(scanState.status) {
    analyzedCount = scanManager.analyzedCount()
  }
  val scanning = scanState.status == LoudnessScanStatus.Scanning

  NormalPreference(
    title = stringResource(R.string.play_loudness_scan),
    content = if (scanning) {
      stringResource(R.string.play_loudness_scanning, scanState.processed, scanState.total)
    } else {
      stringResource(R.string.play_loudness_scan_tip)
    }
  ) {
    if (!scanning) scanManager.scanAll()
  }

  if (scanning) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      Spacer(Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        TextSecondary(
          stringResource(R.string.play_loudness_scanning, scanState.processed, scanState.total),
          fontSize = 14.sp
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { scanManager.cancel() }) {
          TextPrimary(stringResource(R.string.play_loudness_scan_cancel))
        }
      }
    }
  }

  TextSecondary(
    stringResource(R.string.play_loudness_analyzed, analyzedCount),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    fontSize = 13.sp
  )

  NormalPreference(
    title = stringResource(R.string.play_loudness_clear),
    content = stringResource(R.string.play_loudness_clear_tip)
  ) {
    scope.launch {
      scanManager.clearAll()
      analyzedCount = 0
    }
  }
}

private fun formatGainDb(gainDb: Float): String {
  return if (gainDb == 0f) "0 dB" else String.format(Locale.US, "%+.1f dB", gainDb)
}
