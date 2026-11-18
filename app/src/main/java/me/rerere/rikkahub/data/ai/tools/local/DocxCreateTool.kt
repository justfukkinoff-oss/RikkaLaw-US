package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.legal.docx.DocxWriter
import me.rerere.rikkahub.legal.rag.LegalIndex
import me.rerere.rikkahub.legal.rag.LegalIngestor
import java.io.File

/*
 * docx_create: renders "legal markdown" into a court-style Word document entirely on device.
 * The DRAFT banner is on by default and can only be replaced by another header, never silently
 * removed, because every output is a rough draft for counsel review.
 */

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.lines(k: String): List<String> = runCatching {
    this[k]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() }
}.getOrNull() ?: s(k)?.split('\n').orEmpty()

private fun p(type: String, d: String) = buildJsonObject { put("type", type); put("description", d) }

internal fun safeDocxName(raw: String?): String {
    val base = (raw ?: "draft").substringAfterLast('/').removeSuffix(".docx")
        .replace(Regex("[^A-Za-z0-9 ._()-]"), "_").trim().take(80).ifBlank { "draft" }
    return "$base.docx"
}

fun docxCreateTool(context: Context, index: LegalIndex?, ingestor: LegalIngestor?): Tool = Tool(
    name = "docx_create",
    description = "Create a Word (.docx) document from legal markdown for counsel review. Supports # / ## / ### " +
        "headings, paragraphs, **bold**, *italic*, <u>underline</u>, > block quotes, - bullets, 1. / a. / i. lists, " +
        "pipe tables, footnotes ([^1] in text with '[^1]: text' definitions), [[pagebreak]], and lines starting " +
        "with [[center]] or [[right]]. Optional court caption (court, left_lines = party block, right_lines = case " +
        "no./document title). Double-spaced Times New Roman 12 by default with page numbers and the header " +
        "'DRAFT — PREPARED FOR COUNSEL REVIEW — NOT LEGAL ADVICE'. Use bracketed placeholders like [COUNSEL NAME] " +
        "for facts you do not have; never invent record cites. Saves to Downloads/RikkaLaw and can attach the " +
        "draft to a Project so it becomes searchable.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("filename", p("string", "File name, e.g. 'Doe_2254_Brief_Draft'"))
                put("content", p("string", "Document body in legal markdown"))
                put("title", p("string", "Document title shown under the caption, e.g. 'Brief in Support of Petition'"))
                put("court", p("string", "Court name for the caption; use \\n between lines"))
                put("left_lines", buildJsonObject {
                    put("type", "array"); put("description", "Caption party block lines (left column)")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("right_lines", buildJsonObject {
                    put("type", "array"); put("description", "Caption right column lines (case number, judge, filing title)")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("line_spacing", p("number", "1.0, 1.5 or 2.0 (default 2.0)"))
                put("font_size", p("integer", "Font size in points (default 12)"))
                put("header_text", p("string", "Optional replacement header; defaults to the DRAFT banner"))
                put("project", p("string", "Optional project id or name; the draft is indexed into that project"))
            },
            required = listOf("filename", "content"),
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val content = o.s("content") ?: return@Tool fmTextPart(fmErrEnvelope("missing_content", "content is required"))
        val name = safeDocxName(o.s("filename"))
        val court = o.s("court"); val left = o.lines("left_lines"); val right = o.lines("right_lines")
        val title = o.s("title")
        val caption = if (court != null || left.isNotEmpty() || right.isNotEmpty() || title != null)
            DocxWriter.Caption(court.orEmpty(), left, right, title.orEmpty()) else null
        val opts = DocxWriter.Options(
            sizePt = (o["font_size"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 12).coerceIn(8, 16),
            lineSpacing = (o["line_spacing"]?.jsonPrimitive?.doubleOrNull ?: 2.0).coerceIn(1.0, 3.0),
            headerText = o.s("header_text") ?: DocxWriter.DRAFT_BANNER,
            title = title ?: name.removeSuffix(".docx"),
            caption = caption,
        )
        runCatching {
            val dir = File(context.filesDir, "legal/drafts").apply { mkdirs() }
            var file = File(dir, name)
            if (file.exists()) file = File(dir, name.removeSuffix(".docx") + "-" + System.currentTimeMillis() % 100000 + ".docx")
            file.outputStream().use { DocxWriter(opts).write(content, it) }
            val public = exportToDownloads(context, file)
            val attached = o.s("project")?.let { ref ->
                val project = index?.resolveProject(ref) ?: return@let "unknown project '$ref'"
                ingestor?.ingestFile(project.id, file, file.name, "draft")?.let { "indexed into ${project.name}" }
            }
            buildJsonObject {
                put("path", file.absolutePath)
                put("bytes", file.length())
                public?.let { put("downloads", it) }
                attached?.let { put("project", it) }
                put("note", "Draft saved. Counsel must review, cite-check and finalize before any filing.")
            }.toString()
        }.fold(
            onSuccess = { fmTextPart(it) },
            onFailure = { fmTextPart(fmErrEnvelope("docx_failed", it.message ?: it.toString())) },
        )
    },
)

/** Copies the draft to a user-visible location. Returns a human-readable location or null. */
private fun exportToDownloads(context: Context, file: File): String? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, DOCX_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RikkaLaw")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        "Download/RikkaLaw/${file.name}"
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "RikkaLaw").apply { mkdirs() }
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        dest.absolutePath
    }
}.getOrNull()
