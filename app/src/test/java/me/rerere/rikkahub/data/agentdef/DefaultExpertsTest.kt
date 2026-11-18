package me.rerere.rikkahub.data.agentdef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the seeded default experts (researcher / verifier / drafter) — the ids used for
 * idempotent boot-time seeding and the pure `expertsToSeed` decision they pass through, plus
 * the field limits the roster tools enforce on user-written rows.
 */
class DefaultExpertsTest {

    @Test fun `fresh install seeds all three experts`() {
        val seeded = expertsToSeed(existingIds = emptySet(), deletedIds = emptySet())
        assertEquals(DEFAULT_EXPERTS.map { it.id }, seeded.map { it.id })
    }

    @Test fun `full library seeds nothing`() {
        val seeded = expertsToSeed(existingIds = DEFAULT_EXPERTS.map { it.id }.toSet(), deletedIds = emptySet())
        assertTrue(seeded.isEmpty())
    }

    @Test fun `partially deleted library is completed but never resurrects deletes`() {
        val deleted = setOf(DEFAULT_EXPERTS.first().id)
        val existing = setOf(DEFAULT_EXPERTS[2].id)
        val seeded = expertsToSeed(existingIds = existing, deletedIds = deleted)
        assertTrue(seeded.none { it.id in deleted })
        assertTrue(seeded.none { it.id in existing })
        assertEquals(listOf(DEFAULT_EXPERTS[1].id), seeded.map { it.id })
    }

    @Test fun `an existing row that was never a default is respected`() {
        val seeded = expertsToSeed(
            existingIds = setOf("00000000-0000-0000-0000-000000000001"),
            deletedIds = emptySet(),
        )
        assertEquals(DEFAULT_EXPERTS.map { it.id }, seeded.map { it.id })
    }

    @Test fun `ids are unique`() {
        assertEquals(DEFAULT_EXPERTS.size, DEFAULT_EXPERT_IDS.size)
        assertEquals(DEFAULT_EXPERTS.size, DEFAULT_EXPERTS.map { it.id }.distinct().size)
    }

    @Test fun `names are unique case-insensitively`() {
        val names = DEFAULT_EXPERTS.map { it.name.lowercase() }
        assertEquals(DEFAULT_EXPERTS.size, names.distinct().size)
    }

    @Test fun `each expert satisfies the roster write limits`() {
        DEFAULT_EXPERTS.forEach { expert ->
            assertTrue("blank name", expert.name.isNotBlank())
            assertTrue("name too long: ${expert.name}", expert.name.length <= AgentDefinitionDefaults.MAX_NAME_LENGTH)
            assertTrue("blank description: ${expert.name}", expert.description.isNotBlank())
            assertTrue(
                "description too long: ${expert.name}",
                expert.description.length <= AgentDefinitionDefaults.MAX_DESCRIPTION_LENGTH,
            )
            assertTrue("blank prompt: ${expert.name}", expert.systemPrompt.isNotBlank())
            assertTrue(
                "prompt too long: ${expert.name}",
                expert.systemPrompt.length <= AgentDefinitionDefaults.MAX_SYSTEM_PROMPT_LENGTH,
            )
        }
    }

    @Test fun `each expert gets a valid private namespace slug`() {
        DEFAULT_EXPERTS.forEach { expert ->
            assertTrue("invalid slug: ${expert.slug}", AgentNamespace.isValidSlug(expert.slug))
            assertEquals(
                "${AgentDefinitionDefaults.NAMESPACE_ROOT}/${expert.slug ?: expert.id}",
                AgentNamespace.namespaceDirFor(expert.slug),
            )
        }
    }

    @Test fun `default experts inherit and are enabled`() {
        DEFAULT_EXPERTS.forEach { expert ->
            assertTrue(expert.enabled)
            // Null = inherit the parent assistant's surface / model / skills.
            assertEquals(null, expert.modelId)
            assertEquals(null, expert.localTools)
            assertEquals(null, expert.skills)
        }
    }
}
