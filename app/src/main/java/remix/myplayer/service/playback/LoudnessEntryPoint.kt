package remix.myplayer.service.playback

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import remix.myplayer.App

/**
 * Hilt entry point exposing the on-device loudness singletons to non-Hilt call sites
 * (e.g. Compose preference screens).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LoudnessEntryPoint {
  fun loudnessScanManager(): LoudnessScanManager

  fun loudnessGainStore(): LoudnessGainStore
}

fun loudnessScanManager(): LoudnessScanManager =
  EntryPointAccessors.fromApplication(App.context, LoudnessEntryPoint::class.java)
    .loudnessScanManager()

fun loudnessGainStore(): LoudnessGainStore =
  EntryPointAccessors.fromApplication(App.context, LoudnessEntryPoint::class.java)
    .loudnessGainStore()
