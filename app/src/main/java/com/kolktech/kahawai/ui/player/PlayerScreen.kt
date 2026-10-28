@file:OptIn(UnstableApi::class)

package com.kolktech.kahawai.ui.player

import android.app.Activity
import android.app.Application
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager

import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.languageDisplayName
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.data.repository.PlaybackRepository
import com.kolktech.kahawai.data.settings.AppSettingsStore
import com.kolktech.kahawai.ui.MainActivity
import com.kolktech.kahawai.ui.player.subtitle.AssSubtitleOverlay
import com.kolktech.kahawai.ui.player.subtitle.ImageSubtitleOverlay
import com.kolktech.kahawai.util.formatEndsAt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/// How far up cues have to sit while the controls are up: the whole band
/// the controller's bottom cluster occupies, taken from media3's own layout
/// dimens rather than a number of our own, so it follows the bar the
/// controller actually draws (kw_player_control_view.xml lays both out with
/// exactly these).
private fun controlsInsetPx(context: Context): Float = maxOf(
    context.resources.getDimension(androidx.media3.ui.R.dimen.exo_styled_bottom_bar_height),
    context.resources.getDimension(androidx.media3.ui.R.dimen.exo_styled_progress_margin_bottom) +
        context.resources.getDimension(androidx.media3.ui.R.dimen.exo_styled_progress_layout_height),
)

/// Where SubtitleView ("text" delivery) puts its cues: padding, not
/// setBottomPaddingFraction, which media3 only honours for cues that carry
/// NO line of their own — and these all do. Every cue this client renders
/// comes from a sideloaded VTT, and media3's WebVTT parser spells the
/// format's default "auto" line as LINE_TYPE_NUMBER -1, "the last line from
/// the bottom", which is a position. Padding moves the box those lines are
/// counted in, so it moves every cue whatever it asks for.
///
/// Two things ask for room. The view fills exo_content_frame, which sits
/// centred in the player view — letterboxed above the controls when it's
/// smaller than the view, and pushed past the screen edges by Zoom(crop)
/// when it's bigger, which is what puts cues below the visible bottom. And
/// the controls, which cues must clear for as long as they're up. (The
/// Ass/Image overlays span the whole view rather than the frame, and handle
/// both themselves via shiftBottomOverflowIntoView.)
private fun applyCueBottomPadding(view: PlayerView, insetPx: Float) {
    val frameH = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame).height.toFloat()
    if (frameH <= 0f || view.height <= 0) return
    val subtitles = view.subtitleView ?: return
    // Pin the glyph size in absolute pixels, derived from the UNPADDED
    // height, before padding anything.
    //
    // SubtitleView is a FrameLayout that overrides neither setPadding nor
    // onLayout, so padding it lays its CanvasSubtitleOutput child out that
    // much shorter — and the child sizes text as a fraction of its OWN
    // height, which is the already-shortened one. Raising the cues to clear
    // the controls therefore shrank them too. Asking for
    // TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING does not help: the child's
    // own padding is zero either way, and it is the parent's padding that
    // took the height away.
    //
    // An absolute size is resolved as-is (SubtitleViewUtils.resolveTextSize),
    // so it survives whatever the layout does to the box, leaving the
    // padding to do only what it is for: move them.
    val unpaddedH = subtitles.height.takeIf { it > 0 } ?: view.height
    subtitles.setFixedTextSize(
        TypedValue.COMPLEX_UNIT_PX,
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * unpaddedH,
    )
    subtitles.setPadding(0, 0, 0, cueBottomPaddingPx(frameH, view.height.toFloat(), insetPx))
}

/// The arithmetic behind [applyCueBottomPadding], in the frame's own terms:
/// how much of its bottom to keep clear of cues.
internal fun cueBottomPaddingPx(frameH: Float, visibleH: Float, insetPx: Float): Int {
    // The frame is centred, so its own bottom edge already sits this far
    // above the view's — negative when it overflows instead, which is then
    // exactly how much of it hangs off the bottom of the screen.
    val letterboxPx = (visibleH - frameH) / 2f
    return (insetPx - letterboxPx).coerceAtLeast(0f).roundToInt()
}

// Labels are @StringRes ids, not resolved strings — this is a top-level
// property (evaluated once at class-load, outside any Composable/Context),
// so resolution happens at each call site via context.getString/menuContext.getString.
private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.resize_fit,
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.resize_crop,
    AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.resize_stretch,
)

private val DPAD_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

private val OK_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
)

/// How long the floating skip pill waits to be taken before hiding.
private const val SKIP_OFFER_VISIBLE_MS = 10_000

/// Below this width the bottom row's actions drop their labels.
private const val ACTION_LABELS_MIN_WIDTH_DP = 600

