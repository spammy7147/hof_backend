package app.spammy.hof.character.identity

import org.springframework.stereotype.Component

data class CharacterIdentityEvidence(
    val hofCharacterId: String,
    val name: String,
    val job: String = "",
    val level: Int? = null,
)

data class CharacterIdentityCandidate(
    val character: CharacterIdentityEvidence,
    val matchingFields: Set<String>,
)

sealed interface CharacterIdentityResolution {
    data class Confirmed(val replacement: CharacterIdentityEvidence) : CharacterIdentityResolution
    data class Candidates(val candidates: List<CharacterIdentityCandidate>) : CharacterIdentityResolution
    data class Unresolved(val roster: List<CharacterIdentityEvidence>) : CharacterIdentityResolution
}

@Component
class CharacterIdentityResolver {
    fun resolveKnockback(
        targetBefore: CharacterIdentityEvidence,
        rosterBefore: List<CharacterIdentityEvidence>,
        rosterAfter: List<CharacterIdentityEvidence>,
    ): CharacterIdentityResolution {
        val beforeIds = rosterBefore.mapTo(linkedSetOf()) { it.hofCharacterId }
        val afterIds = rosterAfter.mapTo(linkedSetOf()) { it.hofCharacterId }
        if (targetBefore.hofCharacterId in afterIds) return CharacterIdentityResolution.Unresolved(rosterAfter)
        val disappeared = beforeIds - afterIds
        if (targetBefore.hofCharacterId !in disappeared) return CharacterIdentityResolution.Unresolved(rosterAfter)

        val newCharacters = rosterAfter.filter { it.hofCharacterId !in beforeIds }
        val candidates = newCharacters.map { candidate ->
            CharacterIdentityCandidate(candidate, matchingFields(targetBefore, candidate))
        }.filter { it.matchingFields.isNotEmpty() }
            .sortedWith(compareByDescending<CharacterIdentityCandidate> { it.matchingFields.size }.thenBy { it.character.hofCharacterId })

        val best = candidates.firstOrNull()
        val equallyStrong = best?.let { winner -> candidates.count { it.matchingFields.size == winner.matchingFields.size } } ?: 0
        val nameIsUnique = best != null && rosterAfter.count { it.name.isNotBlank() && it.name == targetBefore.name } == 1
        return when {
            newCharacters.size == 1 && best != null && "name" in best.matchingFields && nameIsUnique && equallyStrong == 1 ->
                CharacterIdentityResolution.Confirmed(best.character)
            candidates.isNotEmpty() -> CharacterIdentityResolution.Candidates(candidates)
            else -> CharacterIdentityResolution.Unresolved(rosterAfter)
        }
    }

    private fun matchingFields(before: CharacterIdentityEvidence, after: CharacterIdentityEvidence): Set<String> = buildSet {
        if (before.name.isNotBlank() && before.name == after.name) add("name")
        if (before.job.isNotBlank() && before.job == after.job) add("job")
        if (before.level != null && before.level == after.level) add("level")
    }
}
