package app.spammy.hof.external.parser

import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.external.model.HofBattleMap
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Component
/**
 * HOF 전투/모험 맵 목록 HTML을 내부 맵 모델로 변환한다.
 */
class BattleMapParser {
    private val log = LoggerFactory.getLogger(BattleMapParser::class.java)

    /**
     * 지정한 queryName에 해당하는 맵 링크만 찾아 맵 코드, 이름, 그룹, 필요 Time 등을 파싱한다.
     */
    fun parse(
        categoryId: String,
        queryName: String,
        html: String,
    ): List<HofBattleMap> {
        val document = Jsoup.parse(html, HOF_BASE_URL)
        val queryPattern = Regex("""[?&]${Regex.escape(queryName)}=([^&"'#\s]+)""")
        val placeholderQueryNames = placeholderQueryNames(categoryId, queryName)
        val seenCodes = linkedSetOf<String>()
        val seenUnresolvedIdentities = linkedSetOf<String>()
        val mapOrdersByGroup = mutableMapOf<Int, Int>()
        val candidateLinks = document.select("a[href*=$queryName=], div[id^=$MAP_GROUP_ID_PREFIX] a[href]")
        val linkObservations = coalesceMapLinks(candidateLinks, queryPattern)

        // 시나리오 지도는 동일한 맵 코드를 이미지, 클릭 영역, 한글 라벨 링크에 반복해서 사용한다.
        // 첫 이미지 링크에는 텍스트가 없으므로 뒤에 있는 첫 유효 라벨을 코드별 보조 정보로 미리 수집한다.
        val preferredTextByCode = linkedMapOf<String, PreferredMapText>()
        linkObservations.forEach { observation ->
            val mapCode = observation.mapCode ?: return@forEach
            val displayName = observation.displayName
            if (displayName.isBlank()) return@forEach

            preferredTextByCode.putIfAbsent(
                mapCode,
                PreferredMapText(
                    displayName = displayName,
                    contextText = observation.contextText.ifBlank { displayName },
                ),
            )
        }

        return linkObservations
            .mapNotNull { observation ->
                val link = observation.link
                val rawHref = observation.rawHref
                val groupElement = link.parents().firstOrNull { it.id().startsWith(MAP_GROUP_ID_PREFIX) }
                val groupOrder = groupElement?.groupOrder() ?: 0
                val groupMetadata = groupElement?.previousElementSibling()?.text()?.toGroupMetadata()
                val mapCode = observation.mapCode
                val directDisplayName = observation.displayName
                val preferredText = mapCode?.let(preferredTextByCode::get)
                val displayName = directDisplayName.ifBlank { preferredText?.displayName.orEmpty() }
                val contextText = if (directDisplayName.isBlank()) {
                    preferredText?.contextText.orEmpty().ifBlank { displayName }
                } else {
                    observation.contextText.ifBlank { displayName }
                }
                if (
                    mapCode == null &&
                    (groupElement == null || !isRequestedPlaceholderHref(rawHref, placeholderQueryNames))
                ) {
                    return@mapNotNull null
                }
                val parsedKey = parseKey(displayName)
                val name = displayName
                    .withoutKeySuffix()
                    .withoutTrailingMapState()
                    .ifBlank { mapCode.orEmpty() }
                if (name.isBlank()) return@mapNotNull null

                val isNewObservation = if (mapCode == null) {
                    seenUnresolvedIdentities.add(
                        listOf(
                            categoryId,
                            BattleMapIdentityNormalizer.normalize(groupMetadata?.name),
                            BattleMapIdentityNormalizer.normalize(name),
                        ).joinToString("|"),
                    )
                } else {
                    seenCodes.add(mapCode)
                }
                if (!isNewObservation) return@mapNotNull null

                val mapOrder = mapOrdersByGroup.getOrDefault(groupOrder, 0)
                mapOrdersByGroup[groupOrder] = mapOrder + 1
                val cooldownRemaining = parseCooldownRemaining(contextText)
                val attemptCount = parseAttemptCount(contextText)
                val winCount = parseWinCount(contextText)
                warnIfAdvertisedFieldFailed(
                    field = "cooldownRemainingSeconds",
                    advertised = COOLDOWN_ADVERTISEMENT_PATTERN.containsMatchIn(contextText),
                    parsed = cooldownRemaining,
                    categoryId = categoryId,
                    mapCode = mapCode,
                    name = name,
                    sourceText = contextText,
                )
                warnIfAdvertisedFieldFailed(
                    field = "attemptCount",
                    advertised = ATTEMPT_ADVERTISEMENT_PATTERN.containsMatchIn(contextText),
                    parsed = attemptCount,
                    categoryId = categoryId,
                    mapCode = mapCode,
                    name = name,
                    sourceText = contextText,
                )
                warnIfAdvertisedFieldFailed(
                    field = "winCount",
                    advertised = WIN_ADVERTISEMENT_PATTERN.containsMatchIn(contextText),
                    parsed = winCount,
                    categoryId = categoryId,
                    mapCode = mapCode,
                    name = name,
                    sourceText = contextText,
                )
                warnIfAdvertisedFieldFailed(
                    field = "keyCount",
                    advertised = parsedKey.mode == BattleMapKeyMode.UNKNOWN,
                    parsed = parsedKey.count,
                    categoryId = categoryId,
                    mapCode = mapCode,
                    name = name,
                    sourceText = displayName,
                )
                HofBattleMap(
                    categoryId = categoryId,
                    mapCode = mapCode,
                    name = name,
                    groupName = groupMetadata?.name,
                    groupOrder = groupOrder,
                    mapOrder = mapOrder,
                    recommendedLevel = groupMetadata?.recommendedLevel,
                    availableCount = parseAvailableCount(contextText),
                    attemptCount = attemptCount,
                    winCount = winCount,
                    cooldownRemainingSeconds = cooldownRemaining?.seconds,
                    keyMode = parsedKey.mode,
                    keyCount = parsedKey.count,
                    requiredTime = parseRequiredTime(contextText),
                    supportsThreeBattles = mapCode?.let {
                        supportsThreeBattles(link, it, document.select("form"), queryPattern)
                    },
                    iconUrl = link.selectFirst("img[src]")?.absUrl("src")?.ifBlank { null },
                    rawHref = rawHref,
                )
            }
    }

