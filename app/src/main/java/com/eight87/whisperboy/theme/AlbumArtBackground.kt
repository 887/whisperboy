package com.eight87.whisperboy.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Fullscreen blurred-cover background — the Harmony-glass look,
 * ported wholesale from tonearmboy.
 *
 * Blur via a custom [android.graphics.RenderEffect] with
 * [android.graphics.Shader.TileMode.CLAMP] — sampling past the edge
 * reuses the nearest cover pixel instead of going to transparent /
 * black (`Modifier.blur()` uses `TileMode.DECAL` and produced visible
 * black bars on portrait-cropped covers).
 *
 * The image is scaled 1.6x in the graphicsLayer transform so any
 * letterbox / pillarbox baked into the source bitmap (YouTube
 * thumbnails served by Piped come with 16:9 letterbox bars) falls
 * outside the parent's clipped area before the blur runs.
 *
 * Requires API 31+; on older devices we render the cover unblurred
 * under the scrim, which still works (the scrim itself keeps text
 * legible — the blur is aesthetic, not load-bearing).
 */
@Composable
fun AlbumArtBackground(
  coverUri: String?,
  modifier: Modifier = Modifier,
) {
  if (coverUri.isNullOrBlank()) return
  val context = LocalContext.current
  val density = LocalDensity.current
  val request = remember(coverUri) {
    ImageRequest.Builder(context)
      .data(coverUri)
      .memoryCacheKey("bg-$coverUri")
      .diskCacheKey("bg-$coverUri")
      .build()
  }
  val blurRadiusPx = with(density) { 56.dp.toPx() }
  val composeBlurEffect = remember(blurRadiusPx) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      android.graphics.RenderEffect.createBlurEffect(
        blurRadiusPx,
        blurRadiusPx,
        android.graphics.Shader.TileMode.CLAMP,
      ).asComposeRenderEffect()
    } else null
  }
  val surface = androidx.compose.material3.MaterialTheme.colorScheme.background
  Box(modifier = modifier) {
    AsyncImage(
      model = request,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          scaleX = 1.6f
          scaleY = 1.6f
          if (composeBlurEffect != null) renderEffect = composeBlurEffect
        },
    )
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(surface.copy(alpha = 0.2f)),
    )
  }
}