private val ACTION_LABEL_IDS = listOf(
    R.id.kw_episodes_label,
    R.id.kw_audio_subtitles_label,
    R.id.kw_next_episode_label,
)

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(
    itemId: String,
    startMs: Long,
    appSettingsStore: AppSettingsStore,
    onClose: () -> Unit,
    onNextEpisode: (itemId: String, subtitleTrackId: Long?) -> Unit = { _, _ -> },
    /// Any other episode of the show, from the side panel's episode list,
    /// opened at [startMs] (its resume point, or 0).
    onPlayEpisode: (itemId: String, startMs: Long, subtitleTrackId: Long?) -> Unit = { _, _, _ -> },
    initialAudioTrack: Int = -1,
    initialSubtitleTrackId: Long? = null,
    libraryId: String,
    prefetch: PlaybackPrefetch? = null,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val repo = remember { PlaybackRepository() }
    val viewModel: PlayerViewModel = viewModel(
        key = itemId,
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(application, repo, itemId, startMs, initialAudioTrack, initialSubtitleTrackId, libraryId, prefetch)
            }
        },
    )
    val state by viewModel.state.collectAsState()

    // Fires once handlePlaybackEnded() resolves a next episode — carries
    // the current subtitle selection forward so switching languages mid-
    // season doesn't need to be redone every episode.
    val nextEpisodeId by viewModel.nextEpisodeId.collectAsState()
    LaunchedEffect(nextEpisodeId) {
        nextEpisodeId?.let { id -> onNextEpisode(id, viewModel.selectedSubtitleTrack.value?.id) }
    }

    // Pause immediately on the way out so the video isn't still actively
    // decoding/rendering while the screen pop plays out — otherwise it
    // visibly lingers on top of the previous screen for the duration of
    // the exit transition (see NavGraph's popExitTransition for PLAYER).
    val closeAndPause: () -> Unit = {
        viewModel.player.pause()
        onClose()
    }

    // Fires once handlePlaybackEnded() determines there's no next episode
    // (movie, or last episode of a show) — return to the detail screen
    // already underneath this one on the back stack instead of leaving
    // playback sitting on a frozen/black final frame.
    val playbackFinished by viewModel.playbackFinished.collectAsState()
    LaunchedEffect(playbackFinished) {
        if (playbackFinished) closeAndPause()
    }

    BackHandler(onBack = closeAndPause)

    // True fullscreen for the whole player screen. The status bar is
    // already hidden app-wide (MainActivity.hideStatusBar), so only the
    // navigation bar needs taking away here — and, crucially, only the
    // navigation bar may be restored on the way out, or leaving the
    // player would bring the status bar back for the rest of the app.
    // The root NavHost's safeDrawingPadding() shrinks to match
    // automatically once the bar reports zero inset, so no other screen
    // needs to know about this.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.hide(WindowInsetsCompat.Type.navigationBars())
        // Draw into the camera-cutout area too: with the bars hidden the
        // system otherwise letterboxes the window at the cutout's edge
        // (black band beside the notch in landscape). SHORT_EDGES covers
        // camera cutouts in both orientations — they always sit on a
        // short edge of the panel. Window-level and player-only, restored
        // on the way out — the rest of the app keeps its
        // safeDrawingPadding (see KahawaiNavGraph), which the player
        // route skips.
        val previousCutoutMode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && window != null) {
                val previous = window.attributes.layoutInDisplayCutoutMode
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                previous
            } else {
                null
            }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.navigationBars())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && window != null && previousCutoutMode != null) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = previousCutoutMode }
            }
        }
    }

    // Stop the moment the app stops being visible: home button, app
    // switcher, an incoming call's full-screen UI, etc. Rotating the
    // device also fires ON_STOP (Android tears the activity down and
    // recreates it for the config change) but that's not "backgrounded" -
    // isChangingConfigurations tells the two apart so a rotation doesn't
    // stop playback. A call that doesn't take over the screen (just
    // rings) is instead caught by ExoPlayer's own audio-focus handling
    // (see PlayerViewModel). onBackgrounded()/onForegrounded() (rather
    // than a plain pause()) is what keeps a backgrounded player from
    // silently resuming itself on an unrelated audio-focus change later
    // (see onBackgrounded()'s doc in PlayerViewModel) — ON_START is the
    // exact pair of the ON_STOP this backgrounds on.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (context.findActivity()?.isChangingConfigurations != true) {
                when (event) {
                    Lifecycle.Event.ON_STOP -> viewModel.onBackgrounded()
                    Lifecycle.Event.ON_START -> viewModel.onForegrounded()
                    else -> {}
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            is PlayerState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is PlayerState.Error -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = closeAndPause, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.kw_back))
                }
            }
            is PlayerState.Ready -> PlayerContent(viewModel, appSettingsStore, closeAndPause, onNextEpisode, onPlayEpisode)
        }
    }
}

private sealed interface GestureIndicator {
    data class Brightness(val value: Float) : GestureIndicator
    data class Volume(val value: Float) : GestureIndicator
    data class Seek(val forward: Boolean, val seconds: Float) : GestureIndicator
    data class Resize(val label: String) : GestureIndicator
}

private enum class DragMode { NONE, BRIGHTNESS, VOLUME }

/// Mutable gesture bookkeeping shared between the tap/scroll detector,
/// the pinch detector and the raw touch listener. Lives in a plain class
/// (remembered once) rather than Compose state — nothing here drives
/// recomposition, it only sequences events within a single gesture.
private class TouchState {
    var dragMode = DragMode.NONE
    var pinchActive = false
    var pinchScale = 1f

    // Double-tap-to-seek chain: the first double tap seeks by the
    // configured seek-back/seek-forward increment (Settings > Player >
    // Seeking), and every further quick tap in the same direction grows
    // the chain by another increment. Targets are computed from the
    // position captured at chain start, not currentPosition, because each
    // hub seek is an async round trip that supersedes the previous one —
    // reading currentPosition mid-chain would see a position no tap has
    // landed on yet.
    var seekChainMs = 0L
    var seekChainBasisMs = 0L
    var seekForward = true
    var lastSeekTapTime = 0L

    var lastTapUpTime = 0L
    var pendingControllerToggle: Job? = null
}

/// How long after the last seek-tap another tap still extends the chain
/// (also how long the seek indicator stays up, so the indicator being
/// visible == "another tap will add to the seek").
private const val SEEK_CHAIN_WINDOW_MS = 800L

/// Two TV-remote back presses within this window leave the player; a
/// lone press only toggles the controls.
private const val TV_BACK_EXIT_WINDOW_MS = 1_000L

/// PlayerView's own default auto-hide delay, restored once playback
/// resumes (see the isPaused effect in PlayerContent) — 0 disables the
/// timeout entirely, which is what keeps the controls up indefinitely
/// while genuinely paused.
private const val DEFAULT_CONTROLLER_SHOW_TIMEOUT_MS = 5_000

