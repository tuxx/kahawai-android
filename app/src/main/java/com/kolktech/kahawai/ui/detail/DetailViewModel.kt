package com.kolktech.kahawai.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.kolktech.kahawai.data.network.dto.CatalogueChildGroup
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.network.dto.ItemDetail
import com.kolktech.kahawai.data.network.dto.Pref
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.data.network.isAuthError
import com.kolktech.kahawai.data.network.readableMessage
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.data.repository.PreferencesRepository
import com.kolktech.kahawai.playback.CapabilityProfileBuilder
import com.kolktech.kahawai.playback.PREF_AUDIO
import com.kolktech.kahawai.playback.PREF_AUDIO_TRACK
import com.kolktech.kahawai.playback.needsMediaType
import com.kolktech.kahawai.playback.rememberedAudioValue
import com.kolktech.kahawai.playback.PREF_SUBS
import com.kolktech.kahawai.playback.PREF_SUBS_TRACK
import com.kolktech.kahawai.playback.rememberedSubsTrackValue
import com.kolktech.kahawai.playback.rememberedSubsValue
import com.kolktech.kahawai.playback.resolveSubtitleTrack
import com.kolktech.kahawai.playback.negotiatePlayback
import com.kolktech.kahawai.playback.sourcePreferenceScope
import com.kolktech.kahawai.data.network.dto.negotiatedAudioStreams

/// Containers with no media of their own — you drill into a child
/// (episode/track) to get a Play button.
internal val NOT_DIRECTLY_PLAYABLE = setOf("show", "album")

/// The episode a "Next up" button should start: one left partway through,
/// else the first unwatched one after the furthest watched, else the first
/// unwatched at all (a viewer who skipped ahead and came back). Specials
/// count only where there is nothing else, as on the Specials page itself.
/// Null once everything is watched.
internal fun nextUpEpisode(children: List<Item>): Item? {
    val episodes = children.filter { it.kind == "episode" }.let { all -> all.filter { it.season != 0 }.ifEmpty { all } }
    episodes.firstOrNull { !it.played && (it.resumePositionMs ?: 0) > 0 }?.let { return it }
    val lastWatched = episodes.indexOfLast { it.played }
    return episodes.drop(lastWatched + 1).firstOrNull { !it.played }
        ?: episodes.firstOrNull { !it.played }
}

sealed interface DetailState {
    data object Loading : DetailState
    data class Error(val message: String, val isAuthError: Boolean = false) : DetailState
    data class Loaded(
        val detail: ItemDetail,
        val children: List<Item>,
        val seasons: List<CatalogueChildGroup> = emptyList(),
        /// The episode a show or season page offers to play next. Null for
        /// anything else, and once every episode is watched.
        val nextUp: Item? = null,
        val subtitleTracks: List<SubtitleTrack> = emptyList(),
        val selectedSubtitleTrack: SubtitleTrack? = null,
        val selectedAudioTrackIndex: Int = 0,
        /// The source the negotiation chose — Play pins the session to it so
        /// the audio index and subtitle track ids stay meaningful (they are
        /// per-source). Null when nothing is negotiable.
        val sourceId: Int? = null,
        /// What the hub would pick on its own. Shown as such in the picker,
        /// so choosing by hand is visibly a departure from it rather than an
        /// indistinguishable alternative.
        val automaticSourceId: Int? = null,
        /// Whether [sourceId] is a hand-picked override rather than
        /// [automaticSourceId].
        val sourcePinned: Boolean = false,
        /// True while a "Mark watched"/"Mark unwatched" call is in
        /// flight — disables the button so a slow link can't queue a
        /// second toggle behind the first.
        val watchedActionInFlight: Boolean = false,
    ) : DetailState
}

