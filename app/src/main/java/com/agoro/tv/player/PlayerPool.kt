package com.agoro.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * How forgiving a player is asked to be, and at what cost.
 *
 * ExoPlayer is the only engine now. What used to be "try the other player"
 * when a stream wouldn't decode is this instead: the same engine opened a
 * second way, with the demuxer told to expect a mess and the decoders chosen
 * for breadth rather than speed. It is a real second chance — libVLC's value
 * here was never its UI, it was that its demuxer forgave things ExoPlayer's
 * default configuration does not — and unlike a second engine it keeps track
 * selection, HDR reporting, tunnelling and the media session intact.
 *
 * Not the default, because both halves cost something on TV silicon: access
 * unit detection is per-sample work, and a software decoder will not carry 4K
 * HEVC at all. So streams open [FAST] and only a failure moves them.
 */
enum class DecodeProfile {
    /** Hardware decoders, and a TS reader that assumes the mux is sane. */
    FAST,

    /**
     * Software decoders preferred, and a TS reader told to work it out from
     * the bitstream. The recovery rung, and the reason there is no VLC.
     */
    TOLERANT,
}

/**
 * Transport-stream reader flags, per [DecodeProfile].
 *
 * [DecodeProfile.FAST] takes only the free one. `ALLOW_NON_IDR_KEYFRAMES` lets
 * a live join start on the first recovery point rather than waiting for a true
 * IDR, which on a provider that sends them sparsely is the difference between
 * a channel that tunes in a second and one that sits black for ten.
 *
 * [DecodeProfile.TOLERANT] adds the two that cost or risk something:
 *
 *  - `DETECT_ACCESS_UNITS` makes the H.264 reader find frame boundaries in the
 *    bitstream instead of trusting the container's markers. It is what plays a
 *    mux with no access unit delimiters, and it is per-sample work the fast
 *    path should not be paying on every channel to rescue the few.
 *  - `ENABLE_HDMV_DTS_AUDIO_STREAMS` reads TS stream type 0x82 as DTS audio.
 *    That type is SCTE-35 splice info in ordinary broadcast content, so this
 *    is a genuine trade and not a free win — worth making when the stream has
 *    already failed, never before.
 */
@OptIn(UnstableApi::class)
private fun tsFlagsFor(profile: DecodeProfile): Int {
    val base = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
    return if (profile == DecodeProfile.FAST) {
        base
    } else {
        base or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
    }
}

/**
 * Routes HLS to a factory that has been told about transport streams, and
 * everything else to the stock one.
 *
 * [DefaultMediaSourceFactory] builds its HLS source reflectively and exposes
 * no hook into it, so the TS payload flags set on an [DefaultExtractorsFactory]
 * reach progressive `.ts` playback and nothing else. Nearly every HLS stream an
 * IPTV provider serves is TS segments, so without this the flags miss the half
 * of the catalogue they were set for.
 */
@OptIn(UnstableApi::class)
private class IptvMediaSourceFactory(
    dataSourceFactory: DataSource.Factory,
    profile: DecodeProfile,
) : MediaSource.Factory {

    private val progressive = DefaultMediaSourceFactory(
        dataSourceFactory,
        DefaultExtractorsFactory().setTsExtractorFlags(tsFlagsFor(profile)),
    )

    private val hls = HlsMediaSource.Factory(dataSourceFactory)
        .setExtractorFactory(
            DefaultHlsExtractorFactory(
                tsFlagsFor(profile),
                // media3's own default, restated because the two-argument
                // constructor is the only way to pass the flags and leaving
                // this to guesswork would be how it silently flips one day.
                /* exposeCea608WhenMissingDeclarations = */ true,
            )
        )

    override fun getSupportedTypes(): IntArray = progressive.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val local = mediaItem.localConfiguration
        val type = Util.inferContentTypeForUriAndMimeType(
            local?.uri ?: Uri.EMPTY,
            local?.mimeType,
        )
        return if (type == C.CONTENT_TYPE_HLS) {
            hls.createMediaSource(mediaItem)
        } else {
            progressive.createMediaSource(mediaItem)
        }
    }

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        progressive.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        progressive.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
        return this
    }

    // The rest of MediaSource.Factory has default implementations that return
    // the receiver and forward nothing. ExoPlayer only ever calls
    // createMediaSource, so today none of these is reached — but
    // DefaultMediaSourceFactory applies every one of them to the delegates it
    // builds, including its HLS one, and the bare factory here would silently
    // miss whatever it was told. They agree on the defaults at 1.8.0; the way
    // that stops being true is a media3 upgrade changing one of them, which is
    // the worst possible moment to discover the delegate never listened.
    override fun setSubtitleParserFactory(
        subtitleParserFactory: SubtitleParser.Factory,
    ): MediaSource.Factory {
        progressive.setSubtitleParserFactory(subtitleParserFactory)
        hls.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(
        parseSubtitlesDuringExtraction: Boolean,
    ): MediaSource.Factory {
        progressive.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        hls.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int,
    ): MediaSource.Factory {
        progressive.experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies
        )
        hls.experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies
        )
        return this
    }

    override fun setCmcdConfigurationFactory(
        cmcdConfigurationFactory: CmcdConfiguration.Factory,
    ): MediaSource.Factory {
        progressive.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        hls.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        return this
    }
}

