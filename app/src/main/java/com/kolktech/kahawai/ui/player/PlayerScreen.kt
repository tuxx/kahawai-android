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
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
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
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.data.network.dto.displayLabel
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
    view.subtitleView?.setPadding(0, 0, 0, cueBottomPaddingPx(frameH, view.height.toFloat(), insetPx))
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
    onPreviousEpisode: (itemId: String, subtitleTrackId: Long?) -> Unit = { _, _ -> },
    initialAudioTrack: Int = -1,
    initialSubtitleTrackId: Long? = null,
    libraryId: String? = null,
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
            is PlayerState.Ready -> PlayerContent(viewModel, appSettingsStore, closeAndPause, onNextEpisode, onPreviousEpisode)
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
    onPreviousEpisode: (itemId: String, subtitleTrackId: Long?) -> Unit,
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
    val subtitleSession by viewModel.subtitleSession.collectAsState()
    val transientError by viewModel.transientError.collectAsState()
    val hdrActive by viewModel.hdrActive.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val segments by viewModel.segments.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    // Read once, same as the gesture toggles above: not a value that
    // should change out from under an already-open player.
    val autoSkipEnabled = remember { appSettingsStore.autoSkipIntrosOutros }

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
    var skipCheckPosMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(segments) {
        if (segments.isEmpty()) return@LaunchedEffect
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

    // Mirrors the render condition below (the Skip button itself): true
    // exactly when that button is on screen and able to take the OK key.
    val skipButtonVisible = !isInPip && !autoSkipEnabled && skippingSegment != null && skipLabelRes(skippingSegment) != null
    val skipButtonVisibleState = rememberUpdatedState(skipButtonVisible)

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
                    view.dispatchMediaKeyEvent(event) -> {
                        // Flash the controls up so ff/rew has a timebar to
                        // aim by - except in PiP, whose window has its own.
                        if (!mainActivity.isInPictureInPictureMode) view.showController()
                        true
                    }
                    event.keyCode in DPAD_KEYS && !view.isControllerFullyVisible -> {
                        if (event.action == KeyEvent.ACTION_DOWN) view.showController()
                        true
                    }
                    // OK/enter with the controls hidden toggles play/pause
                    // in place - once the controls are up, OK reaches the
                    // (focused) play/pause button itself and this branch
                    // is skipped. A resulting pause surfaces the controls
                    // anyway (see the isPaused effect below, which reacts
                    // to the player state this produces rather than being
                    // told to show them here). Same deal when the Skip
                    // button is up: it already holds D-pad focus (see
                    // skipButtonFocusRequester below), so OK must fall
                    // through to normal dispatch and land on it instead of
                    // pausing underneath it.
                    event.keyCode in OK_KEYS && !view.isControllerFullyVisible && !skipButtonVisibleState.value -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (viewModel.player.isPlaying) viewModel.player.pause() else viewModel.player.play()
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
                if (inPip) playerView?.hideController()
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

    // Anchored popup, same style as the resize/speed menus below, instead
    // of TrackSelectionDialogBuilder's modal AlertDialog. Reached through
    // the settings gear menu — portrait screens don't have bottom-bar
    // width for a dedicated audio button.
    fun showAudioTrackMenu(menuContext: Context, anchor: View) {
        val groups = viewModel.player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return
        val nameProvider = DefaultTrackNameProvider(menuContext.resources)
        val overrides = viewModel.player.trackSelectionParameters.overrides
        val hasAudioOverride = overrides.values.any { it.type == C.TRACK_TYPE_AUDIO }
        PopupMenu(menuContext, anchor).apply {
            val selectionForItem = mutableMapOf<Int, Pair<TrackGroup, Int>?>()
            var itemId = 0
            var checkedItemId = if (hasAudioOverride) -1 else 0
            menu.add(0, itemId, itemId, menuContext.getString(R.string.default_label))
            selectionForItem[itemId] = null
            itemId++
            groups.forEach { group ->
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    menu.add(0, itemId, itemId, nameProvider.getTrackName(group.getTrackFormat(trackIndex)))
                    selectionForItem[itemId] = group.mediaTrackGroup to trackIndex
                    if (overrides[group.mediaTrackGroup]?.trackIndices?.contains(trackIndex) == true) {
                        checkedItemId = itemId
                    }
                    itemId++
                }
            }
            menu.setGroupCheckable(0, true, true)
            menu.findItem(checkedItemId)?.isChecked = true
            setOnMenuItemClickListener { item ->
                val selection = selectionForItem[item.itemId]
                val params = viewModel.player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                if (selection != null) params.addOverride(TrackSelectionOverride(selection.first, selection.second))
                viewModel.player.trackSelectionParameters = params.build()
                // Remembered for the rest of the series (HUB-33), by the
                // language the chosen format declares — see
                // PlayerViewModel.rememberAudioLanguage.
                selection?.let { (group, trackIndex) ->
                    viewModel.rememberAudioLanguage(group.getFormat(trackIndex).language)
                }
                true
            }
        }.show()
    }

    // Sourced from the hub's own track+delivery list (viewModel.subtitleTracks),
    // not player.currentTracks.groups — Ass/Overlay delivery never
    // becomes an ExoPlayer text track at all, only Text delivery does.
    fun showSubtitleTrackMenu(menuContext: Context, anchor: View) {
        val tracks = viewModel.subtitleTracks.value
        val selected = viewModel.selectedSubtitleTrack.value
        PopupMenu(menuContext, anchor).apply {
            val trackForItem = mutableMapOf<Int, SubtitleTrack?>()
            var itemId = 0
            var checkedItemId = if (selected == null) 0 else -1
            menu.add(0, itemId, itemId, menuContext.getString(R.string.subtitle_off))
            trackForItem[itemId] = null
            itemId++
            tracks.forEach { track ->
                val menuItem = menu.add(0, itemId, itemId, track.displayLabel())
                // Burn restarts the session with a forced video encode and
                // "none" can't be served at all — offer them but don't
                // pretend they're a free client-side switch like the rest.
                menuItem.isEnabled = track.delivery != "none"
                if (track.id == selected?.id) checkedItemId = itemId
                trackForItem[itemId] = track
                itemId++
            }
            menu.setGroupCheckable(0, true, true)
            menu.findItem(checkedItemId)?.isChecked = true
            setOnMenuItemClickListener { item ->
                if (trackForItem.containsKey(item.itemId)) {
                    viewModel.selectSubtitleTrack(trackForItem[item.itemId])
                }
                true
            }
        }.show()
    }

    fun showResizeMenu(menuContext: Context, anchor: View) {
        PopupMenu(menuContext, anchor).apply {
            RESIZE_MODES.forEachIndexed { index, (_, labelRes) -> menu.add(0, index, index, menuContext.getString(labelRes)) }
            menu.setGroupCheckable(0, true, true)
            menu.findItem(resizeModeIndex)?.isChecked = true
            setOnMenuItemClickListener { item ->
                applyResize(item.itemId)
                flash(GestureIndicator.Resize(menuContext.getString(RESIZE_MODES[item.itemId].second)))
                true
            }
        }.show()
    }

    fun showSpeedMenu(menuContext: Context, anchor: View) {
        PopupMenu(menuContext, anchor).apply {
            PLAYBACK_SPEEDS.forEachIndexed { index, speed -> menu.add(0, index, index, "${speed}x") }
            menu.setGroupCheckable(0, true, true)
            PLAYBACK_SPEEDS.indexOfFirst { it == viewModel.player.playbackParameters.speed }
                .takeIf { it >= 0 }
                ?.let { menu.findItem(it)?.isChecked = true }
            setOnMenuItemClickListener { item ->
                viewModel.player.setPlaybackSpeed(PLAYBACK_SPEEDS[item.itemId])
                true
            }
        }.show()
    }

    // Media3's settings gear menu (speed / audio track) is built by a
    // private, non-extensible adapter inside PlayerControlView, so there's
    // no public way to add a "video aspect" row into it. We take over the
    // gear button's click entirely and show our own two-level menu instead,
    // covering what the native one offered (speed / audio track) plus
    // resize mode. Each row shows its current value so the state is
    // visible without opening the submenu.
    fun showSettingsMenu(menuContext: Context, anchor: View) {
        val speed = viewModel.player.playbackParameters.speed
        val nameProvider = DefaultTrackNameProvider(menuContext.resources)
        val audioGroups = viewModel.player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val currentAudio = audioGroups.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                ?.let { nameProvider.getTrackName(group.getTrackFormat(it)) }
        }
        PopupMenu(menuContext, anchor).apply {
            menu.add(0, 0, 0, menuContext.getString(R.string.player_settings_video, menuContext.getString(RESIZE_MODES[resizeModeIndex].second)))
            menu.add(0, 1, 1, menuContext.getString(R.string.player_settings_speed, "${speed}x"))
            if (audioGroups.isNotEmpty()) {
                menu.add(
                    0,
                    2,
                    2,
                    menuContext.getString(R.string.player_settings_audio, currentAudio ?: menuContext.getString(R.string.default_label)),
                )
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> showResizeMenu(menuContext, anchor)
                    1 -> showSpeedMenu(menuContext, anchor)
                    2 -> showAudioTrackMenu(menuContext, anchor)
                }
                true
            }
        }.show()
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
                    // kw_player_control_view's focus order - kw_prev
                    // ahead of exo_play_pause - the moment a previous
                    // episode makes that button visible) as soon as the
                    // controls appear, whichever path showed them. No-op
                    // in touch mode, where none of these buttons take
                    // focus.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsShown = visibility == View.VISIBLE
                            if (visibility == View.VISIBLE) {
                                findViewById<ImageButton>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
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
                    findViewById<ImageButton>(androidx.media3.ui.R.id.exo_settings).setOnClickListener { anchor ->
                        showSettingsMenu(ctx, anchor)
                    }
                    findViewById<ImageButton>(androidx.media3.ui.R.id.exo_subtitle).setOnClickListener { anchor ->
                        showSubtitleTrackMenu(ctx, anchor)
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
                        itemId = viewModel.itemId,
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
            playerView?.findViewById<TextView>(R.id.kw_player_title)?.text = title.orEmpty()
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

        // kw_prev/kw_next (see kw_player_control_view.xml for why they're
        // not exo_prev/exo_next) stay gone until adjacentEpisodes resolves
        // an id on that side — a movie or the first/last episode of a show
        // leaves the corresponding button hidden rather than shown-but-
        // useless. subtitleTrack id rides along so the next/previous
        // episode opens with the same language already selected.
        val adjacentEpisodes by viewModel.adjacentEpisodes.collectAsState()
        LaunchedEffect(adjacentEpisodes, playerView) {
            val previousId = adjacentEpisodes?.previousId
            val nextId = adjacentEpisodes?.nextId
            playerView?.findViewById<ImageButton>(R.id.kw_prev)?.apply {
                visibility = if (previousId != null) View.VISIBLE else View.GONE
                setOnClickListener {
                    previousId?.let { onPreviousEpisode(it, viewModel.selectedSubtitleTrack.value?.id) }
                }
            }
            playerView?.findViewById<ImageButton>(R.id.kw_next)?.apply {
                visibility = if (nextId != null) View.VISIBLE else View.GONE
                setOnClickListener {
                    nextId?.let { onNextEpisode(it, viewModel.selectedSubtitleTrack.value?.id) }
                }
            }
        }

        // Center pause glyph — feedback that playback is actually paused
        // (as opposed to stalled/still loading) whether the viewer paused
        // via the controls or, with them hidden, an OK/enter press (see
        // the onPlayerKey branch above). Excluded from PiP, whose tiny
        // window has no room for anything but the video itself.
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
        LaunchedEffect(isPaused, isInPip, playerView) {
            val view = playerView ?: return@LaunchedEffect
            if (isPaused && !isInPip) {
                view.controllerShowTimeoutMs = 0
                view.showController()
            } else {
                view.controllerShowTimeoutMs = DEFAULT_CONTROLLER_SHOW_TIMEOUT_MS
                view.hideController()
            }
        }

        if (isPaused && !isInPip) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(88.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(androidx.media3.ui.R.drawable.exo_icon_pause),
                    contentDescription = stringResource(androidx.media3.ui.R.string.exo_controls_pause_description),
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
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

        // HUB-37. Bottom-end, clear of the transport, in its own corner
        // regardless of whether the controls happen to be showing — an
        // offer the viewer can act on any time, not just while they're
        // interacting with the controller. Never drawn while auto-skip
        // is on: at that point the LaunchedEffect above has already
        // fired the seek, and a button that would show for a stray 300ms
        // frame before the seek lands is just a flicker, not an offer.
        val skipLabelResId = skipLabelRes(skippingSegment)
        if (skipButtonVisible && skipLabelResId != null) {
            // Grabbing focus is only useful with a d-pad: on TV there's no
            // pointer to click the button with, so unless it's focused a
            // d-pad press has nothing to act on and the offer is dead.
            // The OK-key handler above defers to this focus too, so the
            // button — not play/pause — is always what an OK press hits
            // while it's up.
            val skipButtonFocusRequester = remember { FocusRequester() }
            var skipButtonFocused by remember { mutableStateOf(false) }
            if (isTv) {
                LaunchedEffect(Unit) { skipButtonFocusRequester.requestFocus() }
            }
            Button(
                onClick = {
                    val segment = skippingSegment
                    viewModel.player.seekTo(skipTargetMs(segment, viewModel.player.duration))
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 96.dp)
                    .focusRequester(skipButtonFocusRequester)
                    .onFocusChanged { skipButtonFocused = it.isFocused }
                    .border(
                        width = if (skipButtonFocused) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = ButtonDefaults.shape,
                    ),
            ) {
                Text(stringResource(skipLabelResId))
            }
        }

        if (!isInPip) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}
