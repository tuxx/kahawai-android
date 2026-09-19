package com.kolktech.kahawai.ui.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.ui.components.ErrorView
import com.kolktech.kahawai.ui.components.OnResumeEffect
import com.kolktech.kahawai.ui.components.PosterCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repo: CatalogRepository,
    onOpenItem: (itemId: String, libraryId: String) -> Unit,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val viewModel: SearchViewModel = viewModel(
        factory = viewModelFactory { initializer { SearchViewModel(repo) } },
    )
    val query by viewModel.query.collectAsState()
    val state by viewModel.state.collectAsState()
    OnResumeEffect(viewModel::refresh)
    val focusRequester = remember { FocusRequester() }
    val backFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.let(viewModel::onQueryChange)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val voiceUnavailableMessage = stringResource(R.string.search_voice_unavailable)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.focusRequester(backFocusRequester),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kw_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.search)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        try {
                            voiceLauncher.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, voiceUnavailableMessage, Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.search_voice))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(focusRequester)
                    // D-pad UP from the field has no spatial target (the
                    // TopAppBar back button sits diagonally left-of-above),
                    // so on TV focus would just stay put and the back button
                    // was unreachable without touch. Route it explicitly.
                    .focusProperties { up = backFocusRequester },
            )
            when (val s = state) {
                is SearchState.Idle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyMedium)
                }
                is SearchState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is SearchState.Error -> ErrorView(
                    message = s.message,
                    isAuthError = s.isAuthError,
                    onRetry = { viewModel.retry() },
                    onSignInAgain = onSessionExpired,
                )
                is SearchState.Loaded -> {
                    if (s.items.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.search_no_matches, query))
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(s.items, key = { it.id }) { item ->
                                PosterCard(item, repo, { id -> onOpenItem(id, item.libraryId) })
                            }
                        }
                    }
                }
            }
        }
    }
}