    private fun coalesceMapLinks(
        candidateLinks: List<Element>,
        queryPattern: Regex,
    ): List<MapLinkObservation> {
        val mapCodes = candidateLinks.map { link -> parseDirectMapCode(link.attr("href"), queryPattern) }
        val consumed = BooleanArray(candidateLinks.size)
        val observations = mutableListOf<MapLinkObservation>()
        candidateLinks.forEachIndexed { index, link ->
            if (consumed[index]) return@forEachIndexed

            val rawHref = link.attr("href")
            val mapCode = mapCodes[index]
            val parent = link.parent()
            val sameMapIndexes = if (mapCode == null || parent == null) {
                listOf(index)
            } else {
                candidateLinks.indices.filter { candidateIndex ->
                    candidateIndex >= index &&
                        !consumed[candidateIndex] &&
                        mapCodes[candidateIndex] == mapCode &&
                        candidateLinks[candidateIndex].parent() === parent
                }
            }
            val keyFragmentIndexes = mutableListOf<Int>()
            var accumulatedText = ""
            for (candidateIndex in sameMapIndexes) {
                keyFragmentIndexes += candidateIndex
                accumulatedText += candidateLinks[candidateIndex].text().normalizedText()
                if (KEY_ADVERTISEMENT_PATTERN.containsMatchIn(accumulatedText)) break
            }
            val fragmentIndexes = if (KEY_ADVERTISEMENT_PATTERN.containsMatchIn(accumulatedText)) {
                keyFragmentIndexes
            } else {
                listOf(index)
            }
            fragmentIndexes.forEach { fragmentIndex -> consumed[fragmentIndex] = true }
            observations += MapLinkObservation(
                link = link,
                rawHref = rawHref,
                mapCode = mapCode,
                displayName = fragmentIndexes.joinToString(separator = "") { fragmentIndex ->
                    candidateLinks[fragmentIndex].text().normalizedText()
                },
                contextText = parent?.text()?.trim().orEmpty(),
            )
        }
        return observations
    }

