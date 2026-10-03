package com.vibethroughcode.ftree.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.ChartPreferences
import com.vibethroughcode.ftree.data.KinshipLanguage
import com.vibethroughcode.ftree.data.KinshipPreferences
import com.vibethroughcode.ftree.nearby.NearbyIdentity
import com.vibethroughcode.ftree.nearby.NearbyPreferences
import com.vibethroughcode.ftree.nearby.NearbyRepository
import com.vibethroughcode.ftree.nearby.wire.NearbyNames
import com.vibethroughcode.ftree.data.OccasionCensus
import com.vibethroughcode.ftree.reminders.ReminderLead
import com.vibethroughcode.ftree.reminders.ReminderPreferences
import com.vibethroughcode.ftree.reminders.Reminders
import com.vibethroughcode.ftree.book.TemplateDownloader
import com.vibethroughcode.ftree.update.AvailableUpdate
import com.vibethroughcode.ftree.update.UpdatePreferences
import com.vibethroughcode.ftree.update.UpdateRepository
import com.vibethroughcode.ftree.update.UpdateState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.vibethroughcode.ftree.kutumb.sync.SyncPreferences
import com.vibethroughcode.ftree.kutumb.sync.SyncService
import com.vibethroughcode.ftree.kutumb.sync.SyncStatus
import java.io.File

