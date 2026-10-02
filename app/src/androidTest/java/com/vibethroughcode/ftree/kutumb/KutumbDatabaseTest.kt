package com.vibethroughcode.ftree.kutumb

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.data.FTreeDatabase
import com.vibethroughcode.ftree.data.MIGRATION_1_2
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.db.AndroidKeyVault
import com.vibethroughcode.ftree.kutumb.db.KeyVault
import com.vibethroughcode.ftree.kutumb.db.RoomKutumbRepository
import com.vibethroughcode.ftree.kutumb.game.Fact
import com.vibethroughcode.ftree.kutumb.game.FactAnswer
import com.vibethroughcode.ftree.kutumb.game.FactResolution
import com.vibethroughcode.ftree.kutumb.game.LedgerEntry
import com.vibethroughcode.ftree.kutumb.game.RewardRedemption
import com.vibethroughcode.ftree.kutumb.game.RewardType
import com.vibethroughcode.ftree.kutumb.geo.LocationLabel
import com.vibethroughcode.ftree.kutumb.geo.LocationSource
import com.vibethroughcode.ftree.kutumb.geo.PersonLocation
import com.vibethroughcode.ftree.kutumb.sync.OutboxEntry
import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The family-network tables: that every record survives a round trip, and that the guards hold. */
@RunWith(AndroidJUnit4::class)
class KutumbDatabaseTest {

