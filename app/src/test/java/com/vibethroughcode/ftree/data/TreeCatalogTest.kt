package com.vibethroughcode.ftree.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeCatalogTest {
    private class Memory(var text: String? = null) : CatalogStore {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private var n = 0
    private fun catalog(store: Memory = Memory()) = TreeCatalog(store) { "t${++n}" }

    @Test fun `a fresh install has the original tree under its old file names`() {
        val c = catalog()
        assertEquals(listOf(TreeCatalog.ORIGINAL), c.trees)
        assertEquals("f-tree.db", c.active.databaseName)
        assertEquals("photos", c.active.photoDirectory)
        assertEquals("f-tree", c.active.identityFile)
    }

    @Test fun `trees get their own files and survive a restart`() {
        val store = Memory()
        val c = catalog(store)
        val work = (c.create("  Work ", TreeKind.CIRCLE) as CatalogResult.Ok).tree
        assertEquals("Work", work.name)
        assertEquals(TreeKind.CIRCLE, work.kind)
        assertTrue(work.databaseName != TreeCatalog.ORIGINAL.databaseName)
        assertTrue(c.setActive(work.id))

        val again = TreeCatalog(store)
        assertEquals(listOf("My family", "Work"), again.trees.map { it.name })
        assertEquals(work, again.active)
    }

    @Test fun `names must be non-blank, short and unique ignoring case`() {
        val c = catalog()
        assertEquals(CatalogResult.Refused(CatalogResult.Reason.BLANK_NAME), c.create("  ", TreeKind.CIRCLE))
        assertEquals(CatalogResult.Refused(CatalogResult.Reason.NAME_TOO_LONG), c.create("x".repeat(41), TreeKind.CIRCLE))
        assertEquals(CatalogResult.Refused(CatalogResult.Reason.NAME_TAKEN), c.create("my FAMILY", TreeKind.FAMILY))
        val friends = (c.create("Friends", TreeKind.CIRCLE) as CatalogResult.Ok).tree
        assertEquals(CatalogResult.Refused(CatalogResult.Reason.NAME_TAKEN), c.rename(friends.id, "my family"))
        assertTrue(c.rename(friends.id, "FRIENDS") is CatalogResult.Ok) // its own name again is fine
    }

    @Test fun `the open tree and the last tree cannot be removed`() {
        val c = catalog()
        assertEquals(CatalogResult.Refused(CatalogResult.Reason.IS_ACTIVE), c.remove("main"))
        val work = (c.create("Work", TreeKind.CIRCLE) as CatalogResult.Ok).tree
        assertEquals(CatalogResult.Refused(CatalogResult.Reason.IS_ACTIVE), c.remove("main"))
        assertEquals(CatalogResult.Ok(work), c.remove(work.id)) // returned so its files can be deleted
        assertEquals(listOf("main"), c.trees.map { it.id })
        assertEquals(CatalogResult.Refused(CatalogResult.Reason.UNKNOWN_TREE), c.remove("nope"))
    }

    @Test fun `switching to a tree that does not exist is refused`() {
        val c = catalog()
        assertFalse(c.setActive("nope"))
        assertEquals("main", c.active.id)
    }

    @Test fun `an unreadable list falls back to the original tree`() {
        assertEquals(listOf(TreeCatalog.ORIGINAL), catalog(Memory("{not json")).trees)
        assertEquals(listOf(TreeCatalog.ORIGINAL), catalog(Memory("""{"trees":[],"activeId":"x"}""")).trees)
    }
}
