package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.external.model.HofActionPatternRow
import app.spammy.hof.external.model.HofCharacterStats
import app.spammy.hof.external.model.HofFaith
import app.spammy.hof.external.model.HofPatternOption
import app.spammy.hof.external.model.HofStatusEffect
import app.spammy.hof.external.model.HofEquipment
import app.spammy.hof.external.model.HofPatternSlot
import app.spammy.hof.external.model.HofPositionChoice
import app.spammy.hof.external.model.HofPositionGuard
import app.spammy.hof.external.model.HofSkill
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.springframework.stereotype.Component

@Component
/**
 * HOF 캐릭터 상세 HTML을 내부 캐릭터 스냅샷 모델로 변환한다.
 */
class CharacterDetailParser {
    fun parsePage(characterId: String, html: String): CharacterPageParseResult {
        val snapshot = parse(characterId, html)
        val document = HofHtmlParser.parse(html, HOF_BASE_URL)
        return CharacterPageParseResult(snapshot, CharacterSectionParsers.validate(document, snapshot))
    }

    /**
     * 캐릭터 상세 페이지에서 이름, 레벨, 직업, 이미지, 저장 패턴, 스탯, 장비, 스킬을 파싱한다.
     */
    fun parse(
        characterId: String,
        html: String,
    ): HofCharacter {
        val document = HofHtmlParser.parse(html, HOF_BASE_URL)
        val carpet = document.selectFirst(".carpet_frame")
        val name = carpet?.readCharacterName().orEmpty()
        val levelJob = carpet?.readLevelJob()

        return HofCharacter(
            id = characterId,
            name = name,
            level = levelJob?.level,
            job = levelJob?.job.orEmpty(),
            patternSlots = document.select("form")
                .mapNotNull { form -> form.readPatternSlot() },
            imageUrl = carpet?.selectFirst("img")?.readImageUrl().orEmpty(),
            statusLines = document.readSectionLines(
                start = "Character Status",
                stopPrefixes = listOf("Pattern", "Action Pattern", "Current Equip", "Stock", "Current Skill", "Skill"),
            ),
            stats = document.readStats(),
            statusEffects = document.readStatusEffects(),
            faith = document.readFaith(),
            selectedPatternNumber = document.selectFirst("""input[name=PatternNumber][checked]""")?.attr("value").orEmpty(),
            actionPatterns = document.readActionPatterns(),
            patternOptions = document.readPatternOptions(),
            positionGuard = document.readPositionGuard(),
            equipment = document.select("""input[name=spot]""")
                .mapNotNull { input -> input.readEquipment() },
            equipmentCandidates = EquipmentCandidateScriptParser.parse(document),
            learnedSkills = document.readLearnedSkills(),
            learnableSkills = document.select("""input[name=newskill]""")
                .map { input -> input.readRadioRowSkill() }
                .filter { skill -> skill.name.isNotBlank() },
        )
    }

    /**
     * 캐릭터 카드의 직접 텍스트에서 캐릭터명을 읽는다.
     */
    private fun Element.readCharacterName(): String =
        ownText()
            .normalizeText()
            .substringBeforeLevel()

    /**
     * 캐릭터 카드 텍스트에서 레벨과 직업을 읽는다.
     */
    private fun Element.readLevelJob(): LevelJob? =
        LEVEL_JOB_REGEX.find(ownText().normalizeText())?.let { match ->
            LevelJob(
                level = match.groupValues[1].toInt(),
                job = match.groupValues[2].normalizeText(),
            )
        }

    /**
     * 저장 패턴 form 하나에서 슬롯 번호와 표시 라벨을 읽는다.
     */
    private fun Element.readPatternSlot(): HofPatternSlot? {
        val patternInput = selectFirst("""input[name=patternno]""") ?: return null
        val slot = patternInput.attr("value").trim()
        if (slot.isBlank()) return null

        val label = selectFirst("""input[type=button], button[type=button]""")
            ?.let { button -> button.attr("value").ifBlank { button.text() } }
            ?.normalizeText()
            .orEmpty()

        return HofPatternSlot(
            slot = slot,
            label = label,
            canLoad = selectFirst("""input[name=loadpattern], button[name=loadpattern]""") != null,
        )
    }

