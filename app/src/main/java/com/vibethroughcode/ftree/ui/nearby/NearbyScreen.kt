package com.vibethroughcode.ftree.ui.nearby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.nearby.NearbyPeer
import com.vibethroughcode.ftree.nearby.NearbyRepository
import com.vibethroughcode.ftree.nearby.NearbyState
import com.vibethroughcode.ftree.nearby.wire.NearbyPlatform
import com.vibethroughcode.ftree.nearby.wire.NearbyProblem
import com.vibethroughcode.ftree.nearby.wire.Offer
import com.vibethroughcode.ftree.ui.common.READABLE_MEASURE
import com.vibethroughcode.ftree.ui.theme.FTreeText
import com.vibethroughcode.ftree.ui.theme.FTreeTheme

const val NearbyCloseTag = "nearby-close"
const val NearbyPeerTag = "nearby-peer"
const val NearbyAddressFieldTag = "nearby-address"
const val NearbyAddressSendTag = "nearby-address-send"
const val NearbyCodeTag = "nearby-code"
const val NearbyCodeMatchTag = "nearby-code-match"
const val NearbyCodeDifferTag = "nearby-code-differ"
const val NearbyAcceptTag = "nearby-accept"
const val NearbyDeclineTag = "nearby-decline"
const val NearbyListeningTag = "nearby-listening"

/**
 * Send or receive, as one screen whose body is whatever the transfer is doing now.
 *
 * Shown as a full-screen dialog over the app rather than as a destination, for the same reason the
 * import review is: a transfer is a live object that cannot be put in a route, and backing out has
 * to hang up. A fourth tab would also have taken the bottom bar from three targets to four, for
 * something nobody opens the app in order to *be* in.
 *
 * Every state is drawn from [NearbyState] alone, so the screen can never show two things at once —
 * the pattern the updater's panel set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    mode: NearbyMode,
    state: NearbyState,
    peers: List<NearbyPeer>,
    listening: NearbyRepository.Listening?,
    deviceName: String,
    preparing: Boolean,
    onSend: (NearbyPeer) -> Unit,
    onSendTo: (String, Int) -> Unit,
    onConfirmCode: (Boolean) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    qrPanel: @Composable () -> Unit = {},
    scanAction: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (mode == NearbyMode.SEND) R.string.nearby_send_title
                            else R.string.nearby_receive_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag(NearbyCloseTag)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.nearby_close))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = READABLE_MEASURE)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    NearbyState.Disabled -> Body(stringResource(R.string.nearby_off))

                    is NearbyState.Browsing ->
                        if (mode == NearbyMode.SEND) {
                            Browse(peers, deviceName, preparing, onSend, onSendTo, scanAction)
                        } else {
                            ReadyToReceive(deviceName, listening, qrPanel)
                        }

                    is NearbyState.Connecting -> Working(
                        stringResource(R.string.nearby_connecting, state.name),
                        onCancel = onCancel,
                    )

                    is NearbyState.ConfirmingCode -> Code(state.sas, state.sending, onConfirmCode)

                    is NearbyState.Reviewing -> IncomingOffer(
                        offer = state.offer,
                        fromName = state.fromName,
                        code = state.code,
                        onAccept = onAccept,
                        onDecline = onDecline,
                    )

                    is NearbyState.Sending -> Progress(
                        stringResource(R.string.nearby_sending, percent(state.done, state.total)),
                        fraction(state.done, state.total),
                        onCancel,
                    )

                    is NearbyState.Receiving -> Progress(
                        stringResource(R.string.nearby_receiving, percent(state.done, state.total)),
                        fraction(state.done, state.total),
                        onCancel,
                    )

                    is NearbyState.Arrived -> Working(stringResource(R.string.nearby_arrived), onCancel = null)

                    NearbyState.Sent -> Finished(onDismiss = onDismiss, onClose = onClose)

                    is NearbyState.Failed -> Failed(state.problem, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun Browse(
    peers: List<NearbyPeer>,
    deviceName: String,
    preparing: Boolean,
    onSend: (NearbyPeer) -> Unit,
    onSendTo: (String, Int) -> Unit,
    scanAction: @Composable () -> Unit,
) {
    Body(stringResource(R.string.nearby_appears_as, deviceName), muted = true)

    // One polite line rather than an announcement per device: a screen reader interrupted every
    // time somebody's phone joins the Wi-Fi is a screen reader that cannot finish a sentence.
    // Only the devices that can actually be sent to are counted: "2 devices nearby" over a list
    // where one of them is greyed out and cannot be picked would be a promise the list breaks.
    val reachable = peers.count { it.speakable }
    val status = if (reachable == 0) {
        stringResource(R.string.nearby_looking)
    } else {
        pluralStringResource(R.plurals.nearby_found_count, reachable, reachable)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (reachable == 0 || preparing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            status,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    if (peers.isEmpty()) {
        Body(stringResource(R.string.nearby_none_yet), muted = true)
    } else {
        // In arrival order and never re-sorted (see PeerTable.list), so a row does not move out
        // from under a finger that is reaching for it.
        Column {
            peers.forEach { peer ->
                PeerRow(peer, enabled = !preparing, onClick = { onSend(peer) })
                HorizontalDivider()
            }
        }
    }

    scanAction()

    TypedAddress(enabled = !preparing, onSendTo = onSendTo)
}

/**
 * One device.
 *
 * A device on a version of f-tree this one cannot speak to is shown rather than left out (#191): a
 * row that is never drawn reads as a network fault, and "I can't see it" is the hardest thing to
 * work out over the phone with a relative. It is the same row, dimmed, with no click to give and a
 * line saying what to do about it -- and it is marked disabled so TalkBack says so too.
 */