@Composable
private fun PlayerContent(
    viewModel: PlayerViewModel,
    appSettingsStore: AppSettingsStore,
    onClose: () -> Unit,
    onNextEpisode: (itemId: String, subtitleTrackId: Long?) -> Unit,
    onPlayEpisode: (itemId: String, startMs: Long, subtitleTrackId: Long?) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    // Read once at player open — these are settings-screen toggles, not
    // values that change out from under an already-open player. The
    // master switch gates every individual gesture regardless of its own
    // stored value (Settings screen greys the sub-toggles out for the
    // same reason).
    val gesturesEnabled = remember { appSettingsStore.playerGesturesEnabled }
    val volumeBrightnessGestureEnabled = remember { gesturesEnabled && appSettingsStore.volumeBrightnessGestureEnabled }
    val seekGestureEnabled = remember { gesturesEnabled && appSettingsStore.seekGestureEnabled }
    val zoomGestureEnabled = remember { gesturesEnabled && appSettingsStore.zoomGestureEnabled }
    val rememberBrightnessLevel = remember { appSettingsStore.rememberBrightnessLevel }
    val seekBackMs = remember { appSettingsStore.seekBackMs }
    val seekForwardMs = remember { appSettingsStore.seekForwardMs }

    var brightness by remember {
        mutableFloatStateOf(
            appSettingsStore.lastBrightnessLevel.takeIf { rememberBrightnessLevel && it in 0f..1f }
                ?: activity?.window?.attributes?.screenBrightness?.takeIf { it in 0f..1f }
                ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f),
        )
    }
    var volume by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat())
    }
    var indicator by remember { mutableStateOf<GestureIndicator?>(null) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // Cues ride above the controls for as long as they're up, whichever
    // renderer draws them.
    var controlsShown by remember { mutableStateOf(false) }
    val cueInsetPx = if (controlsShown) remember(context) { controlsInsetPx(context) } else 0f
    val liveCueInsetPx = rememberUpdatedState(cueInsetPx)
    var resizeModeIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val title by viewModel.title.collectAsState()
    val selectedSubtitle by viewModel.selectedSubtitleTrack.collectAsState()
    val subtitleTracks by viewModel.subtitleTracks.collectAsState()
    val subtitleSession by viewModel.subtitleSession.collectAsState()
    val transientError by viewModel.transientError.collectAsState()
    val hdrActive by viewModel.hdrActive.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val segments by viewModel.segments.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    // Starts from the setting, but the side panel can flip it for the
    // rest of this session without touching the stored preference.
    var autoSkipEnabled by remember { mutableStateOf(appSettingsStore.autoSkipIntrosOutros) }
    val autoPlayNext by viewModel.autoPlayNext.collectAsState()

    // Side panel pages, top last; empty while it's closed (see
    // PlayerSidePanel). Read from the key routing below, hence the
    // updated-state copy.
    var panelStack by remember { mutableStateOf<List<PanelPage>>(emptyList()) }
    val panelOpen = panelStack.isNotEmpty()
    val panelOpenState = rememberUpdatedState(panelOpen)

    // The panel lists tracks and shows the speed, neither of which the
    // player publishes as state of its own.
    var currentTracks by remember { mutableStateOf(viewModel.player.currentTracks) }
    var playbackSpeed by remember { mutableFloatStateOf(viewModel.player.playbackParameters.speed) }
    DisposableEffect(viewModel) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                playbackSpeed = playbackParameters.speed
            }
        }
        viewModel.player.addListener(listener)
        onDispose { viewModel.player.removeListener(listener) }
    }

    // TV remote back handling: a first press reveals the controls, a
    // press while they're up dismisses them (playback continues), and
    // two quick presses within TV_BACK_EXIT_WINDOW_MS actually leave
    // the player. Registered after PlayerScreen's own BackHandler so it
    // takes precedence while enabled; on touch devices it stays
    // disabled and back keeps exiting directly.
    val isTv = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    var lastTvBackAtMs by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = isTv) {
        val now = SystemClock.uptimeMillis()
        val view = playerView
        when {
            now - lastTvBackAtMs <= TV_BACK_EXIT_WINDOW_MS -> onClose()
            view?.isControllerFullyVisible == true -> view.hideController()
            else -> {
                view?.showController()
                // Land D-pad focus inside the freshly shown controls so
                // the next arrow press navigates buttons instead of
                // going nowhere (Compose holds no focusable here).
                view?.requestFocus()
            }
        }
        lastTvBackAtMs = now
    }

    // Picture-in-picture on touch devices: playback follows the user to
    // the home screen instead of pausing. Android 12+ auto-enters via
    // setAutoEnterEnabled (which also gets the smooth shrink animation);
    // older versions enter explicitly from onUserLeaveHint. Explicitly
    // excluded on TV (isTv above): some TV devices report
    // FEATURE_PICTURE_IN_PICTURE despite Home meaning "leave the app",
    // so Home there must go straight to the launcher, not PiP. Only
    // entered while actually playing — a paused player on Home is just
    // backgrounded (and the ON_STOP observer above pauses it anyway).
    val mainActivity = activity as? MainActivity
    val supportsPip = remember(context, isTv) {
        !isTv && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    var isInPip by remember { mutableStateOf(mainActivity?.isInPictureInPictureMode == true) }

    fun pipParams(): PictureInPictureParams {
        // The framework rejects aspect ratios outside [1/2.39, 2.39];
        // clamp ultra-wide (and, in theory, ultra-tall) video to the
        // nearest allowed shape. 16:9 stands in until the first
        // onVideoSizeChanged delivers real dimensions.
        val size = viewModel.player.videoSize
        val width = size.width.takeIf { it > 0 } ?: 16
        val height = size.height.takeIf { it > 0 } ?: 9
        val ratio = width.toFloat() / height
        val aspect = when {
            ratio > 2.35f -> Rational(235, 100)
            ratio < 1 / 2.35f -> Rational(100, 235)
            else -> Rational(width, height)
        }
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(viewModel.player.isPlaying)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    // HUB-37. The ViewModel exposes segments as a StateFlow (set once,
    // at session start), but "which segment is the playhead inside" is a
    // function of TIME, which nothing here otherwise polls — position is
    // read straight off the player imperatively everywhere else in this
    // file (gesture indicators, PiP aspect ratio). A short interval only
    // runs while there's something to skip, matching the web client's
    // Vue `computed` off `playing.posMs`, which only re-evaluates because
    // the video element already fires timeupdate constantly.
    // Computed up here (ahead of PlayerView's controller-visibility key
    // handling below) so the OK-key handler can check it: the skip
    // button, when it's up, must win the OK/enter press over the normal
    // play/pause toggle.
    // The next episode's id also drives the end-of-episode "Next episode"
    // offer, so the position is polled whenever there's one of those too.
    val adjacentEpisodes by viewModel.adjacentEpisodes.collectAsState()
    val nextEpisodeId = adjacentEpisodes?.nextId
    var skipCheckPosMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(segments, nextEpisodeId) {
        if (segments.isEmpty() && nextEpisodeId == null) return@LaunchedEffect
        while (true) {
            skipCheckPosMs = viewModel.player.currentPosition
            delay(300)
        }
    }
    val skippingSegment = remember(segments, skipCheckPosMs) { skippableSegment(segments, skipCheckPosMs) }

    // Auto-skip fires once per segment (tracked by its start), not on
    // every 300ms tick that still finds the playhead inside it — the
    // hub seek that follows is an async round trip through the same
    // handleSeek() path a manual Skip press uses, and re-issuing it every
    // tick would just queue redundant hub round trips.
    var autoSkippedStartMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(skippingSegment, autoSkipEnabled) {
        val segment = skippingSegment
        if (autoSkipEnabled && segment != null && segment.startMs != autoSkippedStartMs) {
            autoSkippedStartMs = segment.startMs
            viewModel.player.seekTo(skipTargetMs(segment, viewModel.player.duration))
        }
    }

    // What the skip button offers now (see skipOffer): past a segment, or
    // on to the next episode. Auto-skip takes segment offers itself (the
    // effect above has already seeked), so only Next episode is left to
    // offer then.
    val offer = remember(segments, skipCheckPosMs, nextEpisodeId, autoSkipEnabled) {
        skipOffer(segments, skipCheckPosMs, viewModel.player.duration, nextEpisodeId)
            ?.takeIf { it.isNextEpisode || !autoSkipEnabled }
    }

    // The floating pill hides itself SKIP_OFFER_VISIBLE_MS into an offer
    // nobody took, counting down in its fill; the controller's copy
    // (kw_skip) stays for the whole offer, so it's never more than a tap
    // or a D-pad press away. Leaving the offer (a seek back out of the
    // segment) resets it, so coming back into it offers it afresh.
    var expiredOfferStartMs by remember { mutableStateOf<Long?>(null) }
    val skipCountdown = remember(offer?.startMs) { Animatable(1f) }
    LaunchedEffect(offer?.startMs) {
        val start = offer?.startMs
        if (start == null) {
            expiredOfferStartMs = null
            return@LaunchedEffect
        }
        skipCountdown.animateTo(0f, tween(SKIP_OFFER_VISIBLE_MS, easing = LinearEasing))
        expiredOfferStartMs = start
    }
    // Under the panel there's nothing to take it with, and with the
    // controls up their own copy is the one showing.
    val floatingOffer = offer?.takeIf { it.startMs != expiredOfferStartMs && !isInPip && !panelOpen && !controlsShown }
    val floatingOfferState = rememberUpdatedState(floatingOffer)

    fun takeSkipOffer(taken: SkipOffer) {
        val nextId = taken.nextEpisodeId
        if (nextId != null) {
            onNextEpisode(nextId, viewModel.selectedSubtitleTrack.value?.id)
        } else {
            taken.targetMs?.let { viewModel.player.seekTo(it) }
        }
    }

    // Remote keys, routed straight from the activity (see
    // MainActivity.onPlayerKey). PlayerView already knows what to do with
    // transport keys and with a D-pad press while the controls are
    // hidden - it just never sees either, so both are handed to it here.
    // Everything else, including the D-pad once the controls are up,
    // returns false and dispatches normally.
    DisposableEffect(mainActivity, playerView) {
        val view = playerView
        if (mainActivity == null || view == null) {
            onDispose {}
        } else {
            mainActivity.onPlayerKey = { event ->
                when {
                    // The side panel is Compose and takes the D-pad itself.
                    panelOpenState.value && (event.keyCode in DPAD_KEYS || event.keyCode in OK_KEYS) -> false
                    view.dispatchMediaKeyEvent(event) -> {
                        // Flash the controls up so ff/rew has a timebar to
                        // aim by - except in PiP, whose window has its own,
                        // and under the side panel.
                        if (!mainActivity.isInPictureInPictureMode && !panelOpenState.value) view.showController()
                        true
                    }
                    event.keyCode in DPAD_KEYS && !view.isControllerFullyVisible -> {
                        if (event.action == KeyEvent.ACTION_DOWN) view.showController()
                        true
                    }
                    // OK/enter with the controls hidden takes the skip
                    // offer while its pill is floating, and otherwise
                    // toggles play/pause in place - once the controls are
                    // up, OK reaches the focused button itself and this
                    // branch is skipped. A resulting pause surfaces the
                    // controls anyway (see the isPaused effect below,
                    // which reacts to the player state this produces
                    // rather than being told to show them here).
                    event.keyCode in OK_KEYS && !view.isControllerFullyVisible -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val floating = floatingOfferState.value
                            when {
                                floating != null -> takeSkipOffer(floating)
                                viewModel.player.isPlaying -> viewModel.player.pause()
                                else -> viewModel.player.play()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            onDispose { mainActivity.onPlayerKey = null }
        }
    }

    DisposableEffect(mainActivity, supportsPip, viewModel) {
        if (mainActivity == null || !supportsPip) {
            onDispose {}
        } else {
            mainActivity.onUserLeaveWithPlayback = {
                if (viewModel.player.isPlaying && !mainActivity.isInPictureInPictureMode) {
                    playerView?.hideController()
                    mainActivity.enterPictureInPictureMode(pipParams())
                }
            }
            mainActivity.pipModeListener = { inPip ->
                isInPip = inPip
                if (inPip) {
                    playerView?.hideController()
                    panelStack = emptyList()
                }
            }
            // Keep the registered params fresh so Android 12+'s auto-enter
            // has the right aspect ratio on hand and only triggers while
            // playing.
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        mainActivity.setPictureInPictureParams(pipParams())
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        mainActivity.setPictureInPictureParams(pipParams())
                    }
                }
            }
            viewModel.player.addListener(listener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mainActivity.setPictureInPictureParams(pipParams())
            }
            onDispose {
                viewModel.player.removeListener(listener)
                mainActivity.onUserLeaveWithPlayback = null
                mainActivity.pipModeListener = null
                // Leaving the player must take auto-enter with it, or
                // pressing Home from a browse screen would still pop a
                // (frozen) PiP window.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    mainActivity.setPictureInPictureParams(
                        PictureInPictureParams.Builder().setAutoEnterEnabled(false).build(),
                    )
                }
            }
        }
    }

    // Keep the screen on while video is actually playing so it doesn't
    // time out mid-playback; let normal Android screen-timeout resume
    // as soon as playback is paused or stopped. FLAG_KEEP_SCREEN_ON
    // needs no manifest permission, unlike a PowerManager WakeLock.
    DisposableEffect(activity, viewModel) {
        val window = activity?.window
        if (window == null) {
            onDispose {}
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
            viewModel.player.addListener(listener)
            if (viewModel.player.isPlaying) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                viewModel.player.removeListener(listener)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // Recoverable failures (failed seek, failed subtitle switch) — the
    // stream is still playing, so they get a snackbar here rather than
    // the terminal PlayerState.Error screen.
    LaunchedEffect(transientError) {
        transientError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearTransientError()
        }
    }
    // Ass/Overlay delivery need their own font/session-tap fetches;
    // PlaybackRepository holds no state, so a fresh instance here (rather
    // than threading the one PlayerScreen built for the ViewModel) keeps
    // this composable self-contained.
    val subtitleRepo = remember { PlaybackRepository() }
    val catalogRepo = remember { CatalogRepository() }

    // HUB-37: chapter marks on the native styled progress bar. The
    // controller layout's time bar (id exo_progress, a
    // SeekWindowTimeBar) already supports drawing marks via the ad-break
    // API, which is the only public hook this view offers for "ticks
    // other than the played/buffered fill". A short retry (mirrors syncOrigin's shape) covers
    // "direct" mode, where the true duration isn't known the instant
    // Ready fires; HLS sessions already know it up front via the
    // ForwardingPlayer override.
    LaunchedEffect(chapters, playerView) {
        val timeBar = playerView?.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress) ?: return@LaunchedEffect
        if (chapters.isEmpty()) {
            timeBar.setAdGroupTimesMs(null, null, 0)
            return@LaunchedEffect
        }
        repeat(10) {
            val duration = viewModel.player.duration
            if (duration != C.TIME_UNSET && duration > 0) {
                val marks = chapterMarkTimesMs(chapters, duration)
                timeBar.setAdGroupTimesMs(marks, BooleanArray(marks.size), marks.size)
                return@LaunchedEffect
            }
            delay(300)
        }
    }

    fun flash(next: GestureIndicator, durationMs: Long = 700) {
        indicator = next
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(durationMs)
            indicator = null
        }
    }

    fun applyResize(index: Int) {
        resizeModeIndex = index
        playerView?.resizeMode = RESIZE_MODES[index].first
    }

    fun applyBrightness(value: Float) {
        brightness = value
        activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = value } }
        if (rememberBrightnessLevel) appSettingsStore.lastBrightnessLevel = value
        flash(GestureIndicator.Brightness(value))
    }

    fun applyVolume(value: Float) {
        volume = value
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (value * maxVolume).roundToInt(), 0)
        flash(GestureIndicator.Volume(value))
    }

    // Picks made in the side panel (PlayerSidePanel). A remux/transcode
    // session only ever exposes the single audio stream the hub muxed, so
    // ExoPlayer's own track list has nothing to offer there: the hub's
    // per-source list is what the viewer actually has to choose from, and
    // picking one restarts the pipeline (PlayerViewModel.selectAudioTrackIndex).
    // A direct session plays the file itself, so its own tracks are the choice.
    fun directAudioTracks(): List<Pair<Tracks.Group, Int>> =
        currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }

    fun selectAudio(index: Int) {
        if (!viewModel.isDirect) {
            if (index != viewModel.currentAudioTrack) viewModel.selectAudioTrackIndex(index)
            return
        }
        val (group, trackIndex) = directAudioTracks().getOrNull(index) ?: return
        if (group.isTrackSelected(trackIndex)) return
        viewModel.player.trackSelectionParameters = viewModel.player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        // Remembered for the rest of the series (HUB-33), by the language
        // the chosen format declares — see PlayerViewModel.rememberAudioLanguage.
        viewModel.rememberAudioLanguage(group.getTrackFormat(trackIndex).language)
    }

    // Index 0 is the panel's "Off" row; the rest follow the hub's own
    // track+delivery list (viewModel.subtitleTracks), not
    // player.currentTracks.groups — Ass/Overlay delivery never becomes an
    // ExoPlayer text track at all, only Text delivery does.
    fun selectSubtitle(index: Int) {
        val track = if (index == 0) null else subtitleTracks.getOrNull(index - 1) ?: return
        if (track?.id != selectedSubtitle?.id) viewModel.selectSubtitleTrack(track)
    }

    fun selectPictureSize(index: Int) {
        if (index == resizeModeIndex) return
        applyResize(index)
        flash(GestureIndicator.Resize(context.getString(RESIZE_MODES[index].second)))
    }

    val touchState = remember { TouchState() }
    val doubleTapTimeoutMs = remember { ViewConfiguration.getDoubleTapTimeout().toLong() }
    val density = context.resources.displayMetrics.density
    // Vertical travel required before a swipe starts adjusting anything —
    // well above touch slop, so the incidental jitter of a tap or the
    // start of a system back/home gesture never registers as a swipe.
    val dragStartThresholdPx = remember { 36 * density }
    // Dead zone along every screen edge: left/right are Android's
    // gesture-navigation back zones, bottom is the home gesture. Swipes
    // beginning there belong to the system, not to brightness/volume.
    val edgeExclusionPx = remember { 32 * density }

    // Seeks issued per tap are safe to fire eagerly: PlayerViewModel's
    // handleSeek cancels the in-flight hub round trip when a newer seek
    // supersedes it, so only the final chain target actually lands.
    fun performChainSeek(state: TouchState) {
        val duration = viewModel.player.duration.takeIf { it != C.TIME_UNSET }
        val target = if (state.seekForward) {
            (state.seekChainBasisMs + state.seekChainMs).let { t -> duration?.let { minOf(t, it) } ?: t }
        } else {
            (state.seekChainBasisMs - state.seekChainMs).coerceAtLeast(0)
        }
        viewModel.player.seekTo(target)
        flash(GestureIndicator.Seek(state.seekForward, state.seekChainMs / 1000f), durationMs = SEEK_CHAIN_WINDOW_MS)
    }

    // A single GestureDetector, built once, attached directly to the
    // PlayerView via setOnTouchListener rather than a Compose overlay
    // drawn on top of it. A transparent Compose sibling covering the
    // whole screen would win every hit test and swallow taps meant for
    // the native play/pause/CC/settings buttons underneath; a listener on
    // the PlayerView itself only sees touches its child buttons didn't
    // already consume, so those buttons keep working. Built once (not
    // per-recomposition) so an in-progress drag doesn't get handed to a
    // brand new detector mid-gesture.
    //
    // Double taps are detected manually from onSingleTapUp timing rather
    // than onDoubleTap: once a seek chain is running, every further tap
    // must extend it immediately, but GestureDetector would only report
    // every second tap (it pairs taps back into fresh double-tap
    // gestures) and would delay them through its own timeout.
    val gestureDetector = remember {
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    touchState.dragMode = DragMode.NONE
                    touchState.pinchActive = false
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val view = playerView ?: return true
                    val now = e.eventTime
                    val forward = e.x >= view.width / 2f

                    if (!seekGestureEnabled) {
                        touchState.lastTapUpTime = now
                        touchState.pendingControllerToggle?.cancel()
                        touchState.pendingControllerToggle = scope.launch {
                            delay(doubleTapTimeoutMs)
                            playerView?.performClick()
                        }
                        return true
                    }

                    // Tap while the seek indicator is still up: extend the
                    // running chain (or flip it if the side changed).
                    if (touchState.seekChainMs > 0 && now - touchState.lastSeekTapTime <= SEEK_CHAIN_WINDOW_MS) {
                        if (forward != touchState.seekForward) {
                            touchState.seekForward = forward
                            touchState.seekChainMs = if (forward) seekForwardMs else seekBackMs
                            touchState.seekChainBasisMs = viewModel.player.currentPosition
                        } else {
                            touchState.seekChainMs += if (forward) seekForwardMs else seekBackMs
                        }
                        touchState.lastSeekTapTime = now
                        performChainSeek(touchState)
                        return true
                    }
                    touchState.seekChainMs = 0

                    // Second tap in quick succession: start a seek chain.
                    if (now - touchState.lastTapUpTime <= doubleTapTimeoutMs) {
                        touchState.pendingControllerToggle?.cancel()
                        touchState.pendingControllerToggle = null
                        touchState.lastTapUpTime = 0
                        touchState.seekForward = forward
                        touchState.seekChainMs = if (forward) seekForwardMs else seekBackMs
                        touchState.seekChainBasisMs = viewModel.player.currentPosition
                        touchState.lastSeekTapTime = now
                        performChainSeek(touchState)
                        return true
                    }

                    // Lone tap: toggle the controller, but only once the
                    // double-tap window has passed without a second tap.
                    // PlayerView.performClick() toggles controller
                    // visibility itself; routing the tap through it keeps
                    // touch and accessibility-service activation on the
                    // same code path — a TalkBack double-tap fires
                    // performClick() directly and gets identical behavior.
                    touchState.lastTapUpTime = now
                    touchState.pendingControllerToggle?.cancel()
                    touchState.pendingControllerToggle = scope.launch {
                        delay(doubleTapTimeoutMs)
                        playerView?.performClick()
                    }
                    return true
                }

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (!volumeBrightnessGestureEnabled) return true
                    val view = playerView ?: return true
                    val start = e1 ?: return true
                    val height = view.height.takeIf { it > 0 } ?: return true
                    if (touchState.pinchActive || e2.pointerCount > 1) return true

                    if (touchState.dragMode == DragMode.NONE) {
                        if (start.x < edgeExclusionPx || start.x > view.width - edgeExclusionPx ||
                            start.y < edgeExclusionPx || start.y > height - edgeExclusionPx
                        ) {
                            return true
                        }
                        val totalDx = e2.x - start.x
                        val totalDy = e2.y - start.y
                        // Engage only once the finger has clearly committed
                        // to a vertical swipe; anything shorter or more
                        // horizontal is a tap wobble or a system gesture.
                        if (abs(totalDy) < dragStartThresholdPx || abs(totalDy) < abs(totalDx)) return true
                        touchState.dragMode = if (start.x < view.width / 2f) DragMode.BRIGHTNESS else DragMode.VOLUME
                        return true
                    }

                    val delta = distanceY / height
                    when (touchState.dragMode) {
                        DragMode.BRIGHTNESS -> applyBrightness((brightness + delta).coerceIn(0f, 1f))
                        DragMode.VOLUME -> applyVolume((volume + delta).coerceIn(0f, 1f))
                        DragMode.NONE -> {}
                    }
                    return true
                }
            },
        ).apply {
            // SimpleOnGestureListener registers itself as an
            // OnDoubleTapListener, and once GestureDetector classifies a
            // second tap as a double tap it swallows that tap's
            // onSingleTapUp — which would make the manual tap counting
            // above miss every second tap. Unregistering turns the
            // detector's own double-tap tracking off entirely so
            // onSingleTapUp fires for every tap.
            setOnDoubleTapListener(null)
        }
    }

    // Pinch out anywhere on the video zooms it to fill the screen (crop),
    // pinch in returns it to the normal fit. Runs alongside the tap/swipe
    // detector; pinchActive keeps a finished pinch from being misread as
    // a brightness/volume swipe for the rest of the gesture.
    val scaleGestureDetector = remember {
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    touchState.pinchActive = true
                    touchState.pinchScale = 1f
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!zoomGestureEnabled) return true
                    touchState.pinchScale *= detector.scaleFactor
                    // Resolved off the attached PlayerView's own context
                    // (mirrors menuContext elsewhere in this file) rather
                    // than the Compose-tracked context above — this runs
                    // from a native gesture callback, not recomposition.
                    val viewContext = playerView?.context
                    if (touchState.pinchScale >= 1.2f && resizeModeIndex != 1) {
                        applyResize(1)
                        viewContext?.let { flash(GestureIndicator.Resize(it.getString(R.string.zoomed_to_fill))) }
                    } else if (touchState.pinchScale <= 0.8f && resizeModeIndex != 0) {
                        applyResize(0)
                        viewContext?.let { flash(GestureIndicator.Resize(it.getString(RESIZE_MODES[0].second))) }
                    }
                    return true
                }
            },
        ).apply {
            // Quick scale (double-tap-then-drag zoom) reuses exactly the
            // tap rhythm the seek chain is built on — a double tap whose
            // second tap drags slightly would start "scaling" and fight
            // both the seek and the swipe gestures.
            isQuickScaleEnabled = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.kw_player_view, null) as PlayerView).apply {
                    player = viewModel.player
                    useController = true
                    // PlayerView's buffering spinner (exo_buffering) exists
                    // in its layout but is off by default — a seek-restart
                    // can legitimately take several seconds (the hub tears
                    // down and restarts its whole remux pipeline at the new
                    // position) with nothing but a black frame on screen to
                    // show for it otherwise, indistinguishable from actually
                    // being stuck.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    // Controls (including our back button, which mirrors
                    // this same visibility) start hidden and only appear
                    // once the viewer taps - not automatically whenever
                    // playback starts/pauses/ends.
                    controllerAutoShow = false
                    hideController()
                    // A d-pad press while the controls are hidden is
                    // swallowed by PlayerView just to show them, without
                    // moving focus - so the bar takes two presses to
                    // reach. Land focus on the play/pause button
                    // specifically (not a bare requestFocus() on the
                    // container, which resolves to whatever's first in
                    // kw_player_control_view's focus order - the top
                    // bar's back button) as soon as the controls appear,
                    // whichever path showed them. No-op
                    // in touch mode, where none of these buttons take
                    // focus.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsShown = visibility == View.VISIBLE
                            if (visibility == View.VISIBLE) {
                                // A skip offer, while there is one, is the
                                // likeliest reason to have brought them up.
                                val skip = findViewById<View>(R.id.kw_skip)?.takeIf { it.isVisible }
                                (skip ?: findViewById<ImageButton>(androidx.media3.ui.R.id.exo_play_pause))?.requestFocus()
                                    ?: requestFocus()
                            }
                        },
                    )
                    resizeMode = RESIZE_MODES[resizeModeIndex].first
                    // Taps reach performClick via the gesture detector's
                    // onSingleTapUp (see above), satisfying the
                    // click-path contract this lint check is about.
                    @Suppress("ClickableViewAccessibility")
                    setOnTouchListener { _, event ->
                        scaleGestureDetector.onTouchEvent(event)
                        gestureDetector.onTouchEvent(event)
                        true
                    }
                    // Back button + title live inside the controller
                    // layout (kw_player_control_view.xml) so they fade
                    // in sync with the rest of the controls.
                    // The bar shades what a seek reaches instantly; its
                    // start is the one end it can't get from the player
                    // (see SeekWindowTimeBar).
                    findViewById<SeekWindowTimeBar>(androidx.media3.ui.R.id.exo_progress).windowStartMs =
                        { viewModel.seekWindowStartMs }
                    findViewById<ImageButton>(R.id.kw_back).setOnClickListener { onClose() }
                    // Media3's own settings menu is built by a private,
                    // non-extensible adapter inside PlayerControlView, so
                    // the gear's click is taken over entirely and opens
                    // the side panel instead.
                    findViewById<ImageButton>(androidx.media3.ui.R.id.exo_settings).setOnClickListener {
                        panelStack = listOf(PanelPage.Settings)
                    }
                    findViewById<View>(R.id.kw_audio_subtitles).setOnClickListener {
                        panelStack = listOf(PanelPage.Tracks(TracksTab.Subtitles))
                    }
                    findViewById<View>(R.id.kw_episodes).setOnClickListener {
                        panelStack = listOf(PanelPage.Episodes)
                    }
                    // The bottom row's labels only fit beside the time on
                    // a wide screen; on a narrow one (portrait phone) the
                    // actions go icon-only rather than into media3's
                    // overflow menu.
                    addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
                        if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
                        val wide = (right - left) / v.resources.displayMetrics.density >= ACTION_LABELS_MIN_WIDTH_DP
                        for (id in ACTION_LABEL_IDS) v.findViewById<View>(id)?.isVisible = wide
                    }
                    // Hooked on the content frame's layout, since both
                    // resize-mode switches and video-size changes relayout
                    // it; the controls moving re-applies it from the
                    // LaunchedEffect below instead (nothing relayouts when
                    // they fade in). See applyCueBottomPadding.
                    findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
                        .addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            applyCueBottomPadding(this, liveCueInsetPx.value)
                        }
                    playerView = this
                }
            },
        )

        // Nothing relayouts when the controls fade in or out, so the text
        // renderer's padding is re-applied here; the overlays take the same
        // inset as a parameter and shift their own cues.
        // White-with-outline instead of the platform's white-on-black-box
        // default, dimmed to BT.2408 reference white while the picture is
        // HDR — see TEXT_SUBTITLE_STYLE / TEXT_SUBTITLE_STYLE_HDR. Keyed on
        // hdrActive because the same item plays either way: the hub tone
        // maps when the profile says this device cannot take HDR, and a
        // session restart can renegotiate mid-item.
        LaunchedEffect(hdrActive, playerView) {
            playerView?.subtitleView?.setStyle(
                if (hdrActive) TEXT_SUBTITLE_STYLE_HDR else TEXT_SUBTITLE_STYLE,
            )
        }
        LaunchedEffect(cueInsetPx, playerView) {
            playerView?.let { applyCueBottomPadding(it, cueInsetPx) }
        }

        val activeSession = subtitleSession
        if (activeSession != null) {
            when (selectedSubtitle?.delivery) {
                // An embedded bitmap track in a direct session is the one
                // "overlay" pick this composable can't serve — there's no
                // session pipeline to tap for its display sets. media3
                // decodes that one out of the container itself and draws it
                // in PlayerView's own SubtitleView instead, the same as
                // "text" below (see isNativeBitmapPick).
                "overlay" -> if (!isNativeBitmapPick(selectedSubtitle, isDirect = !activeSession.isHls)) {
                    ImageSubtitleOverlay(
                        player = viewModel.player,
                        track = selectedSubtitle!!,
                        subtitleSession = activeSession,
                        resizeMode = RESIZE_MODES[resizeModeIndex].first,
                        bottomInsetPx = cueInsetPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                "ass" -> AssSubtitleOverlay(
                    player = viewModel.player,
                    itemId = viewModel.itemId,
                    repo = subtitleRepo,
                    track = selectedSubtitle!!,
                    subtitleSession = activeSession,
                    resizeMode = RESIZE_MODES[resizeModeIndex].first,
                    bottomInsetPx = cueInsetPx,
                    modifier = Modifier.fillMaxSize(),
                )
                // "text" renders natively via PlayerView's own subtitle
                // view (sideloaded VTT + TrackSelectionOverride); "burn"/
                // "none"/null need no client-side overlay at all.
                else -> {}
            }
        }

        // The native title view can't observe the StateFlow itself, so
        // push updates into it from composition.
        LaunchedEffect(title, playerView) {
            playerView?.findViewById<TextView>(R.id.kw_player_title)?.text = title?.primary.orEmpty()
            playerView?.findViewById<TextView>(R.id.kw_player_subtitle)?.apply {
                text = title?.secondary.orEmpty()
                isVisible = title?.secondary != null
            }
        }

        // Like kw_player_title, exo_position/exo_duration are refreshed by
        // media3-ui itself, but kw_ends_at needs its own push - and only
        // bothers while the controls (and thus the label) are visible.
        // Position/duration are read imperatively off the player, same as
        // the skip-check loop above, rather than off a StateFlow that
        // doesn't exist for them (see PlayerViewModel).
        LaunchedEffect(controlsShown, playerView) {
            if (!controlsShown) return@LaunchedEffect
            while (true) {
                val duration = viewModel.player.duration
                val remainingMs = if (duration > 0 && duration != C.TIME_UNSET) {
                    (duration - viewModel.player.currentPosition).coerceAtLeast(0)
                } else {
                    null
                }
                playerView?.findViewById<TextView>(R.id.kw_ends_at)?.apply {
                    isVisible = remainingMs != null
                    remainingMs?.let { text = formatEndsAt(context, it) }
                }
                playerView?.findViewById<View>(R.id.kw_ends_at_separator)?.isVisible = remainingMs != null
                delay(5_000)
            }
        }

        // Episodes / Next episode stay gone until the show's episode list
        // resolves — a movie, or the last episode of a show for Next,
        // leaves the button hidden rather than shown-but-useless.
        // subtitleTrack id rides along so the next episode opens with the
        // same language already selected.
        val episodes by viewModel.episodes.collectAsState()
        LaunchedEffect(adjacentEpisodes, episodes, playerView) {
            val nextId = adjacentEpisodes?.nextId
            playerView?.findViewById<View>(R.id.kw_episodes)?.isVisible = episodes.isNotEmpty()
            playerView?.findViewById<View>(R.id.kw_next_episode)?.apply {
                isVisible = nextId != null
                setOnClickListener {
                    nextId?.let { onNextEpisode(it, viewModel.selectedSubtitleTrack.value?.id) }
                }
            }
        }

        val isPaused by viewModel.isPaused.collectAsState()

        // Controls follow pause state directly rather than their own
        // timeout while paused: up (and staying up - no auto-hide) the
        // moment playback is genuinely paused, whether that came from the
        // focused play/pause button or an OK press while they were
        // hidden (see the onPlayerKey OK branch above); hidden again the
        // moment playback resumes, instead of lingering for the normal
        // timeout. isPaused already excludes the internal pause/resume
        // blips a segment auto-skip or subtitle restart make (see
        // PlayerViewModel's internalPause), so this never fires for
        // those. Skipped in PiP, whose tiny window has no controls to
        // manage. showController() alone lands focus on exo_play_pause
        // (see the ControllerVisibilityListener set up on this PlayerView
        // above) - no separate focus call needed here.
        // Opening the side panel puts the controls away (it has the
        // viewer's attention, and on TV the D-pad, now); they come back,
        // if still paused, once it closes.
        LaunchedEffect(isPaused, isInPip, panelOpen, playerView) {
            val view = playerView ?: return@LaunchedEffect
            if (isPaused && !isInPip && !panelOpen) {
                view.controllerShowTimeoutMs = 0
                view.showController()
            } else {
                view.controllerShowTimeoutMs = DEFAULT_CONTROLLER_SHOW_TIMEOUT_MS
                view.hideController()
            }
        }

        // Overlay chrome (gesture indicators, snackbars) has no place in
        // the tiny PiP window — the video and subtitle overlays are all
        // that should render there.
        indicator?.takeIf { !isInPip }?.let { current ->
            val label = when (current) {
                is GestureIndicator.Brightness -> stringResource(R.string.brightness_percent, (current.value * 100).roundToInt())
                is GestureIndicator.Volume -> stringResource(R.string.volume_percent, (current.value * 100).roundToInt())
                is GestureIndicator.Seek -> {
                    val secs = if (current.seconds % 1f == 0f) current.seconds.toInt().toString() else current.seconds.toString()
                    stringResource(if (current.forward) R.string.seek_forward else R.string.seek_backward, secs)
                }
                is GestureIndicator.Resize -> current.label
            }
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = MaterialTheme.shapes.medium,
                color = Color.Black.copy(alpha = 0.6f),
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color.White,
                )
            }
        }

        // HUB-37. The controller's copy of the skip offer (kw_skip): a real
        // controller child, so the D-pad reaches it like any other button
        // while the controls are up (and lands on it first — see the
        // ControllerVisibilityListener). Focus moves to play/pause before
        // it goes, rather than being dropped with it.
        LaunchedEffect(offer, playerView) {
            val view = playerView ?: return@LaunchedEffect
            view.findViewById<TextView>(R.id.kw_skip)?.apply {
                if (offer == null) {
                    if (hasFocus()) view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                    isVisible = false
                } else {
                    text = context.getString(offer.labelRes)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(skipOfferIcon(offer), 0, 0, 0)
                    setOnClickListener { takeSkipOffer(offer) }
                    isVisible = true
                }
            }
        }

        // ...and the floating one, at the same spot (kw_skip's margins
        // less its focus-ring inset), while the controls are hidden.
        var lastFloatingOffer by remember { mutableStateOf<SkipOffer?>(null) }
        if (floatingOffer != null) lastFloatingOffer = floatingOffer
        AnimatedVisibility(
            visible = floatingOffer != null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 108.dp),
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut(),
        ) {
            lastFloatingOffer?.let { shown ->
                SkipPill(
                    label = stringResource(shown.labelRes),
                    icon = skipOfferIcon(shown),
                    remaining = { skipCountdown.value },
                    onClick = { takeSkipOffer(shown) },
                )
            }
        }

        if (!isInPip) {
            val panelModel = remember(
                currentTracks, subtitleTracks, selectedSubtitle, resizeModeIndex, playbackSpeed,
                autoSkipEnabled, autoPlayNext, segments, adjacentEpisodes, episodes,
            ) {
                val mono = context.getString(R.string.channels_mono)
                val stereo = context.getString(R.string.channels_stereo)
                val subtitleStrings = SubtitleOptionStrings(
                    embedded = context.getString(R.string.subtitle_origin_embedded),
                    external = context.getString(R.string.subtitle_origin_external),
                    downloaded = context.getString(R.string.subtitle_origin_downloaded),
                    ocr = context.getString(R.string.subtitle_origin_ocr),
                    burnedIn = context.getString(R.string.subtitle_burned_in),
                    machine = context.getString(R.string.subtitle_badge_machine),
                )
                val audio = if (viewModel.isDirect) {
                    val nameProvider = DefaultTrackNameProvider(context.resources)
                    directAudioTracks().map { (group, index) ->
                        val format = group.getTrackFormat(index)
                        val channels = channelLabel(format.channelCount, mono, stereo)
                        val title = languageDisplayName(format.language)
                            ?: format.label
                            ?: nameProvider.getTrackName(format)
                        TrackOption(
                            title = title,
                            detail = listOfNotNull(
                                format.label?.takeIf { it != title },
                                audioCodecName(format.sampleMimeType),
                                channels,
                            ).joinToString(" · ").ifBlank { null },
                            summary = listOfNotNull(title, channels).joinToString(" · "),
                            selected = group.isTrackSelected(index),
                        )
                    }
                } else {
                    viewModel.negotiatedAudio().mapIndexed { index, stream ->
                        val channels = channelLabel(stream.channels, mono, stereo)
                        val title = languageDisplayName(stream.language)
                            ?: stream.language?.uppercase()
                            ?: audioCodecName(stream.codec).orEmpty()
                        TrackOption(
                            title = title,
                            detail = listOfNotNull(audioCodecName(stream.codec), channels).joinToString(" · ").ifBlank { null },
                            summary = listOfNotNull(title, channels).joinToString(" · "),
                            selected = index == viewModel.currentAudioTrack,
                        )
                    }
                }
                PlayerPanelModel(
                    episodes = episodes.map { episode ->
                        EpisodeOption(
                            id = episode.id,
                            season = episode.season?.let {
                                if (it == 0) context.getString(R.string.detail_season_specials) else context.getString(R.string.detail_season, it)
                            },
                            title = listOfNotNull(episode.episode?.let { "$it." }, episode.title).joinToString(" "),
                            artworkUrl = catalogRepo.artworkUrl(episode.libraryId, episode.id, "card"),
                            resumePositionMs = episode.resumePositionMs,
                            resumeDurationMs = episode.resumeDurationMs,
                            played = episode.played,
                            current = episode.id == viewModel.itemId,
                        )
                    },
                    subtitles = listOf(
                        TrackOption(context.getString(R.string.subtitle_off), selected = selectedSubtitle == null),
                    ) + subtitleTracks.map { subtitleOption(it, it.id == selectedSubtitle?.id, subtitleStrings) },
                    audio = audio,
                    pictureSizes = RESIZE_MODES.mapIndexed { index, (_, labelRes) ->
                        TrackOption(context.getString(labelRes), selected = index == resizeModeIndex)
                    },
                    speeds = PLAYBACK_SPEEDS.map { TrackOption(formatSpeed(it), selected = it == playbackSpeed) },
                    autoSkip = autoSkipEnabled.takeIf { segments.isNotEmpty() },
                    autoPlayNext = autoPlayNext.takeIf { adjacentEpisodes?.nextId != null },
                )
            }
            PlayerSidePanel(
                stack = panelStack,
                onStackChange = { panelStack = it },
                model = panelModel,
                onSelectSubtitle = ::selectSubtitle,
                onSelectAudio = ::selectAudio,
                onSelectPictureSize = ::selectPictureSize,
                onSelectSpeed = { viewModel.player.setPlaybackSpeed(PLAYBACK_SPEEDS[it]) },
                onAutoSkipChange = { autoSkipEnabled = it },
                onAutoPlayNextChange = viewModel::setAutoPlayNext,
                onSelectEpisode = { index ->
                    val episode = episodes[index]
                    // The one already playing just carries on.
                    if (episode.id != viewModel.itemId) {
                        val startMs = episode.resumePositionMs?.takeIf { !episode.played } ?: 0L
                        onPlayEpisode(episode.id, startMs, viewModel.selectedSubtitleTrack.value?.id)
                    }
                },
            )

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}