    /**
     * 0~15번 Action Pattern row를 읽는다.
     */
    private fun Element.readActionPatterns(): List<HofActionPatternRow> =
        select("[name]")
            .mapNotNull { element -> ACTION_FIELD_REGEX.matchEntire(element.attr("name"))?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
            .mapNotNull { index ->
            val judge = selectFirst("""[name=judge$index]""")
            val quantity = selectFirst("""[name=quantity$index]""")
            val skill = selectFirst("""[name=skill$index]""")
            if (judge == null && quantity == null && skill == null) return@mapNotNull null

            HofActionPatternRow(
                index = index,
                judge = judge?.readValue().orEmpty(),
                judgeText = judge?.readSelectedText().orEmpty(),
                quantity = quantity?.readValue().orEmpty(),
                quantityText = quantity?.readSelectedText().orEmpty(),
                skill = skill?.readValue().orEmpty(),
                skillText = skill?.readSelectedText().orEmpty(),
            )
        }

    /**
     * 포지션 radio와 guard select 상태를 읽는다.
     */
    private fun Element.readPositionGuard(): HofPositionGuard {
        val positions = select("""input[name=position]""")
            .map { input ->
                HofPositionChoice(
                    value = input.attr("value").normalizeText(),
                    checked = input.hasAttr("checked"),
                )
            }
        val guard = selectFirst("""select[name=guard]""")

        return HofPositionGuard(
            positions = positions,
            selectedPosition = positions.firstOrNull { it.checked }?.value.orEmpty(),
            guardValue = guard?.readValue().orEmpty(),
            guardText = guard?.readSelectedText().orEmpty(),
        )
    }

    /**
     * 장비 radio가 들어 있는 table row에서 장착 장비 정보를 읽는다.
     */
    private fun Element.readEquipment(): HofEquipment? {
        val tableCell = closest("td") ?: return null
        val tableRow = closest("tr")
        val part = tableRow
            ?.selectFirst("td.align-right")
            ?.text()
            ?.normalizeText()
            ?.replace(Regex("""[:：\s]+$"""), "")
            .orEmpty()
        val icon = tableCell.selectFirst("img")?.readImageUrl().orEmpty()
        val description = tableCell.selectFirst("""span[style*=font-size]""")
            ?.text()
            ?.normalizeText()
            .orEmpty()
        val name = tableCell.readEquipmentName()

        return HofEquipment(
            slot = attr("value").normalizeText(),
            part = part,
            name = name,
            iconUrl = icon,
            description = description,
            checked = name.isNotBlank(),
        )
    }

    private fun Element.readPatternOptions(): List<HofPatternOption> = buildList {
        selectFirst("select[name^=judge]")?.select("option")?.forEach { option ->
            add(
                HofPatternOption(
                    type = "CONDITION",
                    value = option.attr("value").normalizeText(),
                    label = option.text().normalizeText(),
                    category = if (option.hasClass("select0")) option.text().normalizeText() else "",
                ),
            )
        }
        selectFirst("select[name^=skill]")?.select("option")?.forEach { option ->
            add(
                HofPatternOption(
                    type = "SKILL",
                    value = option.attr("value").normalizeText(),
                    label = option.text().normalizeText(),
                ),
            )
        }
        select("form:has(input[name=classchange]) input[name=job]").forEach { input ->
            val cell = input.closest("td")
            add(
                HofPatternOption(
                    type = "CLASS",
                    value = input.attr("value").normalizeText(),
                    label = cell?.ownText()?.normalizeText().orEmpty().ifBlank { cell?.text()?.normalizeText().orEmpty() },
                ),
            )
        }
    }

    /**
     * 장비 설명/효과 텍스트 앞의 실제 장비명만 분리한다.
     */
    private fun Element.readEquipmentName(): String {
        val stopClasses = setOf("light", "dmg", "recover", "support", "charge")
        val name = StringBuilder()
        for (node in childNodes()) {
            if (node is Element) {
                val tag = node.tagName().uppercase()
                if (tag in setOf("INPUT", "IMG", "BR")) continue
                if (node.classNames().any(stopClasses::contains)) break
                name.append(node.text())
            } else {
                name.append(node.nodeText())
            }
        }

        return name.toString()
            .normalizeText()
            .replace(Regex("""^[\s/]+|[\s/]+$"""), "")
    }

    /**
     * 이미 배운 스킬 목록을 섹션별로 읽는다.
     */
    private fun Element.readLearnedSkills(): List<HofSkill> {
        val skillHeading = select("h4")
            .firstOrNull { heading -> heading.ownText().normalizeText().equals("Skill", ignoreCase = true) }
            ?: return readFallbackLearnedSkills()
        val skills = mutableListOf<HofSkill>()
        var collectTable = false
        var section = ""
        var element = skillHeading.nextElementSibling()

        while (element != null) {
            val sectionLabel = element.text().normalizeText()
            if (sectionLabel.contains("Learn New", ignoreCase = true) || sectionLabel.contains("배울 수 있는 스킬")) break

            if (element.hasClass("u") && element.hasClass("bold")) {
                section = sectionLabel
                collectTable = !sectionLabel.contains("Personality", ignoreCase = true) && !sectionLabel.contains("개성")
                element = element.nextElementSibling()
                continue
            }

            if (collectTable && element.tagName().equals("table", ignoreCase = true)) {
                skills += element.select("tr")
                    .mapNotNull { row -> row.readSkillRow(section) }
            }

            element = element.nextElementSibling()
        }

        return skills.ifEmpty { readFallbackLearnedSkills() }
    }

    /**
     * 구조화된 Skill 표를 찾지 못했을 때 텍스트 섹션에서 스킬명을 읽는다.
     */
    private fun Element.readFallbackLearnedSkills(): List<HofSkill> =
        readSectionLines(
            start = "Current Skill",
            stopPrefixes = listOf("Skill", "Stock", "Equipment", "Item"),
        ).map { line -> HofSkill(name = line) }

    /**
     * 스킬 표의 한 행을 스킬 모델로 변환한다.
     */
    private fun Element.readSkillRow(section: String): HofSkill? {
        val text = select("td").lastOrNull()?.text()?.normalizeText().orEmpty()
        if (text.isBlank()) return null
        val target = select("span.support").firstOrNull()?.text()?.normalizeText().orEmpty()
        val scope = select("span.recover").firstOrNull()?.text()?.normalizeText().orEmpty()
        val multiplier = select("span.charge").joinToString(" · ") { it.text().normalizeText() }
        val description = text.substringAfterLast(" / ", "").normalizeText()

        return HofSkill(
            name = text.substringBefore(" /").normalizeText(),
            iconUrl = selectFirst("img")?.readImageUrl().orEmpty(),
            category = section,
            targetText = target,
            scopeText = scope,
            spCost = Regex("""(?i)(\d+)\s*sp""").find(text)?.groupValues?.get(1)?.toIntOrNull(),
            multiplierText = multiplier,
            description = description,
        )
    }

    /**
     * 배울 수 있는 스킬 radio row를 스킬 모델로 변환한다.
     */
    private fun Element.readRadioRowSkill(): HofSkill {
        val row = closest("tr")
        val icon = row?.selectFirst("img")?.readImageUrl().orEmpty()
        val text = row?.text()?.normalizeText()
            ?: collectSiblingTextUntilBreak()

        return HofSkill(
            value = attr("value").normalizeText(),
            name = text,
            iconUrl = icon,
        )
    }

    /**
     * radio input 주변 텍스트를 다음 input 또는 줄바꿈 전까지 모은다.
     */
    private fun Element.collectSiblingTextUntilBreak(): String {
        val text = StringBuilder()
        var node = nextSibling()
        while (node != null) {
            if (node is Element) {
                if (node.tagName().equals("input", ignoreCase = true) && node.attr("name") == attr("name")) break
                if (node.tagName().equals("br", ignoreCase = true)) break
                text.append(node.text())
            } else {
                text.append(node.nodeText())
            }
            node = node.nextSibling()
        }
        return text.toString().normalizeText()
    }

    /**
     * 원문 텍스트에서 특정 제목 이후, 다음 섹션 전까지의 줄들을 읽는다.
     */
    private fun Element.readSectionLines(
        start: String,
        stopPrefixes: List<String>,
    ): List<String> {
        val lines = wholeText()
            .lines()
            .map { line -> line.normalizeText() }
            .filter { line -> line.isNotBlank() }
        val startIndex = lines.indexOfFirst { line -> line.equals(start, ignoreCase = true) || line.contains(start, ignoreCase = true) }
        if (startIndex < 0) return emptyList()

        return lines.drop(startIndex + 1)
            .takeWhile { line ->
                stopPrefixes.none { stop ->
                    line.equals(stop, ignoreCase = true) || line.startsWith(stop, ignoreCase = true)
                }
            }
            .filterNot { line -> line.equals(start, ignoreCase = true) }
    }

    /**
     * Character Status 텍스트에서 핵심 스탯을 읽는다.
     */
    private fun Element.readStats(): HofCharacterStats {
        val values = PRIMARY_STAT_NAMES.associateWith { name -> readLabeledStatus(name) }
        val expText = values["Exp"]?.first.orEmpty()
        return HofCharacterStats(
            statusPoints = selectFirst("select[name=upStr]")
                ?.closest("form")
                ?.text()
                ?.let { STATUS_POINT_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() },
            skillPoints = SKILL_POINT_REGEX.find(text())?.groupValues?.get(1)?.toIntOrNull(),
            atk = text().readSingleStat("Atk"),
            matk = text().readSingleStat("Matk"),
            defBase = text().readBaseBonusStat("Def")?.first,
            defBonus = text().readBaseBonusStat("Def")?.second,
            mdefBase = text().readBaseBonusStat("Mdef")?.first,
            mdefBonus = text().readBaseBonusStat("Mdef")?.second,
            handleUsed = text().readPairStat("handle")?.first,
            handleMax = text().readPairStat("handle")?.second,
            costUsed = text().readPairStat("cost")?.first,
            costMax = text().readPairStat("cost")?.second,
            expCurrent = expText.substringBefore('/').trim().toLongOrNull(),
            expMax = expText.substringAfter('/', "").trim().toLongOrNull(),
            expMaxed = expText.substringAfter('/', "").trim().equals("MAX", ignoreCase = true),
            hpBase = values["HP"]?.first.toBaseBonus().first,
            hpBonus = values["HP"]?.first.toBaseBonus().second,
            spBase = values["SP"]?.first.toBaseBonus().first,
            spBonus = values["SP"]?.first.toBaseBonus().second,
            strReal = values["STR"]?.first.toBaseBonus().first,
            strBonus = values["STR"]?.first.toBaseBonus().second,
            intReal = values["INT"]?.first.toBaseBonus().first,
            intBonus = values["INT"]?.first.toBaseBonus().second,
            dexReal = values["DEX"]?.first.toBaseBonus().first,
            dexBonus = values["DEX"]?.first.toBaseBonus().second,
            spdReal = values["SPD"]?.first.toBaseBonus().first,
            spdBonus = values["SPD"]?.first.toBaseBonus().second,
            lukReal = values["LUK"]?.first.toBaseBonus().first,
            lukBonus = values["LUK"]?.first.toBaseBonus().second,
            descriptions = values.mapNotNull { (name, value) -> value?.second?.takeIf(String::isNotBlank)?.let { name to it } }.toMap(),
        )
    }

    private fun Element.readLabeledStatus(name: String): Pair<String, String>? {
        val label = select("font").firstOrNull { it.text().normalizeText().removeSuffix(":").trim().equals(name, true) }
            ?: return null
        val value = label.closest("td")?.nextElementSibling()?.text()?.normalizeText().orEmpty()
        return value to label.attr("title").normalizeText()
    }

    private fun String?.toBaseBonus(): Pair<Int?, Int?> {
        val match = BASE_BONUS_VALUE_REGEX.find(this.orEmpty()) ?: return null to null
        return match.groupValues[1].toIntOrNull() to match.groupValues.getOrNull(2)?.toIntOrNull()
    }

    private fun Element.readStatusEffects(): List<HofStatusEffect> {
        val heading = select("h4").firstOrNull { it.ownText().normalizeText().startsWith("Character Status") } ?: return emptyList()
        val table = heading.nextElementSibling()?.takeIf { it.tagName() == "table" } ?: return emptyList()
        val cell = table.children().firstOrNull()
            ?.children()?.firstOrNull()
            ?.children()?.lastOrNull()
            ?: return emptyList()
        return cell.html().split(Regex("""(?i)<br\s*/?>""")).mapNotNull { fragment ->
            val element = org.jsoup.Jsoup.parseBodyFragment(fragment, baseUri()).body()
            val value = element.text()
                .replace(STATUS_VISUAL_SEPARATOR_REGEX, " ")
                .normalizeText()
            if (
                value.isBlank() ||
                value.matches(FAITH_BAR_REGEX) ||
                value.contains("믿는 신") ||
                value.contains("신앙심")
            ) return@mapNotNull null
            val set = value.startsWith("[SET:")
            HofStatusEffect(
                type = if (set) "SET" else "EFFECT",
                name = if (set) value.removePrefix("[SET:").removeSuffix("]") else value,
                valueText = value,
                description = element.selectFirst("[title]")?.attr("title")?.normalizeText().orEmpty(),
                active = if (set) !element.select("font[color=#A6A6A6], font[color=A6A6A6]").isNotEmpty() else null,
            )
        }
    }

    private fun Element.readFaith(): HofFaith? {
        val text = text().normalizeText()
        val god = Regex("""믿는 신\s*:\s*([^|\s]+)""").find(text)?.groupValues?.get(1) ?: return null
        val gauge = select("font[title]")
            .map { it.attr("title").normalizeText() }
            .firstNotNullOfOrNull { FAITH_GAUGE_REGEX.matchEntire(it)?.groupValues }
            ?: return null
        return HofFaith(godName = god, current = gauge[1].toLong(), max = gauge[2].toLong())
    }

    private fun String.readSingleStat(name: String): Int? =
        Regex("""(?i)\b${Regex.escape(name)}\s*:\s*([+-]?\d+)""")
            .find(this)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun String.readBaseBonusStat(name: String): Pair<Int?, Int?>? =
        Regex("""(?i)\b${Regex.escape(name)}\s*:\s*([+-]?\d+)(?:\s*\+\s*([+-]?\d+))?""")
            .find(this)
            ?.let { match ->
                match.groupValues[1].toIntOrNull() to match.groupValues.getOrNull(2)?.toIntOrNull()
            }

    private fun String.readPairStat(name: String): Pair<Int?, Int?>? =
        Regex("""(?i)\b${Regex.escape(name)}\s*:\s*([+-]?\d+)\s*/\s*([+-]?\d+)""")
            .find(this)
            ?.let { match ->
                match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
            }

    /**
     * input/select element의 현재 선택 값을 읽는다.
     */
    private fun Element.readValue(): String =
        if (tagName().equals("select", ignoreCase = true)) {
            selectFirst("option[selected]")?.attr("value")?.normalizeText()
                ?: selectFirst("option")?.attr("value")?.normalizeText()
                ?: ""
        } else {
            attr("value").normalizeText()
        }

    /**
     * select/input에서 사용자가 보는 표시 텍스트를 읽는다.
     */
    private fun Element.readSelectedText(): String =
        if (tagName().equals("select", ignoreCase = true)) {
            selectFirst("option[selected]")?.text()?.normalizeText()
                ?: selectFirst("option")?.text()?.normalizeText()
                ?: readValue()
        } else {
            text().normalizeText().ifBlank { readValue() }
        }

    /**
     * 이미지 src를 절대 URL 우선으로 읽는다.
     */
    private fun Element.readImageUrl(): String =
        attr("src").normalizeText().let { source ->
            if (source.contains("_files/")) {
                val fileName = source.substringAfterLast('/')
                val folder = when {
                    closest(".carpet_frame") != null -> "image/char"
                    fileName.startsWith("skill_", ignoreCase = true) -> "skill"
                    else -> "image/icon"
                }
                "https://hof.zerosic.com/$folder/$fileName"
            } else {
                absUrl("src").ifBlank { source }
            }
        }

    /**
     * jsoup Node에서 텍스트성 데이터를 안전하게 꺼낸다.
     */
    private fun Node.nodeText(): String =
        when (this) {
            is TextNode -> wholeText
            is DataNode -> wholeData
            else -> ""
        }

    /**
     * HTML 공백과 non-breaking space를 일반 문자열 공백으로 정규화한다.
     */
    private fun String.normalizeText(): String =
        replace('\u00a0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * `Lv.60 Job` 앞부분만 잘라 캐릭터명으로 사용한다.
     */
    private fun String.substringBeforeLevel(): String =
        LEVEL_JOB_REGEX.find(this)
            ?.let { match -> substring(0, match.range.first).normalizeText() }
            ?: this

    private data class LevelJob(
        val level: Int,
        val job: String,
    )

    private companion object {
        const val HOF_BASE_URL = "https://hof.zerosic.com/index.php"
        val LEVEL_JOB_REGEX = Regex("""Lv\.?\s*(\d+)\s*(.*)$""", RegexOption.IGNORE_CASE)
        val ACTION_FIELD_REGEX = Regex("""(?:judge|quantity|skill)(\d+)""")
        val BASE_BONUS_VALUE_REGEX = Regex("""([+-]?\d+)(?:\s*\+\s*([+-]?\d+))?""")
        val FAITH_GAUGE_REGEX = Regex("""(\d+)\s*/\s*(\d+)""")
        val FAITH_BAR_REGEX = Regex("""^[|｜¦]+$""")
        val STATUS_VISUAL_SEPARATOR_REGEX = Regex("""_{8,}""")
        val STATUS_POINT_REGEX = Regex("""\bPoint\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
        val SKILL_POINT_REGEX = Regex("""Skill\s+Point\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
        val PRIMARY_STAT_NAMES = listOf("Exp", "HP", "SP", "STR", "INT", "DEX", "SPD", "LUK")
    }
}
