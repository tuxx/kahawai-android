package com.kolktech.kahawai.ui.player

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.ApiClient
import com.kolktech.kahawai.data.network.dto.CapabilityProfile
import com.kolktech.kahawai.data.network.dto.Chapter
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.network.dto.Pref
import com.kolktech.kahawai.data.network.dto.Segment
import com.kolktech.kahawai.data.network.dto.StartSessionResponse
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.data.network.readableMessage
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.data.repository.PlaybackRepository
import com.kolktech.kahawai.data.repository.PreferencesRepository
import com.kolktech.kahawai.playback.CapabilityProfileBuilder
import com.kolktech.kahawai.playback.IMAGE_FORMATS
import com.kolktech.kahawai.playback.PREF_AUDIO
import com.kolktech.kahawai.playback.langEq
import com.kolktech.kahawai.playback.needsMediaType
import com.kolktech.kahawai.playback.rememberedAudioValue
import com.kolktech.kahawai.playback.resolveAudioTrack
import com.kolktech.kahawai.playback.PREF_SUBS
import com.kolktech.kahawai.playback.PREF_SUBS_TRACK
import com.kolktech.kahawai.playback.rememberedSubsTrackValue
import com.kolktech.kahawai.playback.rememberedSubsValue
import com.kolktech.kahawai.playback.resolveSubtitleTrack
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface PlayerState {
    data object Loading : PlayerState
    data class Error(val message: String) : PlayerState
    data object Ready : PlayerState
}

/// What an "ass"/"overlay" delivery renderer needs to tap the hub's
/// out-of-band session streams (subs-{id}.ass / subs-{id}.jsonl):
/// [streamBaseUrl] is [session.streamUrl] with the last path segment
/// stripped, mirroring web/src/views/Player.tsx's `base`. [streamBaseUrl]
/// itself doesn't change across a seek-restart within one session, so
/// [epoch] is bumped on every attach() to give overlay composables an
/// explicit key to reconnect their stream on. Timing deliberately does
/// NOT travel through here: overlays read the absolute video position
/// straight off [PlayerViewModel.player], whose position overrides
/// already fold in offsetMs — including [PlayerViewModel.syncOrigin]'s
/// in-place correction — in every mode. (An earlier revision carried
/// offsetMs in this class and had overlays add it to player position
/// themselves; once the ForwardingPlayer's getCurrentPosition() started
/// reporting absolute time for the seekbar fix, that added the offset
/// TWICE, desyncing ass/overlay subtitles after every seek or mid-video
/// resume.)
/// [isHls] mirrors web/src/views/Player.tsx's `isHls`: the live
/// `subs-{id}.ass` tap only exists for Remux/Transcode sessions — the
/// hub's session_file handler no-ops it for Mode::Direct (the raw file
/// is served byte-for-byte with no server-side pipeline to tap), so a
/// direct-mode session must fall back straight to the item-scoped
/// `/items/{id}/subtitles/{id}.ass` whole-file-extraction endpoint
/// instead (see AssSubtitleOverlay).
data class SubtitleSession(
    val streamBaseUrl: String,
    val isHls: Boolean,
    val epoch: Int,
)

/// Progress cadence matches the web client (web/src/views/Player.tsx:678-710):
/// every 10s while playing, plus on pause and on teardown.
private const val PROGRESS_INTERVAL_MS = 10_000L

/// How long onBackgrounded() waits before actually stopping the player —
/// see its doc for why this is debounced rather than immediate.
private const val BACKGROUND_STOP_DELAY_MS = 500L

private const val TAG = "PlayerViewModel"

/// Where to start the player and how to interpret [PlayerViewModel]'s
/// running position, derived from the session mode the hub negotiated.
/// "direct" mode serves the file's own timeline (a real seek to
/// [startMs], offset 0); "remux"/"transcode" mode restarts the hub's
/// pipeline AT [startMs] (attach at local position 0, offset [startMs]
/// so absolute-position math elsewhere stays correct). See start().
internal data class ResumePlan(val offsetMs: Long, val startPositionMs: Long)

internal fun resumePlan(mode: String, startMs: Long): ResumePlan =
    if (mode == "direct") ResumePlan(offsetMs = 0, startPositionMs = startMs)
    else ResumePlan(offsetMs = startMs, startPositionMs = 0)

/// [siblings] is the whole show's episodes pre-sorted by season then
/// episode (hub api.rs item_children) — the next entry after [itemId]'s
/// own position IS the next episode to play, no season-boundary math
/// needed. Returns null for non-episodes, items with no parent, the
/// last episode of a show, or an [itemId] not found in [siblings].
internal fun resolveNextEpisode(
    kind: String?,
    parentId: String?,
    itemId: String,
    siblings: List<Item>,
): String? {
    if (kind != "episode" || parentId == null) return null
    val index = siblings.indexOfFirst { it.id == itemId }
    return if (index >= 0) siblings.getOrNull(index + 1)?.id else null
}

/// Mirror of [resolveNextEpisode] for the "<" button — the sibling
/// before [itemId]'s own position, or null for non-episodes, items with
/// no parent, the first episode of a show, or an [itemId] not found in
/// [siblings].
internal fun resolvePreviousEpisode(
    kind: String?,
    parentId: String?,
    itemId: String,
    siblings: List<Item>,
): String? {
    if (kind != "episode" || parentId == null) return null
    val index = siblings.indexOfFirst { it.id == itemId }
    return if (index > 0) siblings.getOrNull(index - 1)?.id else null
}

/// Which episodes the player's "<"/">" buttons should offer, resolved
/// once at start() alongside the title (see [PlayerViewModel.start]).
/// Both null for a movie/non-episode item — PlayerScreen hides both
/// buttons entirely in that case rather than showing disabled controls.
internal data class AdjacentEpisodes(val previousId: String?, val nextId: String?)

/// See [PlayerViewModel.syncOrigin]. [correctedOffsetMs] is the hub's
/// keyframe-snapped origin (partBaseMs + the pipeline-local position it
/// actually landed on); [inBounds] guards against applying it when it's
/// wildly different from the optimistic offset already in use — a sign
/// of reading the wrong session/a stale file, not a real keyframe snap.
internal data class OriginCorrection(val correctedOffsetMs: Long, val inBounds: Boolean)

internal fun computeOriginCorrection(
    partBaseMs: Long?,
    localMs: Long,
    currentOffsetMs: Long,
    boundMs: Long = 60_000,
): OriginCorrection {
    val corrected = (partBaseMs ?: 0) + localMs
    val inBounds = kotlin.math.abs(corrected - currentOffsetMs) < boundMs
    return OriginCorrection(correctedOffsetMs = corrected, inBounds = inBounds)
}

/// MergingMediaPeriod re-exposes every child source's formats with the id
/// rewritten to "<childIndex>:<originalId>" (see
/// [PlayerViewModel.applySubtitleTrackSelectionOverride]) — a sideloaded
/// VTT config's id "1234" surfaces here as e.g. "1:1234", so matching must
/// strip that prefix rather than compare exactly.
internal fun matchesSideloadedTrackId(formatId: String?, wantedId: String): Boolean =
    formatId?.substringAfterLast(':') == wantedId

/// The sample MIME types a bitmap subtitle track can reach the player's
/// own track list as. Two spellings, because media3 parses subtitles at
/// EXTRACTION time now: SubtitleTranscodingTrackOutput rewrites the
/// format's sampleMimeType to `application/x-media3-cues` and moves the
/// real one down into `codecs`, so a track matched on sampleMimeType
/// alone would never be found in a progressive (direct) source.
internal val NATIVE_BITMAP_SUBTITLE_MIMES = setOf(MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_PGS)

/// One of the player's own text track groups, cut down to what can
/// identify it — see [nativeBitmapGroupIndex].
internal data class TextTrackInfo(val mimeType: String?, val codecs: String?, val language: String?)

/// Whether media3's own decoder renders this pick, rather than
/// [ImageSubtitleOverlay]. "overlay" delivery normally means the hub
/// decodes the display sets and streams them as a session tap — but a
/// "direct" session is a raw byte-range file with no server-side
/// pipeline to tap, and the item-scoped whole-file route only answers
/// for origin=="raster", so for a genuine embedded track that source
/// simply doesn't exist. What a direct session does have is ExoPlayer
/// demuxing the container itself, and media3 ships parsers for both
/// bitmap formats that arrive that way (VobsubParser, PgsParser) — so
/// the track is already in the player's own text groups, wanting only
/// to be selected. Its cues then draw in PlayerView's SubtitleView,
/// which fills exo_content_frame and so lands them on the video the
/// same way the overlay would.
internal fun isNativeBitmapPick(track: SubtitleTrack?, isDirect: Boolean): Boolean =
    isDirect &&
        track != null &&
        track.delivery == "overlay" &&
        track.origin == "embedded" &&
        track.format.lowercase() in IMAGE_FORMATS

/// Where [pick] sits among the bitmap tracks the container itself
/// carries. The hub's listing is wider than the file — sidecars,
/// downloads and OCR rows are in it too — while the extractor only ever
/// sees what's muxed in, so the two only line up once everything else is
/// filtered out and what's left is in the file's own stream order. Null
/// when [pick] isn't one of them.
internal fun embeddedBitmapOrdinal(tracks: List<SubtitleTrack>, pick: SubtitleTrack): Int? =
    tracks
        .filter { it.origin == "embedded" && it.format.lowercase() in IMAGE_FORMATS }
        .sortedBy { it.streamIndex ?: Long.MAX_VALUE }
        .indexOfFirst { it.id == pick.id }
        .takeIf { it >= 0 }

/// Which of the player's text groups is the pick, indexing into [groups]
/// as given (group order). Language decides whenever it can single a
/// track out — it's the one field both listings spell the same way, and
/// it survives a container whose track numbering doesn't match the hub's
/// stream indices. Position among the bitmap groups is the fallback, for
/// a file whose tracks declare no language or declare the same one
/// twice. Null when the container turned out not to carry a bitmap track
/// at all.
internal fun nativeBitmapGroupIndex(groups: List<TextTrackInfo>, ordinal: Int, language: String?): Int? {
    val candidates = groups.withIndex().filter {
        it.value.mimeType in NATIVE_BITMAP_SUBTITLE_MIMES || it.value.codecs in NATIVE_BITMAP_SUBTITLE_MIMES
    }
    if (candidates.isEmpty()) return null
    if (!language.isNullOrEmpty()) {
        candidates.singleOrNull { langEq(it.value.language, language) }?.let { return it.index }
    }
    return candidates.getOrNull(ordinal)?.index
}

