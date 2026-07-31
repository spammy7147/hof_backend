package app.spammy.hof.town.raid.service

import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.raid.dto.*
import app.spammy.hof.town.raid.model.*
import app.spammy.hof.town.raid.parser.RaidPubParser
import org.springframework.stereotype.Service

@Service
class RaidPubService(
    private val executor: TownAuthenticatedExecutor,
    private val locations: TownLocationResolver,
    private val parser: RaidPubParser,
    private val battleMaps: BattleMapService,
) {
    fun load(accountId: Long): RaidPubResponse {
        val snapshot = loadRaw(accountId)
        return RaidPubResponse.from(withBattleAvailability(accountId, snapshot))
    }

    fun action(accountId: Long, request: RaidPubActionRequest): RaidPubResponse {
        if (request.action == RaidAction.REFRESH) {
            if (request.raidId != null) invalid("갱신에는 레이드 식별자가 필요하지 않습니다.")
            return load(accountId)
        }
        val projected = executor.executeProjected(
            accountId = accountId,
            pageUrl = url(),
            resolveAction = { html, finalUrl, page ->
                val current = parser.parse(html, finalUrl, page)
                val actionId = if (request.action in RAID_ACTIONS) {
                    val id = request.raidId?.takeIf(String::isNotBlank) ?: invalid("레이드를 선택해 주세요.")
                    current.raids.singleOrNull { it.id == id }?.actionIds?.get(request.action)
                        ?: invalid("현재 해당 레이드에서 실행할 수 없는 동작입니다.")
                } else {
                    if (request.raidId != null) invalid("전체 레이드 동작에는 레이드 식별자가 필요하지 않습니다.")
                    current.globalActionIds[request.action]
                        ?: invalid("현재 실행할 수 없는 전투 정보실 동작입니다.")
                }
                TownActionRequest(actionId)
            },
        ) { html, finalUrl, result, page -> parser.parse(html, finalUrl, page, result) }

        // START 응답 등이 전투 페이지로 이동하면 raidpub form이 없다. 그때만 최신 GET을 정확히 한 번 더 읽는다.
        val latest = if (projected.observedRaidPubForm) projected else {
            val refreshed = loadRaw(accountId)
            refreshed.copy(result = projected.result)
        }
        return RaidPubResponse.from(withBattleAvailability(accountId, latest))
    }

    private fun loadRaw(accountId: Long): RaidPubSnapshot = executor.loadProjected(accountId, url()) { html, finalUrl, page ->
        val parsed = parser.parse(html, finalUrl, page)
        if (!parsed.observedRaidPubForm) invalid("HOF 전투 정보실 양식을 확인하지 못했습니다.")
        parsed
    }

    private fun withBattleAvailability(accountId: Long, snapshot: RaidPubSnapshot): RaidPubSnapshot {
        val available = battleMaps.findMaps(accountId, "raid")
            .filter { it.enabled && it.resolved }
            .mapNotNull { it.mapCode }
            .toSet()
        return snapshot.copy(raids = snapshot.raids.map { raid ->
            raid.copy(battleTarget = if (raid.playable && raid.joined && raid.id in available) RaidBattleTarget(mapCode = raid.id) else null)
        })
    }

    private fun url() = locations.resolve(TownFeatureId.RAID_INFO).url
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
    private companion object {
        val RAID_ACTIONS = setOf(RaidAction.REGISTER, RaidAction.LEAVE, RaidAction.START, RaidAction.RESET)
    }
}