@Composable
private fun PeerRow(peer: NearbyPeer, enabled: Boolean, onClick: () -> Unit) {
    val dim = !peer.speakable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (dim) {
                    Modifier.semantics { disabled() }
                } else {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                },
            )
            .padding(vertical = 14.dp)
            .testTag(NearbyPeerTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            if (peer.platform == NearbyPlatform.ANDROID) Icons.Default.PhoneAndroid else Icons.Default.Computer,
            contentDescription = null,
            tint = if (dim) tint.copy(alpha = 0.5f) else tint,
        )
        Column(Modifier.weight(1f)) {
            Text(
                peer.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dim) tint else Color.Unspecified,
            )
            if (dim) {
                Text(
                    stringResource(R.string.nearby_other_version),
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
            }
        }
    }
}

@Composable
private fun TypedAddress(enabled: Boolean, onSendTo: (String, Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }

    if (!open) {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.nearby_type_address)) }
        return
    }

    fun submit() {
        val parsed = NearbyMessages.parseAddress(text)
        invalid = parsed == null
        parsed?.let { (address, port) -> onSendTo(address, port) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                invalid = false
            },
            label = { Text(stringResource(R.string.nearby_address_label)) },
            placeholder = { Text("192.168.1.20:43121", style = FTreeText.record) },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) {
                { Text(stringResource(R.string.nearby_address_invalid)) }
            } else {
                null
            },
            textStyle = FTreeText.record.copy(fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag(NearbyAddressFieldTag),
        )
        Button(
            onClick = { submit() },
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.testTag(NearbyAddressSendTag),
        ) { Text(stringResource(R.string.nearby_address_send)) }
    }
}

@Composable
private fun ReadyToReceive(
    deviceName: String,
    listening: NearbyRepository.Listening?,
    qrPanel: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.nearby_ready),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
    }
    Body(stringResource(R.string.nearby_ready_body, deviceName))

    qrPanel()

    val address = listening?.address
    if (listening != null && address != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Body(stringResource(R.string.nearby_ready_address), muted = true)
            SelectionContainer {
                Text(
                    "$address:${listening.port}",
                    style = FTreeText.record.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    modifier = Modifier.testTag(NearbyListeningTag),
                )
            }
        }
    } else if (listening != null) {
        Body(stringResource(R.string.nearby_no_address), muted = true)
    }
}

