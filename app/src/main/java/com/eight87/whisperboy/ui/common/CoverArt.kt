package com.eight87.whisperboy.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.eight87.whisperboy.R
import java.io.File

/**
 * Cover surface. Renders the book's on-disk cover via Coil when [coverPath] is non-null;
 * otherwise (and on Coil load failure) renders the M3E book-glyph placeholder in a
 * `surfaceContainerHigh` square.
 *
 * Uses Coil's [AsyncImage] (NOT [coil3.compose.SubcomposeAsyncImage]) — the latter creates
 * a fresh subcomposition per tile for the loading / error slot which is a significant
 * scrolling jank source in [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]. The
 * placeholder is a static [androidx.compose.ui.graphics.painter.Painter] (the M3E book
 * glyph in a tinted box) so the same surface paints during loading and on miss.
 *
 * Tile decode discipline (tonearmboy `8d8c1a4` "Balanced load speed"): we deliberately do not
 * pass `Size.ORIGINAL` here. Coil derives the request size from the Box constraints, which is
 * the right behaviour for grid tiles. A full-screen player surface (cover-art.md Phase F) is
 * the only place that should override the size.
 */
@Composable
fun CoverArt(
    coverPath: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (coverPath != null) {
            val placeholder = rememberVectorPainter(Icons.AutoMirrored.Filled.MenuBook)
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(coverPath))
                    .crossfade(80)
                    .build(),
                contentDescription = stringResource(R.string.library_cover_placeholder_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                placeholder = placeholder,
                error = placeholder,
                fallback = placeholder,
            )
        } else {
            CoverPlaceholder()
        }
    }
}

@Composable
private fun CoverPlaceholder() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.MenuBook,
        contentDescription = stringResource(R.string.library_cover_placeholder_cd),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxSize(0.45f),
    )
}
