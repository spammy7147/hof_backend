package app.spammy.hof.external.parser

import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.external.model.HofBattleMap
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

enum class RaidCooldownAssociationStatus {
    NONE,
    HOF_DIRECT,
    AMBIGUOUS,
    PARSE_FAILED,
}

data class RaidCooldownPageObservation(
    val status: RaidCooldownAssociationStatus,
    val candidateSeconds: List<Long>,
    val mapCount: Int,
    val candidateCount: Int,
    val domFingerprint: String,
    val responseShapeFingerprint: String,
    val reasonCode: String,
) {
    val incomplete: Boolean
        get() = status in setOf(RaidCooldownAssociationStatus.AMBIGUOUS, RaidCooldownAssociationStatus.PARSE_FAILED)
}

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
        val document = HofHtmlParser.parse(html, HOF_BASE_URL)
        val pageLevelUnionCooldown = if (categoryId == UNION_CATEGORY) {
            UnionBattleCooldownTextParser.parseRemainingSeconds(document.text())
        } else {
            null
        }
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
                val unionCard = if (categoryId == UNION_CATEGORY) link.unionCard() else null
                val directDisplayName = observation.displayName.ifBlank {
                    unionCard?.selectFirst(UNION_CARD_NAME_SELECTOR)?.text()?.normalizedText().orEmpty()
                }
                val directContextText = observation.contextText.ifBlank {
                    unionCard?.text()?.normalizedText().orEmpty()
                }
                val preferredText = mapCode?.let(preferredTextByCode::get)
                val displayName = directDisplayName.ifBlank { preferredText?.displayName.orEmpty() }
                val contextText = if (directDisplayName.isBlank()) {
                    preferredText?.contextText.orEmpty().ifBlank { displayName }
                } else {
                    directContextText.ifBlank { displayName }
                }
                if (
                    mapCode == null &&
                    (groupElement == null || !isRequestedPlaceholderHref(rawHref, placeholderQueryNames))
                ) {
                    return@mapNotNull null
                }
                val parsedKey = parseKey(displayName)
                val parsedName = displayName
                    .withoutKeySuffix()
                    .withoutTrailingMapState()
                    .ifBlank { mapCode.orEmpty() }
                val name = KNOWN_UNION_MAP_NAMES[mapCode]
                    ?.takeIf { categoryId == UNION_CATEGORY && parsedName == mapCode }
                    ?: parsedName
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
                    cooldownRemainingSeconds = cooldownRemaining?.seconds ?: pageLevelUnionCooldown,
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

    /** 유니온 대상이 없어도 정상 전투 페이지와 페이지 공통 쿨다운을 구분한다. */
    fun parseUnionPageState(html: String): UnionBattlePageState {
        val text = HofHtmlParser.parse(html, HOF_BASE_URL).text()
        return UnionBattlePageState(
            authoritative = UNION_PAGE_MARKER.containsMatchIn(text),
            cooldownRemainingSeconds = UnionBattleCooldownTextParser.parseRemainingSeconds(text),
        )
    }

    /** 빈 결과를 파싱 실패가 아닌 완전한 raid_hunt 페이지의 정상적인 맵 부재로만 확정한다. */
    fun observesAuthoritativeRaidAbsence(
        html: String,
        finalUrl: String,
        statusCode: Int,
    ): Boolean {
        if (statusCode !in 200..299 || !RAID_PAGE_URL_PATTERN.containsMatchIn(finalUrl)) return false
        val document = HofHtmlParser.parse(html, HOF_BASE_URL)
        val text = document.text()
        return document.selectFirst("#contents") != null &&
            RAID_PAGE_MARKER.containsMatchIn(text) &&
            RAID_BATTLE_LOG_MARKER.containsMatchIn(text) &&
            RAID_ABSENCE_PATTERN.containsMatchIn(text)
    }

    /**
     * 모험맵 대상 소멸을 판단할 수 있는 완전한 목록인지 확인한다.
     *
     * 공통 footer까지 도착한 정상 sp_hunt 문서여야 하며, map group 안의 모든 모험맵 identity가 실제
     * parser 결과에 포함돼야 한다. 일부 HTML만 내려오거나 새 행을 해석하지 못한 경우에는 target absence를
     * 권위 증거로 사용하지 않는다.
     */
    fun observesCompleteAdventureMapPage(
        html: String,
        finalUrl: String,
        statusCode: Int,
        maps: List<HofBattleMap>,
    ): Boolean {
        if (statusCode !in 200..299 || !ADVENTURE_PAGE_URL_PATTERN.containsMatchIn(finalUrl)) return false
        val document = HofHtmlParser.parse(html, HOF_BASE_URL)
        val contents = document.selectFirst("#contents") ?: return false
        if (!HofHtmlParser.hasCompletePageTerminator(html, HOF_BASE_URL)) return false

        val queryPattern = Regex("""[?&]sp_common=([^&\"'#\s]+)""")
        val candidates = coalesceMapLinks(
            contents.select("a[href*=sp_common=], div[id^=$MAP_GROUP_ID_PREFIX] a[href]"),
            queryPattern,
        )
        val declaredIdentities = linkedSetOf<String>()
        candidates.forEach { candidate ->
            val group = candidate.link.parents().firstOrNull { it.id().startsWith(MAP_GROUP_ID_PREFIX) }
            val relevant = candidate.mapCode != null ||
                (group != null && isRequestedPlaceholderHref(candidate.rawHref, setOf("sp_common", "sp_hunt")))
            if (!relevant) {
                // A new/unknown action link inside a map group could be a target this parser cannot see yet.
                // Treating it as decoration would make a stored target look absent from an otherwise complete page.
                if (group != null && !isMapGroupDecoration(candidate.rawHref)) return false
                return@forEach
            }
            val identity = candidate.mapCode?.let { "code:$it" } ?: run {
                val name = candidate.displayName
                    .withoutKeySuffix()
                    .withoutTrailingMapState()
                    .takeIf(String::isNotBlank)
                    ?: return false
                "group:${group?.groupOrder()}:name:${BattleMapIdentityNormalizer.normalize(name)}"
            }
            declaredIdentities += identity
        }
        val parsedIdentities = maps.mapTo(linkedSetOf()) { map ->
            map.mapCode?.let { "code:$it" }
                ?: "group:${map.groupOrder}:name:${BattleMapIdentityNormalizer.normalize(map.name)}"
        }
        return declaredIdentities.isNotEmpty() && parsedIdentities == declaredIdentities
    }

    /**
     * 레이드 쿨타임 광고와 현재 parser가 맵에 직접 결합한 결과가 일치하는지만 판정한다.
     * 실제 DOM fixture 전에는 바깥 wrapper의 타이머를 성공으로 추정하지 않고 AMBIGUOUS로 닫는다.
     */
    fun inspectRaidCooldown(
        html: String,
        maps: List<HofBattleMap>,
    ): RaidCooldownPageObservation {
        val document = HofHtmlParser.parse(html, HOF_BASE_URL)
        val normalizedText = document.text().normalizedText()
        val candidateSeconds = RAID_COOLDOWN_REMAINING_PATTERN.findAll(normalizedText)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toLongNumberOrNull() }
            .filter { it > 0 }
            .toList()
        val markerPresent = RAID_COOLDOWN_MARKER_PATTERN.containsMatchIn(normalizedText)
        val directlyParsed = maps.mapNotNull(HofBattleMap::cooldownRemainingSeconds).filter { it > 0 }
        val status = when {
            markerPresent && candidateSeconds.isEmpty() -> RaidCooldownAssociationStatus.PARSE_FAILED
            candidateSeconds.isEmpty() -> RaidCooldownAssociationStatus.NONE
            directlyParsed.isNotEmpty() && directlyParsed.sorted() == candidateSeconds.sorted() ->
                RaidCooldownAssociationStatus.HOF_DIRECT
            else -> RaidCooldownAssociationStatus.AMBIGUOUS
        }
        val shape = "raidCooldown|marker=$markerPresent|candidates=${candidateSeconds.size}|" +
            "maps=${maps.size}|direct=${directlyParsed.size}|forms=${document.select("form").size}"
        val domShape = document.select("a[href*=raid_common=]").joinToString("|") { link ->
            val parent = link.parent()
            "${parent?.tagName() ?: "none"}:${parent?.childrenSize() ?: 0}:${link.tagName()}"
        }
        return RaidCooldownPageObservation(
            status = status,
            candidateSeconds = candidateSeconds,
            mapCount = maps.size,
            candidateCount = candidateSeconds.size,
            domFingerprint = fingerprint(domShape),
            responseShapeFingerprint = fingerprint(shape),
            reasonCode = when (status) {
                RaidCooldownAssociationStatus.NONE -> "RAID_COOLDOWN_NOT_ADVERTISED"
                RaidCooldownAssociationStatus.HOF_DIRECT -> "RAID_COOLDOWN_HOF_DIRECT"
                RaidCooldownAssociationStatus.AMBIGUOUS -> "RAID_COOLDOWN_ASSOCIATION_AMBIGUOUS"
                RaidCooldownAssociationStatus.PARSE_FAILED -> "RAID_COOLDOWN_PARSE_FAILED"
            },
        )
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
        val document = HofHtmlParser.parse(html, HOF_BASE_URL)
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

    /** 실제 sp_hunt map group에 있는 fragment-only anchor는 이동·제출 동작이 아닌 장식 경계다. */
    private fun isMapGroupDecoration(rawHref: String): Boolean = rawHref.trim() == "#"

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

    /** HOF의 시간·분 또는 레이드의 `다음 전투까지 N초 남음` 표기를 초 단위로 바꾼다. */
    private fun parseCooldownRemaining(text: String): CooldownRemaining? {
        RAID_COOLDOWN_REMAINING_PATTERN.find(text)?.groupValues?.get(1)?.toLongNumberOrNull()?.let {
            return CooldownRemaining(seconds = it)
        }
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

    private fun Element.unionCard(): Element? =
        parents().firstOrNull { parent -> parent.hasClass(UNION_CARD_CLASS) }

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

    private fun fingerprint(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val HOF_BASE_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
        const val MAP_GROUP_ID_PREFIX = "mapgroup"
        const val UNION_CATEGORY = "union"
        const val UNION_CARD_CLASS = "carpet_frame"
        const val UNION_CARD_NAME_SELECTOR = ".bold.dmg"
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 3_600L
        const val MAX_WARNING_SOURCE_LENGTH = 300
        val MAP_GROUP_ID_PATTERN = Regex("""^mapgroup(\d+)$""")
        val CODELESS_QUERY_KEY_PATTERN = Regex("""[?&]([^?&#=\s]+)(?:=(?=[#&]|$))?(?=[#&]|$)""")
        val AVAILABLE_COUNT_PATTERN = Regex("""([\d,]+)\s*가능""")
        val ATTEMPT_COUNT_PATTERN = Regex("""도전\s*([\d,]+)\s*회""")
        val WIN_COUNT_PATTERN = Regex("""승리\s*([\d,]+)\s*회""")
        val DAILY_VICTORY_COMPLETE_PATTERN = Regex(
            """승리\s*!\s*\(\s*총\s*(?:[\d,]+\s*)?회\s*도전\s*\)""",
        )
        val COOLDOWN_REMAINING_PATTERN =
            Regex("""\(\s*((?:([\d,]+)\s*시간(?:\s*([\d,]+)\s*분)?)|([\d,]+)\s*분)\s*\)\s*남음""")
        val RAID_COOLDOWN_REMAINING_PATTERN = Regex("""(?:다음\s*전투까지\s*)?([\d,]+)\s*초\s*남음""")
        val RAID_COOLDOWN_MARKER_PATTERN = Regex("""다음\s*전투까지|전투[^\r\n]{0,40}(?:초|분|시간)[^\r\n]{0,20}남음""")
        val COOLDOWN_ADVERTISEMENT_PATTERN = Regex("""\([^)]*(?:시간|분)[^)]*\)\s*남음|(?:다음\s*전투까지\s*)?[\d,]+\s*초\s*남음""")
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
        val KNOWN_UNION_MAP_NAMES = mapOf(
            "0003" to "도적소탕",
            "0004" to "사막의 살인적",
        )
        val UNION_PAGE_MARKER = Regex("Union(?:\\s*Monster|\\s*Battle\\s*Log)", RegexOption.IGNORE_CASE)
        val RAID_ABSENCE_PATTERN = Regex(
            "(?:현재\\s*열린\\s*레이드가|진행\\s*중인\\s*전투가)\\s*없습니다[.]?",
        )
        val RAID_PAGE_URL_PATTERN = Regex("""/ZeroHOF/index[.]php[?](?:[^#&]*&)*raid_hunt(?:[=&][^#]*)?(?:#.*)?$""")
        val ADVENTURE_PAGE_URL_PATTERN = Regex(
            """/ZeroHOF/index[.]php[?](?:[^#&]*&)*sp_hunt(?:[=&][^#]*)?(?:#.*)?$""",
        )
        val RAID_PAGE_MARKER = Regex("Special\\s*Battle", RegexOption.IGNORE_CASE)
        val RAID_BATTLE_LOG_MARKER = Regex("Battle\\s*Log", RegexOption.IGNORE_CASE)
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

data class UnionBattlePageState(
    val authoritative: Boolean,
    val cooldownRemainingSeconds: Long?,
)
