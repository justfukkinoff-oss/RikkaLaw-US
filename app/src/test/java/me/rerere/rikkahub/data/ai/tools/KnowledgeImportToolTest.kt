package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline coverage for `corpus_import`: [KnowledgeImportRules] is pure and exercised directly,
 * and the execute paths run against in-memory fake lambdas — no workspace, no Android.
 */
class KnowledgeImportToolTest {

    // ---------- pure rules ----------

    @Test fun `blank source falls back to the matter corpus default`() {
        assertEquals("corpus", KnowledgeImportRules.resolveSource(null))
        assertEquals("corpus", KnowledgeImportRules.resolveSource(""))
        assertEquals("corpus", KnowledgeImportRules.resolveSource("   "))
    }

    @Test fun `workspace-prefixed and relative sources both resolve`() {
        assertEquals("matters/smith/corpus", KnowledgeImportRules.resolveSource("/workspace/matters/smith/corpus"))
        assertEquals("matters/smith/corpus", KnowledgeImportRules.resolveSource("matters/smith/corpus"))
        assertEquals("", KnowledgeImportRules.resolveSource("/workspace"))
    }

    @Test fun `an escaping source is refused`() {
        assertNull(KnowledgeImportRules.resolveSource("../etc"))
        assertNull(KnowledgeImportRules.resolveSource("matters/../../rootfs"))
    }

    @Test fun `only markdown text and csv are supported`() {
        assertTrue(KnowledgeImportRules.isSupported("notes.MD"))
        assertTrue(KnowledgeImportRules.isSupported("record.txt"))
        assertTrue(KnowledgeImportRules.isSupported("dockets.csv"))
        assertFalse(KnowledgeImportRules.isSupported("scan.pdf"))
        assertFalse(KnowledgeImportRules.isSupported("photo.jpg"))
        assertFalse(KnowledgeImportRules.isSupported("notes.md.bin"))
    }

    @Test fun `target names are slugged predictable kb files`() {
        assertEquals("kb-petition-draft-notes.md", KnowledgeImportRules.targetNameFor("Petition Draft__Notes.md"))
        assertEquals("kb-dockets-2024.md", KnowledgeImportRules.targetNameFor("Dockets...2024.csv"))
        assertEquals("kb-doc.md", KnowledgeImportRules.targetNameFor("--.txt"))
    }

    @Test fun `target stems never exceed the cap`() {
        val long = "x".repeat(120) + ".md"
        val target = KnowledgeImportRules.targetNameFor(long)
        assertTrue(target.length <= "kb-".length + KnowledgeImportRules.MAX_TARGET_STEM_CHARS + ".md".length)
        assertFalse(target.contains("--"))
    }

    @Test fun `import plan stays unique and never clobbers existing without overwrite`() {
        val sources = listOf("a.md", "a.txt", "b.csv")
        val planNoOverwrite = KnowledgeImportRules.importPlan(sources, existingNames = setOf("kb-a.md"), overwrite = false)
        assertEquals(listOf("kb-a-2.md", "kb-a-3.md", "kb-b.md"), planNoOverwrite)

        val planOverwrite = KnowledgeImportRules.importPlan(sources, existingNames = setOf("kb-a.md"), overwrite = true)
        assertEquals(listOf("kb-a.md", "kb-a-2.md", "kb-b.md"), planOverwrite)

        val clean = KnowledgeImportRules.importPlan(sources, existingNames = emptySet(), overwrite = false)
        assertEquals(listOf("kb-a.md", "kb-a-2.md", "kb-b.md"), clean)
    }

    @Test fun `clip marks truncation only past the cap`() {
        val (small, smallTruncated) = KnowledgeImportRules.clip("short note")
        assertFalse(smallTruncated)
        assertEquals("short note", small)

        val (big, bigTruncated) = KnowledgeImportRules.clip("y".repeat(KnowledgeImportRules.MAX_BODY_CHARS + 10))
        assertTrue(bigTruncated)
        assertEquals(KnowledgeImportRules.MAX_BODY_CHARS, big.length)
    }

    @Test fun `render carries provenance and fences csv`() {
        val md = KnowledgeImportRules.render("corpus/a.md", "a.md", "body text", truncated = false)
        assertTrue(md.startsWith("# Import: a.md"))
        assertTrue(md.contains("`corpus/a.md`"))
        assertTrue(md.contains("Markdown"))
        assertTrue(md.contains("body text"))

        val csv = KnowledgeImportRules.render("corpus/t.csv", "t.csv", "id,name\n1,Smith", truncated = false)
        assertTrue(csv.contains("```\nid,name\n1,Smith\n```"))
        assertTrue(csv.contains("CSV"))

        val truncated = KnowledgeImportRules.render("corpus/big.txt", "big.txt", "z", truncated = true)
        assertTrue(truncated.contains("Truncated"))
    }

    // ---------- execute (fake lambdas) ----------

    private class FakeStorage(
        val sources: Map<String, List<KbSourceFile>>,
        val sourceTexts: Map<String, String> = emptyMap(),
        val kbDocs: MutableMap<String, String> = mutableMapOf(),
    ) {
        fun tools(dirLabel: String = "/workspace/notes/memory"): List<Tool> = buildKnowledgeImportTools(
            dirLabel = dirLabel,
            listSourceFiles = { source -> sources[source].orEmpty() },
            readSource = { path -> sourceTexts[path] },
            listKb = { kbDocs.keys.toList() },
            writeKbDoc = { name, content ->
                kbDocs[name] = content
                true
            },
        )
    }

