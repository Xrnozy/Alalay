package com.alalay.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliasResolverTest {

    private fun alias(a: String, target: String) =
        Alias(alias = a, target_name = target, target_kind = "contact", created_at = 0)

    private val saved = listOf(
        alias("mama", "Mama Remedios"),
        alias("tita rosa", "Rosa Cruz"),
    )

    @Test
    fun `alias resolves to target name`() {
        assertEquals(listOf("Mama Remedios"),
            AliasResolver.resolve("mama", saved).map { it.target_name })
    }

    @Test
    fun `target name also resolves back to itself`() {
        // Saying the full name still finds the entry.
        assertEquals(listOf("Mama Remedios"),
            AliasResolver.resolve("Mama Remedios", saved).map { it.target_name })
    }

    @Test
    fun `aliases mentioned in a task surface as hints`() {
        val mentioned = AliasResolver.findAliasesMentioned("read mama's latest message", saved)
        assertEquals(listOf("Mama Remedios"), mentioned.map { it.target_name })
        assertTrue(AliasResolver.findAliasesMentioned("open settings", saved).isEmpty())
    }

    @Test
    fun `multiple matches stay multiple - ambiguity is for the caller`() {
        val two = saved + alias("rosa", "Rosa Diaz")
        val hits = AliasResolver.resolve("rosa", two)
        assertEquals(2, hits.size)
    }
}
