package com.vibethroughcode.ftree.ui.circle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.ui.FTreeViewModels
import com.vibethroughcode.ftree.ui.common.PersonRow
import com.vibethroughcode.ftree.ui.common.SectionRule
import com.vibethroughcode.ftree.ui.common.displayName
import com.vibethroughcode.ftree.ui.common.readableMeasure
import com.vibethroughcode.ftree.ui.common.rejectionMessage

const val AddConnectionNameTag = "add-connection-name"
const val AddConnectionLabelTag = "add-connection-label"
const val AddConnectionSaveTag = "add-connection-save"

/** Connect [anchor] to someone: say what the connection is, then name a new person or pick one already here. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddConnectionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddConnectionViewModel = viewModel(factory = FTreeViewModels.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val defaults = listOf(
        stringResource(R.string.circle_label_friend),
        stringResource(R.string.circle_label_colleague),
        stringResource(R.string.circle_label_manager),
        stringResource(R.string.circle_label_teammate),
        stringResource(R.string.circle_label_classmate),
        stringResource(R.string.circle_label_neighbour),
    )
    // Labels already in use come first, then the starting set, without repeats ignoring case.
    val suggestions = (state.usedLabels + defaults).distinctBy { it.lowercase() }.take(8)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.circle_add_connection_title, state.anchor?.displayName().orEmpty())) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.edit_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxHeight().padding(padding).readableMeasure(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionRule(stringResource(R.string.circle_label_section))
                    OutlinedTextField(
                        value = state.label,
                        onValueChange = viewModel::onLabelChange,
                        label = { Text(stringResource(R.string.circle_label_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().testTag(AddConnectionLabelTag),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { suggestion ->
                            FilterChip(
                                selected = state.label.equals(suggestion, ignoreCase = true),
                                onClick = { viewModel.onLabelChange(suggestion) },
                                label = { Text(suggestion) },
                            )
                        }
                    }

                    SectionRule(stringResource(R.string.add_relative_new))
                    OutlinedTextField(
                        value = state.newName,
                        onValueChange = viewModel::onNewNameChange,
                        label = { Text(stringResource(R.string.add_relative_name_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth().testTag(AddConnectionNameTag),
                    )
                    Button(
                        onClick = { viewModel.createAndLink(onBack) },
                        enabled = state.newName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag(AddConnectionSaveTag),
                    ) {
                        Icon(Icons.Default.PersonAddAlt, contentDescription = null)
                        Text(stringResource(R.string.add_relative_save), modifier = Modifier.padding(start = 8.dp))
                    }

                    SectionRule(stringResource(R.string.add_relative_existing))
                    if (state.candidates.isEmpty() && state.query.isBlank()) {
                        Text(
                            stringResource(R.string.circle_nobody_else),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            label = { Text(stringResource(R.string.people_search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            items(state.candidates, key = { it.id }) { person ->
                PersonRow(person = person, onClick = { viewModel.linkExisting(person.id, onBack) })
            }
        }
    }

    state.rejection?.let { reason ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRejection,
            text = { Text(stringResource(rejectionMessage(reason))) },
            confirmButton = { TextButton(onClick = viewModel::dismissRejection) { Text(stringResource(R.string.delete_cancel)) } },
        )
    }
}
