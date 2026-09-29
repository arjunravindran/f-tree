package com.vibethroughcode.ftree

import android.content.Context
import com.vibethroughcode.ftree.data.ChartPreferences
import com.vibethroughcode.ftree.data.FTreeDatabase
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.KinshipPreferences
import com.vibethroughcode.ftree.data.PhotoStore
import com.vibethroughcode.ftree.book.BookPrinter
import com.vibethroughcode.ftree.book.BookTemplates
import com.vibethroughcode.ftree.book.TemplateDownloader
import com.vibethroughcode.ftree.entitlement.EntitlementSource
import com.vibethroughcode.ftree.entitlement.FreeForEveryone
import com.vibethroughcode.ftree.entitlement.Policy
import com.vibethroughcode.ftree.entitlement.PolicyAssets
import com.vibethroughcode.ftree.entitlement.UsageLedger
import com.vibethroughcode.ftree.nearby.LanTransport
import com.vibethroughcode.ftree.nearby.NearbyIdentity
import com.vibethroughcode.ftree.nearby.NearbyPreferences
import com.vibethroughcode.ftree.nearby.NearbyRepository
import com.vibethroughcode.ftree.nearby.NearbyTransport
import com.vibethroughcode.ftree.reminders.ReminderPreferences
import com.vibethroughcode.ftree.reminders.Reminders
import com.vibethroughcode.ftree.transfer.BranchShare
import com.vibethroughcode.ftree.transfer.CardShare
import com.vibethroughcode.ftree.transfer.TreeExporter
import com.vibethroughcode.ftree.transfer.TreeIdentity
import com.vibethroughcode.ftree.transfer.TreeImporter
import com.vibethroughcode.ftree.update.ApkGuard
import com.vibethroughcode.ftree.update.UpdateClient
import com.vibethroughcode.ftree.update.UpdateInstaller
import com.vibethroughcode.ftree.update.UpdatePreferences
import com.vibethroughcode.ftree.update.UpdateRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency wiring.
 *
 * The app has a handful of screens and one repository; a DI framework would add a compiler plugin
 * and a layer of indirection to solve a problem this size does not have. Tests construct the
 * repository directly against an in-memory database.
 */
class AppContainer(context: Context) {
    /** Public so instrumented tests can call `clearAllTables()` between runs. */
    val database: FTreeDatabase by lazy { FTreeDatabase.build(context) }
    val familyRepository: FamilyRepository by lazy { FamilyRepository(database, photoStore) }
    val photoStore: PhotoStore by lazy { PhotoStore(context.applicationContext) }
    val treeIdentity: TreeIdentity by lazy { TreeIdentity(context.applicationContext) }
    val exporter: TreeExporter by lazy { TreeExporter(familyRepository, photoStore, treeIdentity) }
    val branchShare: BranchShare by lazy { BranchShare(context, familyRepository, exporter) }
    val cardShare: CardShare by lazy { CardShare(context) }
    val importer: TreeImporter by lazy {
        TreeImporter(
            database = database,
            repository = familyRepository,
            photos = photoStore,
            identity = treeIdentity,
            exporter = exporter,
            workingDirectory = context.applicationContext.filesDir,
        )
    }

    val updatePreferences: UpdatePreferences by lazy { UpdatePreferences(context) }
    val chartPreferences: ChartPreferences by lazy { ChartPreferences(context) }
    val kinshipPreferences: KinshipPreferences by lazy { KinshipPreferences(context) }

    /**
     * The policy switch (#156). Nothing calls `Entitlements.decide` yet -- that starts with the
     * family book itself (#155 onward) -- but every piece it will need is already wired here,
     * lazily like everything else, so wiring it into a screen later is the whole of the work.
     *
     * [entitlementPolicy] is read from `assets/book/policy.json` once and held rather than
     * re-parsed per call; a missing or unreadable asset comes back `null`, which
     * `Entitlements.decide` already treats as "allow everything" (see its own doc comment for why).
     * [entitlementSource] has exactly one implementation today -- there is no account to be on any
     * other plan -- and [usageLedger] is local, resettable, and records nothing until something
     * calls it.
     */
    val entitlementPolicy: Policy? by lazy { PolicyAssets.loadShippedPolicy(context.applicationContext) }
    val entitlementSource: EntitlementSource by lazy { FreeForEveryone }
    val usageLedger: UsageLedger by lazy { UsageLedger(context.applicationContext) }

    /** The family book's fonts, photographs and PDF writer (#200). The composer is per screen. */
    val bookPrinter: BookPrinter by lazy { BookPrinter(context.applicationContext, photoStore) }
    val bookTemplates: BookTemplates by lazy { BookTemplates(context.applicationContext, templateDownloader) }

    /**
     * Downloaded book templates (#214), wired to the updater's one network client rather than a
     * second one: `UpdateClient` stays the only code in the app that opens a connection.
     */
    val templateDownloader: TemplateDownloader by lazy {
        val client = UpdateClient()
        TemplateDownloader(
            directory = File(context.applicationContext.filesDir, "templates"),
            enabled = { updatePreferences.enabled.value && updatePreferences.templates.value },
            seq = object : TemplateDownloader.SeqStore {
                override var value: Long
                    get() = updatePreferences.templatesSeq
                    set(v) { updatePreferences.templatesSeq = v }
            },
            publicKey = BuildConfig.TEMPLATE_PUBLIC_KEY,
            // The beta channel chooses the release here for the same reason it does for an update: a
            // reader on the beta channel is looking at the beta's catalogue.
            fetch = { client.fetchLatestRelease(includePreReleases = updatePreferences.betaChannel.value) },
            transfer = { url, destination, bytes -> client.download(url, destination, bytes) {} },
        )
    }

    /**
     * Built lazily like everything else, which also means the updater's objects do not exist at
     * all in a session where nobody opens Settings.
     */
    val updateRepository: UpdateRepository by lazy {
        UpdateRepository(
            preferences = updatePreferences,
            client = UpdateClient(),
            guard = ApkGuard(context.applicationContext),
            installer = UpdateInstaller(context.applicationContext),
        )
    }

    val reminderPreferences: ReminderPreferences by lazy { ReminderPreferences(context) }

    /** Birthday reminders (#154). Nothing is scheduled unless [ReminderPreferences.enabled] is on. */
    val reminders: Reminders by lazy { Reminders(context, reminderPreferences, familyRepository) }

    val nearbyPreferences: NearbyPreferences by lazy { NearbyPreferences(context) }
    val nearbyIdentity: NearbyIdentity by lazy { NearbyIdentity(context) }

    /**
     * Built lazily like everything else, which here is load-bearing rather than tidy: in a session
     * where nobody opens the nearby screen, none of these objects exist, no socket is constructed
     * and no multicast lock is ever taken.
     *
     * The transport is a `var` so an instrumented test can substitute a fake before this is first
     * touched. An emulator sits behind a user-mode NAT and cannot do multicast at all, so without
     * that seam the receive flow could only ever be exercised by hand on two physical devices.
     */
    var nearbyTransport: NearbyTransport? = null

    val nearbyRepository: NearbyRepository by lazy {
        NearbyRepository(
            preferences = nearbyPreferences,
            identity = nearbyIdentity,
            transport = nearbyTransport
                ?: LanTransport(context.applicationContext, nearbyIdentity.deviceId),
            downloadDirectory = File(context.applicationContext.filesDir, "nearby"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