/**
 * How much to buffer, and how long to wait before resuming after a stall.
 *
 * Two shapes, because live and a film are not the same problem and one set of
 * numbers had been serving both — the live one, since that is what they were
 * measured against.
 *
 * LIVE is deliberately SHALLOW, and the reason is the socket rather than the
 * buffer. ExoPlayer loads until the buffer reaches maxBufferMs and then stops
 * — `ProgressiveMediaPeriod`'s loadable parks on a condition variable between
 * reads, `loadCondition.block()` sitting immediately before every
 * `extractor.read` — and it does not start again until the buffer has drained
 * to minBufferMs. At 25s/60s that left a raw `.ts` connection open and
 * UNDRAINED for thirty-five seconds at a stretch, again and again, on a panel
 * that serves faster than real time. A re-streamer with a fixed send buffer is
 * entitled to drop a client that has stopped collecting, and the read that
 * follows the silence gets eight seconds (see the data source below) before it
 * fails into the recovery ladder. 20s to 24s keeps the working cushion and
 * shortens the unread window to about four seconds of playback, which no
 * reasonable server times out on. What it gives up is the deep end of the
 * buffer, which resumption at 2.5s was never reaching anyway.
 *
 * That the window WAS the problem is a theory about the provider's server and
 * is not yet measured; [TransferGaps] and the listener in [build] are what
 * measure it. The numbers here are worth keeping either way, because the deep
 * end also cost memory this box does not have.
 *
 * VOD is where that reasoning stops being true. A film is served at roughly
 * real time, so the SAME six seconds costs six seconds of frozen picture on
 * every hiccup — three times the price for a cushion that buys less, because a
 * film has no live edge to fall off and re-stalling is only another short
 * wait. It resumes on two seconds instead.
 *
 * VOD keeps its window, and that is a decision rather than an oversight: 20s
 * to 50s is thirty seconds unread, the same shape as live had. A film is
 * fetched from something that serves byte ranges rather than pushed by
 * something re-streaming, so it has far less reason to mind — but if films are
 * still breaking after this, that is the next number to try.
 *
 * The byte cap is the other half, and it matters most on the weakest boxes.
 * `prioritizeTimeOverSizeThresholds` buffers by TIME and ignores
 * DEFAULT_VIDEO_BUFFER_SIZE (125 MB) entirely — which live can afford at 11
 * Mbit/s, where 25 seconds is about 34 MB, and a 40 Mbit/s 4K film cannot: 60
 * seconds of it is roughly 300 MB held in the allocator. On a cheap TV box
 * that is not a buffer, it is garbage collection, and GC pauses read to a
 * viewer as exactly the stutter the deep buffer was meant to prevent. VOD
 * keeps the cap, so the buffer stays deep in seconds where the bitrate is
 * modest and gives way to memory where it is not.
 *
 * Live keeps the flag, but it is worth less than it looks: since 1.11.0
 * `DefaultLoadControl` gates it on heap headroom, and when free memory plus
 * unused allocator falls below maxMemory/25 it stops loading BELOW minBufferMs
 * and logs "Stopped loading before minBufferUs reached due to memory pressure,
 * despite prioritizeTimeOverSizeThresholds=true". A 60-second live buffer with
 * no cap was the way to meet that on 2 GB; 24 seconds of a 20 Mbit/s channel
 * is about 60 MB and stays well clear of it. If that line ever appears in a
 * capture, the buffer is starving the stream and these numbers come down
 * again.
 */
