package com.vibethroughcode.ftree.ui.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.nearby.NearbyIdentity
import com.vibethroughcode.ftree.nearby.NearbyPeer
import com.vibethroughcode.ftree.nearby.NearbyRepository
import com.vibethroughcode.ftree.nearby.NearbyState
import com.vibethroughcode.ftree.nearby.OutgoingFile
import com.vibethroughcode.ftree.nearby.wire.QrLink
import com.vibethroughcode.ftree.transfer.ImportProblem
import com.vibethroughcode.ftree.transfer.TreeExporter
import com.vibethroughcode.ftree.ui.transfer.defaultExportName
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the two screens is open. There is no third: a transfer is an act, not a place. */
enum class NearbyMode { SEND, RECEIVE }

/**
 * The nearby screen's state, held above any one composition.
 *
 * Scoped to the activity rather than to the dialog, so a rotation does not hang up a transfer: the
 * dialog is recomposed, this is not, and the socket underneath never notices. Closing the screen is
 * the only thing that stops it — which is also what makes this device invisible again.
 */
class NearbyViewModel(
    private val repository: NearbyRepository,
    private val identity: NearbyIdentity,
    private val exporter: TreeExporter,
    private val outgoingDirectory: File,
) : ViewModel() {

    private val _mode = MutableStateFlow<NearbyMode?>(null)
    val mode: StateFlow<NearbyMode?> = _mode.asStateFlow()

    val state: StateFlow<NearbyState> = repository.state
    val peers: StateFlow<List<NearbyPeer>> = repository.peers
    val listening: StateFlow<NearbyRepository.Listening?> = repository.listening
    val pairing: StateFlow<QrLink?> = repository.pairing

    /** Set while the export is being written, before any socket exists. */
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    /** A flow, not a getter: the beacon and this line have to agree while the screen is open. */
    val deviceName: StateFlow<String> = identity.displayNames

    fun open(mode: NearbyMode) {
        _mode.value = mode
        repository.setVisible(true)
        // Only the receive screen shows a code; the send screen scans one.
        repository.showPairing(mode == NearbyMode.RECEIVE)
    }

    fun close() {
        repository.stop()
        _mode.value = null
        outgoingDirectory.listFiles()?.forEach { it.delete() }
    }

    fun send(peer: NearbyPeer) = withOutgoing { repository.send(peer, it) }

    fun sendTo(address: String, port: Int) = withOutgoing { repository.sendTo(address, port, it) }

    fun sendByLink(link: QrLink) = withOutgoing { repository.sendByLink(link, it) }

    fun confirmCode(matched: Boolean) = repository.confirmCode(matched)

    fun accept() = repository.acceptIncoming()

    fun decline() = repository.declineIncoming()

    fun cancel() = repository.cancel()

    fun dismiss() = repository.dismiss()

    private var handedOff: File? = null

    /**
     * Gives an arrived file to [prepare] exactly once, however many times the screen that watches
     * for it is recomposed or recreated — a rotation must not read the same file twice.
     */
    fun handOff(prepare: (File) -> Unit) {
        val arrived = (state.value as? NearbyState.Arrived)?.file ?: return
        if (arrived == handedOff) return
        handedOff = arrived
        prepare(arrived)
    }

    /** The importer has read what arrived; tell the sender, and step aside for the review. */
    fun importPrepared(problem: ImportProblem?) {
        repository.importFinished(problem)
        close()
    }

    /**
     * Writes the whole tree to a file first, then offers it.
     *
     * The size and digest go in the offer, so they have to be known before connecting — and a file
     * means no database transaction is held open across a network while somebody decides.
     */
    private fun withOutgoing(send: (OutgoingFile) -> Unit) {
        if (_preparing.value) return
        viewModelScope.launch {
            _preparing.value = true
            val name = defaultExportName()
            val outgoing = runCatching {
                withContext(Dispatchers.IO) { outgoingDirectory.mkdirs() }
                val file = File(outgoingDirectory, name)
                val summary = withContext(Dispatchers.IO) { file.outputStream() }.use { exporter.exportTo(it) }
                OutgoingFile(
                    file = file,
                    peopleCount = summary.people,
                    relationshipCount = summary.relationships,
                    photoCount = summary.photos,
                    suggestedFileName = name,
                )
            }.getOrNull()
            _preparing.value = false
            outgoing?.let(send)
        }
    }

    override fun onCleared() {
        repository.stop()
    }
}
