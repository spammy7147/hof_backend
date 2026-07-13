package app.spammy.hof.automation.policy

import org.springframework.stereotype.Component

data class KeyQuestMapCandidate(
    val mapCode: String,
    val mapName: String,
    val keyCount: Int?,
    val executionOrder: Int,
    val partyPresetId: Long? = null,
    val categoryId: String = "battle_map",
)

@Component
class EastMansionMapPolicy {
    fun select(selected: List<KeyQuestMapCandidate>): KeyQuestMapCandidate {
        require(selected.isNotEmpty()) { "저택 동관 퀘스트에는 맵을 하나 이상 선택해야 합니다." }
        if (selected.size == 1) {
            val fixed = selected.single()
            if (fixed.mapCode == CORRIDOR_CODE || (fixed.keyCount ?: 0) > 0) return fixed
            return corridorFallback()
        }

        return selected
            .asSequence()
            .filter { it.mapCode != CORRIDOR_CODE }
            .filter { (it.keyCount ?: 0) > 0 }
            .sortedWith(compareByDescending<KeyQuestMapCandidate> { it.keyCount }.thenBy { it.executionOrder })
            .firstOrNull()
            ?: selected.firstOrNull { it.mapCode == CORRIDOR_CODE }
            ?: corridorFallback()
    }

    private fun corridorFallback() = KeyQuestMapCandidate(
        mapCode = CORRIDOR_CODE,
        mapName = "저택 동관(복도)",
        keyCount = null,
        executionOrder = Int.MAX_VALUE,
    )

    companion object {
        const val CORRIDOR_CODE = "Noble102"
    }
}