class SettingsViewModel(
    private val preferences: UpdatePreferences,
    private val chart: ChartPreferences,
    private val kinship: KinshipPreferences,
    private val updates: UpdateRepository,
    private val nearbyPreferences: NearbyPreferences,
    private val nearbyIdentity: NearbyIdentity,
    private val nearbyRepository: NearbyRepository,
    private val reminderPreferences: ReminderPreferences,
    private val reminders: Reminders,
    private val templateDownloads: TemplateDownloader,
    private val syncPreferences: SyncPreferences,
    private val syncService: () -> SyncService,
) : ViewModel() {

    val syncEnabled: StateFlow<Boolean> = syncPreferences.enabled
    val syncRelays: StateFlow<List<String>> = syncPreferences.relays

    /** The service is built only once sync is on, so a phone that never turns it on never builds one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val syncStatus: StateFlow<SyncStatus> = syncPreferences.enabled
        .flatMapLatest { on -> if (on) syncService().status else flowOf(SyncStatus.OFF) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus.OFF)

    fun setSyncEnabled(value: Boolean) = syncPreferences.setEnabled(value)

    /** Returns the entries that were not usable relay addresses. */
    fun setSyncRelays(text: String): List<String> = syncPreferences.setRelaysFromText(text)

    val remindersEnabled: StateFlow<Boolean> = reminderPreferences.enabled
    val reminderLead: StateFlow<ReminderLead> = reminderPreferences.lead
    val reminderRemembrance: StateFlow<Boolean> = reminderPreferences.remembrance

    /** Called only once Android has said yes; the screen asks for the permission first. */
    fun enableReminders() {
        reminders.enable()
        refreshReminders()
    }

    fun disableReminders() = reminders.disable()

    fun setReminderLead(value: ReminderLead) = reminderPreferences.setLead(value)

    fun setReminderRemembrance(value: Boolean) = reminderPreferences.setRemembrance(value)

    private val _canNotify = MutableStateFlow(reminders.canNotify())

    /** False when Android will not show a note — refused, or turned off in the system's settings since. */
    val canNotify: StateFlow<Boolean> = _canNotify.asStateFlow()

    private val _census = MutableStateFlow<OccasionCensus?>(null)

    /** Who a reminder covers, and who it cannot and why. Null until counted. */
    val reminderCensus: StateFlow<OccasionCensus?> = _census.asStateFlow()

    /** On resume: the tree, and what Android allows, may both have changed while Settings was away. */
    fun refreshReminders() {
        _canNotify.value = reminders.canNotify()
        viewModelScope.launch { _census.value = reminders.census() }
    }

    val nearbyEnabled: StateFlow<Boolean> = nearbyPreferences.enabled

    /**
     * Turning it off also stops anything in progress. It does not clear the devices remembered:
     * those are something somebody built, not a cache, and forgetting them is its own button.
     */
    fun setNearbyEnabled(enabled: Boolean) {
        nearbyPreferences.setEnabled(enabled)
        nearbyRepository.onEnabledChanged(enabled)
    }

    val deviceName: StateFlow<String> = nearbyIdentity.displayNames

    /** The name this device is generated, for the hint in the rename dialog. */
    val generatedDeviceName: String
        get() = NearbyNames.friendlyName(nearbyIdentity.deviceId.bytes)

    /** Committed once, on Done: the name is broadcast, and half of one should never be. */
    fun renameDevice(name: String) {
        nearbyIdentity.chosenName = name.ifBlank { null }
    }

    val trustedOnly: StateFlow<Boolean> = nearbyPreferences.trustedOnly

    fun setTrustedOnly(value: Boolean) = nearbyPreferences.setTrustedOnly(value)

    val trustedDevices: StateFlow<Map<String, String>> = nearbyPreferences.trusted

    fun forgetDevice(deviceId: String) = nearbyPreferences.forget(deviceId)

    fun forgetAllDevices() = nearbyPreferences.forgetAll()

    val updatesEnabled: StateFlow<Boolean> = preferences.enabled
    val updateState: StateFlow<UpdateState> = updates.state

    /** Whether the updater may offer an unfinished release. See [UpdatePreferences.betaChannel]. */
    val betaChannel: StateFlow<Boolean> = preferences.betaChannel

    fun setBetaChannel(value: Boolean) = preferences.setBetaChannel(value)

    /**
     * Whether the app may ask about book templates, and whether this build can at all (#214).
     *
     * [templatesOffered] is false when no signing key is pinned in the build, and the switch is not
     * shown at all then: a switch that could only ever refuse would be a promise the build cannot
     * keep.
     */
    val templatesEnabled: StateFlow<Boolean> = preferences.templates
    val templatesOffered: Boolean = templateDownloads.configured()

    private val _templateRefresh = MutableStateFlow<TemplateDownloader.Refresh?>(null)

    /** What the last catalogue check came to, for the one line under the switch. */
    val templateRefresh: StateFlow<TemplateDownloader.Refresh?> = _templateRefresh.asStateFlow()

    fun setTemplatesEnabled(value: Boolean) {
        preferences.setTemplates(value)
        if (!value) {
            _templateRefresh.value = null
        } else {
            // Turning it on is the consent to look, and looking means the catalogue - never a template.
            refreshTemplates()
        }
    }

    /**
     * Fetches the signed catalogue, and only that.
     *
     * Called from here and from [check], so the button that already asks GitHub about releases asks
     * about templates in the same breath. It never downloads a template: that takes a tap on the
     * template itself, in the book screen.
     */
    fun refreshTemplates() {
        if (!templatesOffered) return
        viewModelScope.launch { _templateRefresh.value = templateDownloads.refreshCatalogue() }
    }

    val photosInChart: StateFlow<Boolean> = chart.photosInChart

    fun setPhotosInChart(enabled: Boolean) = chart.setPhotosInChart(enabled)

    val kinshipLanguage: StateFlow<KinshipLanguage> = kinship.language

    fun setKinshipLanguage(value: KinshipLanguage) = kinship.setLanguage(value)

    private var work: Job? = null

    fun setUpdatesEnabled(enabled: Boolean) {
        preferences.setEnabled(enabled)
        updates.onEnabledChanged(enabled)
        work?.cancel()
        // Switching it on is itself the consent to look, so the first check happens straight away
        // rather than leaving the reader to press a second button to find out.
        if (enabled) check()
    }

    fun check() {
        work?.cancel()
        work = viewModelScope.launch { updates.check(manual = true) }
        refreshTemplates()
    }

    fun download(update: AvailableUpdate) {
        work?.cancel()
        work = viewModelScope.launch { updates.download(update) }
    }

    fun cancel() {
        work?.cancel()
        work = null
    }

    fun install(file: File) = updates.install(file)

    fun skip(update: AvailableUpdate) = updates.skip(update)

    fun dismissFailure() = updates.dismissFailure()

    fun canInstall(): Boolean = updates.canInstall()

    fun permissionIntent() = updates.permissionIntent()

    override fun onCleared() {
        work?.cancel()
    }
}
