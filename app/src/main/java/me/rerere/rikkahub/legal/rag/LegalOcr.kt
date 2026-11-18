package me.rerere.rikkahub.legal.rag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.PdfParser
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import java.io.File

private const val TAG = "LegalOcr"
private const val PAGE_TIMEOUT_MS = 120_000L
private const val RENDER_LONG_EDGE = 2000
private const val MAX_PAGES_PER_RUN = 1000

/**
 * OCR for scanned record pages (court transcripts, police reports, handwritten affidavits).
 * Pages without a text layer are rendered on device and sent one at a time to the vision
 * model chosen in Settings → Models → OCR. Results replace only the blank pages, keeping
 * page numbering intact so pinpoint cites (Doc, p. N) stay correct.
 */
class LegalOcr(
    private val context: Context,
    private val index: LegalIndex,
    private val ingestor: LegalIngestor,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _progress = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())
    /** docId -> (pages done, pages total) while OCR is running. */
    val progress: StateFlow<Map<Long, Pair<Int, Int>>> = _progress

    fun isConfigured(): Boolean {
        val s = settingsStore.settingsFlow.value
        return s.findModelById(s.ocrModelId) != null
    }

    fun schedule(docId: Long) {
        scope.launch { runCatching { ocrDocument(docId) }.onFailure { Log.w(TAG, "OCR failed for $docId", it) } }
    }

    /** Returns the number of pages that received OCR text. */
    suspend fun ocrDocument(docId: Long): Int = lock.withLock {
        val doc = index.getDocument(docId) ?: return 0
        val file = File(doc.filePath)
        require(file.isFile) { "Source file missing for ${doc.title}" }
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId)
            ?: throw IllegalStateException("No OCR model selected (Settings → Models → OCR)")
        val providerSetting = model.findProvider(settings.providers)
            ?: throw IllegalStateException("OCR model has no provider")
        val provider = providerManager.getProviderByType(providerSetting)

        val isPdf = file.extension.equals("pdf", true) || doc.mime == "application/pdf"
        val isImage = file.extension.lowercase() in IMAGE_EXTS || doc.mime.startsWith("image/")
        require(isPdf || isImage) { "OCR supports PDF and image files" }

        // Current text per page (PDF text layer), so only blank pages are sent to the model.
        val pages: MutableList<Pair<Int, String>> =
            if (isPdf) PdfParser.parsePages(file).toMutableList() else mutableListOf(1 to "")
        val targets = pages.withIndex().filter { it.value.second.isBlank() }.map { it.index }.take(MAX_PAGES_PER_RUN)
        if (targets.isEmpty()) return 0
        _progress.update { it + (docId to (0 to targets.size)) }

        var done = 0
        val tmpDir = File(context.cacheDir, "legal-ocr").apply { mkdirs() }
        val renderer = if (isPdf) PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) else null
        try {
            for ((n, idx) in targets.withIndex()) {
                val img = if (renderer != null) renderPage(renderer, idx, File(tmpDir, "d${docId}_p${idx + 1}.png")) else file
                val text = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                    provider.generateText(
                        providerSetting = providerSetting,
                        messages = listOf(
                            UIMessage.system(LEGAL_OCR_PROMPT),
                            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file://" + img.absolutePath))),
                        ),
                        params = TextGenerationParams(model = model, customHeaders = model.customHeaders, customBody = model.customBodies),
                    ).message.toText()
                }.orEmpty().trim()
                if (renderer != null) img.delete()
                if (text.isNotBlank() && !text.equals("[BLANK PAGE]", true)) {
                    pages[idx] = pages[idx].first to text
                    done++
                }
                _progress.update { it + (docId to (n + 1 to targets.size)) }
            }
        } finally {
            renderer?.close()
            _progress.update { it - docId }
        }
        if (done > 0) {
            val chunkPages = pages.map { (num, t) -> LegalChunker.Page(num, t) }
            val prepared = LegalChunker.prepare(chunkPages)
            val chunks = LegalChunker.chunk(prepared)
            index.replaceContent(
                docId,
                LegalIndex.NewDocument(
                    projectId = doc.projectId, title = doc.title, filePath = doc.filePath, mime = doc.mime,
                    sha256 = doc.sha256, docType = doc.docType, prepared = prepared, chunks = chunks,
                    citations = CitationExtractor.extract(prepared.text),
                    emptyPages = chunkPages.count { it.text.isBlank() },
                ),
            )
            if (index.getProject(doc.projectId)?.autoEmbed == true && chunks.isNotEmpty()) ingestor.scheduleEmbedding(docId)
        }
        done
    }

    private fun renderPage(renderer: PdfRenderer, idx: Int, out: File): File {
        renderer.openPage(idx).use { page ->
            val scale = RENDER_LONG_EDGE.toFloat() / maxOf(page.width, page.height)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        return out
    }

    companion object {
        val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "heic", "tif", "tiff", "bmp", "gif")

        const val LEGAL_OCR_PROMPT = """You are transcribing one page of a U.S. legal record (court transcript, police report, lab report, affidavit, motion, or opinion) for a criminal defense / post-conviction team.
Rules:
- Transcribe VERBATIM. Do not summarize, correct spelling or grammar, or fill gaps.
- Preserve transcript line numbers, speaker labels (Q:, A:, THE COURT:, MR. SMITH:), page headers, Bates numbers, exhibit stamps, dates and case numbers exactly as printed.
- Keep the original line breaks. Represent tables as pipe tables.
- Mark unreadable text as [illegible], handwriting as [handwritten: ...], redactions as [redacted], stamps/signatures as [stamp: ...] / [signature].
- Output only the page text. If the page is blank, output exactly [BLANK PAGE]."""
    }
}
