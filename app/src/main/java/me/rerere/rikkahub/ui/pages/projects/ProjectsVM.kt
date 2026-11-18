package me.rerere.rikkahub.ui.pages.projects

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.legal.rag.IngestProgress
import me.rerere.rikkahub.legal.rag.LegalDocument
import me.rerere.rikkahub.legal.rag.LegalIndex
import me.rerere.rikkahub.legal.rag.LegalIngestor
import me.rerere.rikkahub.legal.rag.LegalProject
import me.rerere.rikkahub.legal.rag.LegalRetriever
import me.rerere.rikkahub.legal.rag.SearchHit
import me.rerere.rikkahub.legal.rag.SearchMode
import me.rerere.rikkahub.legal.rag.resolveSlot

class ProjectsVM(
    private val index: LegalIndex,
    private val ingestor: LegalIngestor,
    private val retriever: LegalRetriever,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _projects = MutableStateFlow<List<LegalProject>>(emptyList())
    val projects: StateFlow<List<LegalProject>> = _projects.asStateFlow()

    private val _documents = MutableStateFlow<Map<String, List<LegalDocument>>>(emptyMap())
    val documents: StateFlow<Map<String, List<LegalDocument>>> = _documents.asStateFlow()

    val progress: StateFlow<Map<String, IngestProgress>> = ingestor.progress

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchHit>?>(null)
    val searchResults: StateFlow<List<SearchHit>?> = _searchResults.asStateFlow()

    val embeddingModelLabel: StateFlow<String?> = settingsStore.settingsFlow
        .map { it.resolveSlot(it.embeddingModelId)?.label }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        refresh()
        // Refresh lists as ingestion / embedding progresses.
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            ingestor.progress.debounce(400).collect { refresh() }
        }
    }

    fun refresh(projectId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _projects.value = index.listProjects()
            val ids = projectId?.let { listOf(it) } ?: _documents.value.keys
            _documents.value = _documents.value + ids.associateWith { index.listDocuments(it) }
        }
    }

    fun loadDocuments(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _documents.value = _documents.value + (projectId to index.listDocuments(projectId))
        }
    }

    fun createProject(name: String, description: String, autoEmbed: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            ingestor.createProject(name, description, autoEmbed = autoEmbed)
            refresh()
        }
    }

    fun setAutoEmbed(projectId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            index.updateProject(projectId, autoEmbed = enabled)
            if (enabled) ingestor.scheduleProjectEmbedding(projectId)
            refresh(projectId)
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ingestor.deleteProject(projectId)
            _documents.value = _documents.value - projectId
            refresh()
        }
    }

    fun deleteDocument(projectId: String, docId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            ingestor.deleteDocument(docId)
            refresh(projectId)
        }
    }

    fun upload(projectId: String, uris: List<Uri>) {
        viewModelScope.launch {
            var ok = 0
            var dup = 0
            val failures = ArrayList<String>()
            for (u in uris) {
                runCatching { ingestor.ingestUri(projectId, u) }
                    .onSuccess { if (it.duplicate) dup++ else ok++ }
                    .onFailure { failures += (it.message ?: it.toString()) }
                refresh(projectId)
            }
            _messages.value = buildString {
                append("Indexed $ok document(s)")
                if (dup > 0) append(", $dup already in project")
                if (failures.isNotEmpty()) append(". Failed: ").append(failures.joinToString("; ").take(300))
            }
        }
    }

    fun embedNow(projectId: String) {
        if (embeddingModelLabel.value == null) {
            _messages.value = "Select an embedding model in Settings → Models first"
            return
        }
        ingestor.scheduleProjectEmbedding(projectId)
        _messages.value = "Embedding started"
    }

    fun reEmbed(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            index.clearEmbeddings(projectId)
            withContext(Dispatchers.Main) { embedNow(projectId) }
        }
    }

    fun search(projectId: String, query: String, mode: SearchMode) {
        if (query.isBlank()) { _searchResults.value = null; return }
        viewModelScope.launch {
            val out = retriever.search(LegalRetriever.Query(query, mode, listOf(projectId), topK = 10))
            _searchResults.value = out.hits
            if (out.notes.isNotEmpty()) _messages.value = out.notes.joinToString("; ")
        }
    }

    fun clearSearch() { _searchResults.value = null }

    fun consumeMessage() { _messages.value = null }
}