@OptIn(UnstableApi::class)
private fun loadControlFor(live: Boolean): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            // Live starts loading again at 22s, not 20s.
            //
            // The loader parks between min and max: it fills to maxBufferMs,
            // then reads NOTHING until the buffer drains back to minBufferMs.
            // At 20/24 that leaves the live socket deliberately unread for
            // about four seconds at a time, over and over, on every channel.
            // A provider whose edge closes a quiet socket faster than that
            // drops the connection on a cycle, the ladder reconnects, and the
            // viewer sees the picture stop and come back — on every channel,
            // which is what a stream-specific fault never does.
            //
            // 22/24 halves that idle window to roughly two seconds. The cost
            // is more frequent, smaller reads, which is the trade this
            // provider appears to want: the previous narrowing, 35s down to
            // four, is recorded as never verified on hardware, and the
            // symptom it was meant to remove is still being reported.
            //
            // NOT MEASURED. Asked for directly after the mechanism was
            // explained, on a night when the log could not be captured. If it
            // does not help, the number is not the cause and the next step is
            // lowering IDLE_REPORT_MS below the idle window so the gap is
            // visible at all — at 6s it cannot see a four-second park, which
            // is why the log can be silent while this happens.
            /* minBufferMs = */ if (live) 22_000 else 20_000,
            /* maxBufferMs = */ if (live) 24_000 else 50_000,
            // 2.5s to start on live: 1.5s made channel changes feel quicker
            // but began playback on a thinner buffer, so a marginal connection
            // re-stalled seconds later. A film is opened once, deliberately,
            // and a second of start-up is cheaper there than a stall later.
            /* bufferForPlaybackMs = */ 2_500,
            // 2.5s on live too. This was 6s, on the reasoning that the panel
            // serves a channel at three to three and a half times real time,
            // so six seconds of media costs about two of wall clock. That
            // holds only while the panel is actually running ahead. When it
            // serves at or near 1x - a loaded panel, a middleman re-streaming,
            // a marginal line - six seconds of media costs six seconds of
            // frozen picture, and the player will not resume until it has
            // them. It resumes, falls behind, and waits six seconds again:
            // the stall loop trips three-in-sixty, which hops the source and
            // then runs out of ladder. That is the same trade a film was
            // losing before it got its own numbers, and live loses it worse,
            // because live is the one with a recovery ladder to exhaust.
            /* bufferForPlaybackAfterRebufferMs = */ 2_500,
        )
        .setPrioritizeTimeOverSizeThresholds(live)
        .build()

/**
 * How long a live connection may go without a byte before the log says so.
 *
 * Above the window the load control designs in — maxBufferMs minus minBufferMs
 * is four seconds of playback — so a stream behaving as configured says
 * nothing and only an unexpected silence is reported. Under the live read
 * timeout, so a gap logged immediately before a failure is that failure's
 * first half rather than a separate event.
 */
private const val IDLE_REPORT_MS = 6_000L

/**
 * Says in the log how long the live connection went unread.
 *
 * The symptom this exists for is "live breaks for a few seconds", which has
 * been guessed at for weeks and never measured. Three different faults produce
 * it — a line with no throughput, a renderer that cannot keep up, and a server
 * that drops a client the player stopped reading from — and only the third
 * leaves no trace at all today. The existing `Rebuffer at …` line says what the
 * buffer held when the picture stopped; this one says whether the connection
 * had already been silent when it happened. Read them together, by timestamp.
 *
 * Attached to the live player the viewer is watching and nothing else. The
 * guide's muted preview opens and closes constantly by design, and a film's
 * connection idles for thirty seconds at a time on purpose ([loadControlFor]),
 * so both would report gaps that mean nothing.
 *
 * One instance per player, and the callbacks arrive on whichever thread is
 * loading. A progressive `.ts` — every live stream, until the ladder swaps the
 * format — is one connection read by one loader, so [TransferGaps] is never
 * touched from two threads at once. HLS segments arrive one after another on
 * the same loader. Neither case needs synchronisation, and a diagnostic is not
 * worth a lock.
 */
@OptIn(UnstableApi::class)
private class LiveTransferLog : TransferListener {

