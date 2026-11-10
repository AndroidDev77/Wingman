package com.wingman.core.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wingman.core.model.ErrorCode
import com.wingman.core.ui.UiError
import com.wingman.core.ui.UiState
import com.wingman.ui.theme.WingmanTheme

/**
 * Renders the four [UiState] cases consistently. Features pass only the Content composable;
 * Loading, Empty and Error (with Retry when the error is retryable) come from the shell.
 */
@Composable
fun <T> UiStateContent(
    state: UiState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = "Nothing here yet.",
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> CenteredBox(modifier) { CircularProgressIndicator() }
        UiState.Empty -> CenteredBox(modifier) { EmptyState(emptyMessage) }
        is UiState.Error -> ErrorView(state.error, onRetry, modifier)
        is UiState.Content -> content(state.data)
    }
}

@Composable
fun ErrorView(error: UiError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    CenteredBox(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp),
            )
            Text(
                error.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            if (error.retryable) Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun CenteredBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Preview(name = "Error (network)")
@Composable
private fun ErrorPreview() = WingmanTheme {
    UiStateContent<Unit>(
        UiState.Error(UiError("Can't reach Wingman. Check your connection and try again.", null, retryable = true, isNetwork = true)),
        onRetry = {},
    ) {}
}

@Preview(name = "Empty")
@Composable
private fun EmptyPreview() = WingmanTheme {
    androidx.compose.material3.Surface { UiStateContent<Unit>(UiState.Empty, onRetry = {}, emptyMessage = "No drafts are waiting for your approval.") {} }
}

@Preview(name = "Empty dark")
@Composable
private fun EmptyDarkPreview() = WingmanTheme(darkTheme = true) {
    androidx.compose.material3.Surface { UiStateContent<Unit>(UiState.Empty, onRetry = {}, emptyMessage = "No messages yet.") {} }
}

@Preview(name = "Error (not retryable)")
@Composable
private fun ErrorNotRetryablePreview() = WingmanTheme(darkTheme = true) {
    UiStateContent<Unit>(UiState.Error(UiError("Please sign in again.", ErrorCode.UNAUTHENTICATED, retryable = false)), onRetry = {}) {}
}
