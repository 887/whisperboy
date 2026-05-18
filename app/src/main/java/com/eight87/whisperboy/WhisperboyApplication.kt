package com.eight87.whisperboy

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers

class WhisperboyApplication : Application(), SingletonImageLoader.Factory {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    /**
     * Custom Coil 3 [ImageLoader]. The Coil default loader is conservative on memory cache
     * (~15% of available app memory) and uses a single shared decoder dispatcher; with N
     * cover tiles in the library grid that's a known scrolling-jank source.
     *
     * Knobs:
     *  - **Memory cache 30%** — covers stay hot across scrolls, no re-decode on every fling.
     *  - **Disk cache at `cacheDir/image_cache`** — explicit so Coil never tries to put it on
     *    storage that needs an SD card mount (some Android devices).
     *  - **Fetcher / decoder dispatchers on [Dispatchers.IO]** with up to 8 parallel slots
     *    via `limitedParallelism(8)` — work-stealing pool for cover decode/I/O so a slow disk
     *    read doesn't bottleneck the rest of the grid.
     *  - **80ms crossfade** matching [com.eight87.whisperboy.ui.common.CoverArt] (Coil's
     *    default is no crossfade; we want a short one so cache hits don't pop).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val ioPool = Dispatchers.IO.limitedParallelism(8)
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(128L * 1024L * 1024L)
                    .build()
            }
            .fetcherCoroutineContext(ioPool)
            .decoderCoroutineContext(ioPool)
            .crossfade(80)
            .build()
    }
}
