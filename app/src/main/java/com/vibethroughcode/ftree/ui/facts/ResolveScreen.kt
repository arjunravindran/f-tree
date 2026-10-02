package com.vibethroughcode.ftree.ui.facts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.ui.FTreeViewModels
import com.vibethroughcode.ftree.ui.common.PersonAvatar
import com.vibethroughcode.ftree.ui.common.displayName

const val ResolveBackTag = "resolve-back"
const val ResolveQuestionTag = "resolve-question"
const val ResolveConfirmTag = "resolve-confirm"
const val ResolveWinnerTag = "resolve-winner"
const val ResolveDoneTag = "resolve-done"
fun resolveAnswerTag(answerId: String) = "resolve-answer-$answerId"

/**
 * The card where somebody picks the right answer to a question about them. Every answer is listed,
 * the owner's own included; nothing is picked for them and nothing expires.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    onBack: () -> Unit,
    viewModel: ResolveViewModel = viewModel(factory = FTreeViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val reward = state.rewardFor
    val rewardText = reward?.let { stringResource(R.string.resolve_reward, it.displayName()) }
    LaunchedEffect(reward) {
        if (rewardText != null) {
            viewModel.rewardShown()
            snackbars.showSnackbar(rewardText)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.resolve_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        state.fact?.let {
                            Text(
                                stringResource(QuestionText.self(it.prompt)),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag(ResolveQuestionTag),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(ResolveBackTag)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.path_back))
                    }
                },
            )
        },
        bottomBar = {
            if (state.fact != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Button(
                        onClick = viewModel::confirm,
                        enabled = state.selected != null,
                        modifier = Modifier.fillMaxWidth().testTag(ResolveConfirmTag),
                    ) { Text(stringResource(R.string.resolve_confirm)) }
                }
            }
        },
    ) { padding ->
        if (state.loaded && state.fact == null) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp).testTag(ResolveDoneTag),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.resolve_done_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.resolve_done_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.resolve_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.answers.forEach { line ->
                AnswerCard(line, selected = line.answer.id == state.selectedId, onSelect = { viewModel.select(line.answer.id) })
            }
            state.selected?.let { Winner(state, it) }
        }
    }
}

@Composable
private fun AnswerCard(line: AnswerLine, selected: Boolean, onSelect: () -> Unit) {
    val name = line.answerer?.displayName() ?: stringResource(R.string.network_removed_person)
    val shown = if (line.answer.isSelfReported) stringResource(R.string.resolve_self, name) else name
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .testTag(resolveAnswerTag(line.answer.id)),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            line.answerer?.let { PersonAvatar(it, diameter = 38.dp, decorative = true) }
            Column(Modifier.weight(1f)) {
                Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line.answer.answerText, style = MaterialTheme.typography.titleMedium)
            }
            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Winner(state: ResolveUiState, choice: AnswerLine) {
    val name = choice.answerer?.displayName() ?: stringResource(R.string.network_removed_person)
    // The owner's own answer earns points on the ledger but never a reward: nothing is owed to oneself.
    val ownAnswer = choice.answer.isSelfReported
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(ResolveWinnerTag),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (ownAnswer) R.string.resolve_winner_own else R.string.resolve_winner, name),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!ownAnswer) {
                val total = (state.earnedSoFar + 1).coerceAtMost(state.threshold)
                LinearProgressIndicator(progress = { total.toFloat() / state.threshold }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.resolve_progress, total, state.threshold),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