    private val gaps = TransferGaps(IDLE_REPORT_MS)

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) gaps.started(SystemClock.elapsedRealtime())
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (!isNetwork) return
        val gap = gaps.bytes(SystemClock.elapsedRealtime()) ?: return
        Log.w("Agoro", "Live connection went ${gap}ms with no byte, then read again")
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val gap = gaps.ended(SystemClock.elapsedRealtime()) ?: return
        Log.w("Agoro", "Live connection closed after ${gap}ms with no byte")
    }
}

/**
 * Process-lifetime ExoPlayer instances that [ExoEngine] borrows instead of
 * building and releasing its own.
 *
 * `ExoPlayer.release()` is the one call in the player that blocks the main
 * thread: it parks on a ConditionVariable until the playback thread has torn
 * down its renderers — released the hardware decoders — and quit, which on a
 * TV SoC is anywhere from 20 ms to the 500 ms timeout. The app paid that on
 * every exit from the player, on every engine swap, and on every guide
 * preview that stopped, each time as a frozen frame or two right where the
 * viewer was expecting the next screen. Netflix never releases its player
 * between titles, and neither does this.
 *
 * `stop()` is the cheap half of the same work: with foreground mode off (the
 * default) it resets the renderers on the playback thread, so the decoders
 * are still given back the moment an engine is done — just without the
 * main thread waiting for it. The idle player that stays behind is a handler
 * thread and an empty allocator, which is nothing worth a stall to reclaim.
 *
 * A slot per (main, profile) pair, because those differ at construction and
 * cannot be reconfigured on a live player. The guide's muted preview must
 * never share the main player: it is built without audio focus, a builder-time
 * choice, and a preview that borrowed the instance the viewer is watching on
 * would have to stop it first. The tolerant player exists only after a stream
 * has failed, so most sessions never build one at all. Each slot holds at most
 * one idle instance; a borrow while the slot's instance is still out (a
 * profile change composes the new engine before the old one's teardown runs)
 * builds a fresh one, and the surplus is released — off the main thread's
 * critical path as far as ExoPlayer allows — when it comes back.
 */
@OptIn(UnstableApi::class)
object PlayerPool {

    /** What makes two players non-interchangeable; see the class comment. */
    /**
     * [silent] is part of the key, not a property of the borrower.
     *
     * A silent player has audio disabled in its track selector, and handing
     * one to a viewer is a film that plays with no sound and nothing on screen
     * to explain it. Today [main] happens to separate them — the only silent
     * borrower is the guide preview, which is also the only one that declines
     * audio focus — so this is belt and braces. It is worth the field anyway:
     * that alignment is a coincidence of the current call sites, and the next
     * silent-but-focus-taking player (or the reverse) would inherit the wrong
     * selector with no compile error and no crash, only silence.
     *
     * [pcmOnly], [smoothPts] and [reinitVideo] are the output latches —
     * [AudioOutputPolicy], [VideoOutputPolicy] — as they stood when the
     * player was built. Sink and renderers are fixed at build time, so a
     * player built while passthrough was still trusted keeps offering it for
     * as long as it lives, and an idle one lent out after the output has
     * refused a track would hand the viewer the very failure the latch
     * exists to end.
     */
    internal data class Slot(
        val main: Boolean,
        val profile: DecodeProfile,
        val live: Boolean,
        val silent: Boolean,
        val pcmOnly: Boolean,
        val smoothPts: Boolean,
        val reinitVideo: Boolean,
    )

    /** One borrowed player, the selector it was built with, and its sink's tunnel gate. */
    class Lease internal constructor(
        val player: ExoPlayer,
        val trackSelector: DefaultTrackSelector,
        internal val slot: Slot,
        internal val audioGate: TunnelledAudioGate,
    )

    /**
     * How many idle players may be kept alive at once, across ALL slots.
     *
     * There was no cap. One instance per slot, released only by [drain] when
     * the activity finished — which on a session that watched live, opened a
     * film, and hit the recovery profile once meant five ExoPlayers alive at
     * the same time, plus the one actually playing. stop() does not release a
     * player; the instance stays and so does its audio session, and a box has
     * a finite number of those. The one that fails is never the one that took
     * them: it is the next stream to ask, which is "AudioTrack init failed" on
     * a film with the guide and two dead profiles still holding sessions
     * behind it.
     *
     * One is enough for what the pool is FOR. The blocking release it exists
     * to keep off the hot path is the one between a zap and the next channel,
     * or across a profile swap — both of which hand back and re-borrow the
     * same slot immediately, and the most recently returned player is the one
     * that serves them. Anything older is a session held on the chance it is
     * wanted again.
     */
    private const val MAX_IDLE = 1

