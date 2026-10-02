package com.vibethroughcode.ftree.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.kutumb.PairingProblem
import com.vibethroughcode.ftree.kutumb.PairingState
import com.vibethroughcode.ftree.ui.FTreeViewModels
import com.vibethroughcode.ftree.ui.common.PersonAvatar
import com.vibethroughcode.ftree.ui.common.PersonPicker
import com.vibethroughcode.ftree.ui.nearby.QrPanel
import com.vibethroughcode.ftree.ui.nearby.ScanAction

const val PairingBackTag = "pairing-back"
const val PairingWaitingTag = "pairing-waiting"
const val PairingCodeTag = "pairing-code"
const val PairingMatchTag = "pairing-match"
const val PairingMismatchTag = "pairing-mismatch"
const val PairingWhoTag = "pairing-who"
const val PairingFailedTag = "pairing-failed"
const val PairingRetryTag = "pairing-retry"

/** "123456" as "123 456", the way a code is read aloud and compared across a table. */
fun spacedCode(code: String): String = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

/**
 * Pairing with a relative in person: show a code to scan, compare six digits, then say who they are.
 *
 * The comparison is the point. Nothing here is trusted because a network said so; a contact exists
 * only after two people looked at the same digits on two screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onClose: () -> Unit,
    viewModel: PairingViewModel = viewModel(factory = FTreeViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.pairing_title)) },
            navigationIcon = {
                IconButton(onClick = onClose, modifier = Modifier.testTag(PairingBackTag)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.pairing_cancel))
                }
            },
        )
        val flow = state.flow
        if (flow is PairingState.Verified) {
            Who(state, flow, viewModel)
            return@Column
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            YouAndThem(state)
            when (flow) {
                is PairingState.Waiting -> Waiting(flow, viewModel)
                is PairingState.Connecting -> Text(
                    stringResource(R.string.pairing_connecting, flow.name),
                    style = MaterialTheme.typography.bodyLarge,
                )
                is PairingState.ConfirmCode -> Confirm(flow, viewModel)
                is PairingState.Failed -> Failed(flow.problem, viewModel)
                PairingState.Idle, is PairingState.Verified -> Unit
            }
        }
        Text(
            text = stringResource(R.string.pairing_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
        )
    }
}

/** The reader, a line, and a question mark where the other person will be. */
@Composable
private fun YouAndThem(state: PairingUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        state.me?.let { PersonAvatar(it, diameter = 52.dp, decorative = true) }
        Box(Modifier.size(width = 22.dp, height = 2.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Waiting(state: PairingState.Waiting, viewModel: PairingViewModel) {
    Column(
        Modifier.testTag(PairingWaitingTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.pairing_waiting, state.deviceName),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        state.link?.let { QrPanel(it) }
        ScanAction(onScanned = viewModel::scanned)
    }
}

@Composable
private fun Confirm(state: PairingState.ConfirmCode, viewModel: PairingViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val code = state.code
        if (code != null) {
            Text(stringResource(R.string.pairing_code_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = spacedCode(code),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.testTag(PairingCodeTag),
            )
            Text(
                stringResource(R.string.pairing_code_question, state.peerName),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        } else {
            // They scanned this phone: nothing to compare, only whether to go ahead.
            Text(
                stringResource(R.string.pairing_scanned_question, state.peerName),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(PairingCodeTag),
            )
        }
        Button(onClick = { viewModel.confirmCode(true) }, modifier = Modifier.fillMaxWidth().testTag(PairingMatchTag)) {
            Text(stringResource(R.string.pairing_code_match))
        }
        OutlinedButton(onClick = { viewModel.confirmCode(false) }, modifier = Modifier.fillMaxWidth().testTag(PairingMismatchTag)) {
            Text(stringResource(R.string.pairing_code_mismatch))
        }
    }
}

@Composable
private fun Failed(problem: PairingProblem, viewModel: PairingViewModel) {
    Column(
        Modifier.testTag(PairingFailedTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(
                when (problem) {
                    PairingProblem.UNAVAILABLE -> R.string.pairing_failed_unavailable
                    PairingProblem.NEARBY_OFF -> R.string.pairing_failed_off
                    PairingProblem.NO_IDENTITY -> R.string.pairing_failed_no_identity
                    PairingProblem.CODE_MISMATCH -> R.string.pairing_failed_mismatch
                    PairingProblem.CONNECTION_LOST -> R.string.pairing_failed_lost
                    PairingProblem.DECLINED -> R.string.pairing_failed_declined
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        // A mismatch is not something to retry until it works: it means somebody may be in the middle.
        if (problem == PairingProblem.CONNECTION_LOST || problem == PairingProblem.DECLINED) {
            Button(onClick = viewModel::retry, modifier = Modifier.testTag(PairingRetryTag)) {
                Text(stringResource(R.string.pairing_retry))
            }
        }
    }
}

@Composable
private fun Who(state: PairingUiState, verified: PairingState.Verified, viewModel: PairingViewModel) {
    Column(Modifier.fillMaxSize().testTag(PairingWhoTag)) {
        Text(
            text = stringResource(R.string.pairing_who_title, verified.peerName),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
        )
        Text(
            text = stringResource(R.string.pairing_who_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
        )
        PersonPicker(
            people = state.candidates,
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            onPick = viewModel::choosePerson,
            onCancel = { viewModel.onQueryChange("") },
            hint = stringResource(R.string.network_who_hint),
            noneFound = stringResource(R.string.pairing_who_none),
            searchTag = "pairing-who-search",
            listTag = "pairing-who-list",
            cancelTag = "pairing-who-clear",
        )
    }
}
