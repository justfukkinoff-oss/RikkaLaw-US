package me.rerere.rikkahub.legal.rag

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "LegalIndex"
private const val DB_NAME = "legal_index.db"
private const val DB_VERSION = 1

@Serializable
data class LegalProject(
    val id: String,
    val name: String,
    val description: String = "",
    val jurisdiction: String = "",
    val autoEmbed: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val documentCount: Int = 0,
    val chunkCount: Int = 0,
    val embeddedCount: Int = 0,
)

@Serializable
data class LegalDocument(
    val id: Long,
    val projectId: String,
    val title: String,
    val filePath: String,
    val mime: String,
    val sha256: String,
    val pageCount: Int,
    val charCount: Int,
    val docType: String,
    val status: String,
    val embedStatus: String,
    val error: String? = null,
    val emptyPages: Int = 0,
    val createdAt: Long,
)

data class ChunkRow(
    val id: Long,
    val docId: Long,
    val projectId: String,
    val ordinal: Int,
    val pageStart: Int,
    val pageEnd: Int,
    val heading: String,
    val text: String,
    val charStart: Int,
    val charEnd: Int,
)

data class KeywordHit(val chunkId: Long, val score: Double, val snippet: String)

object DocStatus {
    const val INDEXED = "indexed"
    const val NEEDS_OCR = "needs_ocr"
    const val FAILED = "failed"
}

object EmbedStatus {
    const val NONE = "none"
    const val PENDING = "pending"
    const val PARTIAL = "partial"
    const val DONE = "done"
    const val FAILED = "failed"
}

/**
 * On-device legal index. A single SQLite database (requery build, FTS5 enabled) holding
 * projects, documents (with normalized full text for verbatim checks), chunks, embeddings and
 * extracted citations.
 *
 * Retrieval surfaces:
 *  - `chunk_fts`  FTS5, porter + unicode61 — BM25 keyword search and token phrase queries.
 *  - `chunk_tri`  FTS5, trigram — exact substring matching (Elasticsearch `match_phrase`-like,
 *                 but also works inside tokens and across punctuation such as `§ 2254(d)`).
 *  - `chunks.embedding` L2-normalized float32 vectors — brute-force cosine, scoped per project.
 *  - `citations`  every authority the extractor found, keyed for exact citation lookup.
 */
class LegalIndex(context: Context) {

