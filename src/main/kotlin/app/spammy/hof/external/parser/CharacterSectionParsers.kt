package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofCharacter
import org.jsoup.nodes.Document

object CharacterSectionParsers {
    const val VERSION = "character-v1"

    fun validate(document: Document, snapshot: HofCharacter): Map<CharacterPageSection, CharacterSectionParseResult> =
        linkedMapOf(
            CharacterPageSection.PROFILE to result(snapshot.name.isNotBlank() && snapshot.job.isNotBlank(), ".carpet_frame + Lv.", 1),
            CharacterPageSection.STATS to result(snapshot.stats.hpBase != null && snapshot.stats.strReal != null, "Exp/HP/SP/STR/INT/DEX/SPD/LUK labels", 8),
            CharacterPageSection.EFFECTS_FAITH to result(
                document.select("h4").any { it.ownText().trim().startsWith("Character Status") },
                "Character Status effect cell",
                snapshot.statusEffects.size + if (snapshot.faith == null) 0 else 1,
            ),
            CharacterPageSection.CURRENT_PATTERN to result(snapshot.actionPatterns.isNotEmpty(), "judgeN/quantityN/skillN rows", snapshot.actionPatterns.size),
            CharacterPageSection.POSITION_GUARD to result(snapshot.positionGuard.positions.size == 2, "position radios and guard select", snapshot.positionGuard.positions.size),
            CharacterPageSection.SAVED_PATTERNS to result(document.select("input[name=patternno]").isNotEmpty(), "patternno forms", snapshot.patternSlots.size),
            CharacterPageSection.EQUIPMENT to result(document.select("input[name=spot]").size == 12, "12 equipment spot controls", document.select("input[name=spot]").size),
            CharacterPageSection.EQUIPMENT_CANDIDATES to result(
                document.select("script").any { it.data().contains("Listtype_equip") },
                "Listtype_equip script",
                snapshot.equipmentCandidates.size,
            ),
            CharacterPageSection.SKILLS to result(
                document.select("h4").any { it.ownText().trim().equals("Skill", true) },
                "Skill heading",
                snapshot.learnedSkills.size + snapshot.learnableSkills.size,
            ),
            CharacterPageSection.MANAGEMENT to result(document.select("form").isNotEmpty(), "management forms", document.select("form").size),
        )

    private fun result(ok: Boolean, expected: String, observed: Int): CharacterSectionParseResult =
        if (ok) CharacterSectionParseResult.Success(observed)
        else CharacterSectionParseResult.Failure(VERSION, "SECTION_CONTRACT_MISMATCH", expected, observed)
}