    // Insertion-ordered, so eviction can take the OLDEST rather than whatever
    // a hash iteration happens to yield first.
    private val idle = LinkedHashMap<Slot, Lease>()

    /**
     * @param main True for the player the viewer watches on: takes audio
     * focus, pauses when headphones unplug, may use tunnelled decoding. False
     * for the guide's silent preview, which must do none of those.
     * @param profile How forgiving to build it; see [DecodeProfile].
     * @param silent Built with audio decoding switched off; see [Slot].
     */
    fun borrow(
        context: Context,
        main: Boolean,
        profile: DecodeProfile = DecodeProfile.FAST,
        live: Boolean = true,
        silent: Boolean = false,
    ): Lease {
        val slot = Slot(
            main, profile, live, silent,
            pcmOnly = AudioOutputPolicy.pcmOnly,
            smoothPts = AudioOutputPolicy.smoothTimestamps,
            reinitVideo = VideoOutputPolicy.reinitOnly,
        )
        idle.remove(slot)?.let { return it }
        return build(context.applicationContext, slot)
    }

    /**
     * Hands a player back. Stops it — which releases its decoders on the
     * playback thread — and empties it, so the next borrower starts from the
     * same blank state a freshly built player would. The caller has already
     * detached its view, its listener and its media session.
     */
    fun giveBack(lease: Lease) {
        val player = lease.player
        player.stop()
        player.clearMediaItems()
        player.playWhenReady = false
        player.volume = 1f
        player.setPlaybackSpeed(1f)
        player.clearVideoSurface()
        lease.audioGate.tunnelling = false
        if (idle[lease.slot] == null) {
            idle[lease.slot] = lease
            evictBeyondCap(keep = lease.slot)
        } else {
            // A second instance for the same slot only exists across a swap's
            // overlap. Nothing keeps it; see releaseLater for why not now.
            releaseLater(player)
        }
    }

