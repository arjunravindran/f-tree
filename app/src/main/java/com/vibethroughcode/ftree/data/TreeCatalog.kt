package com.vibethroughcode.ftree.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * What a tree is for. A [FAMILY] tree is today's parent/spouse/sibling chart with kinship names;
 * a [CIRCLE] (friends, work, a team) holds people joined by plain connections and has no kinship.
 */
enum class TreeKind { FAMILY, CIRCLE }

/**
 * One tree and where its data lives. Each tree is a separate database file, photo directory and
 * identity file, so nothing in a tree's queries, layout, export or merge logic knows other trees
 * exist, and deleting a tree deletes exactly its own files.
 *
 * The first tree keeps the names the app has always used, so an update changes no file on disk.
 */
@Serializable
data class TreeInfo(
    val id: String,
    val name: String,
    val kind: TreeKind = TreeKind.FAMILY,
    val databaseName: String,
    val photoDirectory: String,
    val identityFile: String,
)

/** Where the catalog is kept; a SharedPreferences string in the app, a variable in tests. */
interface CatalogStore {
    fun read(): String?
    fun write(text: String)
}

sealed interface CatalogResult {
    data class Ok(val tree: TreeInfo) : CatalogResult
    data class Refused(val reason: Reason) : CatalogResult

    enum class Reason { BLANK_NAME, NAME_TOO_LONG, NAME_TAKEN, UNKNOWN_TREE, IS_ACTIVE, IS_LAST }
}

/**
 * The list of trees and which one is open. Pure bookkeeping: it never touches a database, so the
 * rules (unique names, the last tree cannot be removed, the open tree cannot be removed) are
 * JVM-tested. Whoever deletes a tree removes the files named in the [TreeInfo] it gets back.
 */
class TreeCatalog(
    private val store: CatalogStore,
    private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
) {
    @Serializable
    private data class Stored(val trees: List<TreeInfo>, val activeId: String)

    private var state: Stored = load()

    val trees: List<TreeInfo> get() = state.trees
    val active: TreeInfo get() = state.trees.first { it.id == state.activeId }

    fun create(name: String, kind: TreeKind): CatalogResult {
        val clean = validName(name, except = null) ?: return refusal(name, null)
        val id = newId()
        val tree = TreeInfo(
            id = id,
            name = clean,
            kind = kind,
            databaseName = "tree-$id.db",
            photoDirectory = "photos-$id",
            identityFile = "f-tree-$id",
        )
        save(Stored(state.trees + tree, state.activeId))
        return CatalogResult.Ok(tree)
    }

    fun rename(id: String, name: String): CatalogResult {
        val existing = state.trees.firstOrNull { it.id == id } ?: return CatalogResult.Refused(CatalogResult.Reason.UNKNOWN_TREE)
        val clean = validName(name, except = id) ?: return refusal(name, id)
        val renamed = existing.copy(name = clean)
        save(Stored(state.trees.map { if (it.id == id) renamed else it }, state.activeId))
        return CatalogResult.Ok(renamed)
    }

    fun setActive(id: String): Boolean {
        if (state.trees.none { it.id == id }) return false
        save(Stored(state.trees, id))
        return true
    }

    /** Removes [id] from the list and returns it, so the caller can delete its files. */
    fun remove(id: String): CatalogResult {
        val tree = state.trees.firstOrNull { it.id == id } ?: return CatalogResult.Refused(CatalogResult.Reason.UNKNOWN_TREE)
        if (id == state.activeId) return CatalogResult.Refused(CatalogResult.Reason.IS_ACTIVE)
        if (state.trees.size == 1) return CatalogResult.Refused(CatalogResult.Reason.IS_LAST)
        save(Stored(state.trees.filter { it.id != id }, state.activeId))
        return CatalogResult.Ok(tree)
    }

    private fun validName(name: String, except: String?): String? {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME) return null
        if (state.trees.any { it.id != except && it.name.equals(clean, ignoreCase = true) }) return null
        return clean
    }

    private fun refusal(name: String, except: String?): CatalogResult.Refused {
        val clean = name.trim()
        return CatalogResult.Refused(
            when {
                clean.isEmpty() -> CatalogResult.Reason.BLANK_NAME
                clean.length > MAX_NAME -> CatalogResult.Reason.NAME_TOO_LONG
                else -> CatalogResult.Reason.NAME_TAKEN
            },
        )
    }

    private fun save(next: Stored) {
        store.write(json.encodeToString(Stored.serializer(), next))
        state = next
    }

    private fun load(): Stored {
        val text = store.read()
        val parsed = text?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
        if (parsed != null && parsed.trees.isNotEmpty() && parsed.trees.any { it.id == parsed.activeId }) return parsed
        // First run, or an unreadable list: the original tree, under the names it has always had.
        return Stored(listOf(ORIGINAL), ORIGINAL.id).also { store.write(json.encodeToString(Stored.serializer(), it)) }
    }

    companion object {
        const val MAX_NAME = 40

        /** The tree every install already has. Its file names are the ones used before there were several. */
        val ORIGINAL = TreeInfo(
            id = "main",
            name = "My family",
            kind = TreeKind.FAMILY,
            databaseName = "f-tree.db",
            photoDirectory = "photos",
            identityFile = "f-tree",
        )

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
