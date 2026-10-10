package com.kolktech.kahawai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kolktech.kahawai.BuildConfig
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.auth.ServerConfigStore
import com.kolktech.kahawai.data.auth.TokenStore
import com.kolktech.kahawai.data.settings.AppSettingsStore
import kotlinx.coroutines.launch

/// Every device-local setting on one scrolling page, grouped under
/// section headers. Only the seek increments (free-text fields) get a
/// screen of their own.
///
/// [reserveNotchSpace]/[onReserveNotchSpaceChange] are threaded in from
/// [com.kolktech.kahawai.ui.nav.KahawaiNavGraph] rather than read straight
/// from the store here, so a toggle takes effect on this very screen the
/// instant it's flipped — the nav graph's own window-inset handling reacts
/// to the same Compose state, not just to a later route change.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appSettingsStore: AppSettingsStore,
    serverConfigStore: ServerConfigStore,
    tokenStore: TokenStore,
    reserveNotchSpace: Boolean,
    onReserveNotchSpaceChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenSeeking: () -> Unit,
    onChangeServer: () -> Unit,
) {
    var gesturesEnabled by remember { mutableStateOf(appSettingsStore.playerGesturesEnabled) }
    var volumeBrightnessEnabled by remember { mutableStateOf(appSettingsStore.volumeBrightnessGestureEnabled) }
    var seekGestureEnabled by remember { mutableStateOf(appSettingsStore.seekGestureEnabled) }
    var zoomGestureEnabled by remember { mutableStateOf(appSettingsStore.zoomGestureEnabled) }
    var rememberBrightness by remember { mutableStateOf(appSettingsStore.rememberBrightnessLevel) }
    val seekBackMs = remember { mutableStateOf(appSettingsStore.seekBackMs) }
    val seekForwardMs = remember { mutableStateOf(appSettingsStore.seekForwardMs) }
    var autoSkipIntrosOutros by remember { mutableStateOf(appSettingsStore.autoSkipIntrosOutros) }
    var confirmChangeServer by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kw_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSectionTitle(stringResource(R.string.settings_playback))
            SettingsNavigationRow(
                title = stringResource(R.string.player_settings_seeking),
                description = stringResource(
                    R.string.player_settings_seeking_desc,
                    seekBackMs.value / 1000,
                    seekForwardMs.value / 1000,
                ),
                onClick = onOpenSeeking,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.player_settings_auto_skip),
                description = stringResource(R.string.player_settings_auto_skip_hint),
                checked = autoSkipIntrosOutros,
                onCheckedChange = {
                    autoSkipIntrosOutros = it
                    appSettingsStore.autoSkipIntrosOutros = it
                },
            )

            SettingsSectionTitle(stringResource(R.string.player_settings_gestures))
            SettingsSwitchRow(
                title = stringResource(R.string.player_settings_gestures_enabled),
                description = stringResource(R.string.player_settings_gestures_enabled_hint),
                checked = gesturesEnabled,
                onCheckedChange = {
                    gesturesEnabled = it
                    appSettingsStore.playerGesturesEnabled = it
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.player_settings_volume_brightness),
                description = stringResource(R.string.player_settings_volume_brightness_hint),
                checked = volumeBrightnessEnabled,
                enabled = gesturesEnabled,
                onCheckedChange = {
                    volumeBrightnessEnabled = it
                    appSettingsStore.volumeBrightnessGestureEnabled = it
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.player_settings_seek_gesture),
                description = stringResource(R.string.player_settings_seek_gesture_hint),
                checked = seekGestureEnabled,
                enabled = gesturesEnabled,
                onCheckedChange = {
                    seekGestureEnabled = it
                    appSettingsStore.seekGestureEnabled = it
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.player_settings_zoom_gesture),
                description = stringResource(R.string.player_settings_zoom_gesture_hint),
                checked = zoomGestureEnabled,
                enabled = gesturesEnabled,
                onCheckedChange = {
                    zoomGestureEnabled = it
                    appSettingsStore.zoomGestureEnabled = it
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.player_settings_remember_brightness),
                description = stringResource(R.string.player_settings_remember_brightness_hint),
                checked = rememberBrightness,
                enabled = gesturesEnabled,
                onCheckedChange = {
                    rememberBrightness = it
                    appSettingsStore.rememberBrightnessLevel = it
                },
            )

            SettingsSectionTitle(stringResource(R.string.settings_display))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_reserve_notch_space),
                description = stringResource(R.string.settings_reserve_notch_space_hint),
                checked = reserveNotchSpace,
                onCheckedChange = onReserveNotchSpaceChange,
            )

            SettingsSectionTitle(stringResource(R.string.settings_connection))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_change_server),
                description = serverConfigStore.baseUrl ?: stringResource(R.string.settings_server_not_configured),
                onClick = { confirmChangeServer = true },
            )

            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
        }
    }

    if (confirmChangeServer) {
        AlertDialog(
            onDismissRequest = { confirmChangeServer = false },
            title = { Text(stringResource(R.string.settings_change_server)) },
            text = { Text(stringResource(R.string.settings_change_server_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmChangeServer = false
                        coroutineScope.launch {
                            tokenStore.clear()
                            serverConfigStore.baseUrl = null
                            onChangeServer()
                        }
                    },
                ) { Text(stringResource(R.string.settings_change_server)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmChangeServer = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