    /**
     * Parses one authenticated map-detail page. `null` means the page did not expose exactly one execution form
     * for this map, so callers must preserve the prior observation instead of guessing or downgrading it.
     */
    fun observeThreeBattleCapability(
        queryName: String,
        mapCode: String,
        html: String,
        authoritativeCurrentMapCode: String? = null,
    ): Boolean? {
        val document = Jsoup.parse(html, HOF_BASE_URL)
        val queryPattern = Regex("""[?&]${Regex.escape(queryName)}=([^&"'#\s]+)""")
        val executionForm = document.select("form").singleOrNull { form ->
            val action = form.attr("action").trim()
            if (action.isEmpty()) {
                authoritativeCurrentMapCode == mapCode
            } else {
                parseDirectMapCode(action, queryPattern) == mapCode
            }
        } ?: return null
        return executionForm.supportsThreeBattleSubmit()
    }

    /** Capability is authoritative only when the real submit control belongs to this map's execution form. */
    private fun supportsThreeBattles(
        mapLink: Element,
        mapCode: String,
        forms: List<Element>,
        queryPattern: Regex,
    ): Boolean? {
        val containingForm = mapLink.parents()
            .firstOrNull { it.tagName() == "form" }
            ?.takeIf { form ->
                val actionMapCode = parseDirectMapCode(form.attr("action"), queryPattern)
                actionMapCode == null || actionMapCode == mapCode
            }
        val executionForm = containingForm ?: forms.singleOrNull { form ->
            parseDirectMapCode(form.attr("action"), queryPattern) == mapCode
        } ?: return null

        return executionForm.supportsThreeBattleSubmit()
    }

    private fun Element.supportsThreeBattleSubmit(): Boolean =
        ownerDocument()
            ?.select("[name=monster_battle_10]")
            .orEmpty()
            .any { control -> control.isThreeBattleSubmit() && control.belongsTo(this) }

    private fun Element.isThreeBattleSubmit(): Boolean {
        if (hasAttr("disabled")) return false
        val type = attr("type").trim().lowercase()
        return when (tagName()) {
            "button" -> type.isBlank() || type == "submit"
            "input" -> type == "submit"
            else -> false
        }
    }

    private fun Element.belongsTo(form: Element): Boolean {
        val explicitFormId = attr("form").trim()
        if (explicitFormId.isNotBlank()) return form.id().isNotBlank() && explicitFormId == form.id()
        return parents().firstOrNull { it.tagName() == "form" } === form
    }

