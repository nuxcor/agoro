package com.agoro.tv

import android.app.Application
import android.content.ComponentCallbacks2
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import com.agoro.tv.data.ContentRepository
import com.agoro.tv.data.StorageUsage
import com.agoro.tv.data.sentence
import com.agoro.tv.player.AudioOutputPolicy

class NuxTvApp : Application(), SingletonImageLoader.Factory {
    val repository: ContentRepository by lazy { ContentRepository(this) }

    /**
     * Coil, with a cache ceiling this box can afford.
     *
     * There was no factory here at all, so artwork ran on Coil's default: a
     * share of free space capped at 250MB. On a 2GB Chromecast that is a
     * quarter of a gigabyte of posters competing with the recordings and with
     * the room an update needs to install — and a box reporting 688MB against
     * this app could not install a 7MB APK.
     *
     * Named directory as well as size, because the cleanup in Settings has to
     * know where to look; a default path it had to guess would silently stop
     * matching the day Coil changed it.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(StorageUsage.IMAGE_CACHE_DIR))
                    .maxSizeBytes(StorageUsage.IMAGE_CACHE_MAX_BYTES)
                    .build()
            }
            .build()

    /**
     * Gives the idle player back when the system asks for memory.
     *
     * There was no handler here at all, so the one thing in this process that
     * is pure spare — the pooled player kept warm for the next zap — was held
     * through every warning the system gave. On a 2 GB Chromecast that is the
     * difference between an app that yields and one the low-memory killer has
     * to make room around, and it takes that room out of whatever else is
     * running.
     *
     * Only the player, and only from [ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW]
     * up. Artwork is deliberately not touched here: Coil registers its own
     * [ComponentCallbacks2] and already does the graduated version of this
     * (halve the memory cache from RUNNING_LOW, clear it from
     * TRIM_MEMORY_BACKGROUND), so a clear() of our own would only replace a
     * tuned policy with a blunter one. It would also fire on every HOME press,
     * since TRIM_MEMORY_UI_HIDDEN is numerically ABOVE RUNNING_CRITICAL and
     * carries no memory pressure at all — every poster on Home re-decoded on
     * the way back in, on the box least able to afford it.
     *
     * What this does NOT free is the honest part: the live buffer, which is
     * sized in seconds rather than bytes, and the catalogue. Both are much
     * larger than a spare player and neither is safely droppable from under a
     * running stream. If the footprint still has to come down, those are the
     * numbers to change, not this callback.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.agoro.tv.player.PlayerPool.releaseIdle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Whether this TV really takes the Dolby it advertises, settled
        // before the first film asks; see AudioOutputPolicy.probe. Off the
        // main thread: two AudioTracks are milliseconds, but AudioFlinger is
        // a binder call away and start-up is the wrong place to wait on one.
        Thread({ AudioOutputPolicy.probe(this) }, "audio-probe").start()
        // How the last run ended, if it ended badly. Logged on the way in
        // because a process that is killed never gets to say anything on the
        // way out, and "it sometimes freezes and closes" is unactionable
        // without it. See LastExit; nothing leaves the box.
        com.agoro.tv.data.ExitReasons.lastAbnormal(this)?.let {
            android.util.Log.w("Agoro", "Previous run ended badly: ${it.sentence()}" +
                (it.detail?.let { d -> " ($d)" } ?: ""))
        }
    }
}
