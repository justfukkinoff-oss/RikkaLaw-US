package me.rerere.rikkahub.ui.pages.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.legal.rag.DocStatus
import me.rerere.rikkahub.legal.rag.EmbedStatus
import me.rerere.rikkahub.legal.rag.IngestProgress
import me.rerere.rikkahub.legal.rag.LegalDocument
import me.rerere.rikkahub.legal.rag.SearchMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

private val UPLOAD_MIME = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain", "text/markdown", "text/html", "application/epub+zip",
)

/** Projects = case files. Each holds record documents indexed for search, quote checks and grounding. */
@Composable
fun ProjectsPage(vm: ProjectsVM = koinViewModel()) {
    val nav = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val projects by vm.projects.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text("Projects") },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(HugeIcons.Add01, null) },
                text = { Text("New project") },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { inner ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No projects yet. Create one per case, then upload transcripts, opinions, briefs, " +
                        "police and lab reports. Everything is indexed on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = inner + PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(projects, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate(Screen.ProjectDetail(p.id)) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(HugeIcons.Folder01, null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.description.isNotBlank()) {
                                    Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    "${p.documentCount} docs · ${p.chunkCount} passages · " +
                                        if (p.chunkCount == 0) "—" else "${p.embeddedCount * 100 / p.chunkCount}% embedded",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, desc, auto -> vm.createProject(name, desc, auto); showCreate = false },
        )
    }
}

@Composable
private fun CreateProjectDialog(onDismiss: () -> Unit, onCreate: (String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Case name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text("Court / case no. / posture (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Embed uploads automatically", Modifier.weight(1f))
                    Switch(auto, { auto = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, desc, auto) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProjectDetailPage(projectId: String, vm: ProjectsVM = koinViewModel()) {
    val nav = LocalNavController.current
    val projects by vm.projects.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val ocrProgress by vm.ocrProgress.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val embedModel by vm.embeddingModelLabel.collectAsStateWithLifecycle()
    val project = projects.firstOrNull { it.id == projectId }
    val docs = documents[projectId].orEmpty()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SearchMode.HYBRID) }

    LaunchedEffect(projectId) { vm.loadDocuments(projectId); vm.clearSearch() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(projectId, uris)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(project?.name ?: "Project", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) { Icon(HugeIcons.Delete01, "Delete project") }
                },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(UPLOAD_MIME) },
                icon = { Icon(HugeIcons.Files02, null) },
                text = { Text("Upload") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { inner ->
        LazyColumn(
            contentPadding = inner + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto-embed uploads", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    embedModel?.let { "Model: $it" } ?: "No embedding model selected (Settings → Models). Keyword and exact search still work.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(project?.autoEmbed == true, { vm.setAutoEmbed(projectId, it) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { vm.embedNow(projectId) }, label = { Text("Embed pending") })
                            AssistChip(
                                onClick = { vm.reEmbed(projectId) },
                                label = { Text("Re-embed all") },
                                leadingIcon = { Icon(HugeIcons.Refresh01, null) },
                            )
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search this record") },
                            placeholder = { Text("\"did not object\" informant") },
                            leadingIcon = { Icon(HugeIcons.Search01, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search(projectId, query, mode) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(SearchMode.HYBRID, SearchMode.EXACT, SearchMode.KEYWORD, SearchMode.CITATION).forEach { m ->
                                FilterChip(
                                    selected = mode == m,
                                    onClick = { mode = m; if (query.isNotBlank()) vm.search(projectId, query, m) },
                                    label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                )
                            }
                        }
                    }
                }
            }

            results?.let { hits ->
                item { Text("${hits.size} result(s)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp)) }
                items(hits, key = { "hit-${it.chunkId}" }) { h ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${h.document} · p. ${if (h.pageStart == h.pageEnd) h.pageStart else "${h.pageStart}–${h.pageEnd}"}",
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            )
                            if (h.heading.isNotBlank()) Text(h.heading, style = MaterialTheme.typography.labelSmall)
                            Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
                            Text(h.matchedBy.joinToString(" + "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            val pendingUploads = progress.filterKeys { it.startsWith("$projectId:") }.values
            items(pendingUploads.toList(), key = { "up-${it.title}" }) { p -> ProgressCard(p) }

            item { Text("Documents (${docs.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp)) }
            items(docs, key = { "doc-${it.id}" }) { d ->
                DocumentCard(
                    d, progress["doc:${d.id}"], ocrProgress[d.id],
                    onOcr = { vm.runOcr(projectId, d.id) },
                    onDelete = { vm.deleteDocument(projectId, d.id) },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete project?") },
            text = { Text("Removes the project, its index and its copies of uploaded files from this device. Originals elsewhere are not touched.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteProject(projectId); confirmDelete = false; nav.popBackStack() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProgressCard(p: IngestProgress) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(p.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (p.stage) {
                    IngestProgress.Stage.PARSING -> if (p.total > 0) "Reading page ${p.done} of ${p.total}" else "Reading…"
                    IngestProgress.Stage.FAILED -> "Failed: ${p.message.orEmpty()}"
                    else -> p.stage.name.lowercase().replaceFirstChar { it.uppercase() } + "…"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            if (p.stage != IngestProgress.Stage.FAILED) {
                if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DocumentCard(
    d: LegalDocument,
    p: IngestProgress?,
    ocr: Pair<Int, Int>?,
    onOcr: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val parts = buildList {
                    add("${d.pageCount} pp.")
                    if (d.docType.isNotBlank()) add(d.docType.replace('_', ' '))
                    if (d.status == DocStatus.NEEDS_OCR) add("scanned — needs OCR")
                    else if (d.emptyPages > 0) add("${d.emptyPages} pages without text")
                    add(
                        when (d.embedStatus) {
                            EmbedStatus.DONE -> "embedded"
                            EmbedStatus.PENDING -> "embedding…"
                            EmbedStatus.PARTIAL -> "partly embedded"
                            EmbedStatus.FAILED -> "embedding failed"
                            else -> "keyword-indexed"
                        }
                    )
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                if (ocr != null) {
                    Text("OCR page ${ocr.first} of ${ocr.second}", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { ocr.first.toFloat() / ocr.second.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else if (d.emptyPages > 0) {
                    TextButton(onClick = onOcr, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Run OCR on ${d.emptyPages} page(s)") }
                }
                if (p != null && p.stage == IngestProgress.Stage.EMBEDDING && p.total > 0) {
                    LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            IconButton(onClick = { confirm = true }) { Icon(HugeIcons.Delete01, "Remove document") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove document?") },
            text = { Text("Removes \"${d.title}\" and its index from this project.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirm = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