    /**
     * 실제 맵 링크의 query 값만 맵 코드로 인정한다.
     *
     * `sp_hunt#` 같은 더미 링크는 코드를 추측하지 않고 `null` 관측으로 남겨 DB 별칭이 해결하게 한다.
     */
    private fun parseDirectMapCode(
        rawHref: String,
        queryPattern: Regex,
    ): String? =
        queryPattern.find(rawHref)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decode)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    /**
     * 코드가 없는 링크는 현재 맵 query 또는 해당 카테고리 페이지 action을 값 없이 명시한 경우만 placeholder로 인정한다.
     * `#`, JavaScript, 마을/메뉴 이동 링크는 mapgroup 안에 있어도 맵 관측이 아니다.
     */
    private fun isRequestedPlaceholderHref(
        rawHref: String,
        expectedQueryNames: Set<String>,
    ): Boolean =
        CODELESS_QUERY_KEY_PATTERN.findAll(rawHref)
            .map { match -> match.groupValues[1] }
            .any(expectedQueryNames::contains)

    private fun placeholderQueryNames(
        categoryId: String,
        queryName: String,
    ): Set<String> =
        setOf(
            queryName,
            when (categoryId) {
                "adventure_map" -> "sp_hunt"
                "scenario_ocean", "raid" -> "raid_hunt"
                else -> "hunt"
            },
        )

    /**
     * 맵 설명 텍스트에서 남은 가능 횟수를 읽는다.
     */
    private fun parseAvailableCount(text: String): Int? =
        AVAILABLE_COUNT_PATTERN.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toNumberOrNull()

    /**
     * `( 도전 15회 , 승리 5회 ) 남음` 형식에서 하루 남은 도전 횟수를 읽는다.
     */
    private fun parseAttemptCount(text: String): Int? =
        ATTEMPT_COUNT_PATTERN.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toNumberOrNull()

    /**
     * `( 도전 15회 , 승리 5회 ) 남음` 형식에서 하루 남은 승리 가능 횟수를 읽는다.
     * `승리!(총 2회 도전)`은 해당 맵의 당일 승리가 이미 완료됐다는 뜻이므로 0으로 정규화한다.
     */
    private fun parseWinCount(text: String): Int? =
        if (DAILY_VICTORY_COMPLETE_PATTERN.containsMatchIn(text)) {
            0
        } else {
            WIN_COUNT_PATTERN.find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toNumberOrNull()
        }

    /**
     * HOF의 `(시간 분) 남음` 표기를 절대 만료 시각 계산에 쓸 초 단위로 바꾼다.
     * 시간만 있거나 분만 있는 표기도 모두 허용한다.
     */
    private fun parseCooldownRemaining(text: String): CooldownRemaining? {
        val match = COOLDOWN_REMAINING_PATTERN.find(text) ?: return null
        val hours = match.groupValues[2].toOptionalLong() ?: return null
        val combinedMinutes = match.groupValues[3].toOptionalLong() ?: return null
        val minutesOnly = match.groupValues[4].toOptionalLong() ?: return null
        val seconds = runCatching {
            Math.addExact(
                Math.multiplyExact(hours, SECONDS_PER_HOUR),
                Math.multiplyExact(Math.addExact(combinedMinutes, minutesOnly), SECONDS_PER_MINUTE),
            )
        }.getOrNull() ?: return null
        return CooldownRemaining(seconds = seconds)
    }

    /**
     * 맵 설명 텍스트에서 필요 Time을 읽는다.
     */
    private fun parseRequiredTime(text: String): Int? =
        REQUIRED_TIME_PATTERN.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toNumberOrNull()

    private fun String.toNumberOrNull(): Int? =
        replace(",", "").toIntOrNull()

    private fun String.toLongNumberOrNull(): Long? =
        replace(",", "").toLongOrNull()

    private fun String.toOptionalLong(): Long? =
        if (isBlank()) 0L else toLongNumberOrNull()

    /**
     * HOF 원본이 특정 동적 값을 표시했지만 알려진 형식으로 읽지 못한 경우만 경고한다.
     * 해당 표시가 없는 일반 맵은 로그를 남기지 않아 운영 잡음을 피한다.
     */
    private fun warnIfAdvertisedFieldFailed(
        field: String,
        advertised: Boolean,
        parsed: Any?,
        categoryId: String,
        mapCode: String?,
        name: String,
        sourceText: String,
    ) {
        if (!advertised || parsed != null) return

        log.warn(
            "Battle map advertised field parse failed field={} categoryId={} mapCode={} name={} sourceText={}",
            field,
            categoryId,
            mapCode,
            name,
            sourceText.normalizedText().take(MAX_WARNING_SOURCE_LENGTH),
        )
    }

    private fun parseKey(text: String): ParsedKey =
        when {
            PERMANENT_KEY_PATTERN.containsMatchIn(text) ->
                ParsedKey(BattleMapKeyMode.UNLIMITED, null)
            FINITE_KEY_PATTERN.containsMatchIn(text) -> {
                val count = FINITE_KEY_PATTERN.find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toNumberOrNull()
                if (count == null) {
                    ParsedKey(BattleMapKeyMode.UNKNOWN, null)
                } else {
                    ParsedKey(BattleMapKeyMode.LIMITED, count)
                }
            }
            KEY_ADVERTISEMENT_PATTERN.containsMatchIn(text) ->
                ParsedKey(BattleMapKeyMode.UNKNOWN, null)
            else -> ParsedKey(BattleMapKeyMode.NOT_REQUIRED, null)
        }

    /**
     * 사용자에게 보여줄 맵 이름에서 키 표기를 제거한다.
     */
    private fun String.withoutKeySuffix(): String =
        replace(KEY_ADVERTISEMENT_PATTERN, "").normalizedText()

    /** 코드 없는 링크 이름 끝에 포함된 쿨다운과 Time 상태를 별칭 비교 전에 제거한다. */
    private fun String.withoutTrailingMapState(): String =
        replace(TRAILING_COOLDOWN_STATE_PATTERN, "").normalizedText()

    /**
     * mapgroupN id에서 그룹 순서를 읽는다.
     */
    private fun Element.groupOrder(): Int =
        MAP_GROUP_ID_PATTERN.find(id())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

    /**
     * 그룹 헤더 텍스트에서 그룹명과 권장 레벨을 분리한다.
     */
    private fun String.toGroupMetadata(): GroupMetadata {
        val recommendedLevel = RECOMMENDED_LEVEL_PATTERN.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        val name = replace(RECOMMENDED_LEVEL_PATTERN, "")
            .replace(TRAILING_GROUP_COUNT_PATTERN, "")
            .normalizedText()

        return GroupMetadata(
            name = name.ifBlank { null },
            recommendedLevel = recommendedLevel,
        )
    }

    private fun String.normalizedText(): String =
        replace(Regex("""\s+"""), " ").trim()

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    private companion object {
        const val HOF_BASE_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
        const val MAP_GROUP_ID_PREFIX = "mapgroup"
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 3_600L
        const val MAX_WARNING_SOURCE_LENGTH = 300
        val MAP_GROUP_ID_PATTERN = Regex("""^mapgroup(\d+)$""")
        val CODELESS_QUERY_KEY_PATTERN = Regex("""[?&]([^?&#=\s]+)(?:=(?=[#&]|$))?(?=[#&]|$)""")
        val AVAILABLE_COUNT_PATTERN = Regex("""([\d,]+)\s*가능""")
        val ATTEMPT_COUNT_PATTERN = Regex("""도전\s*([\d,]+)\s*회""")
        val WIN_COUNT_PATTERN = Regex("""승리\s*([\d,]+)\s*회""")
        val DAILY_VICTORY_COMPLETE_PATTERN = Regex(
            """승리\s*!\s*\(\s*총\s*[\d,]+\s*회\s*도전\s*\)""",
        )
        val COOLDOWN_REMAINING_PATTERN =
            Regex("""\(\s*((?:([\d,]+)\s*시간(?:\s*([\d,]+)\s*분)?)|([\d,]+)\s*분)\s*\)\s*남음""")
        val COOLDOWN_ADVERTISEMENT_PATTERN = Regex("""\([^)]*(?:시간|분)[^)]*\)\s*남음""")
        val TRAILING_COOLDOWN_STATE_PATTERN = Regex(
            """\s*\(\s*(?:(?:[\d,]+\s*시간(?:\s*[\d,]+\s*분)?)|(?:[\d,]+\s*분))\s*\)\s*남음(?:\s*\([^)]*(?:Time|타임)[^)]*\))?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val ATTEMPT_ADVERTISEMENT_PATTERN = Regex("""도전[^)\r\n]*?회""")
        val WIN_ADVERTISEMENT_PATTERN = Regex("""승리[^)\r\n]*?회""")
        val REQUIRED_TIME_PATTERN =
            Regex("""(?:Time|타임\s*소모|타임|필요\s*Time)\s*[:：]?\s*([\d,]+)""", RegexOption.IGNORE_CASE)
        val FINITE_KEY_PATTERN =
            Regex("""\(\s*x\s*(\d+|\d{1,3}(?:,\d{3})+)\s*\)\s*$""", RegexOption.IGNORE_CASE)
        val PERMANENT_KEY_PATTERN = Regex("""\(\s*x\s*\)\s*$""", RegexOption.IGNORE_CASE)
        val KEY_ADVERTISEMENT_PATTERN = Regex("""\(\s*x[^)]*\)\s*$""", RegexOption.IGNORE_CASE)
        val RECOMMENDED_LEVEL_PATTERN = Regex("""\(\s*적정\s*레벨\s*:\s*([^)]+)\)""")
        val TRAILING_GROUP_COUNT_PATTERN = Regex("""\(\s*[\d,]+\s*\)\s*$""")
    }

    private data class GroupMetadata(
        val name: String?,
        val recommendedLevel: String?,
    )

    private data class CooldownRemaining(
        val seconds: Long,
    )

    private data class ParsedKey(
        val mode: BattleMapKeyMode,
        val count: Int?,
    )

    private data class MapLinkObservation(
        val link: Element,
        val rawHref: String,
        val mapCode: String?,
        val displayName: String,
        val contextText: String,
    )

    private data class PreferredMapText(
        val displayName: String,
        val contextText: String,
    )
}