    private val helper: SupportSQLiteOpenHelper = RequerySQLiteOpenHelperFactory(
        listOf(RequerySQLiteOpenHelperFactory.ConfigurationOptions { it })
    ).create(
        SupportSQLiteOpenHelper.Configuration.builder(context.applicationContext)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(DB_VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) = createSchema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("PRAGMA foreign_keys=ON").close()
                }
            })
            .build()
    )

    val db: SupportSQLiteDatabase get() = helper.writableDatabase

    /** Bumped on every write that changes embeddings, so vector caches can invalidate. */
    private val version = AtomicLong(0)

    @Volatile
    private var trigramChecked: Boolean? = null

    val hasTrigram: Boolean
        get() {
            trigramChecked?.let { return it }
            val ok = runCatching {
                db.query("SELECT value FROM meta WHERE key='trigram'").use { c -> c.moveToFirst() && c.getString(0) == "1" }
            }.getOrDefault(false)
            trigramChecked = ok
            return ok
        }

    private fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS projects(
                id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
                jurisdiction TEXT NOT NULL DEFAULT '', auto_embed INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS documents(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                title TEXT NOT NULL, file_path TEXT NOT NULL, mime TEXT NOT NULL, sha256 TEXT NOT NULL,
                page_count INTEGER NOT NULL, char_count INTEGER NOT NULL, doc_type TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL, embed_status TEXT NOT NULL, error TEXT, empty_pages INTEGER NOT NULL DEFAULT 0,
                full_text TEXT NOT NULL, page_starts TEXT NOT NULL, page_numbers TEXT NOT NULL,
                created_at INTEGER NOT NULL, UNIQUE(project_id, sha256))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chunks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                project_id TEXT NOT NULL, ord INTEGER NOT NULL, page_start INTEGER NOT NULL,
                page_end INTEGER NOT NULL, heading TEXT NOT NULL DEFAULT '', text TEXT NOT NULL,
                char_start INTEGER NOT NULL, char_end INTEGER NOT NULL, embedding BLOB, embed_model TEXT)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS chunks_doc ON chunks(doc_id, ord)")
        db.execSQL("CREATE INDEX IF NOT EXISTS chunks_project ON chunks(project_id)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS citations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                project_id TEXT NOT NULL, chunk_id INTEGER, kind TEXT NOT NULL, cite_key TEXT NOT NULL,
                raw TEXT NOT NULL, case_name TEXT, pin TEXT, year TEXT, court TEXT,
                char_start INTEGER NOT NULL, char_end INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS citations_key ON citations(project_id, cite_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS citations_doc ON citations(doc_id)")
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(text, heading, content='chunks', " +
                "content_rowid='id', tokenize='porter unicode61 remove_diacritics 2')"
        )
        val trigram = runCatching {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS chunk_tri USING fts5(text, content='chunks', " +
                    "content_rowid='id', tokenize='trigram')"
            )
            true
        }.onFailure { Log.w(TAG, "trigram tokenizer unavailable; exact search falls back to phrase+scan", it) }
            .getOrDefault(false)
        db.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES('trigram', '${if (trigram) 1 else 0}')")
        val triIns = if (trigram) "INSERT INTO chunk_tri(rowid, text) VALUES (new.id, new.text);" else ""
        val triDel = if (trigram) "INSERT INTO chunk_tri(chunk_tri, rowid, text) VALUES ('delete', old.id, old.text);" else ""
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS chunks_ai AFTER INSERT ON chunks BEGIN " +
                "INSERT INTO chunk_fts(rowid, text, heading) VALUES (new.id, new.text, new.heading); $triIns END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS chunks_ad AFTER DELETE ON chunks BEGIN " +
                "INSERT INTO chunk_fts(chunk_fts, rowid, text, heading) VALUES ('delete', old.id, old.text, old.heading); $triDel END"
        )
    }

    // ---------------------------------------------------------------- projects

    fun createProject(id: String, name: String, description: String, jurisdiction: String, autoEmbed: Boolean): LegalProject {
        val now = System.currentTimeMillis()
        db.insert("projects", CONFLICT_IGNORE, ContentValues().apply {
            put("id", id); put("name", name); put("description", description)
            put("jurisdiction", jurisdiction); put("auto_embed", if (autoEmbed) 1 else 0)
            put("created_at", now); put("updated_at", now)
        })
        return getProject(id)!!
    }

    fun updateProject(id: String, name: String? = null, description: String? = null, jurisdiction: String? = null, autoEmbed: Boolean? = null) {
        val cv = ContentValues()
        name?.let { cv.put("name", it) }
        description?.let { cv.put("description", it) }
        jurisdiction?.let { cv.put("jurisdiction", it) }
        autoEmbed?.let { cv.put("auto_embed", if (it) 1 else 0) }
        cv.put("updated_at", System.currentTimeMillis())
        db.update("projects", CONFLICT_IGNORE, cv, "id = ?", arrayOf(id))
    }

    fun deleteProject(id: String) {
        db.beginTransaction()
        try {
            // Delete chunks row-by-row through the trigger so the FTS tables stay consistent.
            db.execSQL("DELETE FROM chunks WHERE project_id = ?", arrayOf(id))
            db.execSQL("DELETE FROM citations WHERE project_id = ?", arrayOf(id))
            db.execSQL("DELETE FROM documents WHERE project_id = ?", arrayOf(id))
            db.execSQL("DELETE FROM projects WHERE id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        version.incrementAndGet()
        vectorCache.remove(id)
    }

    private val projectSelect = """
        SELECT p.id, p.name, p.description, p.jurisdiction, p.auto_embed, p.created_at, p.updated_at,
          (SELECT COUNT(*) FROM documents d WHERE d.project_id = p.id),
          (SELECT COUNT(*) FROM chunks c WHERE c.project_id = p.id),
          (SELECT COUNT(*) FROM chunks c WHERE c.project_id = p.id AND c.embedding IS NOT NULL)
        FROM projects p
    """.trimIndent()

    private fun Cursor.toProject() = LegalProject(
        id = getString(0), name = getString(1), description = getString(2), jurisdiction = getString(3),
        autoEmbed = getInt(4) == 1, createdAt = getLong(5), updatedAt = getLong(6),
        documentCount = getInt(7), chunkCount = getInt(8), embeddedCount = getInt(9),
    )

    fun listProjects(): List<LegalProject> =
        db.query("$projectSelect ORDER BY p.updated_at DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toProject()) }
        }

    fun getProject(id: String): LegalProject? =
        db.query("$projectSelect WHERE p.id = ?", arrayOf<Any?>(id)).use { c -> if (c.moveToFirst()) c.toProject() else null }

    /** Resolve a project by exact id, or case-insensitive name / name prefix. */
    fun resolveProject(idOrName: String): LegalProject? {
        getProject(idOrName)?.let { return it }
        val all = listProjects()
        return all.firstOrNull { it.name.equals(idOrName, true) }
            ?: all.firstOrNull { it.name.startsWith(idOrName, true) }
            ?: all.firstOrNull { it.name.contains(idOrName, true) }
    }

    // ---------------------------------------------------------------- documents

    data class NewDocument(
        val projectId: String,
        val title: String,
        val filePath: String,
        val mime: String,
        val sha256: String,
        val docType: String,
        val prepared: LegalChunker.Prepared,
        val chunks: List<LegalChunker.Chunk>,
        val citations: List<CitationExtractor.Citation>,
        val emptyPages: Int,
    )

    fun findDocumentBySha(projectId: String, sha: String): LegalDocument? =
        db.query("$docSelect WHERE project_id = ? AND sha256 = ?", arrayOf<Any?>(projectId, sha)).use { c ->
            if (c.moveToFirst()) c.toDocument() else null
        }

    /** Insert a parsed document with its chunks and citations in one transaction. */
    fun insertDocument(d: NewDocument): Long {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val docId = db.insert("documents", CONFLICT_IGNORE, ContentValues().apply {
                put("project_id", d.projectId); put("title", d.title); put("file_path", d.filePath)
                put("mime", d.mime); put("sha256", d.sha256)
                put("page_count", d.prepared.pageNumbers.size); put("char_count", d.prepared.text.length)
                put("doc_type", d.docType)
                put("status", if (d.chunks.isEmpty() && d.emptyPages > 0) DocStatus.NEEDS_OCR else DocStatus.INDEXED)
                put("embed_status", EmbedStatus.NONE)
                put("empty_pages", d.emptyPages)
                put("full_text", d.prepared.text)
                put("page_starts", d.prepared.pageStarts.joinToString(","))
                put("page_numbers", d.prepared.pageNumbers.joinToString(","))
                put("created_at", now)
            })
            require(docId > 0) { "document already indexed in this project" }
            val chunkIds = LongArray(d.chunks.size)
            d.chunks.forEachIndexed { i, ch ->
                chunkIds[i] = db.insert("chunks", CONFLICT_IGNORE, ContentValues().apply {
                    put("doc_id", docId); put("project_id", d.projectId); put("ord", ch.ordinal)
                    put("page_start", ch.pageStart); put("page_end", ch.pageEnd)
                    put("heading", ch.heading.orEmpty()); put("text", ch.text)
                    put("char_start", ch.start); put("char_end", ch.end)
                })
            }
            d.citations.forEach { c ->
                val owner = d.chunks.indexOfFirst { c.start >= it.start && c.start < it.end }
                db.insert("citations", CONFLICT_IGNORE, ContentValues().apply {
                    put("doc_id", docId); put("project_id", d.projectId)
                    if (owner >= 0) put("chunk_id", chunkIds[owner])
                    put("kind", c.kind.name); put("cite_key", c.key); put("raw", c.raw)
                    put("case_name", c.caseName); put("pin", c.pin); put("year", c.year); put("court", c.court)
                    put("char_start", c.start); put("char_end", c.end)
                })
            }
            db.execSQL("UPDATE projects SET updated_at = ? WHERE id = ?", arrayOf<Any?>(now, d.projectId))
            db.setTransactionSuccessful()
            return docId
        } finally {
            db.endTransaction()
        }
    }

    fun deleteDocument(docId: Long) {
        val projectId = getDocument(docId)?.projectId
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunks WHERE doc_id = ?", arrayOf<Any?>(docId))
            db.execSQL("DELETE FROM citations WHERE doc_id = ?", arrayOf<Any?>(docId))
            db.execSQL("DELETE FROM documents WHERE id = ?", arrayOf<Any?>(docId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        version.incrementAndGet()
        projectId?.let { vectorCache.remove(it) }
    }

    private val docSelect = "SELECT id, project_id, title, file_path, mime, sha256, page_count, char_count, doc_type, " +
        "status, embed_status, error, empty_pages, created_at FROM documents"

    private fun Cursor.toDocument() = LegalDocument(
        id = getLong(0), projectId = getString(1), title = getString(2), filePath = getString(3), mime = getString(4),
        sha256 = getString(5), pageCount = getInt(6), charCount = getInt(7), docType = getString(8),
        status = getString(9), embedStatus = getString(10), error = if (isNull(11)) null else getString(11),
        emptyPages = getInt(12), createdAt = getLong(13),
    )

    fun listDocuments(projectId: String): List<LegalDocument> =
        db.query("$docSelect WHERE project_id = ? ORDER BY created_at DESC", arrayOf<Any?>(projectId)).use { c ->
            buildList { while (c.moveToNext()) add(c.toDocument()) }
        }

    fun getDocument(id: Long): LegalDocument? =
        db.query("$docSelect WHERE id = ?", arrayOf<Any?>(id)).use { c -> if (c.moveToFirst()) c.toDocument() else null }

    fun setEmbedStatus(docId: Long, status: String, error: String? = null) {
        db.update("documents", CONFLICT_IGNORE, ContentValues().apply {
            put("embed_status", status); put("error", error)
        }, "id = ?", arrayOf(docId))
    }

    data class FullText(val text: String, val prepared: LegalChunker.Prepared)

    fun fullText(docId: Long): FullText? =
        db.query("SELECT full_text, page_starts, page_numbers FROM documents WHERE id = ?", arrayOf<Any?>(docId)).use { c ->
            if (!c.moveToFirst()) return null
            val text = c.getString(0)
            val starts = c.getString(1).split(',').filter { it.isNotBlank() }.map { it.toInt() }.toIntArray()
            val numbers = c.getString(2).split(',').filter { it.isNotBlank() }.map { it.toInt() }.toIntArray()
            FullText(text, LegalChunker.Prepared(text, starts, numbers))
        }

    // ---------------------------------------------------------------- chunks

    private val chunkSelect = "SELECT id, doc_id, project_id, ord, page_start, page_end, heading, text, char_start, char_end FROM chunks"

    private fun Cursor.toChunk() = ChunkRow(
        id = getLong(0), docId = getLong(1), projectId = getString(2), ordinal = getInt(3), pageStart = getInt(4),
        pageEnd = getInt(5), heading = getString(6), text = getString(7), charStart = getInt(8), charEnd = getInt(9),
    )

    fun chunks(ids: Collection<Long>): Map<Long, ChunkRow> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, ChunkRow>()
        ids.chunked(500).forEach { batch ->
            db.query("$chunkSelect WHERE id IN (${batch.joinToString(",")})").use { c ->
                while (c.moveToNext()) c.toChunk().let { out[it.id] = it }
            }
        }
        return out
    }

    fun chunksWithoutEmbedding(docId: Long, limit: Int): List<ChunkRow> =
        db.query("$chunkSelect WHERE doc_id = ? AND embedding IS NULL ORDER BY ord LIMIT ?", arrayOf<Any?>(docId, limit)).use { c ->
            buildList { while (c.moveToNext()) add(c.toChunk()) }
        }

    fun countChunks(docId: Long, embeddedOnly: Boolean = false): Int =
        db.query(
            "SELECT COUNT(*) FROM chunks WHERE doc_id = ?" + if (embeddedOnly) " AND embedding IS NOT NULL" else "",
            arrayOf<Any?>(docId),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun storeEmbeddings(rows: List<Pair<Long, FloatArray>>, model: String) {
        db.beginTransaction()
        try {
            rows.forEach { (id, v) ->
                db.update("chunks", CONFLICT_IGNORE, ContentValues().apply {
                    put("embedding", VectorMath.toBytes(VectorMath.normalize(v)))
                    put("embed_model", model)
                }, "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        version.incrementAndGet()
        vectorCache.clear()
    }

    fun clearEmbeddings(projectId: String) {
        db.execSQL("UPDATE chunks SET embedding = NULL, embed_model = NULL WHERE project_id = ?", arrayOf(projectId))
        db.execSQL("UPDATE documents SET embed_status = '${EmbedStatus.NONE}' WHERE project_id = ?", arrayOf(projectId))
        version.incrementAndGet()
        vectorCache.remove(projectId)
    }

    fun documentsNeedingEmbedding(projectId: String): List<LegalDocument> =
        listDocuments(projectId).filter { it.embedStatus != EmbedStatus.DONE && it.status != DocStatus.FAILED }

    /** Chunks adjacent to [chunk] in the same document, for context expansion. */
    fun neighbors(chunk: ChunkRow, radius: Int): List<ChunkRow> =
        db.query(
            "$chunkSelect WHERE doc_id = ? AND ord BETWEEN ? AND ? ORDER BY ord",
            arrayOf<Any?>(chunk.docId, chunk.ordinal - radius, chunk.ordinal + radius),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toChunk()) } }

    fun chunksForPages(docId: Long, pageFrom: Int, pageTo: Int): List<ChunkRow> =
        db.query(
            "$chunkSelect WHERE doc_id = ? AND page_end >= ? AND page_start <= ? ORDER BY ord",
            arrayOf<Any?>(docId, pageFrom, pageTo),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toChunk()) } }

    // ---------------------------------------------------------------- search primitives

    private fun scopeClause(projectIds: List<String>?, docIds: List<Long>?, alias: String = "c"): Pair<String, List<Any?>> {
        val parts = ArrayList<String>()
        val args = ArrayList<Any?>()
        if (!projectIds.isNullOrEmpty()) {
            parts += "$alias.project_id IN (${projectIds.joinToString(",") { "?" }})"
            args.addAll(projectIds)
        }
        if (!docIds.isNullOrEmpty()) {
            parts += "$alias.doc_id IN (${docIds.joinToString(",")})"
        }
        return (if (parts.isEmpty()) "" else " AND " + parts.joinToString(" AND ")) to args
    }

    /** BM25 keyword search over chunk text + heading (heading weighted lower). */
    fun keyword(ftsQuery: String, projectIds: List<String>?, docIds: List<Long>?, limit: Int): List<KeywordHit> {
        if (ftsQuery.isBlank()) return emptyList()
        val (scope, scopeArgs) = scopeClause(projectIds, docIds)
        val sql = "SELECT c.id, bm25(chunk_fts, 1.0, 0.35) AS s, snippet(chunk_fts, 0, '«', '»', '…', 28) " +
            "FROM chunk_fts JOIN chunks c ON c.id = chunk_fts.rowid WHERE chunk_fts MATCH ?$scope ORDER BY s LIMIT ?"
        return runCatching {
            db.query(sql, (listOf<Any?>(ftsQuery) + scopeArgs + limit).toTypedArray()).use { c ->
                buildList { while (c.moveToNext()) add(KeywordHit(c.getLong(0), -c.getDouble(1), c.getString(2))) }
            }
        }.onFailure { Log.w(TAG, "keyword query failed: $ftsQuery", it) }.getOrDefault(emptyList())
    }

    /**
     * Candidate chunks for an exact phrase. With trigram: every >=3-char token must occur as a
     * substring (robust to line wraps); the caller then verifies the phrase verbatim. Without
     * trigram: a token phrase query on the porter index.
     */
    fun exactCandidates(phrase: String, projectIds: List<String>?, docIds: List<Long>?, limit: Int): List<Long> {
        val (scope, scopeArgs) = scopeClause(projectIds, docIds)
        val tokens = phrase.split(Regex("\\s+")).map { it.replace("\"", "") }.filter { it.length >= 3 }
        val (table, q) = if (hasTrigram && tokens.isNotEmpty()) {
            "chunk_tri" to tokens.distinct().joinToString(" AND ") { "\"$it\"" }
        } else {
            val words = Regex("[\\p{L}\\p{N}]+").findAll(phrase).map { it.value }.toList()
            if (words.isEmpty()) return emptyList()
            "chunk_fts" to "\"${words.joinToString(" ")}\""
        }
        val sql = "SELECT c.id FROM $table JOIN chunks c ON c.id = $table.rowid WHERE $table MATCH ?$scope LIMIT ?"
        return runCatching {
            db.query(sql, (listOf<Any?>(q) + scopeArgs + limit).toTypedArray()).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
        }.onFailure { Log.w(TAG, "exact query failed: $q", it) }.getOrDefault(emptyList())
    }

    data class CitationRow(
        val docId: Long, val chunkId: Long?, val kind: String, val key: String, val raw: String,
        val caseName: String?, val pin: String?, val year: String?, val court: String?,
    )

    fun citationsByKey(keys: List<String>, projectIds: List<String>?, limit: Int): List<CitationRow> {
        if (keys.isEmpty()) return emptyList()
        val (scope, scopeArgs) = scopeClause(projectIds, null)
        val sql = "SELECT c.doc_id, c.chunk_id, c.kind, c.cite_key, c.raw, c.case_name, c.pin, c.year, c.court " +
            "FROM citations c WHERE c.cite_key IN (${keys.joinToString(",") { "?" }})$scope LIMIT ?"
        return db.query(sql, (keys + scopeArgs + limit).toTypedArray()).use { it.toCitationRows() }
    }

    fun citationsForScope(projectIds: List<String>?, docIds: List<Long>?): List<CitationRow> {
        val (scope, scopeArgs) = scopeClause(projectIds, docIds)
        val sql = "SELECT c.doc_id, c.chunk_id, c.kind, c.cite_key, c.raw, c.case_name, c.pin, c.year, c.court " +
            "FROM citations c WHERE 1=1$scope ORDER BY c.doc_id, c.char_start"
        return db.query(sql, scopeArgs.toTypedArray()).use { it.toCitationRows() }
    }

    private fun Cursor.toCitationRows(): List<CitationRow> = buildList {
        while (moveToNext()) add(
            CitationRow(
                docId = getLong(0), chunkId = if (isNull(1)) null else getLong(1), kind = getString(2),
                key = getString(3), raw = getString(4), caseName = if (isNull(5)) null else getString(5),
                pin = if (isNull(6)) null else getString(6), year = if (isNull(7)) null else getString(7),
                court = if (isNull(8)) null else getString(8),
            )
        )
    }

    // ---------------------------------------------------------------- vectors

    private class VectorSet(val version: Long, val ids: LongArray, val vectors: Array<FloatArray>, val docIds: LongArray)

    private val vectorCache = ConcurrentHashMap<String, VectorSet>()

    private fun vectorsFor(projectId: String): VectorSet {
        val v = version.get()
        vectorCache[projectId]?.takeIf { it.version == v }?.let { return it }
        val ids = ArrayList<Long>()
        val docs = ArrayList<Long>()
        val vecs = ArrayList<FloatArray>()
        db.query("SELECT id, doc_id, embedding FROM chunks WHERE project_id = ? AND embedding IS NOT NULL", arrayOf<Any?>(projectId)).use { c ->
            while (c.moveToNext()) {
                ids += c.getLong(0); docs += c.getLong(1); vecs += VectorMath.fromBytes(c.getBlob(2))
            }
        }
        return VectorSet(v, ids.toLongArray(), vecs.toTypedArray(), docs.toLongArray()).also { vectorCache[projectId] = it }
    }

    fun allProjectIds(): List<String> =
        db.query("SELECT id FROM projects").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Brute-force cosine over unit vectors. Fine for tens of thousands of chunks per project. */
    fun semantic(query: FloatArray, projectIds: List<String>?, docIds: List<Long>?, limit: Int): List<Pair<Long, Float>> {
        val q = VectorMath.normalize(query)
        val docFilter = docIds?.toHashSet()
        val heap = java.util.PriorityQueue<Pair<Long, Float>>(compareBy { it.second })
        for (pid in projectIds ?: allProjectIds()) {
            val set = vectorsFor(pid)
            for (i in set.ids.indices) {
                if (docFilter != null && set.docIds[i] !in docFilter) continue
                if (set.vectors[i].size != q.size) continue // embedded with a different model
                val s = VectorMath.dot(q, set.vectors[i])
                if (heap.size < limit) heap.add(set.ids[i] to s)
                else if (s > heap.peek()!!.second) { heap.poll(); heap.add(set.ids[i] to s) }
            }
        }
        return heap.sortedByDescending { it.second }
    }
}
