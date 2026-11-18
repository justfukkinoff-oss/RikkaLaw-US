package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * RikkaLaw — the knowledge half of cold memory: bulk import of a matter's corpus into the
 * knowledge base the `memory_index` / `memory_read` tools serve.
 *
 * Counsel drops source material (notes, transcriptions, CSV exports) into a directory of the
 * bound workspace — by convention the matter's `corpus/` folder — and the agent calls
 * `corpus_import` once to normalize every supported file into a Markdown knowledge-base
 * document. From then on the corpus costs nothing until consulted, exactly like anything
 * written through `memory_write`.
 *
 * Like cold memory, the tool is only registered when cold memory is enabled AND a workspace
 * with a knowledge directory is configured, so the tool list of every other assistant is
 * unchanged. All name/clip/render decisions are pure ([KnowledgeImportRules]) and unit-tested;
 * the storage access is injected, and `ChatService` routes it to `WorkspaceRepository` in the
 * same FILES area the knowledge directory itself lives in.
 */

/** One source file offered to `corpus_import`, already resolved inside the workspace. */
data class KbSourceFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
)

/**
 * Pure rules for the corpus import. No IO, no Android — exercised directly by
 * `KnowledgeImportToolTest`.
 */
object KnowledgeImportRules {

    /** Source directory used when the model does not name one. */
    const val DEFAULT_SOURCE_DIR = "corpus"

    /** Every imported document gets this prefix so they are recognizable in `memory_index`. */
    const val TARGET_PREFIX = "kb-"

    const val MARKDOWN_SUFFIX = ".md"

    /** File types the import understands; everything else is listed as ignored, not read. */
    val ALLOWED_SUFFIXES = listOf(".md", ".txt", ".csv")

    /** Upper bound on documents one call converts — re-run for larger corpora. */
    const val MAX_FILES_PER_IMPORT = 50

    /** Upper bound on slug characters kept from a source stem inside a kb-<slug>.md name. */
    const val MAX_TARGET_STEM_CHARS = 80

    /**
     * Upper bound on characters copied from one source file. The reader caps at 512 KB, and a
     * knowledge document should stay reachable in a few `memory_read` windows, not all of them.
     */
    const val MAX_BODY_CHARS = 60_000

    /** Echoed in every success envelope so the model knows what to do next. */
    const val CORPUS_IMPORT_NOTE =
        "Corpus documents were copied into the cold-memory knowledge base. " +
            "Use memory_index to list them and memory_read to consult them on demand."

    fun isSupported(name: String): Boolean =
        ALLOWED_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

    fun describeFormat(name: String): String = when {
        name.endsWith(".md", ignoreCase = true) -> "Markdown"
        name.endsWith(".csv", ignoreCase = true) -> "CSV (raw table, preserved verbatim in a fenced block)"
        else -> "Plain text"
    }

    /**
     * Resolves the model-supplied `source` argument. Blank means the default matter corpus
     * directory; otherwise the same rules as [ColdMemoryRules.normalizeDir] apply, so an
     * absolute `/workspace/...` path works but `..` segments do not. `""` is legitimate and
     * means the workspace root. Returns `null` only for an escaping path.
     */
    fun resolveSource(raw: String?): String? {
        if (raw.isNullOrBlank()) return DEFAULT_SOURCE_DIR
        return ColdMemoryRules.normalizeDir(raw)
    }

