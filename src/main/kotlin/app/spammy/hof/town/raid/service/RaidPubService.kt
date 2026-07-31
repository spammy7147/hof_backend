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
        val projected = executor.executeProjectedWithSingleFallbackGet(
            accountId = accountId,
            pageUrl = url(),
            resolveAction = { html, finalUrl, page ->
                val current = parser.parse(html, finalUrl, page)
                val actionId = if (request.action in RAID_ACTIONS) {
                    val id = request.raidId?.takeIf(String::isNotBlank) ?: invalid("레이드를 선택해 주세요.")
                    val raid = current.raids.singleOrNull { it.id == id }
                        ?: invalid("현재 해당 레이드를 확인할 수 없습니다.")
                    if (!canExecute(current, raid, request.action)) {
                        invalid("현재 해당 레이드에서 실행할 수 없는 동작입니다.")
                    }
                    raid.actionIds[request.action] ?: invalid("현재 해당 레이드에서 실행할 수 없는 동작입니다.")
                } else {
                    if (request.raidId != null) invalid("전체 레이드 동작에는 레이드 식별자가 필요하지 않습니다.")
                    current.globalActionIds[request.action]
                        ?: invalid("현재 실행할 수 없는 전투 정보실 동작입니다.")
                }
                TownActionRequest(actionId)
            },
            acceptsActionResponse = RaidPubSnapshot::observedRaidPubForm,
        ) { html, finalUrl, result, page -> parser.parse(html, finalUrl, page, result) }
        if (!projected.observedRaidPubForm) invalid("HOF 전투 정보실 양식을 확인하지 못했습니다.")
        return RaidPubResponse.from(withBattleAvailability(accountId, projected))
    }

    private fun loadRaw(accountId: Long): RaidPubSnapshot = executor.loadProjected(accountId, url()) { html, finalUrl, page ->
        val parsed = parser.parse(html, finalUrl, page)
        if (!parsed.observedRaidPubForm) invalid("HOF 전투 정보실 양식을 확인하지 못했습니다.")
        parsed
    }

    private fun withBattleAvailability(accountId: Long, snapshot: RaidPubSnapshot): RaidPubSnapshot {
        if (snapshot.raids.none { it.playable && it.joined }) return snapshot
        val available = battleMaps.findCurrentlyObservedMaps(accountId, "raid")
            .filter { it.enabled && it.resolved }
            .mapNotNull { it.mapCode }
            .toSet()
        return snapshot.copy(raids = snapshot.raids.map { raid ->
            raid.copy(battleTarget = if (raid.playable && raid.joined && raid.id in available) RaidBattleTarget(mapCode = raid.id) else null)
        })
    }

    private fun canExecute(snapshot: RaidPubSnapshot, raid: RaidPubRaid, action: RaidAction): Boolean {
        if (action !in raid.actions || !raid.playable) return false
        return when (action) {
            RaidAction.REGISTER -> !raid.joined && !snapshot.applyWait && raid.status !in REGISTER_BLOCKED_STATUSES
            RaidAction.LEAVE, RaidAction.START -> raid.joined
            RaidAction.RESET -> true
            else -> false
        }
    }

    private fun url() = locations.resolve(TownFeatureId.RAID_INFO).url
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
    private companion object {
        val RAID_ACTIONS = setOf(RaidAction.REGISTER, RaidAction.LEAVE, RaidAction.START, RaidAction.RESET)
        val REGISTER_BLOCKED_STATUSES = setOf(RaidStatus.IN_BATTLE, RaidStatus.COMPLETED, RaidStatus.CLOSED, RaidStatus.TESTING)
    }
}