@Composable
private fun Code(sas: String, sending: Boolean, onConfirmCode: (Boolean) -> Unit) {
    Text(
        stringResource(R.string.nearby_code_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    CodeDigits(sas)
    Body(stringResource(if (sending) R.string.nearby_code_sender else R.string.nearby_code_receiver))
    if (sending) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onConfirmCode(true) }, modifier = Modifier.testTag(NearbyCodeMatchTag)) {
                Text(stringResource(R.string.nearby_code_match))
            }
            OutlinedButton(
                onClick = { onConfirmCode(false) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag(NearbyCodeDifferTag),
            ) { Text(stringResource(R.string.nearby_code_differ)) }
        }
        Text(
            stringResource(R.string.nearby_code_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** The six digits, large, three and three, and read out one at a time. */
@Composable
private fun CodeDigits(sas: String) {
    val spoken = stringResource(R.string.nearby_code_digits_description, NearbyMessages.spokenDigits(sas))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            NearbyMessages.groupedDigits(sas),
            style = FTreeText.record.copy(fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = 4.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(vertical = 20.dp)
                .fillMaxWidth()
                .testTag(NearbyCodeTag)
                .clearAndSetSemantics { contentDescription = spoken },
        )
    }
}

@Composable
private fun IncomingOffer(
    offer: Offer,
    fromName: String,
    code: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Text(
        if (fromName.isNotBlank()) stringResource(R.string.nearby_offer_title, fromName)
        else stringResource(R.string.nearby_offer_title_unnamed),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A sender that states no counts at all gets its size shown alone, rather than a line
            // claiming a family of nobody.
            val counted = offer.peopleCount + offer.relationshipCount + offer.photoCount > 0
            Text(
                if (!counted) formatBytes(offer.totalBytes) else stringResource(
                    R.string.nearby_offer_counts,
                    pluralStringResource(R.plurals.nearby_offer_people, offer.peopleCount, offer.peopleCount),
                    pluralStringResource(
                        R.plurals.nearby_offer_relationships,
                        offer.relationshipCount,
                        offer.relationshipCount,
                    ),
                    pluralStringResource(R.plurals.nearby_offer_photos, offer.photoCount, offer.photoCount),
                    formatBytes(offer.totalBytes),
                ),
                style = FTreeText.record,
            )
            if (offer.suggestedFileName.isNotBlank()) {
                Text(
                    offer.suggestedFileName,
                    style = FTreeText.recordSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.nearby_offer_claim),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (code != null) {
        Text(
            stringResource(R.string.nearby_offer_code, NearbyMessages.groupedDigits(code)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics {
                contentDescription = NearbyMessages.spokenDigits(code)
            },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onAccept, modifier = Modifier.testTag(NearbyAcceptTag)) {
            Text(stringResource(R.string.nearby_offer_accept))
        }
        OutlinedButton(onClick = onDecline, modifier = Modifier.testTag(NearbyDeclineTag)) {
            Text(stringResource(R.string.nearby_offer_decline))
        }
    }
}

@Composable
private fun Working(text: String, onCancel: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
    onCancel?.let { TextButton(onClick = it) { Text(stringResource(R.string.nearby_cancel)) } }
}

@Composable
private fun Progress(label: String, fraction: Float, onCancel: () -> Unit) {
    Text(label, style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = onCancel) { Text(stringResource(R.string.nearby_cancel)) }
}

@Composable
private fun Finished(onDismiss: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.nearby_sent_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
    }
    Body(stringResource(R.string.nearby_sent_body))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onClose) { Text(stringResource(R.string.nearby_done)) }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.nearby_back)) }
    }
}

@Composable
private fun Failed(problem: NearbyProblem, onDismiss: () -> Unit) {
    val alarming = NearbyMessages.isAlarming(problem)
    Surface(
        color = if (alarming) MaterialTheme.colorScheme.errorContainer else FTreeTheme.accents.unknownSurface,
        contentColor = if (alarming) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (alarming) Icons.Default.WarningAmber else Icons.Default.ErrorOutline, contentDescription = null)
                Text(
                    stringResource(if (alarming) R.string.nearby_failed_alarm_title else R.string.nearby_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                stringResource(NearbyMessages.message(problem)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
    // "Back", never "Try again": after a warning, retrying on the same network is what the device
    // in the middle would want.
    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.nearby_back)) }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Body(text: String, muted: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

private fun fraction(done: Long, total: Long): Float =
    if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

private fun percent(done: Long, total: Long): Int = (fraction(done, total) * 100).toInt()

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
}
