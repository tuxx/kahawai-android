package com.kolktech.kahawai.ui.setup

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.auth.ServerConfigStore
import com.kolktech.kahawai.data.auth.TokenStore

@Composable
fun ServerSetupScreen(
    serverConfigStore: ServerConfigStore,
    tokenStore: TokenStore,
    onReady: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: ServerSetupViewModel = viewModel(
        factory = viewModelFactory { initializer { ServerSetupViewModel(application, serverConfigStore, tokenStore) } },
    )
    val state by viewModel.state.collectAsState()
    var urlInput by remember { mutableStateOf(serverConfigStore.baseUrl.orEmpty()) }

    LaunchedEffect(state) {
        if (state is ServerSetupState.ReadyForLogin) onReady()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.kahawai_lockup),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.padding(bottom = 32.dp).height(36.dp),
        )
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.setup_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text(stringResource(R.string.setup_hub_address)) },
            singleLine = true,
            // Uri + no autocorrect/autocapitalize: the leanback (TV) system
            // keyboard otherwise treats this as a prose field and applies
            // word suggestions/autocap, silently mangling typed addresses
            // (e.g. "uwu." landing as "Wu.").
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state is ServerSetupState.Error) {
            Text(
                (state as ServerSetupState.Error).message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = { viewModel.submit(urlInput) },
            enabled = state !is ServerSetupState.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            if (state is ServerSetupState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Text(stringResource(R.string.setup_continue))
            }
        }
    }
}
