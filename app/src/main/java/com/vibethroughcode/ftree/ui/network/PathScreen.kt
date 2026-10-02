package com.vibethroughcode.ftree.ui.network

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.kutumb.geo.FunFact
import com.vibethroughcode.ftree.kutumb.geo.Geo
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.ui.FTreeViewModels
import com.vibethroughcode.ftree.ui.common.PersonAvatar
import com.vibethroughcode.ftree.ui.common.displayName
import com.vibethroughcode.ftree.ui.common.kinshipName
import java.text.NumberFormat
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

const val PathBackTag = "path-back"
const val PathTermTag = "path-term"
const val PathChainTag = "path-chain"
const val PathDistanceTag = "path-distance"
const val PathFactsTag = "path-facts"
const val PathMessageTag = "path-message"
const val PathTreeTag = "path-tree"

/** Kilometres, or miles where the reader's region measures roads in them. */
internal fun usesMiles(locale: Locale): Boolean = locale.country in setOf("US", "GB", "LR", "MM")

/**
 * How the reader is related to one relative: the path between them, how far apart they live, and
 * what they have in common. Distance and the place-based fact appear only when both people share
 * their locations; with nothing to say, a card is left out rather than shown empty.
 *
 * [onMessage] is null while messaging does not exist; the button is then drawn unavailable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathScreen(
    onBack: () -> Unit,
    onTree: (personId: String) -> Unit,
    onMessage: ((personId: String) -> Unit)? = null,
    viewModel: PathViewModel = viewModel(factory = FTreeViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val path = state.path

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.path_title, path?.other?.displayName().orEmpty()))
                    Text(
                        stringResource(R.string.path_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag(PathBackTag)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.path_back))
                }
            },
        )
        if (path == null) return@Column
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ChainCard(path)
            path.distanceKm?.let { DistanceCard(it) }
            if (path.facts.isNotEmpty()) Facts(path.facts)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Messaging needs a direct, in-person pairing with this person specifically.
            val canMessage = state.contact?.pairingMethod == PairingMethod.DIRECT
            if (canMessage) {
                Button(
                    onClick = { onMessage?.invoke(path.other.id) },
                    enabled = onMessage != null,
                    modifier = Modifier.weight(1f).testTag(PathMessageTag),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.path_message, path.other.displayName()))
                }
            }
            OutlinedButton(
                onClick = { onTree(path.other.id) },
                modifier = (if (canMessage) Modifier else Modifier.weight(1f)).testTag(PathTreeTag),
            ) { Text(stringResource(R.string.path_tree)) }
        }
    }
}

@Composable
private fun ChainCard(path: PathUi) {
    val relation = path.relation
    val term = relation?.term?.let { kinshipName(it, relation.path, path.me.gender, path.other.gender)?.term }
    Card {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.testTag(PathChainTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                path.people.forEachIndexed { index, person ->
                    if (index > 0) Box(Modifier.size(width = 16.dp, height = 2.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    val end = index == 0 || index == path.people.lastIndex
                    PersonAvatar(person, diameter = if (end) 48.dp else 32.dp)
                }
            }
            if (relation == null) {
                Text(
                    stringResource(R.string.path_unrecorded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                if (term != null) {
                    Text(term, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(PathTermTag))
                }
                Text(
                    text = path.people.mapIndexed { i, p -> if (i == 0) stringResource(R.string.path_you) else p.displayName() }
                        .joinToString("  →  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DistanceCard(km: Double) {
    val locale = Locale.getDefault()
    val miles = usesMiles(locale)
    val value = NumberFormat.getIntegerInstance(locale).format(if (miles) km / Geo.KM_PER_MILE else km)
    Card(Modifier.testTag(PathDistanceTag)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(stringResource(R.string.path_distance_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(if (miles) R.string.path_distance_mi else R.string.path_distance_km, value),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
    }
}

@Composable
private fun Facts(facts: List<FunFact>) {
    val locale = Locale.getDefault()
    val zodiacNames = stringArrayResource(R.array.zodiac_names)
    Column(Modifier.testTag(PathFactsTag), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.path_facts_title), style = MaterialTheme.typography.titleMedium)
        facts.forEach { fact ->
            val text = when (fact) {
                is FunFact.SameBirthMonth ->
                    stringResource(R.string.path_fact_birth_month, Month.of(fact.month).getDisplayName(TextStyle.FULL, locale))
                is FunFact.AgeGap -> pluralStringResource(R.plurals.path_fact_age_gap, fact.years, fact.years)
                is FunFact.SameZodiac -> stringResource(R.string.path_fact_zodiac, zodiacNames[fact.sign.ordinal])
                is FunFact.BornNearby -> {
                    val miles = usesMiles(locale)
                    val n = NumberFormat.getIntegerInstance(locale).format(if (miles) fact.km / Geo.KM_PER_MILE else fact.km)
                    stringResource(R.string.path_fact_nearby, stringResource(if (miles) R.string.path_distance_mi else R.string.path_distance_km, n))
                }
            }
            Card { Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}