    /** A vault that does nothing clever, so tests can see exactly what reaches the database. */
    private class ReversingVault(private val tag: String = "A") : KeyVault {
        override fun seal(plain: String) = "$tag:" + plain.reversed()
        override fun open(sealed: String) = sealed.removePrefix("$tag:").takeIf { sealed.startsWith("$tag:") }?.reversed()
    }

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FTreeDatabase::class.java)

    private lateinit var db: FTreeDatabase
    private lateinit var repo: RoomKutumbRepository

    private val keyA = Bip340Scheme.generateKeyPair()
    private val keyB = Bip340Scheme.generateKeyPair()
    private val keyC = Bip340Scheme.generateKeyPair()

    @Before
    fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FTreeDatabase::class.java).addCallback(FTreeDatabase.enforceForeignKeys).build()
        repo = RoomKutumbRepository(db, ReversingVault())
    }

    @After
    fun close() = db.close()

    @Test
    fun theIdentityRoundTripsAndThePrivateKeyIsNeverStoredAsItIs() = runBlocking {
        repo.setIdentity(LocalIdentity("me", keyA))

        val back = repo.observeIdentity().first()!!
        assertEquals("me", back.personId)
        assertEquals(keyA.publicKey, back.keyPair.publicKey)
        assertEquals(keyA.privateKey, back.keyPair.privateKey)

        val stored = db.query("SELECT sealedPrivateKey FROM local_identity", null).use { it.moveToFirst(); it.getString(0) }
        assertFalse(stored.contains(keyA.privateKey))
    }

    @Test
    fun anIdentitySealedByAnotherVaultReadsAsAbsentRatherThanBroken() = runBlocking {
        repo.setIdentity(LocalIdentity("me", keyA))
        val elsewhere = RoomKutumbRepository(db, ReversingVault("B"))
        assertNull(elsewhere.observeIdentity().first())
    }

    @Test
    fun contactsKeepTheirHistoryAndTheirPairingMethod() = runBlocking {
        val contact = TrustedContact("devika", keyB.publicKey, listOf(keyC.publicKey), 5, PairingMethod.VOUCHED, vouchedByPersonId = "asha")
        repo.saveContact(contact)
        assertEquals(listOf(contact), repo.observeContacts().first())
        // Saving again replaces the person's row rather than adding a second.
        repo.saveContact(contact.copy(pairedAt = 9))
        assertEquals(listOf(9L), repo.observeContacts().first().map { it.pairedAt })
    }

    @Test
    fun oneKeyCannotBelongToTwoPeople() {
        runBlocking { repo.saveContact(TrustedContact("a", keyA.publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT)) }
        val failed = runCatching {
            runBlocking { repo.saveContact(TrustedContact("b", keyA.publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT)) }
        }
        assertTrue(failed.isFailure)
    }

    @Test
    fun aRedeliveredRotationIsTheSameRow() = runBlocking {
        val event = IdentityRotationEvent.attest(Bip340Scheme, keyA, "p", keyB.publicKey, keyC.publicKey, 10)
        repo.saveRotation(event)
        repo.saveRotation(event)
        assertEquals(listOf(event), repo.observeRotations().first())
    }

    @Test
    fun locationsAreOnePerPersonPerLabel() = runBlocking {
        repo.saveLocation(PersonLocation("p", LocationLabel.CURRENT, 1.0, 2.0, LocationSource.MANUAL, 1))
        repo.saveLocation(PersonLocation("p", LocationLabel.BIRTHPLACE, 3.0, 4.0, LocationSource.GEOCODED, 1))
        repo.saveLocation(PersonLocation("p", LocationLabel.CURRENT, 5.0, 6.0, LocationSource.MANUAL, 2, sharingEnabled = false))
        val all = repo.observeLocations().first()
        assertEquals(2, all.size)
        assertEquals(5.0, all.first { it.label == LocationLabel.CURRENT }.lat, 0.0)
        assertFalse(all.first { it.label == LocationLabel.CURRENT }.sharingEnabled)
    }

    @Test
    fun everyAnswerIsKeptAndARepeatedIdIsNotDuplicated() = runBlocking {
        repo.saveFact(Fact("kiran/food.pizza", "kiran", "food", "food.pizza"))
        repo.saveAnswer(FactAnswer("a1", "kiran/food.pizza", "devika", "Pepperoni", false, 1))
        repo.saveAnswer(FactAnswer("a2", "kiran/food.pizza", "kiran", "Margherita", true, 2))
        repo.saveAnswer(FactAnswer("a1", "kiran/food.pizza", "devika", "Pepperoni", false, 1))
        assertEquals(listOf("a1", "a2"), repo.observeAnswers().first().map { it.id })
    }

    @Test
    fun aFactCanOnlyBeResolvedOnceSoPointsCannotBeAwardedTwice() = runBlocking {
        repo.saveResolution(FactResolution("f", "a1", "kiran", 1), LedgerEntry("devika", 1, "f", 10))
        repo.saveResolution(FactResolution("f", "a2", "kiran", 1), LedgerEntry("asha", 1, "f", 11))

        assertEquals("a1", repo.observeResolutions().first().single().winningAnswerId)
        assertEquals(listOf("devika"), repo.observeLedger().first().map { it.personId })
    }

    @Test
    fun redemptionsAndTheOutboxRoundTrip() = runBlocking {
        val owed = RewardRedemption("r1", "kiran", "devika", RewardType.COFFEE, 5)
        repo.saveRedemption(owed)
        repo.saveRedemption(owed.settle("devika", 9))
        assertEquals(listOf(owed.settle("devika", 9)), repo.observeRedemptions().first())

        val entry = OutboxEntry("e1", "sealed", listOf(keyA.publicKey), listOf("wss://a", "wss://b"), ackedBy = setOf("wss://a"), attempts = 2, nextAttemptAt = 7)
        repo.saveOutboxEntry(entry)
        assertEquals(listOf(entry), repo.outboxEntries())
        repo.deleteOutboxEntry("e1")
        assertTrue(repo.outboxEntries().isEmpty())
    }

    @Test
    fun clearingTheNetworkLeavesTheFamilyTreeAlone() = runBlocking {
        db.personDao().insert(Person(id = "p", name = "Kept"))
        repo.setIdentity(LocalIdentity("p", keyA))
        repo.saveContact(TrustedContact("p", keyB.publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT))
        repo.clearAll()
        assertTrue(repo.observeContacts().first().isEmpty())
        assertNull(repo.observeIdentity().first())
        assertEquals("Kept", db.personDao().findById("p")?.name)
    }

    @Test
    fun deletingAPersonDoesNotDropTheirTrustedKey() = runBlocking {
        db.personDao().insert(Person(id = "p", name = "Gone"))
        repo.saveContact(TrustedContact("p", keyB.publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT))
        db.personDao().deleteById("p")
        assertEquals(1, repo.observeContacts().first().size)
    }

    @Test
    fun theKeystoreVaultSealsAndOpensAndRefusesAnythingElse() {
        val vault = AndroidKeyVault("ftree-test-vault")
        val sealed = vault.seal(keyA.privateKey)
        assertFalse(sealed.contains(keyA.privateKey))
        assertNotEquals(sealed, vault.seal(keyA.privateKey))
        assertEquals(keyA.privateKey, vault.open(sealed))
        assertNull(vault.open("not-a-sealed-value"))
        assertNull(vault.open("AAAA:BBBB"))
        // Another alias is another key: it cannot open what this one sealed.
        assertNull(AndroidKeyVault("ftree-test-vault-other").open(sealed))
    }

    @Test
    fun updatingFromVersionOneKeepsTheTreeAndAddsTheTables() {
        val name = "migration-test"
        migrations.createDatabase(name, 1).apply {
            execSQL("INSERT INTO people (id, name, gender, deceased, createdAt, updatedAt) VALUES ('p', 'Asha', 'UNSPECIFIED', 0, 1, 1)")
            close()
        }
        val migrated = migrations.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2)
        migrated.query("SELECT name FROM people WHERE id = 'p'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Asha", it.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM trusted_contacts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        migrated.close()
    }
}