    /**
     * Deterministic target document name for one source file: `kb-<slug>.md`, where the slug is
     * the lowercased stem with every non-alphanumerical run folded to a single dash.
     */
    fun targetNameFor(sourceName: String): String {
        val stem = sourceName.substringBeforeLast('.', sourceName)
        val slug = stem.lowercase()
            .map { c -> if (c in 'a'..'z' || c in '0'..'9') c else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        val capped = if (slug.length > MAX_TARGET_STEM_CHARS) {
            slug.take(MAX_TARGET_STEM_CHARS).trimEnd('-')
        } else {
            slug
        }
        return "$TARGET_PREFIX${capped.ifEmpty { "doc" }}$MARKDOWN_SUFFIX"
    }

    /**
     * Aligned with the (already ordered, already capped) list of source file names: returns the
     * final knowledge-base document for each. Names stay unique inside the batch, and — when
     * [overwrite] is false — none of the existing documents is reused as a target, so a re-run
     * of the same import never silently clobbers anything.
     */
    fun importPlan(
        sourceNames: List<String>,
        existingNames: Set<String>,
        overwrite: Boolean,
    ): List<String> {
        val taken = mutableSetOf<String>()
        if (!overwrite) taken += existingNames
        return sourceNames.map { sourceName ->
            var candidate = targetNameFor(sourceName)
            candidate = uniqueName(candidate, taken)
            taken += candidate
            candidate
        }
    }

    /** First free variant of [target]: `kb-x.md`, then `kb-x-2.md`, `kb-x-3.md`, ... */
    fun uniqueName(target: String, taken: Set<String>): String {
        if (target !in taken) return target
        val stem = target.removeSuffix(MARKDOWN_SUFFIX)
        var n = 2
        while ("$stem-$n$MARKDOWN_SUFFIX" in taken) n++
        return "$stem-$n$MARKDOWN_SUFFIX"
    }

    /** Clips the raw source to [MAX_BODY_CHARS]; second value says whether anything was cut. */
    fun clip(raw: String): Pair<String, Boolean> {
        if (raw.length <= MAX_BODY_CHARS) return raw to false
        return raw.take(MAX_BODY_CHARS).trimEnd() to true
    }

    /**
     * Renders one knowledge-base document: a short provenance header over the (clipped) body.
     * CSV is fenced so the model reads it as a literal table.
     */
    fun render(sourcePath: String, sourceName: String, body: String, truncated: Boolean): String {
        val header = buildString {
            appendLine("# Import: $sourceName")
            appendLine()
            appendLine("- Source: `$sourcePath`")
            appendLine("- Format: ${describeFormat(sourceName)}")
            if (truncated) {
                appendLine(
                    "- Truncated to $MAX_BODY_CHARS characters (the source was longer); " +
                        "re-read the source file in the workspace for the rest.",
                )
            }
        }
        val fenced = if (sourceName.endsWith(".csv", ignoreCase = true)) {
            "```\n$body\n```"
        } else {
            body
        }
        return "$header\n$fenced\n"
    }
}

/**
 * Builds the `corpus_import` tool. Pure wiring, like [buildColdMemoryTools] — every storage
 * access is injected and routed by `ChatService`:
 *
 * @param dirLabel human-readable knowledge directory, echoed for orientation.
 * @param listSourceFiles lists the entries of one source directory in the workspace file area.
 * @param readSource reads one source file's text, or null when it cannot be read.
 * @param listKb the documents already in the knowledge base.
 * @param writeKbDoc persists one rendered knowledge document (never appends). The plan stage
 *   guarantees no existing document is targeted unless the model asked for `overwrite`.
 */
fun buildKnowledgeImportTools(
    dirLabel: String,
    listSourceFiles: suspend (source: String) -> List<KbSourceFile>,
    readSource: suspend (path: String) -> String?,
    listKb: suspend () -> List<String>,
    writeKbDoc: suspend (name: String, content: String) -> Boolean,
): List<Tool> = listOf(
    Tool(
        name = "corpus_import",
        description = """
            Bulk-import a matter's corpus into the cold-memory knowledge base.

            Reads the supported files (.md, .txt, .csv) of one directory in the workspace file
            area and copies each one into a normalized Markdown document next to everything
            `memory_index` serves, so the corpus is searchable on demand instead of being pasted
            into context. `source` defaults to "corpus" (the matter's corpus folder); pass a
            workspace path such as "/workspace/matters/smith/corpus" for anything else.

            Nothing already in the knowledge base is overwritten unless `overwrite` is true.
            Call this once at matter setup (and again after bulk uploads) rather than reading
            source files one by one.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("source", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Workspace directory holding the corpus files. Default: \"corpus\".",
                        )
                    })
                    put("overwrite", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "Replace knowledge documents from an earlier import with the same " +
                                "names. Default false: existing documents are kept and listed " +
                                "under skipped_existing.",
                        )
                    })
                },
                required = emptyList(),
            )
        },
        needsApproval = { true },
        execute = {
            val params = it.jsonObject
            val source = KnowledgeImportRules.resolveSource(params["source"]?.jsonPrimitive?.contentOrNull)
                ?: return@Tool listOf(
                    UIMessagePart.Text(errorEnvelopeFor(
                        "invalid_source",
                        "The source path must stay inside the workspace (no \"..\" segments).",
                    )),
                )
            val overwrite = params["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false

            val entries = try {
                listSourceFiles(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@Tool listOf(
                    UIMessagePart.Text(errorEnvelopeFor(
                        "source_unreadable",
                        "Could not list \"$source\": ${e.message ?: e.javaClass.simpleName}",
                    )),
                )
            }

            val supported = entries
                .filter { KnowledgeImportRules.isSupported(it.name) }
                .sortedBy { it.name }
            if (supported.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "nothing_to_import")
                    put("source", source)
                    put("listedFiles", buildJsonArray {
                        entries.take(20).forEach { entry -> add(JsonPrimitive(entry.name)) }
                    })
                    put(
                        "detail",
                        "No .md/.txt/.csv files in that directory. Supported types: " +
                            KnowledgeImportRules.ALLOWED_SUFFIXES.joinToString(", "),
                    )
                }.toString()))
            }

            val listedTruncated = supported.size > KnowledgeImportRules.MAX_FILES_PER_IMPORT
            val batch = supported.take(KnowledgeImportRules.MAX_FILES_PER_IMPORT)
            val existing = try {
                listKb().toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptySet()
            }
            val targets = KnowledgeImportRules.importPlan(
                sourceNames = batch.map { it.name },
                existingNames = existing,
                overwrite = overwrite,
            )

            val imported = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val skippedExisting = mutableListOf<String>()
            val failed = mutableListOf<kotlinx.serialization.json.JsonObject>()
            batch.forEachIndexed { index, file ->
                val target = targets[index]
                if (!overwrite && target in existing) {
                    skippedExisting += target
                    return@forEachIndexed
                }
                val raw = readSource(file.path) ?: run {
                    failed += buildJsonObject {
                        put("from", file.path)
                        put("detail", "unreadable")
                    }
                    return@forEachIndexed
                }
                val (body, truncated) = KnowledgeImportRules.clip(raw)
                val doc = KnowledgeImportRules.render(
                    sourcePath = file.path,
                    sourceName = file.name,
                    body = body,
                    truncated = truncated,
                )
                val written = try {
                    writeKbDoc(target, doc)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += buildJsonObject {
                        put("from", file.path)
                        put("to", target)
                        put("detail", e.message ?: e.javaClass.simpleName)
                    }
                    return@forEachIndexed
                }
                if (!written) {
                    failed += buildJsonObject {
                        put("from", file.path)
                        put("to", target)
                        put("detail", "write refused")
                    }
                } else {
                    imported += buildJsonObject {
                        put("from", file.path)
                        put("to", target)
                        put("chars", doc.length)
                    }
                }
            }

            listOf(UIMessagePart.Text(buildJsonObject {
                put("source", source)
                put("target_dir", dirLabel)
                put("imported_count", imported.size)
                put("imported", buildJsonArray { imported.forEach { add(it) } })
                put("skipped_existing", buildJsonArray { skippedExisting.forEach { add(JsonPrimitive(it)) } })
                put("failed", buildJsonArray { failed.forEach { add(it) } })
                put("listed_truncated", listedTruncated)
                put("note", KnowledgeImportRules.CORPUS_IMPORT_NOTE)
            }.toString()))
        },
    ),
)

private fun errorEnvelopeFor(code: String, detail: String): String = buildJsonObject {
    put("error", code)
    put("detail", detail)
}.toString()
