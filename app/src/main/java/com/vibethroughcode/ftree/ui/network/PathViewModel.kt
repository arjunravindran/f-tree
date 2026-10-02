package com.vibethroughcode.ftree.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PathUiState(
    val loaded: Boolean = false,
    /** Null when the reader has not said who they are, or either person left the tree. */
    val path: PathUi? = null,
    val contact: TrustedContact? = null,
)

class PathViewModel(
    family: FamilyRepository,
    kutumb: KutumbRepository,
    personId: String,
) : ViewModel() {

    val state: StateFlow<PathUiState> = combine(
        kutumb.observeIdentity(), kutumb.observeContacts(), kutumb.observeLocations(), family.observeWholeGraph(),
    ) { identity, contacts, locations, snapshot ->
        PathUiState(
            loaded = true,
            path = identity?.let { PathModel.build(it.personId, personId, snapshot, locations) },
            contact = contacts.firstOrNull { it.personId == personId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PathUiState())
}
