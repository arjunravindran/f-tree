package com.vibethroughcode.ftree.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.data.CatalogResult
import com.vibethroughcode.ftree.data.TreeInfo
import com.vibethroughcode.ftree.data.TreeKind
import com.vibethroughcode.ftree.ui.common.SectionRule

const val SettingsTreesNewTag = "settings-trees-new"
const val SettingsTreesNameTag = "settings-trees-name"

private fun nameProblem(reason: CatalogResult.Reason): Int = when (reason) {
    CatalogResult.Reason.BLANK_NAME -> R.string.trees_error_blank
    CatalogResult.Reason.NAME_TOO_LONG -> R.string.trees_error_long
    CatalogResult.Reason.NAME_TAKEN -> R.string.trees_error_taken
    else -> R.string.trees_error_other
}

/**
 * Lists the trees, opens one, and makes, renames and deletes them. Opening restarts the app's UI
 * on the chosen tree: every screen and view model belongs to exactly one tree.
 */
@Composable
fun TreesSettings() {
    val app = LocalContext.current.applicationContext as FTreeApplication
    var trees by remember { mutableStateOf(app.catalog.trees) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TreeInfo?>(null) }
    var deleting by remember { mutableStateOf<TreeInfo?>(null) }
    val activeId = app.container.tree.id

    Column {
        SectionRule(stringResource(R.string.settings_section_trees))
        Text(
            stringResource(R.string.trees_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trees.forEach { tree ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tree.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(
                            when {
                                tree.id == activeId -> R.string.trees_open_now
                                tree.kind == TreeKind.FAMILY -> R.string.trees_kind_family
                                else -> R.string.trees_kind_circle
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (tree.id != activeId) {
                    TextButton(onClick = { if (app.switchTo(tree.id)) app.relaunch() }) { Text(stringResource(R.string.trees_open)) }
                }
                TextButton(onClick = { renaming = tree }) { Text(stringResource(R.string.trees_rename)) }
                if (tree.id != activeId) {
                    TextButton(onClick = { deleting = tree }) { Text(stringResource(R.string.trees_delete)) }
                }
            }
        }
        TextButton(onClick = { creating = true }, modifier = Modifier.testTag(SettingsTreesNewTag)) {
            Text(stringResource(R.string.trees_new))
        }
    }

    if (creating) {
        NameDialog(
            title = R.string.trees_new,
            initial = "",
            chooseKind = true,
            onDismiss = { creating = false },
            onDone = { name, kind ->
                val result = app.catalog.create(name, kind)
                if (result is CatalogResult.Ok) {
                    trees = app.catalog.trees
                    creating = false
                    null
                } else {
                    nameProblem((result as CatalogResult.Refused).reason)
                }
            },
        )
    }
    renaming?.let { tree ->
        NameDialog(
            title = R.string.trees_rename,
            initial = tree.name,
            chooseKind = false,
            onDismiss = { renaming = null },
            onDone = { name, _ ->
                val result = app.catalog.rename(tree.id, name)
                if (result is CatalogResult.Ok) {
                    trees = app.catalog.trees
                    renaming = null
                    null
                } else {
                    nameProblem((result as CatalogResult.Refused).reason)
                }
            },
        )
    }
    deleting?.let { tree ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.trees_delete_title, tree.name)) },
            text = { Text(stringResource(R.string.trees_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val result = app.catalog.remove(tree.id)
                    if (result is CatalogResult.Ok) app.deleteFilesOf(result.tree)
                    trees = app.catalog.trees
                    deleting = null
                }) { Text(stringResource(R.string.trees_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.trees_cancel)) } },
        )
    }
}

/** [onDone] returns a string resource to show as the problem, or null when it worked. */
@Composable
private fun NameDialog(
    title: Int,
    initial: String,
    chooseKind: Boolean,
    onDismiss: () -> Unit,
    onDone: (String, TreeKind) -> Int?,
) {
    var name by remember { mutableStateOf(initial) }
    var kind by remember { mutableStateOf(TreeKind.FAMILY) }
    var problem by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60); problem = null },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(stringResource(it)) } },
                    modifier = Modifier.testTag(SettingsTreesNameTag),
                )
                if (chooseKind) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = kind == TreeKind.FAMILY,
                            onClick = { kind = TreeKind.FAMILY },
                            label = { Text(stringResource(R.string.trees_kind_family_short)) },
                        )
                        FilterChip(
                            selected = kind == TreeKind.CIRCLE,
                            onClick = { kind = TreeKind.CIRCLE },
                            label = { Text(stringResource(R.string.trees_kind_circle_short)) },
                        )
                    }
                    Text(
                        stringResource(if (kind == TreeKind.FAMILY) R.string.trees_kind_family_help else R.string.trees_kind_circle_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { problem = onDone(name, kind) }) { Text(stringResource(R.string.trees_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.trees_cancel)) } },
    )
}
