package de.nettoolbox.feature.tools.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.nettoolbox.core.ui.icon.NetToolboxIcons
import de.nettoolbox.feature.tools.R

/**
 * Common frame for a single tool: title and the entry point into its run history.
 *
 * Every tool gets the same one, so the history is never a feature that some tools
 * happen to have.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onShowHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    if (onShowHistory != null) {
                        IconButton(onClick = onShowHistory) {
                            Icon(
                                painter = painterResource(NetToolboxIcons.History),
                                contentDescription = stringResource(R.string.history_title),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            content(Modifier)
        }
    }
}
