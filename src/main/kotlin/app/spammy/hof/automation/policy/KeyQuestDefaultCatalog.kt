package app.spammy.hof.automation.policy

data class PartyBlueprintSlot(
    val characterName: String,
    val patternSlot: Int,
)

data class PartyBlueprint(
    val slots: List<PartyBlueprintSlot>,
    val battleCount: Int,
)

data class QuestDefault(
    val candidates: List<String>,
    val mapNames: Map<String, String>,
    val fallbackMapCode: String?,
    val partyBlueprint: PartyBlueprint? = null,
    val partyBlueprintByMap: Map<String, PartyBlueprint> = emptyMap(),
)

object KeyQuestDefaultCatalog {
    val priorityQuestIds = listOf("0563", "0571", "0171", "0351")

    val defaults: Map<String, QuestDefault> = mapOf(
        "0563" to QuestDefault(
            candidates = listOf("Noble1021", "Noble1022", "Noble1023", "Noble102"),
            mapNames = mapOf(
                "Noble1021" to "저택 동관(보쉬의 방)",
                "Noble1022" to "저택 동관(하인켈의 방)",
                "Noble1023" to "저택 동관(커티스의 방)",
                "Noble102" to "저택 동관(복도)",
            ),
            fallbackMapCode = "Noble102",
            partyBlueprintByMap = mapOf(
                "Noble1021" to blueprint(3, "소셜2" to 5, "사제" to 7, "바드2" to 8, "솬서" to 0, "에인" to 3),
                "Noble1022" to blueprint(3, "소셜2" to 6, "사제" to 7, "바드" to 0, "솬서" to 0, "에인" to 3),
                "Noble1023" to blueprint(3, "암흑네크" to 3, "사제" to 7, "바드2" to 0, "솬서" to 0, "에인" to 3),
                "Noble102" to blueprint(3, "소셜" to 0, "사제" to 0, "바드" to 0, "에인" to 0, "동방" to 0),
            ),
        ),
        "0571" to QuestDefault(
            candidates = listOf("Noble201"),
            mapNames = mapOf("Noble201" to "저택 서관"),
            fallbackMapCode = null,
            partyBlueprint = blueprint(1, "카발" to 2, "사제2" to 3, "낫망네크" to 3),
        ),
        "0351" to QuestDefault(
            candidates = listOf("tnfh1"),
            mapNames = mapOf("tnfh1" to "Culvert- 마을 지하 수로(입구)"),
            fallbackMapCode = null,
        ),
    )

    private fun blueprint(
        battleCount: Int,
        vararg slots: Pair<String, Int>,
    ) = PartyBlueprint(
        slots = slots.map { (name, pattern) -> PartyBlueprintSlot(name, pattern) },
        battleCount = battleCount,
    )
}
