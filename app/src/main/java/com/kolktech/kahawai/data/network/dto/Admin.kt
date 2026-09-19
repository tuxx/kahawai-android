package com.kolktech.kahawai.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/// DTOs for the hub's `/admin/v1/*` surface (crates/kahawai-hub/src/api.rs).
/// Requires the JWT `admin` claim — see
/// [com.kolktech.kahawai.data.auth.TokenStore.isAdmin]. Library and
/// collection administration moved under `/admin/v1/catalogue/*` with the
/// mediadb rewrite; shapes are authoritative in web/openapi.json.

@Serializable
data class PendingEnrollment(
    val csrFingerprint: String,
    val moduleType: String,
    val moduleId: String,
    val name: String,
)

@Serializable
data class EnrollmentsResponse(val pending: List<PendingEnrollment>)

@Serializable
data class ApproveRequest(val code: String)

@Serializable
data class ApproveResponse(val approved: String)

/// One verified encoder and what it was measured doing (HUB-36). Speeds
/// are realtime multiples; null = never measured, not the same as slow.
@Serializable
data class EncoderCap(
    val codec: String,
    val element: String,
    val hardware: Boolean,
    val speed1080: Double? = null,
    val speed2160: Double? = null,
)

@Serializable
data class SatelliteCaps(
    val encoders: List<EncoderCap> = emptyList(),
    val maxSessions: Int? = null,
    val tonemap: Boolean? = null,
    val tonemapSpeed1080: Double? = null,
    val tonemapSpeed2160: Double? = null,
)

/// What a box has ACHIEVED on a kind of work, as opposed to what its
/// benchmark claims. `class` is `{res}|{src}|{dst}[|tm]`.
@Serializable
data class PaceRow(
    @SerialName("class") val cls: String,
    val multiple: Double,
)

@Serializable
data class Satellite(
    val moduleId: String,
    val moduleType: String,
    val name: String,
    val certFingerprint: String,
    val connected: Boolean,
    val disabled: Boolean,
    val capabilities: SatelliteCaps? = null,
    val pace: List<PaceRow> = emptyList(),
    val linkBytesPerSec: Long? = null,
    val build: String? = null,
    val enrolledAt: Long = 0,
)

@Serializable
data class SatellitesResponse(val satellites: List<Satellite>)

@Serializable
data class SetDisabledRequest(val disabled: Boolean)

/// A collection is one opaque id now, not a (module, collection) pair: it
/// is owned by a mediahost (`mediahostId`) and identified there by
/// `remoteId`, but a library refers to it only by [id].
@Serializable
data class CatalogueCollection(
    val id: String,
    val mediaType: String,
    val mediahostId: String,
    val remoteId: String,
    val connected: Boolean = false,
    val scanning: Boolean = false,
    val snapshot: Boolean = false,
    val fileCount: Int = 0,
    val version: Int = 0,
    val epoch: String = "",
    val roots: List<CatalogueRoot> = emptyList(),
)

@Serializable
data class CatalogueRoot(
    val id: String,
    val path: String,
    val token: String = "",
    val active: Boolean = false,
)

@Serializable
data class CreateLibraryRequest(
    val name: String,
    val mediaType: String,
    val collectionIds: List<String> = emptyList(),
)

/// `PUT /admin/v1/catalogue/libraries/{id}/collections` — the membership
/// is SET, not patched. The old attach/detach pair is gone: send the whole
/// list you want the library to end up with.
@Serializable
data class CatalogueMembership(val collectionIds: List<String>)

@Serializable
data class RefreshLibraryResponse(
    val asked: Int = 0,
    val offline: Int = 0,
    val unsupported: Int = 0,
)

/// HUB-5 provider precedence: earlier providers own a field, later ones
/// only fill what's left empty.
@Serializable
data class ProviderChain(val order: List<String>, val default: List<String>)

@Serializable
data class TheAudioDbConfiguration(val premiumKeyConfigured: Boolean = false)

@Serializable
data class ProvidersResponse(
    val tmdb: ProviderConfiguration,
    val tvdb: ProviderConfiguration,
    val anidb: ProviderConfiguration,
    val fanart: ProviderConfiguration,
    val theaudiodb: TheAudioDbConfiguration,
    /// Every provider the hub can chain, in no particular order — the
    /// chain editor's candidate list.
    val available: List<String> = emptyList(),
    val chains: Map<String, ProviderChain> = emptyMap(),
)

@Serializable
data class SetFanartRequest(val clientKey: String)

@Serializable
data class SetTheAudioDbRequest(val apiKey: String)

@Serializable
data class SetChainRequest(val order: List<String>)

@Serializable
data class SetTmdbRequest(val apiKey: String)

@Serializable
data class SetTvdbRequest(val apiKey: String, val pin: String? = null)

@Serializable
data class SetAnidbRequest(val username: String, val password: String, val udpApiKey: String? = null)

@Serializable
data class SetAnidbResponse(val saved: Boolean, val verified: Boolean, val error: String? = null)

@Serializable
data class SavedResponse(val saved: Boolean)

@Serializable
data class EnrichStatusResponse(val running: Boolean, val matched: Int, val weak: Int, val missed: Int)

@Serializable
data class EnrichRunResponse(val started: Boolean)

@Serializable
data class AdminSession(
    val sessionId: String,
    val username: String? = null,
    val title: String? = null,
    val mode: String,
    val moduleId: String,
    val idleSecs: Long,
    val streams: SessionStreamSummary? = null,
)

@Serializable
data class SessionStreamSummary(
    val video: String = "",
    val audio: String = "",
    val cost: String? = null,
)

@Serializable
data class SessionsResponse(val sessions: List<AdminSession> = emptyList())
