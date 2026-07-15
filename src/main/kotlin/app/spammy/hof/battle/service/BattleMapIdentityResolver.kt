package app.spammy.hof.battle.service

import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.external.model.HofBattleMap
import org.springframework.stereotype.Component

/**
 * HOF 맵 관측을 DB 카탈로그 식별자로 해결한다.
 *
 * 직접 코드, 정확 별칭, 유일한 접미사 후보 순서로만 해결하며, 후보가 없거나 둘 이상이면
 * 추측하지 않는다. 관측 그룹이 있으면 같은 정규화 그룹을 벗어난 후보로는 절대 폴백하지 않고,
 * 그룹이 없을 때만 카테고리 전체 유일성을 쓴다. 모든 DB 읽기는 QueryDSL 전용 repository를 통한다.
 */
@Component
class BattleMapIdentityResolver(
    private val queryRepository: BattleMapQueryRepository,
) {
    /**
     * 직접 mapCode가 있으면 category와 함께 정확 조회하고, placeholder면 해당 category alias만 읽어 해결한다.
     * 결과가 유일하지 않으면 null을 반환해 호출자가 unresolved 관측으로 보존하게 한다.
     */
    fun resolve(observation: HofBattleMap): BattleMapEntity? {
        observation.mapCode
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { mapCode ->
                return queryRepository.findMapByCategoryIdAndMapCode(observation.categoryId, mapCode)
            }

        return resolveBattleMapAlias(
            observation = observation,
            aliases = queryRepository.findAliasesByCategoryId(observation.categoryId),
        )
    }

    /** Resolves a quest mission target only when its conservative alias match identifies one map. */
    fun resolveAlias(categoryId: String, target: String): BattleMapAliasResolution {
        return resolveBattleMapAlias(target, loadAliasCandidates(categoryId))
    }

    /** DB-loading boundary used by callers before constructing a pure automation snapshot. */
    fun loadAliasCandidates(categoryId: String): List<BattleMapIdentityCandidate> {
        val aliasesByMapId = queryRepository.findAliasesByCategoryId(categoryId)
            .groupBy { it.battleMap.id }
        return queryRepository.findMapsByCategoryId(categoryId).map { map ->
            BattleMapIdentityCandidate(
                categoryId = map.categoryId,
                mapCode = map.mapCode,
                mapName = map.name,
                aliases = aliasesByMapId[map.id].orEmpty().mapTo(linkedSetOf()) { it.alias },
            )
        }
    }
}

data class BattleMapIdentityCandidate(
    val categoryId: String,
    val mapCode: String,
    val mapName: String,
    val aliases: Set<String>,
)

/** Pure target resolution over an immutable catalog snapshot; never performs repository I/O. */
fun resolveBattleMapAlias(
    target: String,
    candidates: Collection<BattleMapIdentityCandidate>,
): BattleMapAliasResolution {
    val normalizedTarget = questTargetNormalize(target)
    if (normalizedTarget.isBlank()) return BattleMapAliasResolution.Missing
    val exactMatches = candidates.filter { candidate ->
        sequenceOf(candidate.mapName)
            .plus(candidate.aliases.asSequence())
            .any { questTargetNormalize(it) == normalizedTarget }
    }.distinctBy { it.categoryId to it.mapCode }
    resolveUnique(exactMatches)?.let { return it }
    if (exactMatches.size > 1) return BattleMapAliasResolution.Ambiguous

    val derivedTargets = questTargetDerivedAliases(target)
    val fallbackMatches = candidates.filter { candidate ->
        sequenceOf(candidate.mapName)
            .plus(candidate.aliases.asSequence())
            .flatMap { questTargetDerivedAliases(it).asSequence() }
            .any { it in derivedTargets }
    }.distinctBy { it.categoryId to it.mapCode }
    return resolveUnique(fallbackMatches) ?: when (fallbackMatches.size) {
        0 -> BattleMapAliasResolution.Missing
        else -> BattleMapAliasResolution.Ambiguous
    }
}

private fun resolveUnique(matches: List<BattleMapIdentityCandidate>): BattleMapAliasResolution.Resolved? =
    matches.singleOrNull()?.let {
        BattleMapAliasResolution.Resolved(it.categoryId, it.mapCode, it.mapName)
    }

private val questTargetSeparatorPattern = Regex("""\s*[-/·]\s*""")

private fun questTargetAliases(value: String): Set<String> =
    BattleMapIdentityNormalizer.aliasValues(value).mapTo(linkedSetOf(), ::questTargetNormalize)

private fun questTargetDerivedAliases(value: String): Set<String> {
    val full = questTargetNormalize(value)
    val derived = questTargetAliases(value).filterTo(linkedSetOf()) { it != full }
    return derived.ifEmpty { setOf(full) }
}

private fun questTargetNormalize(value: String): String =
    BattleMapIdentityNormalizer.normalize(value).replace(questTargetSeparatorPattern, " - ")

sealed interface BattleMapAliasResolution {
    data class Resolved(val categoryId: String, val mapCode: String, val mapName: String) : BattleMapAliasResolution
    data object Missing : BattleMapAliasResolution
    data object Ambiguous : BattleMapAliasResolution
}
