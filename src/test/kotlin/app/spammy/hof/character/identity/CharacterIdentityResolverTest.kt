package app.spammy.hof.character.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CharacterIdentityResolverTest {
    private val resolver = CharacterIdentityResolver()
    private val target = CharacterIdentityEvidence("old", "소셜", "Social Knight", 60)

    @Test
    fun `one disappeared and one matching new id is automatically confirmed`() {
        val before = listOf(target, CharacterIdentityEvidence("other", "카발"))
        val replacement = CharacterIdentityEvidence("new", "소셜", "Social Knight", 60)

        val result = resolver.resolveKnockback(target, before, listOf(before[1], replacement))

        assertEquals(replacement, assertIs<CharacterIdentityResolution.Confirmed>(result).replacement)
    }

    @Test
    fun `same name candidates are never automatically linked`() {
        val before = listOf(target, CharacterIdentityEvidence("other", "카발"))
        val after = listOf(
            before[1],
            CharacterIdentityEvidence("new-1", "소셜", "Social Knight", 60),
            CharacterIdentityEvidence("new-2", "소셜", "Social Knight", 60),
        )

        val result = resolver.resolveKnockback(target, before, after)

        assertEquals(listOf("new-1", "new-2"), assertIs<CharacterIdentityResolution.Candidates>(result).candidates.map { it.character.hofCharacterId })
    }

    @Test
    fun `weak or missing evidence returns candidates then entire roster fallback`() {
        val before = listOf(target)
        val weak = resolver.resolveKnockback(target, before, listOf(CharacterIdentityEvidence("new", "다른이름", "Social Knight", 1)))
        assertIs<CharacterIdentityResolution.Candidates>(weak)

        val none = resolver.resolveKnockback(target, before, listOf(CharacterIdentityEvidence("new", "다른이름")))
        assertEquals(listOf("new"), assertIs<CharacterIdentityResolution.Unresolved>(none).roster.map { it.hofCharacterId })
    }
}
