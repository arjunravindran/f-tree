package com.vibethroughcode.ftree.ui.circle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.graph.Circle
import com.vibethroughcode.ftree.ui.FTreeViewModels
import com.vibethroughcode.ftree.ui.common.PersonRow
import com.vibethroughcode.ftree.ui.common.SectionRule
import com.vibethroughcode.ftree.ui.common.displayName
import com.vibethroughcode.ftree.ui.common.readableMeasure

const val CircleAddPersonTag = "circle-add-person"
const val CircleAddConnectionTag = "circle-add-connection"
const val CircleDetailsTag = "circle-details"

/**
 * A Circle seen from one person: who they know, grouped by what the connection is, and, below,
 * who they could be introduced to through those people. Tapping anyone moves the view to them, so
 * the same symmetric step walks both ways and there is no back button to manage. This is the
 * Circle's counterpart of the family chart: no ancestors above, no descendants below, because
 * nothing here is ancestral.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircleScreen(
    focusId: String?,
    onOpenPerson: (String) -> Unit,
    onAddPerson: () -> Unit,
    onAddConnection: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CircleViewModel = viewModel(factory = FTreeViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val treeName = (LocalContext.current.applicationContext as FTreeApplication).container.tree.name
    var chosen by rememberSaveable { mutableStateOf(focusId) }

    val egoId = chosen?.takeIf { id -> state.people.any { it.id == id } } ?: Circle.startingPoint(state.people, state.edges)
    val ego = state.people.firstOrNull { it.id == egoId }
    val connections = remember(egoId, state) { egoId?.let { Circle.connectionsOf(it, state.people, state.edges) }.orEmpty() }
    val groups = remember(connections) { Circle.grouped(connections) }
    val through = remember(egoId, state) { egoId?.let { Circle.throughOthers(it, state.people, state.edges) }.orEmpty() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(treeName) },
                actions = {
                    IconButton(onClick = onAddPerson, modifier = Modifier.testTag(CircleAddPersonTag)) {
                        Icon(Icons.Default.PersonAddAlt, contentDescription = stringResource(R.string.circle_add_person))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxHeight().padding(padding).readableMeasure(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            if (ego == null) {
                item {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.circle_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.circle_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onAddPerson, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.circle_add_first))
                        }
                    }
                }
                return@LazyColumn
            }

            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(ego.displayName(), style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onAddConnection(ego.id) }, modifier = Modifier.testTag(CircleAddConnectionTag)) {
                            Text(stringResource(R.string.circle_add_connection))
                        }
                        TextButton(onClick = { onOpenPerson(ego.id) }, modifier = Modifier.testTag(CircleDetailsTag)) {
                            Text(stringResource(R.string.circle_details))
                        }
                    }
                }
            }

            if (connections.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.circle_no_connections, ego.displayName()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }

            groups.forEach { group ->
                item(key = "group-${group.label?.lowercase()}") {
                    SectionRule(
                        label = group.label ?: stringResource(R.string.circle_unlabelled),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(group.connections, key = { it.edgeId }) { connection ->
                    PersonRow(person = connection.other, onClick = { chosen = connection.other.id })
                }
            }

            if (through.isNotEmpty()) {
                item(key = "through") {
                    SectionRule(
                        label = stringResource(R.string.circle_through),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(through, key = { "through-${it.first.id}" }) { (person, via) ->
                    PersonRow(
                        person = person,
                        onClick = { chosen = person.id },
                        supporting = stringResource(R.string.circle_via, via.joinToString(", ") { it.name?.trim().orEmpty().ifEmpty { "?" } }),
                    )
                }
            }
        }
    }
}
