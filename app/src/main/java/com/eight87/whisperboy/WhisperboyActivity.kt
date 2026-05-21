package com.eight87.whisperboy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.eight87.whisperboy.theme.AlbumArtBackground
import com.eight87.whisperboy.theme.LocalAlbumArtBackgroundActive
import com.eight87.whisperboy.theme.LocalPlayingCoverUri
import com.eight87.whisperboy.theme.WhisperboyTheme

class WhisperboyActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    val graph = (application as WhisperboyApplication).graph
    // One-shot migrations (album-art visuals V2). Fire-and-forget on
    // the lifecycle scope — DataStore writes are async, but the V2
    // marker guards re-runs so the next process start is a no-op.
    lifecycleScope.launch { graph.themeSettings.runMigrations() }
    setContent {
      WhisperboyTheme(
        themeSettings = graph.themeSettings,
        nowPlayingState = graph.nowPlayingState,
      ) {
        // Activity-root cover wallpaper layer. When the album-art
        // background mode is active, paint the playing book's blurred
        // cover behind a transparent root Surface so chrome surfaces
        // (now translucent — see Theme.kt) reveal it. When inactive,
        // fall back to the opaque background colour.
        val backgroundActive = LocalAlbumArtBackgroundActive.current
        val coverUri = LocalPlayingCoverUri.current
        Box(modifier = Modifier.fillMaxSize()) {
          if (backgroundActive) {
            AlbumArtBackground(
              coverUri = coverUri,
              modifier = Modifier.fillMaxSize(),
            )
          }
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (backgroundActive) Color.Transparent else MaterialTheme.colorScheme.background,
          ) { WhisperboyApp() }
        }
      }
    }
  }
}