    private fun invoke(tool: Tool, args: String): String {
        val parts = runBlocking { tool.execute(Json.parseToJsonElement(args)) }
        return (parts.first() as UIMessagePart.Text).text
    }

    private fun obj(result: String) = Json.parseToJsonElement(result).jsonObject

    private fun names(result: String, key: String) =
        obj(result)[key]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test fun `a happy path imports every supported file with provenance`() {
        val storage = FakeStorage(
            sources = mapOf(
                "corpus" to listOf(
                    KbSourceFile("corpus/b.md", "b.md", 100),
                    KbSourceFile("corpus/a.md", "a.md", 100),
                    KbSourceFile("corpus/skip.pdf", "skip.pdf", 1),
                ),
            ),
            sourceTexts = mapOf(
                "corpus/a.md" to "# A",
                "corpus/b.md" to "B",
            ),
        )
        val tool = storage.tools().first { it.name == "corpus_import" }
        val result = invoke(tool, "{}")

        assertEquals(
            listOf("kb-a.md", "kb-b.md"),
            obj(result)["imported"]!!.jsonArray.map {
                obj(it.toString())["to"]!!.jsonPrimitive.content
            },
        )
        assertEquals(
            KnowledgeImportRules.render("corpus/a.md", "a.md", "# A", truncated = false),
            storage.kbDocs["kb-a.md"],
        )
        assertTrue(result.contains(KnowledgeImportRules.CORPUS_IMPORT_NOTE))
    }

    @Test fun `existing documents are skipped unless overwrite is requested`() {
        val storage = FakeStorage(
            sources = mapOf("corpus" to listOf(KbSourceFile("corpus/a.md", "a.md", 100))),
            sourceTexts = mapOf("corpus/a.md" to "NEW"),
            kbDocs = mutableMapOf("kb-a.md" to "OLD"),
        )
        val tool = storage.tools().first { it.name == "corpus_import" }

        val kept = invoke(tool, "{}")
        assertEquals(listOf("kb-a.md"), names(kept, "skipped_existing"))
        assertEquals(0, obj(kept)["imported_count"]!!.jsonPrimitive.content.toInt())
        assertEquals("OLD", storage.kbDocs["kb-a.md"])

        val overwrote = invoke(tool, """{"overwrite": true}""")
        assertEquals(1, obj(overwrote)["imported_count"]!!.jsonPrimitive.content.toInt())
        assertTrue(storage.kbDocs["kb-a.md"]!!.contains("NEW"))
    }

    @Test fun `an empty or unsupported directory reports nothing_to_import with the listing`() {
        val storage = FakeStorage(
            sources = mapOf("corpus" to listOf(KbSourceFile("corpus/scan.pdf", "scan.pdf", 1))),
        )
        val tool = storage.tools().first { it.name == "corpus_import" }
        val result = invoke(tool, """{"source": "/workspace/corpus"}""")
        assertEquals("nothing_to_import", obj(result)["error"]!!.jsonPrimitive.content)
        assertEquals(listOf("scan.pdf"), names(result, "listedFiles"))
    }

    @Test fun `a source path outside the workspace is refused`() {
        val storage = FakeStorage(sources = emptyMap())
        val tool = storage.tools().first { it.name == "corpus_import" }
        val result = invoke(tool, """{"source": "../rootfs/home"}""")
        assertEquals("invalid_source", obj(result)["error"]!!.jsonPrimitive.content)
    }

    @Test fun `unreadable files are reported as failed not silently dropped`() {
        val storage = FakeStorage(
            sources = mapOf("corpus" to listOf(KbSourceFile("corpus/a.md", "a.md", 100))),
        )
        var calls = 0
        val tool = buildKnowledgeImportTools(
            dirLabel = "/workspace/notes/memory",
            listSourceFiles = { listOf(KbSourceFile("corpus/a.md", "a.md", 100)) },
            readSource = { calls++; null },
            listKb = { emptyList() },
            writeKbDoc = { _, _ -> true },
        ).first { it.name == "corpus_import" }

        val result = invoke(tool, "{}")
        assertEquals(1, calls)
        assertEquals(listOf("corpus/a.md"), obj(result)["failed"]!!.jsonArray.map {
            obj(it.toString())["from"]!!.jsonPrimitive.content
        })
    }

    @Test fun `beyond the per-call cap the listing is marked truncated`() {
        val many = (1..KnowledgeImportRules.MAX_FILES_PER_IMPORT + 5).map {
            KbSourceFile("corpus/f$it.md", "f$it.md", 10)
        }
        val tool = buildKnowledgeImportTools(
            dirLabel = "/workspace/notes/memory",
            listSourceFiles = { many },
            readSource = { "content" },
            listKb = { emptyList() },
            writeKbDoc = { _, _ -> true },
        ).first { it.name == "corpus_import" }

        val result = invoke(tool, "{}")
        assertEquals("true", obj(result)["listed_truncated"]!!.jsonPrimitive.content)
        assertEquals(KnowledgeImportRules.MAX_FILES_PER_IMPORT, obj(result)["imported_count"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `import is approval-gated`() {
        val tool = FakeStorage(sources = emptyMap()).tools().first { it.name == "corpus_import" }
        assertTrue(tool.needsApproval(Json.parseToJsonElement("{}")))
    }
}