class DetailViewModel(
    application: Application,
    private val repo: CatalogRepository,
    private val itemId: String,
    /// Which library this item is in. Required, not optional: every
    /// catalogue route is library-scoped now, so without it the item
    /// cannot be fetched at all — and it is also the only route to the
    /// media type the account's track lists are keyed by (see TrackChoice).
    private val libraryId: String,
    private val prefsRepo: PreferencesRepository = PreferencesRepository(),
    private val season: SeasonFilter? = null,
) : AndroidViewModel(application) {
    /// The scope a subtitle pick is remembered under (HUB-33): the show, so
    /// every episode of it opens the same way. Known once the item loads.
    private var seriesId: String? = null

    /// Where this item's EXACT track choices live — keyed by the negotiated
    /// physical source, not by the item (see [sourcePreferenceScope]). Known
    /// once the QUERY answers, and null while nothing is negotiated.
    private var sourceScope: String? = null

    /// A source the viewer picked by hand, which outranks whatever the hub
    /// would negotiate. Transient like the web client's own override: it
    /// describes this visit to the page, not a standing choice, and source
    /// ids are only stable within one response anyway.
    private var pinnedSourceId: Int? = null

    /// What the hub chooses when nothing is pinned. Captured from the FIRST,
    /// unpinned negotiation and then held: once a pin is in place the hub
    /// simply agrees with the pin, so asking it again would answer the wrong
    /// question. The UI marks this one so "automatic" is never a mystery.
    private var automaticSourceId: Int? = null

    /// Preferences and media type, kept from the load so a source change can
    /// re-rank without re-reading them.
    private var knownPrefs: List<Pref> = emptyList()
    private var mediaType: String = ""
    private var prefsJob: Job? = null
    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = _state

    private val _transientError = MutableStateFlow<String?>(null)
    val transientError: StateFlow<String?> = _transientError

    fun clearTransientError() {
        _transientError.value = null
    }

    init {
        load()
    }

    private suspend fun loadContainer(detail: ItemDetail): Container {
        val seasons = if (detail.kind == "show") repo.seasons(libraryId, itemId) else emptyList()
        val showGrid = detail.kind == "show" && seasons.size > 1 && season == null
        val children = if (detail.kind in NOT_DIRECTLY_PLAYABLE && !showGrid) {
            repo.children(libraryId, itemId, season = season?.let { it.number?.toString() ?: "absolute" })
        } else {
            emptyList()
        }
        // A season grid has no episode rows of its own, and a season can
        // run to hundreds of them, so the show's place is asked of the
        // home feeds rather than worked out from a downloaded season. A
        // show nobody has started opens on its first episode. Best-effort:
        // without it the page just has no Next up button.
        val nextUp = if (showGrid) {
            runCatching {
                repo.seriesNextUp(libraryId, itemId) ?: seasons
                    .takeIf { groups -> groups.none { it.played > 0 } }
                    ?.firstOrNull { it.kind == "episode" && it.number != 0 }
                    ?.let { first -> repo.firstEpisode(libraryId, itemId, first.number?.toString() ?: "absolute") }
            }.getOrNull()
        } else {
            nextUpEpisode(children)
        }
        return Container(seasons, children, nextUp)
    }

    private data class Container(val seasons: List<CatalogueChildGroup>, val children: List<Item>, val nextUp: Item?)

    fun load() {
        _state.value = DetailState.Loading
        viewModelScope.launch {
            try {
                // QUERY (not GET): one request negotiates the item's
                // source and returns it plus per-source stream info
                // (audio tracks, duration — GET no longer carries these,
                // kahawai commit 5147059) and the subtitle track list
                // with delivery computed for this profile (replaces the
                // deleted `GET /items/{id}/subtitles` call).
                val profile = CapabilityProfileBuilder.build(getApplication())
                // Both best-effort and both started before the QUERY they
                // overlap: preferences that failed to load cost the
                // remembered subtitle pick, not the screen.
                val prefs = async { runCatching { prefsRepo.all() }.getOrDefault(emptyList()) }
                val libraries = async(start = CoroutineStart.LAZY) {
                    runCatching { repo.libraries() }.getOrDefault(emptyList())
                }
                // The first QUERY can only rank sources on audio index 0 —
                // stream lists arrive with it. It doubles as the page's own
                // data and as the negotiation's preview, so the common case
                // (everything already prefers index 0) costs one request.
                val preview = repo.queryItem(libraryId, itemId, profile)
                seriesId = preview.parentId ?: preview.id
                val known = prefs.await()
                knownPrefs = known
                mediaType = if (needsMediaType(known)) {
                    libraries.await().firstOrNull { it.id == libraryId }?.mediaType ?: ""
                } else {
                    ""
                }
                // Re-ranks the sources on the audio each would actually be
                // played with, when that isn't index 0 — see
                // [negotiatePlayback]. The result is the source Play will
                // pin, so the page draws the same one that will be served.
                val choice = negotiatePlayback(
                    repo = repo,
                    libraryId = libraryId,
                    itemId = itemId,
                    profile = profile,
                    prefs = known,
                    mediaType = mediaType,
                    preview = preview,
                    pinnedSourceId = pinnedSourceId,
                )
                val detail = choice.item
                if (pinnedSourceId == null) automaticSourceId = choice.sourceId
                val (seasons, children, nextUp) = loadContainer(detail)
                val subtitleTracks = detail.negotiated?.subtitles ?: emptyList()
                sourceScope = sourcePreferenceScope(detail.sources, choice.sourceId)
                // What this title is remembered as being watched with, so the
                // pickers open on the tracks Play is about to use rather than
                // on "none" and track zero (see TrackChoice). The audio index
                // came out of the negotiation, already resolved against the
                // source it chose.
                val selected = resolveSubtitleTrack(
                    prefs = known,
                    seriesId = seriesId ?: itemId,
                    sourceScope = sourceScope,
                    mediaType = mediaType,
                    tracks = subtitleTracks,
                )
                _state.value = DetailState.Loaded(
                    detail = detail,
                    children = children,
                    seasons = seasons,
                    nextUp = nextUp,
                    subtitleTracks = subtitleTracks,
                    selectedSubtitleTrack = selected,
                    selectedAudioTrackIndex = choice.audioTrack,
                    sourceId = choice.sourceId,
                    automaticSourceId = automaticSourceId,
                    sourcePinned = pinnedSourceId != null,
                )
            } catch (e: Exception) {
                _state.value = DetailState.Error(e.readableMessage(), e.isAuthError())
            }
        }
    }

    /// Play this item from a specific physical source — or, with null, hand
    /// the choice back to the hub.
    ///
    /// Re-runs the whole negotiation pinned to [sourceId] rather than just
    /// relabelling the page: the streams, the delivery plan, the subtitle
    /// track list and the audio index are all properties of the chosen file,
    /// so a page still showing the previous source's answers would be
    /// describing something else. Keeps the current content on screen while
    /// it runs — a source change is not a reason to blank a page that
    /// already has one.
    fun selectSource(sourceId: Int?) {
        val current = _state.value as? DetailState.Loaded ?: return
        if (sourceId == pinnedSourceId) return
        val previousPin = pinnedSourceId
        pinnedSourceId = sourceId
        viewModelScope.launch {
            try {
                val profile = CapabilityProfileBuilder.build(getApplication())
                val choice = negotiatePlayback(
                    repo = repo,
                    libraryId = libraryId,
                    itemId = itemId,
                    profile = profile,
                    prefs = knownPrefs,
                    mediaType = mediaType,
                    pinnedSourceId = sourceId,
                )
                val detail = choice.item
                val subtitleTracks = detail.negotiated?.subtitles ?: emptyList()
                sourceScope = sourcePreferenceScope(detail.sources, choice.sourceId)
                val selected = resolveSubtitleTrack(
                    prefs = knownPrefs,
                    seriesId = seriesId ?: itemId,
                    sourceScope = sourceScope,
                    mediaType = mediaType,
                    tracks = subtitleTracks,
                )
                val loaded = _state.value as? DetailState.Loaded ?: return@launch
                _state.value = loaded.copy(
                    detail = detail,
                    subtitleTracks = subtitleTracks,
                    selectedSubtitleTrack = selected,
                    selectedAudioTrackIndex = choice.audioTrack,
                    sourceId = choice.sourceId,
                    automaticSourceId = automaticSourceId,
                    sourcePinned = sourceId != null,
                )
            } catch (e: Exception) {
                // Put the pin back: the page is still showing the source that
                // actually answered.
                pinnedSourceId = previousPin
                _transientError.value = e.readableMessage()
            }
        }
    }

    /// Re-fetch for a screen that's already showing data — returning from
    /// the player (resume position moved), or the app coming back to the
    /// foreground. Unlike load() this never drops to Loading: the stale
    /// content stays up and is swapped in place, so there's no spinner
    /// flash and no D-pad focus loss. The Loaded guard doubles as
    /// first-composition protection — ON_RESUME also fires right after
    /// init{}'s load() starts, while state is still Loading, and this
    /// silently skips instead of racing it. Track selections survive via
    /// copy(); a failed refresh keeps showing what we have.
    fun refresh() {
        if (_state.value !is DetailState.Loaded) return
        viewModelScope.launch {
            try {
                val profile = CapabilityProfileBuilder.build(getApplication())
                // Honours a hand-picked source: coming back from the player
                // is not a reason to quietly hand the choice back to the hub.
                val choice = negotiatePlayback(
                    repo = repo,
                    libraryId = libraryId,
                    itemId = itemId,
                    profile = profile,
                    prefs = knownPrefs,
                    mediaType = mediaType,
                    pinnedSourceId = pinnedSourceId,
                )
                val detail = choice.item
                if (pinnedSourceId == null) automaticSourceId = choice.sourceId
                val (seasons, children, nextUp) = loadContainer(detail)
                sourceScope = sourcePreferenceScope(detail.sources, choice.sourceId)
                val current = _state.value as? DetailState.Loaded ?: return@launch
                _state.value = current.copy(
                    detail = detail,
                    children = children,
                    seasons = seasons,
                    nextUp = nextUp,
                    subtitleTracks = detail.negotiated?.subtitles ?: emptyList(),
                    sourceId = choice.sourceId,
                    automaticSourceId = automaticSourceId,
                    sourcePinned = pinnedSourceId != null,
                )
            } catch (e: Exception) {
                // Best-effort; the screen already has content to show.
            }
        }
    }

    /// Ticks this item watched/unwatched with no playback session
    /// (mirrors web's `useWatched().mark`, item.ts). Marking either
    /// direction clears the resume position server-side, so the local
    /// copy drops it too rather than waiting for a re-fetch.
    fun toggleWatched() {
        val current = _state.value as? DetailState.Loaded ?: return
        if (current.watchedActionInFlight) return
        val target = !current.detail.played
        _state.value = current.copy(watchedActionInFlight = true)
        viewModelScope.launch {
            try {
                val update = repo.setWatched(libraryId, itemId, target)
                val loaded = _state.value as? DetailState.Loaded ?: return@launch
                _state.value = loaded.copy(
                    detail = loaded.detail.copy(
                        played = update.played,
                        resumePositionMs = update.positionMs,
                    ),
                    watchedActionInFlight = false,
                )
            } catch (e: Exception) {
                val loaded = _state.value as? DetailState.Loaded ?: return@launch
                _state.value = loaded.copy(watchedActionInFlight = false)
                _transientError.value = e.readableMessage()
            }
        }
    }

    /// A pick here is a pick, not a one-playback override: it's remembered
    /// exactly as the player's own picker remembers one, so choosing a
    /// language on the show's page carries into every episode of it — and
    /// choosing none stays none, which nothing else could express (Play
    /// carries the chosen id, and "none" and "nothing chosen" are the same
    /// absent id). See PlayerViewModel.rememberSubtitlePick for the pair.
    fun selectSubtitleTrack(track: SubtitleTrack?) {
        val current = _state.value as? DetailState.Loaded ?: return
        _state.value = current.copy(selectedSubtitleTrack = track)
        val series = seriesId ?: return
        val previous = prefsJob
        prefsJob = viewModelScope.launch {
            previous?.join()
            runCatching {
                prefsRepo.put(series, PREF_SUBS, rememberedSubsValue(track))
                // The exact row is the source's, not the item's: a track id
                // only means anything against the file it came from.
                sourceScope?.let { prefsRepo.put(it, PREF_SUBS_TRACK, rememberedSubsTrackValue(track)) }
            }
        }
    }

    /// Remembered like a subtitle pick, in the two layers the web client
    /// writes (HUB-33): the series remembers the LANGUAGE, which is what
    /// carries across episodes whose track order differs, and a FILM also
    /// pins the exact index — "the commentary track of this film" has no
    /// language representation, and there is no series intent to follow.
    /// Episodes deliberately don't pin, so one episode never freezes the
    /// rest of the series on an index that meant something only in it.
    fun selectAudioTrackIndex(index: Int) {
        val current = _state.value as? DetailState.Loaded ?: return
        _state.value = current.copy(selectedAudioTrackIndex = index)
        val series = seriesId ?: return
        val streams = current.detail.negotiatedAudioStreams()
        val previous = prefsJob
        prefsJob = viewModelScope.launch {
            previous?.join()
            runCatching {
                prefsRepo.put(series, PREF_AUDIO, rememberedAudioValue(streams.getOrNull(index), index))
                if (current.detail.kind == "movie") {
                    sourceScope?.let { prefsRepo.put(it, PREF_AUDIO_TRACK, "#$index") }
                }
            }
        }
    }
}
