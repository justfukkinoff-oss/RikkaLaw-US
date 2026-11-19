package me.rerere.rikkahub.legal.rag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.jsoup.Jsoup
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

private const val TAG = "LegalIngestor"
private const val EMBED_BATCH = 16
private const val EMBED_MAX_CHARS = 6_000

/** Live status of a document going through ingestion / embedding, for the Projects UI. */
data class IngestProgress(
    val docId: Long?,
    val title: String,
    val stage: Stage,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
) {
    enum class Stage { COPYING, PARSING, INDEXING, EMBEDDING, DONE, FAILED }
}

data class IngestResult(val document: LegalDocument, val duplicate: Boolean, val citations: Int, val chunks: Int)

/**
 * Turns uploaded files into indexed legal documents:
 * copy -> parse page-by-page -> normalize/chunk -> extract citations -> FTS index ->
 * (optional, background) embed with the user's embedding slot.
 */
class LegalIngestor(
    private val context: Context,
    private val index: LegalIndex,
    private val settingsStore: SettingsStore,
    private val embeddings: EmbeddingClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val embedLock = Mutex()

    private val _progress = MutableStateFlow<Map<String, IngestProgress>>(emptyMap())
    /** keyed by a transient job id (`<projectId>:<title>` while parsing, `doc:<id>` once indexed). */
    val progress: StateFlow<Map<String, IngestProgress>> = _progress

    private fun report(key: String, p: IngestProgress?) = _progress.update { m ->
        if (p == null) m - key else m + (key to p)
    }

    init {
        // Resume embedding whenever an embedding model becomes available (first selection, a
        // model change, or app start after an interrupted run). Only documents that were never
        // embedded or were cut off mid-run are picked up, so a failing model does not loop.
        scope.launch {
            settingsStore.settingsFlow
                .map { it.resolveSlot(it.embeddingModelId)?.label }
                .distinctUntilChanged()
                .collect { label ->
                    if (label == null) return@collect
                    runCatching {
                        index.listProjects().filter { it.autoEmbed }.forEach { p ->
                            index.listDocuments(p.id)
                                .filter { it.embedStatus == EmbedStatus.NONE || it.embedStatus == EmbedStatus.PENDING }
                                .forEach { d -> runCatching { embedDocument(d.id) }.onFailure { Log.w(TAG, "embedding failed for ${d.id}", it) } }
                        }
                    }.onFailure { Log.w(TAG, "embedding resume failed", it) }
                }
        }
    }

    fun projectDir(projectId: String): File =
        File(context.filesDir, "legal/projects/$projectId/sources").apply { mkdirs() }

    fun createProject(name: String, description: String = "", jurisdiction: String = "", autoEmbed: Boolean = true): LegalProject =
        index.createProject(Uuid.random().toString(), name.trim(), description.trim(), jurisdiction.trim(), autoEmbed)

    /** The shared "Library" project that holds files uploaded from the Files area. */
    fun libraryProject(): LegalProject =
        index.getProject(LIBRARY_PROJECT_ID) ?: index.createProject(
            LIBRARY_PROJECT_ID, "Library",
            "General reference uploads from the Files area (statutes, treatises, forms, prior briefs).",
            "", true,
        )

    fun deleteProject(projectId: String) {
        index.deleteProject(projectId)
        File(context.filesDir, "legal/projects/$projectId").deleteRecursively()
    }

    fun deleteDocument(docId: Long) {
        val doc = index.getDocument(docId) ?: return
        index.deleteDocument(docId)
        runCatching { File(doc.filePath).takeIf { it.path.startsWith(context.filesDir.path) }?.delete() }
    }

    /** Ingest a content:// or file:// uri picked by the user. */
    suspend fun ingestUri(projectId: String, uri: Uri, docType: String = ""): IngestResult = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "upload-${System.currentTimeMillis()}"
        val key = "$projectId:$name"
        report(key, IngestProgress(null, name, IngestProgress.Stage.COPYING))
        try {
            val tmp = File(context.cacheDir, "legal-upload-${System.nanoTime()}")
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalArgumentException("Cannot open $uri")
            val mime = context.contentResolver.getType(uri)
            ingestFileInternal(projectId, tmp, name, mime, docType, key, moveSource = true)
        } catch (t: Throwable) {
            report(key, IngestProgress(null, name, IngestProgress.Stage.FAILED, message = t.message))
            throw t
        }
    }

    /** Ingest a file already on disk (e.g. a download in the workspace). The file is copied. */
    suspend fun ingestFile(projectId: String, file: File, title: String? = null, docType: String = ""): IngestResult =
        withContext(Dispatchers.IO) {
            require(file.isFile && file.canRead()) { "Not a readable file: ${file.path}" }
            val name = title?.takeIf { it.isNotBlank() } ?: file.name
            val key = "$projectId:$name"
            try {
                ingestFileInternal(projectId, file, name, null, docType, key, moveSource = false)
            } catch (t: Throwable) {
                report(key, IngestProgress(null, name, IngestProgress.Stage.FAILED, message = t.message))
                throw t
            }
        }

    private fun ingestFileInternal(
        projectId: String, src: File, title: String, mimeHint: String?, docType: String, key: String, moveSource: Boolean,
    ): IngestResult {
        val project = index.getProject(projectId) ?: throw IllegalArgumentException("Unknown project $projectId")
        val sha = sha256(src)
        index.findDocumentBySha(projectId, sha)?.let {
            if (moveSource) src.delete()
            report(key, null)
            return IngestResult(it, duplicate = true, citations = 0, chunks = index.countChunks(it.id))
        }
        val safeName = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
        val dest = File(projectDir(projectId), "${sha.take(10)}_$safeName")
        if (moveSource) {
            if (!src.renameTo(dest)) { src.copyTo(dest, overwrite = true); src.delete() }
        } else {
            src.copyTo(dest, overwrite = true)
        }
        val mime = mimeHint ?: guessMime(dest.name)

        report(key, IngestProgress(null, title, IngestProgress.Stage.PARSING))
        val pages = parsePages(dest, mime) { done, total ->
            report(key, IngestProgress(null, title, IngestProgress.Stage.PARSING, done, total))
        }
        val emptyPages = pages.count { it.text.isBlank() }

        report(key, IngestProgress(null, title, IngestProgress.Stage.INDEXING))
        val prepared = LegalChunker.prepare(pages)
        val chunks = LegalChunker.chunk(prepared)
        val citations = CitationExtractor.extract(prepared.text)
        val docId = index.insertDocument(
            LegalIndex.NewDocument(
                projectId = projectId, title = title, filePath = dest.absolutePath, mime = mime, sha256 = sha,
                docType = docType.ifBlank { guessDocType(title, prepared.text) }, prepared = prepared,
                chunks = chunks, citations = citations, emptyPages = emptyPages,
            )
        )
        report(key, null)
        val doc = index.getDocument(docId)!!
        report("doc:$docId", IngestProgress(docId, title, IngestProgress.Stage.DONE, chunks.size, chunks.size))
        if (project.autoEmbed && chunks.isNotEmpty()) scheduleEmbedding(docId)
        // Scanned pages: hand off to OCR when an OCR model is configured (resolved lazily to
        // avoid a constructor cycle LegalOcr -> LegalIngestor).
        if (emptyPages > 0) runCatching {
            val ocr = org.koin.java.KoinJavaComponent.getKoin().get<LegalOcr>()
            if (ocr.isConfigured()) ocr.schedule(docId)
        }
        return IngestResult(doc, duplicate = false, citations = citations.size, chunks = chunks.size)
    }

    // ---------------------------------------------------------------- parsing

    private fun parsePages(file: File, mime: String, onPage: (Int, Int) -> Unit): List<LegalChunker.Page> {
        val ext = file.extension.lowercase()
        return when {
            ext == "pdf" || mime == "application/pdf" ->
                PdfParser.parsePages(file, onPage = onPage).map { (n, t) -> LegalChunker.Page(n, t) }
            ext in LegalOcr.IMAGE_EXTS || mime.startsWith("image/") -> listOf(LegalChunker.Page(1, ""))
            ext == "docx" -> listOf(LegalChunker.Page(1, DocxParser.parse(file)))
            ext == "pptx" -> listOf(LegalChunker.Page(1, PptxParser.parse(file)))
            ext == "epub" -> listOf(LegalChunker.Page(1, EpubParser.parse(file)))
            ext in setOf("html", "htm", "xhtml") || mime.contains("html") ->
                listOf(LegalChunker.Page(1, Jsoup.parse(file, "UTF-8").let { doc ->
                    doc.select("script,style,nav,header,footer").remove()
                    doc.body()?.wholeText() ?: doc.text()
                }))
            else -> listOf(LegalChunker.Page(1, file.readText()))
        }.let(::splitFormFeeds)
    }

    /** Plain-text transcripts often carry form feeds as page breaks — honour them. */
    private fun splitFormFeeds(pages: List<LegalChunker.Page>): List<LegalChunker.Page> {
        if (pages.size != 1 || '\u000C' !in pages[0].text) return pages
        return pages[0].text.split('\u000C').mapIndexed { i, t -> LegalChunker.Page(i + 1, t) }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "epub" -> "application/epub+zip"
        "html", "htm" -> "text/html"
        "md" -> "text/markdown"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "tif", "tiff" -> "image/tiff"
        else -> "text/plain"
    }

    /** Rough document classification used for filtering and for the assistant's orientation. */
    private fun guessDocType(title: String, text: String): String {
        val head = (title + "\n" + text.take(4_000)).lowercase()
        return when {
            "petition for writ of habeas corpus" in head || "§ 2254" in head || "2254" in title.lowercase() -> "habeas_petition"
            "transcript" in head && ("direct examination" in head || "cross-examination" in head || "the court:" in head) -> "transcript"
            "opinion" in head && ("affirmed" in head || "reversed" in head || "remanded" in head) -> "opinion"
            "motion" in head -> "motion"
            "brief" in head -> "brief"
            "indictment" in head || "information" in head && "count" in head -> "charging_document"
            "police report" in head || "incident report" in head || "supplemental report" in head -> "police_report"
            "laboratory" in head || "lab report" in head || "dna" in head -> "forensic_report"
            "affidavit" in head || "declaration" in head -> "affidavit"
            "docket" in head -> "docket"
            else -> ""
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- embedding

    fun scheduleEmbedding(docId: Long) {
        scope.launch { runCatching { embedDocument(docId) }.onFailure { Log.w(TAG, "embedding failed for $docId", it) } }
    }

    fun scheduleProjectEmbedding(projectId: String) {
        scope.launch {
            index.documentsNeedingEmbedding(projectId).forEach { d ->
                runCatching { embedDocument(d.id) }.onFailure { Log.w(TAG, "embedding failed for ${d.id}", it) }
            }
        }
    }

    /** Embed every not-yet-embedded chunk of [docId]. Serialized so batches don't fight for rate limits. */
    suspend fun embedDocument(docId: Long) = embedLock.withLock {
        val doc = index.getDocument(docId) ?: return@withLock
        val slot = settingsStore.settingsFlow.value.resolveSlot(settingsStore.settingsFlow.value.embeddingModelId)
        val key = "doc:$docId"
        if (slot == null) {
            index.setEmbedStatus(docId, EmbedStatus.NONE, "No embedding model selected (Settings → Models → Embedding)")
            report(key, IngestProgress(docId, doc.title, IngestProgress.Stage.DONE))
            return@withLock
        }
        val total = index.countChunks(docId)
        index.setEmbedStatus(docId, EmbedStatus.PENDING)
        report(key, IngestProgress(docId, doc.title, IngestProgress.Stage.EMBEDDING, index.countChunks(docId, embeddedOnly = true), total))
        try {
            while (true) {
                val batch = index.chunksWithoutEmbedding(docId, EMBED_BATCH)
                if (batch.isEmpty()) break
                val inputs = batch.map { c ->
                    buildString {
                        append(doc.title)
                        if (c.heading.isNotBlank()) append(" — ").append(c.heading)
                        append(" (p. ").append(c.pageStart).append(")\n")
                        append(c.text)
                    }.take(EMBED_MAX_CHARS)
                }
                val vectors = embeddings.embed(slot, inputs, EmbedRole.DOCUMENT)
                index.storeEmbeddings(batch.map { it.id }.zip(vectors), slot.label)
                val done = index.countChunks(docId, embeddedOnly = true)
                report(key, IngestProgress(docId, doc.title, IngestProgress.Stage.EMBEDDING, done, total))
            }
            index.setEmbedStatus(docId, EmbedStatus.DONE)
            report(key, IngestProgress(docId, doc.title, IngestProgress.Stage.DONE, total, total))
        } catch (t: Throwable) {
            val done = index.countChunks(docId, embeddedOnly = true)
            index.setEmbedStatus(docId, if (done > 0) EmbedStatus.PARTIAL else EmbedStatus.FAILED, t.message)
            report(key, IngestProgress(docId, doc.title, IngestProgress.Stage.FAILED, done, total, t.message))
            throw t
        }
    }
}

const val LIBRARY_PROJECT_ID = "library"
