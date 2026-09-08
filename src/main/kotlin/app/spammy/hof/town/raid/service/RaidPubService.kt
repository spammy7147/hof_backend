package app.spammy.hof.town.raid.service

import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.battle.service.CurrentBattleMapObservationStatus
import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.external.parser.RaidCooldownAssociationStatus
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofRequestOrigin
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
    private val cooldownEvidence: RaidCooldownEvidenceRecorder = NonPersistentRaidCooldownEvidenceRecorder,
) {
    fun load(accountId: Long): RaidPubResponse = load(accountId, HofRequestOrigin.INTERACTIVE)

    fun load(accountId: Long, origin: HofRequestOrigin): RaidPubResponse {
        val snapshot = loadRaw(accountId, origin)
        rememberAutomationTargets(accountId, snapshot)
        return RaidPubResponse.from(withBattleAvailability(accountId, snapshot, origin))
    }

    fun action(accountId: Long, request: RaidPubActionRequest): RaidPubResponse =
        action(accountId, request, reportPreconditionChange = false)

    /** 저장 행동을 실행할 때 최신 GET에서 사전조건이 사라졌음을 POST 전에 구분한다. */
    fun actionForAutomation(
        accountId: Long,
        request: RaidPubActionRequest,
        expectedRaidId: String?,
    ): RaidPubResponse = action(
        accountId = accountId,
        request = request,
        reportPreconditionChange = true,
        expectedRaidId = expectedRaidId,
        origin = HofRequestOrigin.AUTOMATION,
    )

    private fun action(
        accountId: Long,
        request: RaidPubActionRequest,
        reportPreconditionChange: Boolean,
        expectedRaidId: String? = null,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): RaidPubResponse {
        val projected = executor.executeProjectedWithSingleFallbackGet(
            accountId = accountId,
            pageUrl = url(),
            origin = origin,
            resolveAction = { html, finalUrl, page ->
                val current = parser.parse(html, finalUrl, page)
                val actionId = if (request.action in RAID_ACTIONS) {
                    val id = request.raidId?.takeIf(String::isNotBlank) ?: invalid("레이드를 선택해 주세요.")
                    val raid = current.raids.singleOrNull { it.id == id }
                        ?: unavailable(reportPreconditionChange, "현재 해당 레이드를 확인할 수 없습니다.")
                    if (!canExecute(current, raid, request.action)) {
                        unavailable(reportPreconditionChange, "현재 해당 레이드에서 실행할 수 없는 동작입니다.")
                    }
                    raid.actionIds[request.action]
                        ?: unavailable(reportPreconditionChange, "현재 해당 레이드에서 실행할 수 없는 동작입니다.")
                } else {
                    if (request.raidId != null) invalid("전체 레이드 동작에는 레이드 식별자가 필요하지 않습니다.")
                    if (request.action == RaidAction.REWARD) {
                        val expectedRaid = if (reportPreconditionChange) {
                            val id = expectedRaidId?.takeIf(String::isNotBlank)
                                ?: unavailable(true, "저장된 보상 행동의 대상 레이드를 확인할 수 없습니다.")
                            current.raids.singleOrNull { it.id == id }
                                ?: unavailable(reportPreconditionChange, "현재 대상 레이드를 확인할 수 없습니다.")
                        } else null
                        if (expectedRaid != null &&
                            (!expectedRaid.joined ||
                                expectedRaid.status != RaidStatus.COMPLETED ||
                                isRaidResetRequiredStatus(expectedRaid.statusText))
                        ) {
                            unavailable(reportPreconditionChange, "대상 레이드가 더 이상 보상 확인 단계가 아닙니다.")
                        }
                        if (current.raids.none {
                                it.status == RaidStatus.COMPLETED && !isRaidResetRequiredStatus(it.statusText)
                            }
                        ) {
                            unavailable(reportPreconditionChange, "현재 보상을 확인할 수 있는 완료 레이드가 없습니다.")
                        }
                    }
                    current.globalActionIds[request.action]
                        ?: unavailable(reportPreconditionChange, "현재 실행할 수 없는 전투 정보실 동작입니다.")
                }
                TownActionRequest(actionId)
            },
            acceptsActionResponse = { observed ->
                // 신청 복구의 갱신 근거는 직접 응답이어야 하므로 보충 GET으로 대체하지 않는다.
                if (origin == HofRequestOrigin.AUTOMATION && request.action == RaidAction.REFRESH && !observed.pageComplete) {
                    incompletePage()
                }
                observed.pageComplete
            },
        ) { html, finalUrl, result, page, directActionResponse ->
            parser.parse(html, finalUrl, page, result,
                rewardResponse = directActionResponse && request.action == RaidAction.REWARD)
        }
        if (!projected.pageComplete) incompletePage()
        rememberAutomationTargets(accountId, projected)
        return RaidPubResponse.from(withBattleAvailability(accountId, projected, origin))
    }

    private fun loadRaw(accountId: Long, origin: HofRequestOrigin): RaidPubSnapshot =
        executor.loadProjected(accountId, url(), origin) { html, finalUrl, page ->
        val parsed = parser.parse(html, finalUrl, page)
        if (!parsed.pageComplete) incompletePage()
        parsed
    }

    private fun incompletePage(): Nothing = throw ApiException(
        ErrorCode.HOF_REQUEST_FAILED,
        "HOF 전투 정보실의 완전한 응답을 확인하지 못했습니다.",
    )

    private fun withBattleAvailability(
        accountId: Long,
        snapshot: RaidPubSnapshot,
        origin: HofRequestOrigin,
    ): RaidPubSnapshot {
        if (snapshot.raids.none { it.playable && it.joined }) return snapshot
        val current = battleMaps.observeCurrentlyAvailableMaps(accountId, "raid", origin)
        val available = current.maps
            .filter { it.resolved && (it.enabled || it.cooldownRemainingSeconds?.let { seconds -> seconds > 0 } == true) }
            .filter { it.mapCode != null }
        val joined = snapshot.raids.filter { it.playable && it.joined }
        val evidenceCaseId = current.raidCooldown?.takeIf { it.incomplete }?.let { evidence ->
            cooldownEvidence.record(
                RaidCooldownEvidenceContext(
                    accountId = accountId,
                    actionKind = RaidCooldownEvidenceAction.RAID_BATTLE_WINDOW,
                    raidScope = joined.sortedBy { it.id }.take(MAX_EVIDENCE_SCOPE_RAIDS)
                        .joinToString(",") { it.id.take(MAX_EVIDENCE_RAID_ID_LENGTH) },
                    activeJoinedRaidCount = joined.size,
                ),
                evidence,
            )
        }
        return snapshot.copy(
            raids = snapshot.raids.map { raid ->
                if (!raid.playable || !raid.joined) return@map raid.copy(battleTarget = null)
                val byCode = available.singleOrNull { it.mapCode == raid.id }
                val raidName = BattleMapIdentityNormalizer.normalize(raid.name)
                val byName = available.filter { map ->
                    val mapName = BattleMapIdentityNormalizer.normalize(map.name)
                    mapName == raidName || mapName.endsWith(raidName) || raidName.endsWith(mapName)
                }.singleOrNull()
                val observed = byCode ?: byName ?: available.singleOrNull()?.takeIf { joined.size == 1 }
                raid.copy(battleTarget = observed?.mapCode?.let { mapCode ->
                    RaidBattleTarget(
                        mapCode = mapCode,
                        cooldownRemainingSeconds = observed.cooldownRemainingSeconds,
                        cooldownSource = if (
                            observed.cooldownRemainingSeconds?.let { it > 0 } == true &&
                            current.raidCooldown?.status == RaidCooldownAssociationStatus.HOF_DIRECT
                        ) {
                            RaidCooldownObservationSource.HOF_DIRECT
                        } else {
                            null
                        },
                    )
                })
            },
            battleObservationStatus = when (current.status) {
                CurrentBattleMapObservationStatus.OBSERVED -> RaidBattleObservationStatus.OBSERVED
                CurrentBattleMapObservationStatus.ABSENT -> RaidBattleObservationStatus.ABSENT
                CurrentBattleMapObservationStatus.INCOMPLETE -> RaidBattleObservationStatus.INCOMPLETE
            },
            battleObservationEvidence = current.raidCooldown?.takeIf { it.incomplete }?.let { evidence ->
                RaidBattleObservationEvidence(
                    caseId = evidenceCaseId ?: evidence.responseShapeFingerprint.take(16),
                    reasonCode = evidence.reasonCode,
                    candidateSeconds = evidence.candidateSeconds,
                    candidateCount = evidence.candidateCount,
                    mapCount = evidence.mapCount,
                    domFingerprint = evidence.domFingerprint,
                    responseShapeFingerprint = evidence.responseShapeFingerprint,
                )
            },
        )
    }

    private fun rememberAutomationTargets(accountId: Long, snapshot: RaidPubSnapshot) {
        battleMaps.rememberRaidTargets(
            accountId,
            snapshot.raids.filter(RaidPubRaid::playable).associate { it.id to it.name },
        )
    }

    private fun canExecute(snapshot: RaidPubSnapshot, raid: RaidPubRaid, action: RaidAction): Boolean {
        if (action !in raid.actions || !raid.playable) return false
        return when (action) {
            RaidAction.REGISTER -> !raid.joined && !snapshot.applyWait &&
                isRaidRegistrationAvailable(raid.status)
            RaidAction.LEAVE -> raid.joined
            RaidAction.START -> raid.joined && raid.status == RaidStatus.READY
            RaidAction.RESET -> raid.status == RaidStatus.COMPLETED && isRaidResetRequiredStatus(raid.statusText)
            else -> false
        }
    }

    private fun url() = locations.resolve(TownFeatureId.RAID_INFO).url
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
    private fun unavailable(reportPreconditionChange: Boolean, message: String): Nothing {
        if (reportPreconditionChange) throw RaidActionPreconditionChangedException(message)
        invalid(message)
    }
    private companion object {
        val RAID_ACTIONS = setOf(RaidAction.REGISTER, RaidAction.LEAVE, RaidAction.START, RaidAction.RESET)
        const val MAX_EVIDENCE_SCOPE_RAIDS = 5
        const val MAX_EVIDENCE_RAID_ID_LENGTH = 40
    }
}

class RaidActionPreconditionChangedException(message: String) : RuntimeException(message)
