package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.legal.rag.EmbedStatus
import me.rerere.rikkahub.legal.rag.IngestProgress
import me.rerere.rikkahub.legal.rag.LIBRARY_PROJECT_ID
import me.rerere.rikkahub.legal.rag.LegalDocument
import me.rerere.rikkahub.legal.rag.LegalIndex
import me.rerere.rikkahub.legal.rag.LegalIngestor
import me.rerere.rikkahub.legal.rag.resolveSlot

/**
 * Live view of the Library project's index, so an upload from the Files area is never a
 * black box: what was indexed, what is embedding right now, what failed and why.
 */
class LibraryIndexSnapshot(
    val docs: List<LegalDocument>,
    val progress: Map<String, IngestProgress>,
) {
    private val byTitle = docs.associateBy { it.title }

    fun docFor(displayName: String): LegalDocument? = byTitle[displayName]

    fun progressFor(doc: LegalDocument?, displayName: String): IngestProgress? =
        (doc?.let { progress["doc:${it.id}"] })
            ?: progress.values.firstOrNull { it.title == displayName && it.stage != IngestProgress.Stage.DONE }
}

/** Polls the Library documents whenever ingest progress changes (and every few seconds while busy). */
@Composable
fun rememberLibraryIndex(index: LegalIndex, ingestor: LegalIngestor, refreshKey: Any?): LibraryIndexSnapshot {
    val progress by ingestor.progress.collectAsStateWithLifecycle()
    var docs by remember { mutableStateOf<List<LegalDocument>>(emptyList()) }
    LaunchedEffect(progress, refreshKey) {
        while (true) {
            docs = withContext(Dispatchers.IO) {
                runCatching { index.listDocuments(LIBRARY_PROJECT_ID) }.getOrDefault(emptyList())
            }
            val busy = progress.values.any { it.stage != IngestProgress.Stage.DONE && it.stage != IngestProgress.Stage.FAILED } ||
                docs.any { it.embedStatus == EmbedStatus.PENDING }
            if (!busy) break
            delay(2_000)
        }
    }
    return LibraryIndexSnapshot(docs, progress)
}

/** One-line, human status for a file card. */
fun fileIndexStatus(doc: LegalDocument?, progress: IngestProgress?, embeddingConfigured: Boolean): Pair<String, Boolean> {
    if (progress != null) {
        val frac = if (progress.total > 0) " ${progress.done}/${progress.total}" else ""
        return when (progress.stage) {
            IngestProgress.Stage.COPYING -> "Copying…" to false
            IngestProgress.Stage.PARSING -> "Reading pages$frac…" to false
            IngestProgress.Stage.INDEXING -> "Building search index…" to false
            IngestProgress.Stage.EMBEDDING -> "Embedding$frac…" to false
            IngestProgress.Stage.FAILED -> "Failed: ${progress.message ?: "unknown error"}" to true
            IngestProgress.Stage.DONE -> if (doc == null) "Indexed" to false else docStatus(doc, embeddingConfigured)
        }
    }
    if (doc == null) return "Not indexed for AI search" to true
    return docStatus(doc, embeddingConfigured)
}

private fun docStatus(doc: LegalDocument, embeddingConfigured: Boolean): Pair<String, Boolean> {
    val ocr = if (doc.emptyPages > 0) " · ${doc.emptyPages} page(s) need OCR" else ""
    return when (doc.embedStatus) {
        EmbedStatus.DONE -> "Searchable · embedded$ocr" to (doc.emptyPages > 0)
        EmbedStatus.PENDING -> "Searchable · embedding…$ocr" to false
        EmbedStatus.PARTIAL -> "Searchable · embedding incomplete: ${doc.error ?: "retry"}$ocr" to true
        EmbedStatus.FAILED -> "Searchable (keyword only) · embedding failed: ${doc.error ?: "unknown"}$ocr" to true
        else -> if (embeddingConfigured) "Searchable (keyword only) · not embedded yet$ocr" to true
        else "Searchable (keyword only) · no embedding model set$ocr" to true
    }
}

@Composable
fun LibraryIndexPanel(
    snapshot: LibraryIndexSnapshot,
    settings: Settings,
    indexUploads: Boolean,
    unindexedCount: Int,
    onRetryEmbedding: () -> Unit,
    onIndexMissing: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val docs = snapshot.docs
    val embeddingLabel = settings.resolveSlot(settings.embeddingModelId)?.label
    val embedded = docs.count { it.embedStatus == EmbedStatus.DONE }
    val pending = docs.count { it.embedStatus == EmbedStatus.PENDING }
    val failed = docs.count { it.embedStatus == EmbedStatus.FAILED || it.embedStatus == EmbedStatus.PARTIAL }
    val notEmbedded = docs.count { it.embedStatus == EmbedStatus.NONE }
    val needOcr = docs.count { it.emptyPages > 0 }
    val active = snapshot.progress.values.filter {
        it.stage != IngestProgress.Stage.DONE && it.stage != IngestProgress.Stage.FAILED
    }
    val recentFailures = snapshot.progress.values.filter { it.stage == IngestProgress.Stage.FAILED }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AI search index (Library project)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${docs.size} document(s) searchable by the assistant · $embedded embedded" +
                    (if (pending > 0) " · $pending embedding" else "") +
                    (if (notEmbedded > 0) " · $notEmbedded not embedded" else "") +
                    (if (failed > 0) " · $failed failed" else "") +
                    (if (needOcr > 0) " · $needOcr need OCR" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (embeddingLabel != null) "Embedding model: $embeddingLabel"
                else "No embedding model selected. Documents are still searchable by keyword, exact phrase and citation, but not by meaning.",
                style = MaterialTheme.typography.bodySmall,
                color = if (embeddingLabel != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            if (!indexUploads) {
                Text(
                    "Indexing of new uploads is off, so the assistant will not see them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            active.take(4).forEach { p ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "${p.stage.name.lowercase().replaceFirstChar { it.uppercase() }}: ${p.title}" +
                            (if (p.total > 0) " (${p.done}/${p.total})" else ""),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            recentFailures.take(3).forEach { p ->
                Text(
                    "Failed: ${p.title} — ${p.message ?: "unknown error"}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (unindexedCount > 0) TextButton(onClick = onIndexMissing) { Text("Index $unindexedCount file(s)") }
                if (embeddingLabel != null && (notEmbedded + failed) > 0) TextButton(onClick = onRetryEmbedding) { Text("Embed now") }
                if (embeddingLabel == null) TextButton(onClick = onOpenModels) { Text("Choose model") }
                TextButton(onClick = onOpenProjects) { Text("Open Projects") }
            }
        }
    }
}
