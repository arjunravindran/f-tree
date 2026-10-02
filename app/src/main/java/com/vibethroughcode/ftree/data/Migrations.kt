package com.vibethroughcode.ftree.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 adds the family-network tables (trust store, facts game, sync outbox, rewards). It only
 * creates; nothing in the tree, the relationships or the import history is touched, so an app
 * updated in place keeps its family. The statements are the ones Room generated into
 * `schemas/.../2.json`, and `MigrationTest` checks the result against that file.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `local_identity` (`id` INTEGER NOT NULL, `personId` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `sealedPrivateKey` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trusted_contacts` (`personId` TEXT NOT NULL, `pubKeyCurrent` TEXT NOT NULL, `pubKeyHistory` TEXT NOT NULL, `pairedAt` INTEGER NOT NULL, `pairingMethod` TEXT NOT NULL, `vouchedByPersonId` TEXT, PRIMARY KEY(`personId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trusted_contacts_pubKeyCurrent` ON `trusted_contacts` (`pubKeyCurrent`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `identity_rotations` (`signature` TEXT NOT NULL, `personId` TEXT NOT NULL, `oldPubKey` TEXT NOT NULL, `newPubKey` TEXT NOT NULL, `vouchedByPubKey` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`signature`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_identity_rotations_personId` ON `identity_rotations` (`personId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `person_locations` (`personId` TEXT NOT NULL, `label` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `source` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `sharingEnabled` INTEGER NOT NULL, PRIMARY KEY(`personId`, `label`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `facts` (`id` TEXT NOT NULL, `personId` TEXT NOT NULL, `category` TEXT NOT NULL, `prompt` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_personId` ON `facts` (`personId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `fact_answers` (`id` TEXT NOT NULL, `factId` TEXT NOT NULL, `answererId` TEXT NOT NULL, `answerText` TEXT NOT NULL, `isSelfReported` INTEGER NOT NULL, `submittedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fact_answers_factId` ON `fact_answers` (`factId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `fact_resolutions` (`factId` TEXT NOT NULL, `winningAnswerId` TEXT NOT NULL, `resolvedByPersonId` TEXT NOT NULL, `pointsAwarded` INTEGER NOT NULL, PRIMARY KEY(`factId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `points_ledger` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` TEXT NOT NULL, `points` INTEGER NOT NULL, `reasonFactId` TEXT NOT NULL, `awardedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_points_ledger_reasonFactId` ON `points_ledger` (`reasonFactId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_points_ledger_personId` ON `points_ledger` (`personId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`eventId` TEXT NOT NULL, `payloadEncrypted` TEXT NOT NULL, `targetPubKeys` TEXT NOT NULL, `relayUrls` TEXT NOT NULL, `publishedAt` INTEGER, `ackedBy` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, PRIMARY KEY(`eventId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reward_redemptions` (`id` TEXT NOT NULL, `fromPersonId` TEXT NOT NULL, `toPersonId` TEXT NOT NULL, `rewardType` TEXT NOT NULL, `pointsThreshold` INTEGER NOT NULL, `status` TEXT NOT NULL, `redeemedAt` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reward_redemptions_fromPersonId_toPersonId` ON `reward_redemptions` (`fromPersonId`, `toPersonId`)")
    }
}