    /**
     * Releases a player that has just been stopped — but not yet.
     *
     * stop() hands the player's AudioTrack to media3's release thread, which
     * reports back to the playback thread once the track is gone; release()
     * quits that thread. Called back to back, the report finds no thread to
     * land on, and on media3 1.9 through 1.11 that lost report leaves a
     * process-wide "release pending" count stuck above zero (androidx/media
     * #3338; fixed on main after 1.11.0). The count gates every AudioTrack
     * retry in the process: from then on a track that fails to open is never
     * retried and never reported, which is a film that buffers silently for
     * ever. A second on the main thread is more than the release needs to
     * finish and report, and it also takes the blocking release() off the
     * path that handed the player back — which is what the pool was for.
     */
    private fun releaseLater(player: ExoPlayer) {
        mainHandler.postDelayed({ player.release() }, DEFERRED_RELEASE_MS)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private const val DEFERRED_RELEASE_MS = 1_000L

    /**
     * Frees idle players beyond [MAX_IDLE], oldest first, never the one just
     * handed back — that is the one the next borrow is most likely to want.
     *
     * The victim was stopped when it came back, which may have been a moment
     * ago on a fast ladder, so it goes through [releaseLater] like any other.
     */
    private fun evictBeyondCap(keep: Slot) {
        if (idle.size <= MAX_IDLE) return
        val victims = idle.keys.filterNot { it == keep }.take(idle.size - MAX_IDLE)
        for (slot in victims) idle.remove(slot)?.player?.let(::releaseLater)
    }

    /**
     * Releases every idle instance, off the main thread's critical path.
     *
     * For the app going to the background, and for the activity finishing —
     * which turn out to want the same thing. An idle player is a handler
     * thread and an audio session kept on the chance the viewer comes back to
     * the same slot, which is a good bet while they are still in the app and a
     * bad one the moment they are not: on a 2 GB box the process nobody is
     * looking at should not be the one holding a finite audio session, and the
     * app that suffers for it is whichever one they opened instead.
     *
     * This replaces a `drain()` that released synchronously on the grounds
     * that a finishing activity has no hot path left to protect. True, but
     * beside the point: the player it released had been handed back moments
     * earlier by the Compose tree being disposed, so `stop()` and `release()`
     * ran back to back on the same instance — the exact pairing [releaseLater]
     * exists to avoid, and the one that leaves media3's process-wide release
     * count stuck for the life of the process. The deferred release costs a
     * second in a process that is usually about to be killed anyway (and if it
     * is killed first, the codecs go with it, which was the outcome either
     * way).
     *
     * That second is also why nothing bypasses [releaseLater] under memory
     * pressure, tempting as it is when the caller is being told the process is
     * next for the killer: a stuck release count is a film that buffers
     * silently for ever, which is a worse and much longer-lived failure than
     * being killed one second early.
     */
    fun releaseIdle() {
        if (idle.isEmpty()) return
        idle.values.forEach { releaseLater(it.player) }
        idle.clear()
    }

    private fun build(context: Context, slot: Slot): Lease {
        val trackSelector = DefaultTrackSelector(context)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            // A live feed that stops sending is dead NOW, not in fifteen
            // seconds: the recovery ladder can't run until this fires, and
            // every second here is a second of frozen picture first.
            //
            // A film is the opposite case. It has twenty to fifty seconds
            // buffered ahead, so a server that pauses for ten is invisible to
            // the viewer — unless the read times out, in which case ExoPlayer
            // retries the range three times and then throws, and the ladder
            // shows "the connection dropped" over a picture that had plenty
            // to play. The buffer pays for the patience; live has no buffer
            // to pay with.
            .setReadTimeoutMs(if (slot.live) 8_000 else 20_000)
        // Diagnostic only, and only where a silent connection means something;
        // see LiveTransferLog.
        if (slot.live && slot.main) httpFactory.setTransferListener(LiveTransferLog())
        // Stock until an output latch says otherwise; see AgoroRenderersFactory.
        val audioGate = TunnelledAudioGate()
        val renderers = AgoroRenderersFactory(context, slot, audioGate)
            // ON, not PREFER: hardware decoders first, software only as a
            // fallback. PREFER puts software ahead of MediaCodec, which drops
            // frames on TV silicon the moment a decoder extension is present.
            //
            // This is also what loads the bundled FFmpeg audio decoder, and
            // with libVLC gone that module is now the app's only answer for
            // AC-3, E-AC-3, DTS and TrueHD on a box whose hardware doesn't
            // cover them. It is load-bearing; see docs/ffmpeg-decoder.md.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // A stream whose hardware decoder refuses to initialise retries on
            // another decoder instead of failing the whole item.
            .setEnableDecoderFallback(true)
        if (slot.profile == DecodeProfile.TOLERANT) {
            // The recovery rung's other half: put the software decoders in
            // front. They play what the vendor's hardware refused — odd
            // profiles, unusual bit depths, streams whose headers lie — at a
            // frame rate that will not carry 4K, which is why nothing opens
            // here and only a failure arrives here.
            renderers.setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
        }
        // Wraps the HTTP factory so file:// (recordings) resolves, and picks up
        // the RTMP data source reflectively now that the module is on the
        // classpath. DefaultMediaSourceFactory does the same for the DASH,
        // SmoothStreaming and RTSP media sources; HLS is routed by hand so the
        // transport-stream flags reach its segments.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(IptvMediaSourceFactory(dataSourceFactory, slot.profile))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControlFor(slot.live))
            // Proper audio-focus citizenship: request focus as media playback
            // and pause when headphones unplug, instead of talking over
            // whatever was already playing.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ slot.main,
            )
            .setHandleAudioBecomingNoisy(slot.main)
            // Belt and braces for the release() calls that remain — a surplus
            // instance coming back, or draining at process end. The default
            // lets the main thread wait half a second for a decoder that is
            // slow to let go; past a hundred milliseconds the wait is worth
            // less than the frame it costs, and ExoPlayer only logs a timeout.
            .setReleaseTimeoutMs(100)
            .build()
        return Lease(player, trackSelector, slot, audioGate)
    }
}

