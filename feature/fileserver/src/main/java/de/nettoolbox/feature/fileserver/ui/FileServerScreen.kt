package de.nettoolbox.feature.fileserver.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.feature.fileserver.R

/**
 * The file server screen: a share you can browse, and three ways to serve it.
 *
 * Four tabs rather than one long screen. They are genuinely different tasks -
 * moving files around, deciding what is exposed, managing who may connect, and
 * watching what happened - and each is used at a different moment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileServerScreen(
    modifier: Modifier = Modifier,
    viewModel: FileServerViewModel = hiltViewModel(),
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Errors are shown as a snackbar with the real reason, never as a bare
    // "something went wrong". The message id is part of the key so the same
    // error twice in a row still shows twice.
    val messageText = message?.error?.asText()
    LaunchedEffect(message?.id) {
        val text = messageText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.dismissMessage()
    }

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Tab(
                selected = selectedTab == FileServerViewModel.TAB_EXPLORER,
                onClick = { viewModel.selectTab(FileServerViewModel.TAB_EXPLORER) },
                text = { Text(stringResource(R.string.fileserver_tab_explorer)) },
            )
            Tab(
                selected = selectedTab == FileServerViewModel.TAB_SERVERS,
                onClick = { viewModel.selectTab(FileServerViewModel.TAB_SERVERS) },
                text = { Text(stringResource(R.string.fileserver_tab_servers)) },
            )
            Tab(
                selected = selectedTab == FileServerViewModel.TAB_ACCOUNTS,
                onClick = { viewModel.selectTab(FileServerViewModel.TAB_ACCOUNTS) },
                text = { Text(stringResource(R.string.fileserver_tab_accounts)) },
            )
            Tab(
                selected = selectedTab == FileServerViewModel.TAB_LOG,
                onClick = { viewModel.selectTab(FileServerViewModel.TAB_LOG) },
                text = { Text(stringResource(R.string.fileserver_tab_log)) },
            )
        }

        SnackbarHost(hostState = snackbarHostState)

        when (selectedTab) {
            FileServerViewModel.TAB_EXPLORER -> ExplorerTab(viewModel)
            FileServerViewModel.TAB_SERVERS -> ServersTab(viewModel)
            FileServerViewModel.TAB_ACCOUNTS -> AccountsTab(viewModel)
            else -> LogTab(viewModel)
        }
    }
}

/** Section heading used by all four tabs, so the rhythm is the same throughout. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}