/// The video format actually being rendered, out of the player's own
/// track list. Null before the first selection, and for an audio-only
/// item.
internal fun selectedVideoFormat(tracks: Tracks): Format? =
    tracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }

/// Whether [format] carries one of the two HDR transfer functions. Both
/// are checked because the hub serves either: PQ (ST 2084) for HDR10 and
/// Dolby Vision profiles that fall back to it, HLG for broadcast-sourced
/// material.
///
/// The colour PRIMARIES are deliberately not consulted. BT.2020 primaries
/// with an SDR transfer is a wide-gamut SDR picture, which needs no
/// dimming, and tone-mapped output keeps its primaries while losing the
/// transfer — so the transfer is the field that says whether the panel is
/// being driven in HDR.
@OptIn(UnstableApi::class)
internal fun isHdrTransfer(format: Format?): Boolean =
    when (format?.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG -> true
        else -> false
    }

/// See [PlayerViewModel.textDeliverySubtitleConfigs]. shift_ms is negative
/// offsetMs: the hub shifts the VTT's own cue timestamps to line up with
/// the player's absolute position, which is offsetMs AHEAD of the file's
/// local time — so cues must be shifted BACK by that amount.
internal fun subtitleVttUrl(baseUrl: String, itemId: String, trackId: Long, offsetMs: Long): String =
    "${baseUrl.trimEnd('/')}/api/v1/items/$itemId/subtitles/$trackId.vtt?shift_ms=${-offsetMs}"

/// How much of the produced HLS window's tail to treat as out of reach
/// for a local seek. The hub keeps extending the playlist as it encodes,
/// so its very edge is a moving target: landing on it means sitting at
/// BUFFERING waiting for the encoder rather than playing, which is the
/// one case where restarting the pipeline at the target is genuinely
/// the better deal.
internal const val LOCAL_SEEK_TAIL_MS = 3_000L

/// Where a seek to absolute [targetMs] lands in the current playlist's
/// own timeline, or null if it lands outside it and the hub has to
/// restart the pipeline at the target instead (see handleSeek).
/// [offsetMs] is the absolute position the playlist starts at and
/// [producedMs] the length the hub has encoded so far ([C.TIME_UNSET]
/// before the playlist's duration is known). The playlist is an EVENT
/// one — nothing is ever dropped from its head — so everything from its
/// start up to the produced edge is reachable locally, which is what
/// makes a 10-second rewind cost nothing instead of a full remux
/// restart.
internal fun localSeekPositionMs(
    targetMs: Long,
    offsetMs: Long,
    producedMs: Long,
    tailMs: Long = LOCAL_SEEK_TAIL_MS,
): Long? {
    if (producedMs == C.TIME_UNSET) return null
    val localMs = targetMs - offsetMs
    return localMs.takeIf { it >= 0 && it <= producedMs - tailMs }
}

/// HUB-37. What to offer to skip, and where pressing it lands — a
/// faithful port of web/src/domain/segments.ts, which the hub's own
/// scan (crate::segments) and web player were designed around.

/// How close to a segment's end still counts as inside it. A button
/// that appears for the last half second of an opening is a button
/// nobody can press and everybody sees.
internal const val SKIP_TAIL_MS = 1_500L

/// kind -> the string resource for its skip button label. An unknown
/// kind is ignored rather than offered as a bare "Skip".
private val SKIP_LABELS: Map<String, Int> = mapOf(
    "recap" to R.string.player_skip_recap,
    "intro" to R.string.player_skip_intro,
    "credits" to R.string.player_skip_credits,
)

/// The segment the playhead is inside, if any. The first match wins:
/// the detector clamps its own recap to the opening's start, but a
/// chapter-named boundary is stored as written and can overlap an
/// inferred one, so inside an overlap the button follows whichever
/// segment started first.
internal fun skippableSegment(segments: List<Segment>, posMs: Long, tailMs: Long = SKIP_TAIL_MS): Segment? =
    segments.firstOrNull { SKIP_LABELS.containsKey(it.kind) && posMs >= it.startMs && posMs < it.endMs - tailMs }

internal fun skipLabelRes(segment: Segment?): Int? = segment?.let { SKIP_LABELS[it.kind] }

/// Where the button lands: the end of the segment, but never the very
/// last second of the file — a seek to the duration stalls on some
/// players, and credits usually end exactly there.
internal fun skipTargetMs(segment: Segment, durationMs: Long): Long {
    val end = if (durationMs > 0) minOf(segment.endMs, durationMs - 1_000) else segment.endMs
    return end.coerceAtLeast(0)
}

/// Chapter marks to draw on the seek bar, in the shape
/// [DefaultTimeBar.setAdGroupTimesMs] wants: a chapter at zero is
/// dropped (every file has one and it marks the left edge, where
/// there's nothing to find), so is anything at or past the end.
/// Mirrors web/src/domain/chapters.ts's chapterTicks.
internal fun chapterMarkTimesMs(chapters: List<Chapter>, durationMs: Long): LongArray =
    if (durationMs <= 0) {
        LongArray(0)
    } else {
        chapters.map { it.startMs }.filter { it in 1 until durationMs }.toLongArray()
    }