/**
 * The stock [DefaultRenderersFactory] until an output latch says otherwise.
 *
 * [PlayerPool.Slot.pcmOnly] — a sink that believes the device can play 16-bit
 * PCM and nothing else, whatever the HDMI EDID says. `MediaCodecAudioRenderer`
 * asks the sink what it supports before choosing between passthrough and
 * decoding, so capping the sink is what actually removes the bitstream path;
 * a track-selection constraint would not, since the selector still picks a
 * 5.1 Dolby track when it is the only one. With passthrough gone the renderer
 * falls back to a platform decoder for the format where one exists, and
 * otherwise to the bundled FFmpeg decoder — which is what
 * [DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON] is for, and the only
 * AC-3/E-AC-3/DTS/TrueHD decoder on a box whose hardware lacks one.
 * Multichannel PCM is still allowed: AudioFlinger mixes it down to whatever
 * the output is, which is the one thing it reliably does. The capabilities
 * are pinned by giving the output provider no Context — the media3 1.11 way;
 * the older `setAudioCapabilities` is deprecated precisely because the
 * Context-taking sink builder installs its own capabilities receiver and
 * ignores it. See [AudioOutputPolicy].
 *
 * [PlayerPool.Slot.smoothPts] — the sink wrapped in [PtsSmoothingAudioSink].
 *
 * [PlayerPool.Slot.reinitVideo] — the stock video renderer swapped for one
 * that never reuses a codec across streams and always re-initialises on a
 * new surface; see [VideoOutputPolicy]. Built the way the stock factory
 * builds its own, from the same builder.
 */
@OptIn(UnstableApi::class)
private class AgoroRenderersFactory(
    context: Context,
    private val slot: PlayerPool.Slot,
    private val audioGate: TunnelledAudioGate,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        // With a Context the provider reads the HDMI capabilities and keeps
        // reading them; without one it believes in 16-bit PCM and nothing
        // else, which is the PCM latch. Either way it sits behind the tunnel
        // gate, which withholds bitstreams from a tunnelled track once the
        // output has refused one; see TunnelGatedAudioOutputProvider.
        val output = AudioTrackAudioOutputProvider.Builder(if (slot.pcmOnly) null else context).build()
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioOutputProvider(TunnelGatedAudioOutputProvider(output, audioGate))
            .build()
        return if (slot.smoothPts) PtsSmoothingAudioSink(sink) else sink
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val first = out.size
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        if (!slot.reinitVideo) return
        // The stock factory puts its MediaCodecVideoRenderer first and any
        // extension renderers after it; only the first is ours to replace.
        if (out.getOrNull(first)?.javaClass != MediaCodecVideoRenderer::class.java) return
        out[first] = ReinitVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
        )
    }
}

/**
 * Whether the player a sink belongs to is asking for tunnelled rendering.
 *
 * Set by the engine BEFORE it changes the selector, because the sink's own
 * tunnelling flag only turns when the audio renderer is enabled — which is
 * after the track selection that decided between passthrough and decoding
 * has already been made. Reset when the player goes back to the pool.
 */
internal class TunnelledAudioGate {
    @Volatile
    var tunnelling: Boolean = false
}

/**
 * The output provider with one rule in front of it: while the player is
 * tunnelling and [AudioOutputPolicy.passthroughWhileTunnelled] has turned,
 * no bitstream is "supported". `MediaCodecAudioRenderer` asks the sink
 * whether it supports the compressed format before choosing between the
 * passthrough codec and a real decoder, so answering no here is what routes
 * a tunnelled Dolby track through the platform's or FFmpeg's decoder and
 * hands the tunnelled AudioTrack PCM — the track such a box does open. The
 * untunnelled question is passed straight through; that path was never the
 * problem.
 */
@OptIn(UnstableApi::class)
private class TunnelGatedAudioOutputProvider(
    delegate: AudioOutputProvider,
    private val gate: TunnelledAudioGate,
) : ForwardingAudioOutputProvider(delegate) {

    override fun getFormatSupport(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.FormatSupport {
        // The gate, not formatConfig.enableTunneling. The sink's flag is a
        // record of the LAST renderer enable, and a selection is made
        // before the next one: after a tunnelled stream it still reads
        // true while an ordinary film is choosing its audio path, and
        // reading it here withheld passthrough from every film that
        // followed a 4K one on the same pooled player.
        val bitstream = formatConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW
        if (gate.tunnelling && bitstream && !AudioOutputPolicy.allowsPassthrough(tunnelled = true)) {
            return AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
        return super.getFormatSupport(formatConfig)
    }
}

/**
 * A video renderer that re-creates its codec for every stream and every new
 * surface, for the decoders that come out of a flush-and-reuse decoding but
 * not drawing; see [VideoOutputPolicy].
 */
@OptIn(UnstableApi::class)
private class ReinitVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
        isAdaptiveFormatChange: Boolean,
    ): DecoderReuseEvaluation =
        DecoderReuseEvaluation(
            codecInfo.name,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE,
        )

    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean = true
}
