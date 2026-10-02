package com.vibethroughcode.ftree.ui.facts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.ui.FTreeViewModels
import com.vibethroughcode.ftree.ui.common.PersonAvatar
import com.vibethroughcode.ftree.ui.common.displayName

const val FactsPointsTag = "facts-points"
const val FactsTabSelfTag = "facts-tab-self"
const val FactsTabGuessTag = "facts-tab-guess"
const val FactsQuestionTag = "facts-question"
const val FactsAnswerTag = "facts-answer"
const val FactsSubmitTag = "facts-submit"
const val FactsNextTag = "facts-next"
const val FactsPendingTag = "facts-pending"
const val FactsNeedIdentityTag = "facts-need-identity"
fun factsRelativeTag(personId: String) = "facts-relative-$personId"

/**
 * The family facts game: answer about yourself, or guess about a relative. Answers are kept, never
 * overwritten; the person the fact is about later picks the right one, and whoever gave it earns
 * the points.
 *
 * [onResolve] opens the card where the reader picks the right answers to questions about them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactsScreen(
    onResolve: () -> Unit,
    viewModel: FactsViewModel = viewModel(factory = FTreeViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.facts_saved)
    LaunchedEffect(state.justSaved) {
        if (state.justSaved) {
            viewModel.savedShown()
            snackbars.showSnackbar(savedText)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.facts_title)) },
                actions = {
                    if (state.me != null) {
                        Text(
                            stringResource(R.string.facts_points, state.points),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 16.dp).testTag(FactsPointsTag),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.loaded && state.me == null) {
            Text(
                stringResource(R.string.facts_need_identity),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(24.dp).testTag(FactsNeedIdentityTag),
            )
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.pendingCount > 0) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onResolve).testTag(FactsPendingTag),
                ) {
                    Text(
                        pluralStringResource(R.plurals.facts_pending, state.pendingCount, state.pendingCount),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            PrimaryTabRow(selectedTabIndex = state.mode.ordinal) {
                Tab(
                    selected = state.mode == FactsMode.SELF,
                    onClick = { viewModel.setMode(FactsMode.SELF) },
                    text = { Text(stringResource(R.string.facts_tab_self)) },
                    modifier = Modifier.testTag(FactsTabSelfTag),
                )
                Tab(
                    selected = state.mode == FactsMode.GUESS,
                    onClick = { viewModel.setMode(FactsMode.GUESS) },
                    text = { Text(stringResource(R.string.facts_tab_guess)) },
                    modifier = Modifier.testTag(FactsTabGuessTag),
                )
            }

            if (state.mode == FactsMode.GUESS) {
                if (state.relatives.isEmpty()) {
                    Text(stringResource(R.string.facts_no_contacts), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.facts_pick_who), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.relatives.take(4).forEach { person ->
                            FilterChip(
                                selected = person.id == state.subject?.id,
                                onClick = { viewModel.chooseSubject(person.id) },
                                label = { Text(person.displayName()) },
                                leadingIcon = { PersonAvatar(person, diameter = 24.dp, decorative = true) },
                                modifier = Modifier.testTag(factsRelativeTag(person.id)),
                            )
                        }
                    }
                }
            }

            val subject = state.subject
            val ready = state.mode == FactsMode.SELF || subject != null
            if (ready) {
                QuestionCard(state, viewModel)
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Coffee, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.facts_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(state: FactsUiState, viewModel: FactsViewModel) {
    val owner = state.owner
    val prompt = if (state.mode == FactsMode.SELF || owner == null) {
        stringResource(QuestionText.self(state.question.id))
    } else {
        stringResource(QuestionText.about(state.question.id), owner.displayName())
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.mode == FactsMode.GUESS && owner != null) {
                Text(stringResource(R.string.facts_guessing, owner.displayName()), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                stringResource(QuestionText.category(state.question.category)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(prompt, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(FactsQuestionTag))
            OutlinedTextField(
                value = state.answer,
                onValueChange = viewModel::onAnswerChange,
                label = { Text(stringResource(R.string.facts_your_answer)) },
                placeholder = { Text(stringResource(R.string.facts_answer_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag(FactsAnswerTag),
            )
            Button(
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().testTag(FactsSubmitTag),
            ) { Text(stringResource(R.string.facts_submit)) }
            TextButton(onClick = viewModel::nextQuestion, modifier = Modifier.testTag(FactsNextTag)) {
                Text(stringResource(R.string.facts_another))
            }
        }
    }
}