@OptIn(UnstableApi::class)
class PlayerViewModel(
    application: Application,
    private val repo: PlaybackRepository,
    val itemId: String,
    private val startMs: Long,
    /// Negative for "nothing chosen" — auto-advance into the next episode
    /// carries no track, and the remembered preferences answer instead.
    private val initialAudioTrack: Int = -1,
    private val initialSubtitleTrackId: Long? = null,
    /// Which library this item was opened from, carried by the route: the
    /// item's own detail doesn't name one, and without it the account's
    /// per-media-type track lists can't be found at all — see TrackChoice.
    private val libraryId: String? = null,
    /// See [PlaybackPrefetch]: the Detail screen's own itemQuery, reused
    /// here instead of repeated. Null for a navigation that skipped
    /// Detail (auto-advance, the in-player "<"/">" buttons), in which
    /// case start() queries it itself exactly as it did before this
    /// existed.
    private val prefetch: PlaybackPrefetch? = null,
    private val catalogRepo: CatalogRepository = CatalogRepository(),
    private val prefsRepo: PreferencesRepository = PreferencesRepository(),
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Loading)
    val state: StateFlow<PlayerState> = _state

    /// True only while genuinely paused (playWhenReady false at
    /// STATE_READY) — deliberately excludes buffering/loading/ended so
    /// the center pause glyph doesn't flash up over the buffering spinner
    /// or a just-finished/not-yet-started playback. Recomputed off both
    /// onIsPlayingChanged and onPlaybackStateChanged (see the listener in
    /// init) since either alone can flip it. Also excludes [internalPause]:
    /// handleSeek()'s hub-restart round trip and restartSessionWithSubtitle()
    /// both pause() the player while they wait on a network call the user
    /// never asked to see — a segment auto-skip or "Skip Intro"/"Skip
    /// Recap"/"Skip Credits" press would otherwise flash the pause glyph
    /// for the round trip's duration even though playback never stopped
    /// from the user's perspective.
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused
    private var internalPause = false

    /// Failures that DON'T end the session — a failed seek or subtitle
    /// switch leaves the current stream playable, so they surface as a
    /// snackbar over the still-running player rather than tearing the
    /// whole UI down to [PlayerState.Error] (which is reserved for
    /// "nothing is playing and nothing will": start() and onPlayerError).
    private val _transientError = MutableStateFlow<String?>(null)
    val transientError: StateFlow<String?> = _transientError

    fun clearTransientError() {
        _transientError.value = null
    }

    /// Resolved once playback naturally reaches the end (see
    /// handlePlaybackEnded) — the id of the next episode in the same show,
    /// if there is one. PlayerScreen navigates to it as soon as it's set.
    private val _nextEpisodeId = MutableStateFlow<String?>(null)
    val nextEpisodeId: StateFlow<String?> = _nextEpisodeId

    /// Resolved once, alongside the title, in start(). Null for a movie;
    /// for an episode, carries whichever of the previous/next sibling ids
    /// actually exist so PlayerScreen can show/hide and wire up the "<"/
    /// ">" buttons.
    private val _adjacentEpisodes = MutableStateFlow<AdjacentEpisodes?>(null)
    internal val adjacentEpisodes: StateFlow<AdjacentEpisodes?> = _adjacentEpisodes

    /// Flips once handlePlaybackEnded() determines there's no next episode
    /// to auto-advance to (a movie, or the last episode of a show).
    /// PlayerScreen closes the player as soon as it's set, landing back on
    /// the detail screen already underneath it on the back stack instead of
    /// leaving playback sitting on a frozen/black final frame.
    /// Whether the video being played is HDR — see [isHdrTransfer] and
    /// [TEXT_SUBTITLE_STYLE_HDR]. Read off the selected video track rather
    /// than the item's catalogue facts, because it has to follow what is
    /// ACTUALLY on the wire: the hub tone maps to SDR when this client's
    /// profile says it cannot take HDR, and the same item can therefore
    /// play either way on different devices, or across a session restart
    /// that renegotiated.
    private val _hdrActive = MutableStateFlow(false)
    val hdrActive: StateFlow<Boolean> = _hdrActive

    private val _playbackFinished = MutableStateFlow(false)
    val playbackFinished: StateFlow<Boolean> = _playbackFinished

    /// Resolved alongside session start (see start()) purely for display
    /// in the player's top bar — best-effort, same as the subtitle track
    /// list, so a failed lookup just leaves the title blank rather than
    /// failing playback.
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title

    private val realPlayer: ExoPlayer by lazy {
        val dataSourceFactory = DefaultDataSource.Factory(
            getApplication(),
            OkHttpDataSource.Factory(ApiClient.streamingOkHttpClient()),
        )
        ExoPlayer.Builder(getApplication())
            .setMediaSourceFactory(buildPlayerMediaSourceFactory(dataSourceFactory))
            // Media3's defaults (~15s minBuffer, 5s bufferForPlaybackAfterRebuffer)
            // assume a source that can be downloaded far ahead of playback.
            // A remux/transcode session is the opposite: the hub is
            // encoding the HLS EVENT playlist in close to real time (see
            // attach()'s targetOffsetMs note), so its production lead over
            // the playhead is often just a few seconds, especially right
            // after a session starts or right after auto-advance starts a
            // new session while the hub is still tearing the previous one
            // down. Holding out for 15s of buffer that the hub simply
            // hasn't produced yet is what showed up as repeated
            // "loading" flashes at the start of a video (worse right after
            // auto-advance, where the hub's lead is thinnest) — the
            // player buffers, catches up to the produced edge, stalls,
            // and re-buffers, several times over, before the hub's lead
            // widens enough to clear the default threshold. Shorter
            // targets let it resume as soon as a realistic amount of
            // media is actually available instead of chasing an
            // unreachable buffer floor.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 10_000,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        /* bufferForPlaybackMs = */ 1_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 1_500,
                    )
                    .build(),
            )
            // handleAudioFocus=true so ExoPlayer pauses itself on transient
            // audio focus loss (e.g. an incoming call ringing) and resumes
            // when focus is returned, without us wiring up an AudioManager
            // listener by hand.
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .build()
            .apply {
                // Text tracks start DISABLED, and applySubtitleTrackSelectionOverride
                // re-enables the renderer only while a Text-delivery track is
                // actually picked. "No override" is not "off": DefaultTrackSelector
                // auto-selects a text track whose language matches the device's
                // captioning locale or that carries SELECTION_FLAG_DEFAULT (which
                // an MKV's embedded subtitle usually does, and direct sessions
                // expose those straight to ExoPlayer). That's how a flattened
                // built-in rendering showed up with subtitles "off", and doubled
                // on top of AssSubtitleOverlay when an ASS track was picked.
                // Ass/Overlay delivery never touches this pipeline at all — those
                // are out-of-band taps rendered by AssSubtitleOverlay /
                // ImageSubtitleOverlay.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
    }

    /// What the UI (PlayerView / its built-in controller) should hold.
    /// HLS sessions serve a growing EVENT playlist: a seek past what's
    /// been produced needs the hub to restart the pipeline elsewhere
    /// (§6 seek-anywhere, HUB-18), not a raw ExoPlayer seek — this
    /// reroutes the controller's seek/seekForward/seekBack through that
    /// logic transparently, mirroring web/src/views/Player.tsx:633-663.
    /// A target already inside the produced window skips that round trip
    /// and seeks locally instead — see handleSeek/localSeekPositionMs.
    val player: Player by lazy {
        object : ForwardingPlayer(realPlayer) {
            /// The seekbar now displays ABSOLUTE position/duration — the
            /// full original video length, with the current position
            /// marked at its true point (see getDuration()/
            /// getCurrentPosition()/getCurrentTimeline() below) — not
            /// "remaining time from 0", which put the scrubber at the
            /// wrong end of the bar and made resuming partway through
            /// look like starting over. PlayerControlView's drag-target
            /// arithmetic is entirely derived from what we report it, so
            /// a drag arrives here already in ABSOLUTE terms — no
            /// offsetMs arithmetic needed (unlike before this change,
            /// when the UI dealt in playlist-local terms).
            override fun seekTo(positionMs: Long) = handleSeek(positionMs)
            override fun seekForward() = handleSeek(offsetMs + realPlayer.currentPosition + realPlayer.seekForwardIncrement)
            override fun seekBack() = handleSeek((offsetMs + realPlayer.currentPosition - realPlayer.seekBackIncrement).coerceAtLeast(0))

            /// realPlayer's own duration only covers the HLS EVENT
            /// playlist's currently-produced window (it grows as the
            /// hub's pipeline keeps encoding) — reporting that straight
            /// to PlayerView's DefaultTimeBar is why the seekbar showed
            /// only a sliver instead of the whole video, with no way to
            /// drag to, say, the 30-minute mark before the hub has
            /// produced that far. The hub tells us the real total
            /// (session.durationMs) up front; reporting it directly here
            /// gives the seekbar its true, full length. Every seek
            /// round-trips through the hub regardless of target (see
            /// seekTo above), so dragging past what's currently produced
            /// is exactly as valid as dragging inside it.
            ///
            /// getDuration()/getContentDuration() alone (tried first)
            /// turned out not to be enough: disassembling
            /// PlayerControlView.class (Media3's source isn't vendored
            /// here) showed its time-bar update reads duration straight
            /// off Timeline.Window/Timeline.Period.durationUs — summed
            /// from getCurrentTimeline() — and only falls back to
            /// getContentDuration() when the timeline is completely
            /// empty. A real (short) HLS timeline always exists here, so
            /// that fallback path never runs; the fix has to happen in
            /// the Timeline itself, via getCurrentTimeline() below.
            override fun getDuration(): Long = contentDurationOrSuper()

            override fun getContentDuration(): Long = contentDurationOrSuper()

            private fun contentDurationOrSuper(): Long =
                if (isDirect) super.getDuration() else session?.durationMs ?: super.getDuration()

            /// Companions to getDuration() above: PlayerControlView reads
            /// getContentPosition()/getContentBufferedPosition() (a
            /// Player-level pair, not derived from the Timeline, unlike
            /// duration) straight into the time bar's scrubber and
            /// buffered-shading. Without these, position/buffered stay
            /// playlist-local (always starting near 0 for a fresh
            /// attach) while duration reports the full video — putting
            /// the scrubber at the wrong end of a bar that's otherwise
            /// the right length, and shading "buffered" from the video's
            /// start rather than from wherever playback actually is.
            /// offsetMs reconstructs the absolute position the same way
            /// reportProgressNow()/seekForward()/seekBack() already do;
            /// "direct" mode needs no reconstruction since it's a real
            /// byte-range file with true native seeking, already
            /// absolute.
            override fun getCurrentPosition(): Long = contentPositionOrSuper()

            override fun getContentPosition(): Long = contentPositionOrSuper()

            private fun contentPositionOrSuper(): Long =
                if (isDirect) super.getCurrentPosition() else offsetMs + realPlayer.currentPosition

            override fun getBufferedPosition(): Long = contentBufferedPositionOrSuper()

            override fun getContentBufferedPosition(): Long = contentBufferedPositionOrSuper()

            /// The END of what a seek can reach without waiting on the
            /// hub — [seekWindowStartMs] is its start, and SeekWindowTimeBar
            /// shades the range between them. NOT the handful of seconds
            /// ExoPlayer has downloaded, which is what realPlayer reports
            /// and which says nothing about what a seek costs: for an HLS
            /// session everything up to the produced edge is a plain local
            /// seek and everything past it a pipeline restart (see
            /// localSeekPositionMs), and realPlayer's playlist duration IS
            /// that produced edge. A direct session has no pipeline to
            /// restart — it's a byte-range-seekable file, reachable end to
            /// end. The downloaded position stays the floor for the moment
            /// before the playlist's length is known.
            private fun contentBufferedPositionOrSuper(): Long {
                if (isDirect) return super.getDuration()
                val downloadedMs = offsetMs + realPlayer.bufferedPosition
                val producedMs = realPlayer.duration.takeIf { it != C.TIME_UNSET } ?: return downloadedMs
                return maxOf(downloadedMs, offsetMs + producedMs)
            }

            override fun isCurrentMediaItemSeekable(): Boolean =
                if (isDirect) super.isCurrentMediaItemSeekable() else true

            /// ExoPlayer's own live detection (no #EXT-X-ENDLIST while
            /// the hub is still producing, see attach()'s
            /// LiveConfiguration note) would otherwise have PlayerView
            /// treat this as a live broadcast — a "LIVE" badge instead
            /// of a normal seekbar. It's a recording being produced on
            /// demand, not a broadcast — always report it as VOD.
            override fun isCurrentMediaItemLive(): Boolean = false

            /// The actual fix for the seekbar's length: PlayerControlView
            /// (and DefaultTimeBar underneath it) get the video's total
            /// duration by summing Timeline.Period.durationUs across the
            /// current Timeline.Window — never by calling a Player-level
            /// getter — so overriding getDuration()/getContentDuration()
            /// above was necessary but not sufficient. This wraps
            /// whatever (short, HLS-window-limited) Timeline realPlayer
            /// currently reports and rewrites the duration fields to the
            /// hub's real, absolute total, leaving everything else (ad
            /// state, period ids/uids, window count) untouched passthrough.
            override fun getCurrentTimeline(): Timeline {
                val real = super.getCurrentTimeline()
                if (real.isEmpty || isDirect) return real
                val total = session?.durationMs ?: return real
                return DurationOverrideTimeline(real, total * 1000L)
            }
        }
    }

    /// Whether the current session serves the file's own byte-range
    /// timeline directly, rather than a growing HLS playlist from a
    /// remux/transcode pipeline. Centralised here because the ForwardingPlayer
    /// overrides below used to each spell `session?.mode == "direct"`
    /// slightly differently (==, !=, takeIf) — harmless today, but exactly
    /// the kind of repetition where a future fix lands in five of the six
    /// spots and not the sixth.
    private val isDirect: Boolean
        get() = session?.mode == "direct"

    /// The audio stream every start of this item uses — resolved once from
    /// the preferences (see start()) and then held, so a recovery restart
    /// doesn't quietly fall back to track 0.
    private var audioTrack: Int = initialAudioTrack.coerceAtLeast(0)

    /// The scope track picks are remembered under — see start().
    private var seriesId: String? = null
    private var prefsJob: Job? = null

    /// The START of what a seek can reach without waiting on the hub: the
    /// absolute position the current playlist begins at, since nothing
    /// before it is in the playlist at all (a rewind past it restarts the
    /// pipeline). Zero for a direct session, which is seekable end to end.
    /// SeekWindowTimeBar reads this live to shade the bar; the range's end
    /// is player.bufferedPosition.
    val seekWindowStartMs: Long
        get() = if (isDirect) 0 else offsetMs

    private var session: StartSessionResponse? = null
    private var offsetMs: Long = 0
    private var progressJob: Job? = null
    private var seekJob: Job? = null
    private var subtitleEpoch: Int = 0
    private var playbackEndedHandled = false

    /// Guards [attemptSessionRecovery] to one retry per failure — reset
    /// on a successful recovery so a LATER reap (e.g. the device going
    /// back to sleep for hours again) still gets its own one-shot retry,
    /// rather than every error after the first going straight to terminal.
    private var recoveryAttempted = false

    /// True once this playback has reached STATE_READY at least once.
    /// [attemptSessionRecovery] only spends a retry (and the hub session
    /// slot that costs) on errors that happen AFTER that — the "hub
    /// reaped a stale session" scenario the retry exists for. A file
    /// that errors before ever reaching READY is broken outright (bad
    /// codec, corrupt source, etc.): retrying it just burns a second
    /// session on the same unplayable file instead of surfacing the
    /// error immediately. Never reset once true — it's a property of
    /// "this media decodes on this device", not of the current session.
    private var everReachedReady = false

    /// The offsetMs value baked into the current MediaItem's sideloaded
    /// VTT configs as `shift_ms` (see textDeliverySubtitleConfigs). When
    /// syncOrigin later corrects offsetMs, the baked shift is stale by
    /// the keyframe-snap delta — rebakeTextSubtitleShiftIfNeeded()
    /// compares against this to decide whether a re-prepare is owed.
    private var attachedVttShiftMs: Long = 0

    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks

    /// null = "Off". Only Text-delivery selection changes ExoPlayer's own
    /// track override; Ass/Overlay delivery is read entirely by
    /// AssSubtitleOverlay/ImageSubtitleOverlay, driven off this + [subtitleSession].
    private val _selectedSubtitleTrack = MutableStateFlow<SubtitleTrack?>(null)
    val selectedSubtitleTrack: StateFlow<SubtitleTrack?> = _selectedSubtitleTrack

    private val _subtitleSession = MutableStateFlow<SubtitleSession?>(null)
    val subtitleSession: StateFlow<SubtitleSession?> = _subtitleSession

    /// HUB-37: the recap/intro/credits boundaries the hub's background
    /// sweep found for this item, and the file's own chapters — both
    /// resolved alongside the subtitle list below, off the same QUERY
    /// round trip (see start()). Empty means "nothing found" and
    /// "nothing analysed yet" alike; PlayerScreen can't act on the
    /// difference, same as the hub's own contract.
    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    val segments: StateFlow<List<Segment>> = _segments

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters

    init {
        realPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) reportProgressNow()
                updatePausedState()
            }

            /// Diagnostic only: if the "start of video jumps forward"
            /// behavior recurs after the LiveConfiguration fix in
            /// attach(), this pins down whether ExoPlayer itself is
            /// issuing the jump (reason would be INTERNAL, not SEEK —
            /// SEEK is what our own handleSeek()/user drags produce) and
            /// what position it's landing on, rather than guessing at
            /// Media3 internals again.
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (kotlin.math.abs(newPosition.positionMs - oldPosition.positionMs) > 1000) {
                    Log.d(
                        TAG,
                        "position discontinuity reason=$reason old=${oldPosition.positionMs} new=${newPosition.positionMs}",
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // sessionId (not itemId) is what names the hub's scratch
                // dir (<data_dir>/sessions/<sessionId>/worker.log) — the
                // thing to check when this is a hub-side pipeline stall,
                // not a client bug.
                Log.e(TAG, "Playback failed for item=$itemId sessionId=${session?.sessionId}", error)
                attemptSessionRecovery(error)
            }

            /// Diagnostic only: pins down whether a "seek far ahead ->
            /// black screen" report is ExoPlayer stuck BUFFERING forever
            /// (no onPlayerError ever fires, so PlayerState.Error never
            /// shows — matching a silent black screen) versus something
            /// that never even reaches the player (state stays whatever
            /// it was, no transition logged at all).
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(
                    TAG,
                    "playback state -> $playbackState (item=$itemId) " +
                        "currentPos=${realPlayer.currentPosition} bufferedPos=${realPlayer.bufferedPosition} " +
                        "playWhenReady=${realPlayer.playWhenReady}",
                )
                if (playbackState == Player.STATE_READY) everReachedReady = true
                if (playbackState == Player.STATE_ENDED) handlePlaybackEnded()
                updatePausedState()
            }

            /// Sideloaded subtitle track groups (attach()'s
            /// setSubtitleConfigurations) don't exist the instant
            /// prepare() returns — they show up here once metadata loads.
            /// Reapplying on every change is what carries a Text-delivery
            /// selection forward across a seek-restart's fresh MediaItem
            /// (a plain post-prepare() call would silently see no groups yet).
            override fun onTracksChanged(tracks: Tracks) {
                applySubtitleTrackSelectionOverride()
                applyAudioTrackSelectionOverride()
                _hdrActive.value = isHdrTransfer(selectedVideoFormat(tracks))
            }
        })
        start()
    }

    /// Starts a fresh hub session at [positionMs] and attaches the player
    /// to it: negotiates the session, adopts its subtitle listing (see
    /// [applySessionSubtitleListing]), resolves [offsetMs]/startPositionMs
    /// via [resumePlan], and [attach]es. start(), attemptSessionRecovery()
    /// and restartSessionWithSubtitle() all do exactly this dance, just
    /// wrapped in different error handling — pulled out once so a fix to
    /// any step here (there have been several) lands in one place instead
    /// of needing to be copied into three.
    ///
    /// offsetMs is set to the ABSOLUTE position this run's playlist starts
    /// at, i.e. [positionMs] itself (confirmed against the hub:
    /// execute_seek/start() always hands the pipeline local_ms = abs_ms -
    /// part.base_ms, and the produced playlist's own t=0 is exactly that
    /// local_ms — so in absolute terms, t=0 always equals the FULL
    /// requested position, not just the multi-part remainder).
    /// startPositionMs is correspondingly always 0 for a non-direct
    /// session: this fresh playlist's own beginning IS where we asked the
    /// pipeline to start, mirroring the web client's unconditional
    /// `startPosition: 0` for hls.js. syncOrigin() (kicked off from
    /// attach()) refines offsetMs once the true keyframe-snapped origin is
    /// known; it doesn't touch startPositionMs, which never needs
    /// correcting.
    ///
    /// "direct" mode is the opposite arrangement: the file's native
    /// timeline IS absolute, so offsetMs must stay 0 (offsetMs +
    /// currentPosition is how every absolute position is reconstructed —
    /// progress reports, the seek overrides, subtitle shift) and resuming
    /// partway is a real player seek to [positionMs], not a pipeline
    /// restart. See [resumePlan].
    private suspend fun startSessionAndAttach(
        profile: CapabilityProfile,
        positionMs: Long,
        subtitleTrackId: Long?,
    ): StartSessionResponse {
        val newSession = repo.startSession(
            itemId,
            profile,
            positionMs,
            audioTrack = audioTrack,
            subtitleTrack = subtitleTrackId,
        )
        session = newSession
        applySessionSubtitleListing(newSession)
        Log.d(
            TAG,
            "session started item=$itemId requestedPositionMs=$positionMs mode=${newSession.mode} " +
                "durationMs=${newSession.durationMs} partBaseMs=${newSession.partBaseMs}",
        )
        val plan = resumePlan(newSession.mode, positionMs)
        offsetMs = plan.offsetMs
        attach(newSession, startPositionMs = plan.startPositionMs, requestedAbsMs = positionMs)
        return newSession
    }

    private fun start() {
        _state.value = PlayerState.Loading
        viewModelScope.launch {
            try {
                val detail = catalogRepo.item(itemId)
                _title.value = if (detail.kind == "episode" && detail.season != null && detail.episode != null) {
                    val seasonEpisode = "S%02dE%02d".format(detail.season, detail.episode)
                    listOfNotNull(seasonEpisode, detail.title, detail.parentTitle).joinToString(" - ")
                } else {
                    detail.title
                }
                val parentId = detail.parentId
                if (detail.kind == "episode" && parentId != null) {
                    val siblings = catalogRepo.children(parentId)
                    _adjacentEpisodes.value = AdjacentEpisodes(
                        previousId = resolvePreviousEpisode(detail.kind, parentId, itemId, siblings),
                        nextId = resolveNextEpisode(detail.kind, parentId, itemId, siblings),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to resolve title/adjacent episodes for item=$itemId", e)
            }
        }
        viewModelScope.launch {
            try {
                val profile = CapabilityProfileBuilder.build(getApplication())
                val tracks: List<SubtitleTrack>
                val initialTrack: SubtitleTrack?
                val prefetched = prefetch
                if (prefetched != null) {
                    // The Detail screen already ran this exact QUERY (and
                    // the same preference resolution, for both audio and
                    // subtitle — see DetailViewModel.load()) before the
                    // user ever pressed Play. Reusing it here means
                    // startSession below is the FIRST network call this
                    // screen makes, not the second — no itemQuery round
                    // trip in front of it. initialAudioTrack/
                    // initialSubtitleTrackId already carry what Detail
                    // resolved (see PlaybackPrefetch), so there's no
                    // preference fallback to fall through to here — unlike
                    // the no-prefetch branch below, an id that doesn't
                    // match is exactly "no subtitle", not "unresolved".
                    tracks = prefetched.subtitleTracks
                    _subtitleTracks.value = tracks
                    _segments.value = prefetched.segments
                    _chapters.value = prefetched.chapters
                    seriesId = prefetched.parentId ?: itemId
                    initialTrack = tracks.firstOrNull { it.id == initialSubtitleTrackId }
                } else {
                    // No prefetch (auto-advance, the in-player "<"/">"
                    // buttons) — resolved BEFORE startSession, not
                    // alongside it: the request below may only carry
                    // subtitle_track for a BURN-delivery pick (see the
                    // burnPickOrNull note), and delivery is only knowable
                    // from this list. Best-effort: a failure here means no
                    // subtitle picker entries and no skip/chapter marks,
                    // not a broken playback session, so it's swallowed
                    // rather than failing the whole start(). One QUERY
                    // carries all three (HUB-37: "the subtitle listing
                    // above rides along for the same reason"). Both
                    // best-effort and both started before the QUERY they
                    // overlap: a preference that failed to load costs the
                    // remembered subtitle pick, not the session.
                    val prefs = async { runCatching { prefsRepo.all() }.getOrDefault(emptyList()) }
                    // Lazy, unlike the preferences beside it: the media
                    // type only matters for the account's per-type list,
                    // which most titles never reach (see needsMediaType).
                    val libraries = async(start = CoroutineStart.LAZY) {
                        runCatching { catalogRepo.libraries() }.getOrDefault(emptyList())
                    }
                    suspend fun mediaType(known: List<Pref>): String =
                        if (needsMediaType(known) && libraryId != null) {
                            libraries.await().firstOrNull { it.id == libraryId }?.mediaType ?: ""
                        } else {
                            ""
                        }
                    val queried = try {
                        repo.itemQuery(itemId, profile)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to query item=$itemId for subtitles/segments/chapters", e)
                        null
                    }
                    tracks = queried?.negotiated?.subtitles ?: emptyList()
                    _subtitleTracks.value = tracks
                    _segments.value = queried?.segments ?: emptyList()
                    _chapters.value = queried?.chapters ?: emptyList()
                    // The scope a pick is remembered under (HUB-33): the
                    // show, so every episode of it opens the same way.
                    // Falls back to this item's own id — which is what a
                    // film's scope is anyway, and all an episode whose
                    // QUERY failed can offer.
                    seriesId = queried?.parentId ?: itemId
                    // Nothing carried, or an id this item doesn't have,
                    // falls to the remembered preferences. The second case
                    // is the one that matters: auto-advance carries the
                    // track id of the episode that just ended, and ids
                    // don't survive the file boundary — which is exactly
                    // what the series-scoped language memory is for.
                    if (initialAudioTrack < 0) {
                        audioTrack = prefs.await().let { known ->
                            resolveAudioTrack(
                                prefs = known,
                                seriesId = seriesId ?: itemId,
                                itemId = itemId,
                                mediaType = mediaType(known),
                                originalLanguage = queried?.metadata?.originalLanguage,
                                audio = queried?.sources?.firstOrNull()?.streams?.audio ?: emptyList(),
                            )
                        }
                    }
                    initialTrack = tracks.firstOrNull { it.id == initialSubtitleTrackId }
                        ?: prefs.await().let { known ->
                            resolveSubtitleTrack(
                                prefs = known,
                                seriesId = seriesId ?: itemId,
                                itemId = itemId,
                                mediaType = mediaType(known),
                                tracks = tracks,
                            )
                        }
                }
                _selectedSubtitleTrack.value = initialTrack
                // Logged like the session start below: which track a title
                // opens with is preference resolution across three scopes
                // and a media type the route had to carry, none of which is
                // visible from the outcome alone.
                Log.d(
                    TAG,
                    "tracks resolved item=$itemId series=$seriesId library=$libraryId prefetched=${prefetched != null} " +
                        "audio=$audioTrack subtitle=${initialTrack?.id} (carried sub=$initialSubtitleTrackId)",
                )
                // See startSessionAndAttach's doc for the offsetMs/
                // startPositionMs reasoning (direct vs remux/transcode).
                startSessionAndAttach(profile, startMs, burnPickOrNull(initialTrack))
                _state.value = PlayerState.Ready
                startProgressLoop()
            } catch (e: Exception) {
                _state.value = PlayerState.Error(e.readableMessage())
            }
        }
    }

    /// A player error AFTER playback was already under way is very often
    /// not a real failure but the hub's own session reaper having torn
    /// the pipeline (or, for "direct", the session record itself) down
    /// out from under a client that stopped reporting progress — e.g. the
    /// device went to sleep mid-episode and came back hours later (hub
    /// sessions are reaped quickly once progress reports stop, see
    /// kahawai-debug-setup memory notes). Whatever ExoPlayer had already
    /// buffered plays out fine; the error only surfaces once it needs a
    /// segment/byte-range the hub can no longer serve. Rather than
    /// tearing the whole screen down for something a fresh startSession()
    /// at the same position fixes outright, this makes exactly ONE retry
    /// before giving up — same recipe as restartSessionWithSubtitle,
    /// minus the picker-revert bookkeeping that only applies to a
    /// user-driven subtitle switch.
    ///
    /// [everReachedReady] gates that retry: an error that arrives before
    /// playback ever got going is a broken/unplayable file, not a reaped
    /// session — retrying would just start a SECOND session against the
    /// same file, which fails the exact same way while also eating one
    /// of the hub's limited concurrent-session slots. Every path out of
    /// here (immediate terminal, or replaced by a recovered session)
    /// explicitly ends [failedSession] rather than leaving it for the
    /// hub's reaper — that's what was filling up the session limit.
    private fun attemptSessionRecovery(error: PlaybackException) {
        val failedSession = session
        if (recoveryAttempted || failedSession == null || !everReachedReady) {
            progressJob?.cancel()
            _state.value = PlayerState.Error(getApplication<Application>().getString(R.string.player_playback_failed, error.readableMessage()))
            endSessionBestEffort(failedSession?.sessionId)
            return
        }
        recoveryAttempted = true
        val positionMs = offsetMs + realPlayer.currentPosition
        Log.w(TAG, "attempting session recovery item=$itemId oldSessionId=${failedSession.sessionId} positionMs=$positionMs")
        viewModelScope.launch {
            try {
                val profile = CapabilityProfileBuilder.build(getApplication())
                val newSession = startSessionAndAttach(profile, positionMs, burnPickOrNull(_selectedSubtitleTrack.value))
                _state.value = PlayerState.Ready
                recoveryAttempted = false
                Log.w(TAG, "session recovery succeeded item=$itemId newSessionId=${newSession.sessionId}")
                endSessionBestEffort(failedSession.sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "session recovery failed item=$itemId", e)
                progressJob?.cancel()
                _state.value = PlayerState.Error(getApplication<Application>().getString(R.string.player_playback_failed, e.readableMessage()))
                endSessionBestEffort(failedSession.sessionId)
            }
        }
    }

    /// Releases a session the moment we're done with it — recovery giving
    /// up, or a recovered session replacing it — instead of leaving it
    /// occupying one of the hub's limited concurrent-session slots until
    /// its own reaper eventually notices no more progress reports are
    /// coming. Fire-and-forget/best-effort, same as onCleared(): the
    /// reaper is still the backstop if this call itself fails.
    private fun endSessionBestEffort(sessionId: String?) {
        sessionId ?: return
        viewModelScope.launch {
            try {
                repo.endSession(sessionId)
            } catch (e: Exception) {
                // Best-effort; the hub's own session reaper is the backstop.
            }
        }
    }

    private fun updatePausedState() {
        _isPaused.value = !internalPause && !realPlayer.playWhenReady && realPlayer.playbackState == Player.STATE_READY
    }

    /// Called when the activity is genuinely backgrounded (Home, recents —
    /// see PlayerScreen's ON_STOP observer), not just user-paused. A plain
    /// pause() leaves playbackState at STATE_READY, and ExoPlayer's
    /// handleAudioFocus only abandons audio focus once playbackState hits
    /// STATE_IDLE (see AudioFocusManager.shouldHandleAudioFocus) — so a
    /// merely-paused player stays registered as the platform's audio focus
    /// listener for as long as it sits in the background. Any unrelated
    /// AUDIOFOCUS_GAIN the system later delivers to that listener (observed
    /// when switching the TV's input) makes ExoPlayer force playWhenReady
    /// back to true on its own (AudioFocusManager.handlePlatformAudioFocusChange
    /// -> executePlayerCommand(PLAYER_COMMAND_PLAY_WHEN_READY)), resuming
    /// playback nothing in this app asked to resume. stop() drives
    /// playbackState to STATE_IDLE, which makes ExoPlayer abandon audio
    /// focus for good, while leaving the MediaItem and position intact —
    /// onForegrounded() only needs to prepare() again.
    ///
    /// The actual stop() is delayed by [BACKGROUND_STOP_DELAY_MS] rather
    /// than run immediately: some TV boxes/launchers flash a system
    /// overlay over the app for a frame or two (e.g. right as playback
    /// starts), which fires a real ON_STOP/ON_START pair even though the
    /// user never left. Paying the stop()+prepare() re-buffer for that is
    /// pure regression — a visibly snappier start turns into two loading
    /// spinners back to back. If onForegrounded() arrives before the delay
    /// elapses, [backgroundJob] is cancelled and playback was never
    /// actually stopped, so there's nothing to re-buffer. A genuine
    /// backgrounding (the user actually leaving) comfortably outlasts this
    /// short window, so the audio-focus fix above still applies.
    private var backgrounded = false
    private var backgroundJob: Job? = null

    fun onBackgrounded() {
        if (realPlayer.playbackState == Player.STATE_IDLE) return
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch {
            delay(BACKGROUND_STOP_DELAY_MS)
            backgrounded = true
            realPlayer.playWhenReady = false
            realPlayer.stop()
        }
    }

    /// Mirrors onBackgrounded(): re-buffers at the position stop() left
    /// behind. playWhenReady is already false from onBackgrounded(), so
    /// this brings the player back to a paused, ready-to-resume state
    /// rather than auto-playing. Gated on [backgrounded] rather than a
    /// bare STATE_IDLE check: lifecycle's addObserver() replays a
    /// synthetic ON_START the instant PlayerScreen's observer registers
    /// (Activity is already STARTED by then), which fires before attach()
    /// has ever set a MediaItem — realPlayer is still its fresh,
    /// just-built STATE_IDLE at that point too. Without this gate that
    /// prepare()s an empty playlist, which ExoPlayer resolves straight to
    /// STATE_ENDED and triggers handlePlaybackEnded() before playback ever
    /// starts (auto-advancing episodes in a rapid loop).
    fun onForegrounded() {
        backgroundJob?.cancel()
        backgroundJob = null
        if (backgrounded) {
            backgrounded = false
            realPlayer.prepare()
        }
    }

    /// STATE_ENDED can fire more than once (e.g. a stray onPlaybackStateChanged
    /// replay), so [playbackEndedHandled] guards against issuing the
    /// children() lookup twice. The current session/progress teardown is
    /// left to onCleared() — it already runs the moment PlayerScreen
    /// navigates away to the next episode's fresh (differently-keyed)
    /// ViewModel, so nothing extra is needed here beyond stopping the
    /// progress loop.
    private fun handlePlaybackEnded() {
        if (playbackEndedHandled) return
        playbackEndedHandled = true
        progressJob?.cancel()
        viewModelScope.launch {
            try {
                val detail = catalogRepo.item(itemId)
                val parentId = detail.parentId
                if (detail.kind != "episode" || parentId == null) {
                    _playbackFinished.value = true
                    return@launch
                }
                val siblings = catalogRepo.children(parentId)
                val nextId = resolveNextEpisode(detail.kind, parentId, itemId, siblings)
                if (nextId != null) {
                    Log.d(TAG, "auto-advancing item=$itemId -> next=$nextId")
                    _nextEpisodeId.value = nextId
                } else {
                    _playbackFinished.value = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to resolve next episode after item=$itemId ended", e)
                _playbackFinished.value = true
            }
        }
    }

    private fun handleSeek(targetMs: Long) {
        val session = session ?: return
        if (session.mode == "direct") {
            realPlayer.seekTo(targetMs)
            return
        }
        Log.d(TAG, "seek requested item=$itemId sessionId=${session.sessionId} targetMs=$targetMs currentOffsetMs=$offsetMs")
        // Already inside the produced window: seek there directly. A hub
        // seek tears down and restarts the whole remux/transcode pipeline
        // and re-attaches a fresh playlist — seconds of black frames for
        // content this playlist can already reach. Skipped while a hub
        // seek is in flight: that one has already restarted the pipeline
        // somewhere else, so this playlist (and the offsetMs this target
        // was measured against) is on its way out.
        if (seekJob?.isActive != true) {
            localSeekPositionMs(targetMs, offsetMs, realPlayer.duration)?.let { localMs ->
                Log.d(TAG, "seek served locally item=$itemId localMs=$localMs")
                realPlayer.seekTo(localMs)
                // The hub paces production against the last reported
                // viewer position (kahawai-media's install_pace_probe),
                // and nothing else here tells it the playhead moved —
                // waiting up to a progress tick to mention a jump
                // forward just stalls the encoder that much longer.
                reportProgressNow()
                return
            }
        }
        // Seeks must not overlap: each one is pause -> hub round trip ->
        // offsetMs/attach, and two in flight at once (rapid double-taps,
        // repeated scrubber drags) race on whose response lands last —
        // the loser's attach()/offsetMs can describe a pipeline position
        // the hub isn't actually at anymore, desyncing subtitles and the
        // seekbar until the next seek. Cancelling the previous seek
        // aborts its in-flight HTTP call (suspend Retrofit), and joining
        // it before issuing ours keeps hub-side execution in the same
        // order as client-side attaches. CancellationException must
        // propagate untouched: a superseded seek is being replaced, so
        // its catch block must neither resume playback nor report an
        // error on the newer seek's behalf.
        val previousSeek = seekJob
        seekJob = viewModelScope.launch {
            previousSeek?.cancelAndJoin()
            try {
                internalPause = true
                realPlayer.pause()
                // No subtitle_track on seeks (mirrors the web client):
                // the hub's session already remembers an active burn
                // pick across seek-restarts, and its seek endpoint
                // treats any IMAGE-format track id as a NEW burn order —
                // sending the current selection here is what silently
                // converted an "overlay"-delivery PGS pick into a
                // burned-in encode on the first seek, leaving the video
                // with baked-in subtitles UNDER this client's own
                // overlay rendering of the same track (two copies,
                // independently timed — the "PGS out of sync" report),
                // with no way to switch off the burned copy afterwards.
                val result = repo.seek(session.sessionId, targetMs)
                // See start()'s comment: offsetMs is the ABSOLUTE
                // position this fresh playlist starts at (targetMs
                // itself), not result.partBaseMs — using partBaseMs here
                // (previously ~0 for single-part content) made
                // startPositionMs evaluate to targetMs verbatim, asking
                // ExoPlayer to seek to a LOCAL position far outside the
                // few-minutes-long fresh playlist's own timeline — it
                // would sit there forever waiting for local content that
                // was never going to arrive, which is exactly the "seek
                // far ahead hangs at BUFFERING" bug.
                offsetMs = targetMs
                Log.d(TAG, "seek accepted by hub item=$itemId newPartBaseMs=${result.partBaseMs}")
                attach(session, startPositionMs = 0, requestedAbsMs = targetMs)
                internalPause = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The old stream is untouched (we only paused) — resume it
                // where it was and let the user retry the seek.
                Log.w(TAG, "seek failed item=$itemId targetMs=$targetMs", e)
                internalPause = false
                realPlayer.play()
                _transientError.value = getApplication<Application>().getString(R.string.player_seek_failed, e.readableMessage())
            }
        }
    }

    /// A burn pick needs the hub to (re)negotiate the whole plan — the
    /// lightweight seek()-restart above assumes an EXISTING remux/
    /// transcode pipeline (`session.plan`), which a "direct"-mode
    /// session never has (direct is a raw byte passthrough; there's no
    /// pipeline to seek within). A session that started direct because
    /// no subtitle was picked yet can't be talked into burning one via
    /// seek() at all, so a burn pick/un-pick instead tears down and
    /// starts a brand new session at the current position — the same
    /// call start() makes, just resuming instead of from startMs.
    /// The only subtitle pick the hub may ever hear about: session start
    /// and seek both interpret an IMAGE-format track id as "burn this
    /// into the video" (crates/kahawai-hub/src/api.rs StartSessionRequest,
    /// sessions.rs seek()) — the hub cannot know this client renders
    /// "overlay" delivery itself. Text/ass picks are hub-side no-ops, so
    /// nothing but an explicit "burn"-delivery choice belongs on the wire.
    private fun burnPickOrNull(track: SubtitleTrack?): Long? =
        track?.takeIf { it.delivery == "burn" }?.id

    /// The session is the delivery authority the instant it starts (see
    /// [StartSessionResponse.subtitleListing]'s doc): the item QUERY's
    /// listing that seeded the picker reflects the profile at page load,
    /// and after a capability-masked restart the two can disagree — a
    /// client still reading the QUERY's copy could keep rendering ASS
    /// client-side while the session actually expects a burn. Called
    /// right after every startSession, before attach() builds the
    /// MediaItem off [_subtitleTracks], so text/ass/overlay sideloading
    /// always reflects THIS session. Re-resolves the current pick by id
    /// against the fresh list rather than trusting its old delivery.
    private fun applySessionSubtitleListing(session: StartSessionResponse) {
        val fresh = session.subtitleListing.ifEmpty { return }
        val selectedId = _selectedSubtitleTrack.value?.id
        _subtitleTracks.value = fresh
        _selectedSubtitleTrack.value = fresh.firstOrNull { it.id == selectedId }
    }

    private fun restartSessionWithSubtitle(subtitleTrackId: Long?, revertTo: SubtitleTrack?) {
        viewModelScope.launch {
            try {
                internalPause = true
                realPlayer.pause()
                val positionMs = offsetMs + realPlayer.currentPosition
                val profile = CapabilityProfileBuilder.build(getApplication())
                val oldSessionId = session?.sessionId
                // See startSessionAndAttach's doc on offsetMs/startPositionMs
                // — un-picking a burn can legitimately come back as a
                // "direct" session, so both arrangements are reachable here.
                val newSession = startSessionAndAttach(profile, positionMs, subtitleTrackId)
                internalPause = false
                if (oldSessionId != null && oldSessionId != newSession.sessionId) {
                    launch {
                        try {
                            repo.endSession(oldSessionId)
                        } catch (e: Exception) {
                            // Best-effort; the hub's own session reaper is the backstop.
                        }
                    }
                }
            } catch (e: Exception) {
                // The new session never started, so the old one is still
                // valid — resume it and roll the picker back to the
                // selection that's actually playing.
                _selectedSubtitleTrack.value = revertTo
                internalPause = false
                realPlayer.play()
                _transientError.value = getApplication<Application>().getString(R.string.player_subtitle_switch_failed, e.readableMessage())
            }
        }
    }

    private fun buildMediaItem(session: StartSessionResponse): MediaItem {
        val uri = ApiClient.baseUrl().trimEnd('/') + session.streamUrl
        // Util.inferContentTypeForUriAndMimeType only recognizes HLS via an
        // exact match on the literal "application/x-mpegURL" — the hub's
        // session.contentType for remux/transcode is the more standard
        // "application/vnd.apple.mpegurl" (or similar), which silently
        // fails that match and sends DefaultMediaSourceFactory down the
        // generic container-extractor path against what's actually an
        // .m3u8 playlist (surfaces as "none of the extractors could read
        // the stream" — there's no HLS extractor in that list at all).
        // session.mode is the reliable signal already used in handleSeek:
        // "direct" is a real byte-range file (session.contentType is
        // accurate there), anything else is the HLS master playlist.
        val mimeType = if (session.mode == "direct") session.contentType else MimeTypes.APPLICATION_M3U8
        val subtitleConfigs = textDeliverySubtitleConfigs()
        Log.d(TAG, "attach item=$itemId textDeliverySubtitleCount=${subtitleConfigs.size} ids=${subtitleConfigs.map { it.id }}")
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .setSubtitleConfigurations(subtitleConfigs)
        if (session.mode != "direct") {
            // The hub's EVENT playlist has no #EXT-X-ENDLIST until the
            // whole file has finished producing (kahawai-media's remux
            // pipeline only appends it at EOS), so ExoPlayer's HLS
            // implementation treats it as a live stream while it's still
            // growing — computing its own "live edge" target position
            // and nudging playback toward it, fighting the explicit
            // startPositionMs passed to setMediaItem below (this is what
            // made playback visibly skip forward a few seconds shortly
            // after starting, even from position 0). The web client hits
            // the exact same thing and disables it outright in hls.js
            // (liveSyncDurationCount/liveMaxLatencyDurationCount, see
            // Player.tsx's `attach` — "Watch from the beginning and never
            // chase").
            //
            // targetOffsetMs means "how far BEHIND the live edge should
            // playback sit" — left unset, Media3 computes its own small
            // heuristic default, which is what caused the original
            // "jumps to 9 seconds" bug. For a GROWING RECORDING, "the
            // start of the video" is the far end of that spectrum, so a
            // large-ish target forces the computed default position to
            // clamp down to the window's actual start instead of landing
            // a few seconds in.
            //
            // A previous version of this set targetOffsetMs/maxOffsetMs
            // to 24 HOURS — which fixed the jump, but (evidenced by a
            // seek-restart never reaching STATE_READY, hanging in
            // STATE_BUFFERING indefinitely, while a fresh start with the
            // SAME config reaches READY in ~200ms every time) also made
            // ExoPlayer treat 24 hours as a buffering target on a
            // seek-restart specifically — a physically impossible amount
            // to accumulate, so it never left BUFFERING. That, in turn,
            // meant it never played, so it never sent a progress report;
            // the hub's own post-seek throttle (kahawai-media's
            // install_pace_probe) gates further production on the
            // client's reported viewer position catching up to within a
            // window of what's already produced — so the client waiting
            // on the hub and the hub waiting on the client deadlocked
            // permanently. Three minutes is comfortably past what the
            // original few-seconds bug needed, and comfortably short of
            // "impossible to buffer" — long enough to survive the fix,
            // short enough not to recreate this deadlock.
            val liveOffsetMs = TimeUnit.MINUTES.toMillis(3)
            mediaItemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .setTargetOffsetMs(liveOffsetMs)
                    .setMinOffsetMs(0)
                    .setMaxOffsetMs(liveOffsetMs)
                    .build(),
            )
        }
        return mediaItemBuilder.build()
    }

    private fun attach(session: StartSessionResponse, startPositionMs: Long, requestedAbsMs: Long) {
        realPlayer.setMediaItem(buildMediaItem(session), startPositionMs)
        realPlayer.prepare()
        realPlayer.playWhenReady = true
        attachedVttShiftMs = offsetMs
        // Track groups for the just-set MediaItem don't exist yet (they
        // load asynchronously) — the onTracksChanged listener in init{}
        // reapplies the pinned selection once they do. Nothing to do here
        // beyond that; a stale override from the PREVIOUS MediaItem can't
        // linger since setMediaItem/prepare drops it.
        // The stream URL's last path segment is the playlist/manifest
        // file (master.m3u8 for remux/transcode); the session's
        // out-of-band taps (subs-{id}.ass / subs-{id}.jsonl) live
        // alongside it. epoch bumps on every attach() (initial + every
        // seek-restart) so AssSubtitleOverlay/ImageSubtitleOverlay know
        // to reconnect even though streamBaseUrl itself doesn't change
        // within one session.
        subtitleEpoch++
        val uri = ApiClient.baseUrl().trimEnd('/') + session.streamUrl
        val streamBaseUrl = uri.substringBeforeLast('/') + "/"
        _subtitleSession.value = SubtitleSession(
            streamBaseUrl = streamBaseUrl,
            isHls = session.mode != "direct",
            epoch = subtitleEpoch,
        )
        // offsetMs (partBaseMs) is only correct for multi-part timeline
        // stitching — it says nothing about where the pipeline actually
        // landed within ITS part, which is 0 only when the requested
        // position happens to fall exactly on a keyframe. Every other
        // case snaps backward to the nearest keyframe at-or-before it,
        // and the true origin is knowable only once the pipeline reports
        // it. "direct" mode is a raw byte-range file with real seeking —
        // no pipeline-local timeline to correct. Starting from the true
        // beginning (0) is skipped too: 0 is always a keyframe, so
        // there's nothing to correct and no reason to pay the round trip.
        if (session.mode != "direct" && requestedAbsMs != 0L) {
            syncOrigin(session, streamBaseUrl, subtitleEpoch)
        }
    }

    /// Corrects the optimistic [offsetMs] once the hub reports the
    /// pipeline's true (keyframe-snapped) origin via the session's
    /// `start.pos` sidecar — mirrors web/src/views/Player.tsx's
    /// syncOrigin ("players align subtitles/seekbar to it", per the
    /// hub's own session_file comment). The mismatch this corrects is
    /// exactly what shows up as subtitles and the reported/seekbar
    /// position drifting out of sync with the video specifically when
    /// resuming or seeking into the middle of something — never when
    /// starting from the beginning.
    ///
    /// The correction is a plain in-place [offsetMs] write (no epoch
    /// bump, no [SubtitleSession] change): ass/overlay rendering and the
    /// seekbar both read position through the ForwardingPlayer, whose
    /// overrides fold in offsetMs at call time, so they pick it up on
    /// their very next read. Text-delivery VTT is the one consumer that
    /// CAN'T — its shift_ms is baked into a sideloaded
    /// MediaItem.SubtitleConfiguration at prepare() time — so it's
    /// handled by rebakeTextSubtitleShiftIfNeeded(): a position-
    /// preserving re-prepare, paid only when a text track is actually
    /// selected (at correction time, or later at pick time via
    /// selectSubtitleTrack).
    ///
    /// [start.pos] may not exist yet the instant a fresh pipeline starts,
    /// so this retries a few times before giving up — same shape as the
    /// web client's 3-attempt/700ms backoff. [forEpoch] guards against a
    /// stale attempt (a slow retry from a PREVIOUS attach()) landing
    /// after a newer seek/restart has already superseded it.
    private fun syncOrigin(session: StartSessionResponse, streamBaseUrl: String, forEpoch: Int) {
        val optimisticOffsetMs = offsetMs
        viewModelScope.launch {
            repeat(3) { attempt ->
                if (subtitleEpoch != forEpoch) {
                    Log.d(TAG, "syncOrigin abandoned epoch=$forEpoch (superseded, now ${subtitleEpoch}) attempt=$attempt")
                    return@launch
                }
                try {
                    // Both execute() AND reading the response body are
                    // blocking calls — viewModelScope.launch{} defaults to
                    // Dispatchers.Main.immediate, so without this the very
                    // first one trips StrictMode's
                    // NetworkOnMainThreadException on every single attempt.
                    // That exception was being swallowed by the catch
                    // below (logged, then retried, 3 times, always the
                    // same failure) — meaning this correction has never
                    // actually landed once, since it was first written.
                    // AssSubtitleOverlay's own blocking OkHttp calls
                    // already do this correctly; this one just didn't.
                    // Everything that touches the response has to stay
                    // inside the IO-confined block too, not just execute()
                    // itself.
                    val local = withContext(Dispatchers.IO) {
                        ApiClient.authenticatedOkHttpClient()
                            .newCall(Request.Builder().url("${streamBaseUrl}start.pos").build())
                            .execute()
                            .use { response ->
                                if (!response.isSuccessful) {
                                    Log.d(TAG, "syncOrigin http ${response.code} epoch=$forEpoch attempt=$attempt")
                                    return@use null
                                }
                                val raw = response.body.string().trim()
                                val parsed = raw.toDoubleOrNull()?.let(Math::round)
                                if (parsed == null) Log.d(TAG, "syncOrigin unparseable epoch=$forEpoch attempt=$attempt raw=$raw")
                                parsed
                            }
                    }
                    if (local != null) {
                        val correction = computeOriginCorrection(session.partBaseMs, local, offsetMs)
                        val corrected = correction.correctedOffsetMs
                        Log.d(
                            TAG,
                            "syncOrigin epoch=$forEpoch attempt=$attempt local=$local " +
                                "partBaseMs=${session.partBaseMs} optimisticOffsetMs=$optimisticOffsetMs " +
                                "corrected=$corrected currentOffsetMs=$offsetMs inBounds=${correction.inBounds}",
                        )
                        // Sanity-bounded like the web client: a wildly
                        // different value means we're reading the wrong
                        // session/a stale file, not a real keyframe snap.
                        if (subtitleEpoch == forEpoch && corrected != offsetMs && correction.inBounds) {
                            offsetMs = corrected
                            Log.d(TAG, "syncOrigin applied epoch=$forEpoch offsetMs=$corrected")
                            if (_selectedSubtitleTrack.value?.delivery == "text") {
                                rebakeTextSubtitleShiftIfNeeded()
                            }
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "syncOrigin fetch failed epoch=$forEpoch attempt=$attempt", e)
                    // Not ready yet, or a transient network hiccup — retry.
                }
                delay(700)
            }
        }
    }

    /// Media3 offers no way to adjust a sideloaded subtitle's time shift
    /// on a live MediaItem, so when [offsetMs] has moved past the value
    /// baked into the current configs (syncOrigin's keyframe-snap
    /// correction — typically a few seconds after any seek that didn't
    /// land exactly on a keyframe), the only fix is re-preparing the same
    /// stream with freshly-baked configs. That's a visible rebuffer, so
    /// it's deliberately NOT done on every correction — only when a text
    /// track is actually selected (callers gate on that), where the
    /// alternative is subtitles permanently off by the snap delta.
    /// Position is preserved: realPlayer.currentPosition is playlist-
    /// local and the playlist itself is unchanged, so handing it back as
    /// startPositionMs resumes in place.
    private fun rebakeTextSubtitleShiftIfNeeded() {
        val session = session ?: return
        if (session.mode == "direct") return // shift is always 0 for direct
        if (attachedVttShiftMs == offsetMs) return
        Log.d(TAG, "rebaking VTT shift item=$itemId stale=$attachedVttShiftMs fresh=$offsetMs")
        val resumeLocalMs = realPlayer.currentPosition
        val wasPlaying = realPlayer.playWhenReady
        realPlayer.setMediaItem(buildMediaItem(session), resumeLocalMs)
        realPlayer.prepare()
        realPlayer.playWhenReady = wasPlaying
        attachedVttShiftMs = offsetMs
    }

    /// Media3 can't attach a new [MediaItem.SubtitleConfiguration] to an
    /// already-prepared MediaItem, so every Text-delivery track is
    /// sideloaded here upfront; switching between them is then a
    /// TrackSelectionOverride (applySubtitleTrackSelectionOverride) with
    /// no re-prepare. `shift_ms` is recomputed every attach() call since
    /// offsetMs changes on every seek-restart. For "direct" sessions
    /// offsetMs is pinned to 0 (the native timeline is already absolute),
    /// so the shift correctly evaluates to 0 there.
    private fun textDeliverySubtitleConfigs(): List<MediaItem.SubtitleConfiguration> =
        _subtitleTracks.value
            .filter { it.delivery == "text" }
            .map { track ->
                val vttUrl = subtitleVttUrl(ApiClient.baseUrl(), itemId, track.id, offsetMs)
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(vttUrl))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(track.language)
                    .setId(track.id.toString())
                    .build()
            }

    /// Reapplies the current [selectedSubtitleTrack] as a
    /// TrackSelectionOverride against whatever text tracks the
    /// just-rebuilt MediaItem carries. A no-op (override cleared) unless
    /// the selection is one this pipeline renders: a Text-delivery track
    /// (sideloaded VTT) or an embedded bitmap track a direct session
    /// hands straight to media3's own parsers ([isNativeBitmapPick]).
    /// Ass delivery, and overlay delivery in every other shape, are read
    /// out of band and are never part of ExoPlayer's track groups.
    private fun applySubtitleTrackSelectionOverride() {
        val selected = _selectedSubtitleTrack.value
        val textTrack = selected?.takeIf { it.delivery == "text" }
        val bitmapTrack = selected?.takeIf { isNativeBitmapPick(it, isDirect) }
        val params = realPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            // Disabled unless a pick this pipeline renders is live — see the
            // ExoPlayer.Builder note above: leaving the renderer enabled lets
            // the selector pick a text track we never asked for.
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textTrack == null && bitmapTrack == null)
        if (bitmapTrack != null) {
            // Found by what it IS, not by an id: this track came out of the
            // file itself, so there's no sideloaded id to match on and the
            // container's own numbering need not agree with the hub's stream
            // indices. See nativeBitmapGroupIndex.
            val groups = realPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val infos = groups.map {
                val format = it.mediaTrackGroup.getFormat(0)
                TextTrackInfo(format.sampleMimeType, format.codecs, format.language)
            }
            val index = embeddedBitmapOrdinal(_subtitleTracks.value, bitmapTrack)
                ?.let { ordinal -> nativeBitmapGroupIndex(infos, ordinal, bitmapTrack.language) }
            // Log.i for the same reason as the text override below.
            Log.i(TAG, "bitmap override: want=${bitmapTrack.id} matched=${index != null} textGroups=$infos")
            if (index != null) params.addOverride(TrackSelectionOverride(groups[index].mediaTrackGroup, 0))
        } else if (textTrack != null) {
            // MergingMediaPeriod re-exposes every child source's formats
            // with the id rewritten to "<childIndex>:<originalId>"
            // (uniqueness across children — confirmed in 1.10.0
            // bytecode), so the sideloaded VTT config's id "1234"
            // surfaces here as e.g. "1:1234" and an exact comparison
            // never matched — the override was silently skipped and
            // text tracks never turned on. The hub's HLS playlists carry
            // no subtitle renditions of their own, so every TEXT group
            // is one of ours and suffix matching is unambiguous.
            val wantedId = textTrack.id.toString()
            val group = realPlayer.currentTracks.groups.firstOrNull {
                it.type == C.TRACK_TYPE_TEXT && matchesSideloadedTrackId(it.mediaTrackGroup.getFormat(0).id, wantedId)
            }
            // Log.i, not Log.d: the vivo test device suppresses D-level
            // logcat output entirely, and this is the one line that says
            // whether a text pick actually engaged.
            Log.i(
                TAG,
                "text override: want=$wantedId matched=${group != null} " +
                    "textGroups=${realPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.map { it.mediaTrackGroup.getFormat(0).id }}",
            )
            if (group != null) params.addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        realPlayer.trackSelectionParameters = params.build()
    }

    /// A "direct" session serves the file byte-for-byte with every
    /// embedded audio track intact — there's no server-side remux to
    /// pick one, unlike a remux/transcode session where the hub already
    /// produced exactly the requested stream and ExoPlayer only ever
    /// sees the one audio track to begin with. Left alone,
    /// DefaultTrackSelector picks its own default (the container's
    /// first track, or whichever carries the DEFAULT selection flag) —
    /// entirely independent of [audioTrack], which is how a title
    /// resolved to English in the Detail/player picker could start
    /// playing its second (e.g. Portuguese) track instead. Ordinal, not
    /// language-matched: [audioTrack] is already a plain stream index
    /// into the same per-source stream list the hub uses for a
    /// remux/transcode session's own selection (resolveAudioTrack,
    /// StartSessionRequest.audioTrack), and container demuxers expose
    /// track groups in that same file order. A no-op for a non-direct
    /// session, where exactly one audio group exists and it's already
    /// the right one.
    private fun applyAudioTrackSelectionOverride() {
        if (!isDirect) return
        val group = realPlayer.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .getOrNull(audioTrack) ?: return
        realPlayer.trackSelectionParameters = realPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    /// null clears selection ("Off"). Text-delivery switches are instant
    /// (TrackSelectionOverride over already-sideloaded VTT configs, no
    /// re-prepare); Ass/Overlay delivery is picked up by
    /// AssSubtitleOverlay/ImageSubtitleOverlay observing [selectedSubtitleTrack]
    /// + [subtitleSession] — neither needs a session restart, matching the
    /// hub's own rule that a text/ass/downloaded pick has no plan impact
    /// (the client fetches those itself).
    ///
    /// A "burn" pick is different: the hub bakes it into the encoded
    /// video server-side, and — now that the Detail screen can
    /// pre-select a subtitle before Play — a session can arrive here
    /// already burning one. Undoing or changing that is a plan change
    /// only the hub can make, so a burn pick on either end of the
    /// switch restarts the session (see [restartSessionWithSubtitle])
    /// instead of the client-only override below.
    fun selectSubtitleTrack(track: SubtitleTrack?) {
        val previous = _selectedSubtitleTrack.value
        _selectedSubtitleTrack.value = track
        rememberSubtitlePick(track)
        if (previous?.delivery == "burn" || track?.delivery == "burn") {
            // burnPickOrNull, not track?.id: switching AWAY from a burn
            // to an overlay/text track must start the new session with
            // NO pick — passing an overlay track's id here would just
            // order a fresh burn of the new track instead of un-burning.
            restartSessionWithSubtitle(burnPickOrNull(track), revertTo = previous)
        } else {
            // Text delivery lives on tracks attached at the START of the
            // CURRENT MediaItem (realPlayer.currentTracks), so this doesn't
            // need a fresh attach() — unlike the initial sideload list
            // itself. One exception: if syncOrigin corrected offsetMs
            // AFTER those tracks were baked, a text pick made now would
            // render off by the snap delta — the rebake (a no-op when the
            // baked shift is still current) settles that first; its
            // re-prepare path applies the override via onTracksChanged.
            if (track?.delivery == "text") rebakeTextSubtitleShiftIfNeeded()
            applySubtitleTrackSelectionOverride()
        }
    }

    /// An audio pick made from the player's own menu, which switches
    /// ExoPlayer's track selection rather than restarting the session — so
    /// only the LANGUAGE is remembered here, the layer that carries across
    /// episodes. The exact-index layer needs the hub's own stream order,
    /// which the Detail screen's picker has and this menu doesn't. Nothing
    /// is written for "Default": that's asking to stop steering, not a pick.
    fun rememberAudioLanguage(language: String?) {
        val series = seriesId ?: return
        val value = language?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        val previous = prefsJob
        prefsJob = viewModelScope.launch {
            previous?.join()
            runCatching { prefsRepo.put(series, PREF_AUDIO, value) }
                .onFailure { Log.w(TAG, "failed to remember audio pick item=$itemId", it) }
        }
    }

    /// Two memory layers, the same pair the web client writes: the series
    /// remembers the LANGUAGE, which is what carries into the next episode,
    /// and this item remembers the exact row, the only spelling that can
    /// name a downloaded or OCR track.
    ///
    /// Fire-and-forget: a remembered pick that didn't save is not worth
    /// interrupting a film for. Chained rather than launched loose, so two
    /// picks in quick succession commit in the order they were made — each
    /// write is whole-state for its key, so the LAST one to land is the one
    /// that sticks.
    private fun rememberSubtitlePick(track: SubtitleTrack?) {
        val series = seriesId ?: return
        val previous = prefsJob
        prefsJob = viewModelScope.launch {
            previous?.join()
            runCatching {
                prefsRepo.put(series, PREF_SUBS, rememberedSubsValue(track))
                prefsRepo.put(itemId, PREF_SUBS_TRACK, rememberedSubsTrackValue(track))
            }.onFailure { Log.w(TAG, "failed to remember subtitle pick item=$itemId", it) }
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                // Diagnostic: onPlaybackStateChanged only logs on
                // transitions, so it goes silent for the whole
                // duration of a stuck BUFFERING period. This fires
                // every tick regardless, to show whether
                // currentPosition/bufferedPosition are moving at all
                // while stuck.
                Log.d(
                    TAG,
                    "progress tick item=$itemId state=${realPlayer.playbackState} " +
                        "currentPos=${realPlayer.currentPosition} bufferedPos=${realPlayer.bufferedPosition} " +
                        "playWhenReady=${realPlayer.playWhenReady}",
                )
                // playWhenReady, not isPlaying — mirrors the web client's
                // gate (web/src/views/Player.tsx's tick: `if
                // (!video.paused) report()`). HTML5's `.paused` only
                // reflects an explicit pause() call, staying false
                // through a stall/rebuffer; ExoPlayer's isPlaying is
                // stricter — false during BUFFERING too, not just
                // PAUSED. Gating on isPlaying meant a seek-restart stuck
                // in BUFFERING never reported progress at all, which
                // silently starved the hub's own post-seek throttle
                // (kahawai-media's install_pace_probe gates further
                // production on viewer.pos catching up to what's already
                // produced) — client and hub waiting on each other,
                // permanently. playWhenReady stays true straight through
                // buffering, exactly like `!paused`, and only goes false
                // on the explicit pause() handleSeek() already does
                // before restarting.
                if (realPlayer.playWhenReady) reportProgressNow()
            }
        }
    }

    private fun reportProgressNow() {
        val session = session ?: return
        val positionMs = offsetMs + realPlayer.currentPosition
        viewModelScope.launch {
            try {
                repo.reportProgress(session.sessionId, positionMs)
                Log.d(TAG, "progress reported sessionId=${session.sessionId} positionMs=$positionMs")
            } catch (e: Exception) {
                Log.w(TAG, "progress report failed sessionId=${session.sessionId} positionMs=$positionMs", e)
                // Best-effort, like the web client's beforeunload report.
            }
        }
    }

    /// The ViewModel's own scope is cancelled around the same time
    /// onCleared() runs, which isn't a reliable place to await a network
    /// call — this cleanup is best-effort background work, same as the
    /// web client's `beforeunload` handler (web/src/views/Player.tsx:695-699).
    override fun onCleared() {
        val session = session
        val finalPositionMs = if (session != null) offsetMs + realPlayer.currentPosition else null
        realPlayer.release()
        if (session != null && finalPositionMs != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    repo.reportProgress(session.sessionId, finalPositionMs)
                    repo.endSession(session.sessionId)
                } catch (e: Exception) {
                    // Nothing left to recover to; the hub's own session
                    // reaper is the backstop.
                }
            }
        }
        super.onCleared()
    }
}
