package app.spammy.hof.automation.policy

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import org.springframework.stereotype.Component

enum class AutomationDecisionType {
    CLAIM_QUEST,
    ACCEPT_QUEST,
    RUN_BATTLE,
    WAITING_CONFIG,
    SLEEP,
}

data class AutomationMapCandidate(
    val mapCode: String,
    val mapName: String,
    val executionOrder: Int,
    val partyPresetId: Long? = null,
    val categoryId: String = "battle_map",
)

/** DB에 저장된 모듈 맵 설정 한 건이다. 계정별 가변 상태는 [AutomationMapState]에서 분리한다. */
data class ConfiguredAutomationMap(
    val categoryId: String,
    val mapCode: String,
    val mapName: String,
    val partyPresetId: Long?,
    val executionOrder: Int,
)

/** 한 모듈 안에서 사용자가 선택한 퀘스트와 해당 퀘스트 전투 맵 순서를 보존한다. */
data class ConfiguredAutomationQuest(
    val questCode: String,
    val executionOrder: Int,
    val maps: List<ConfiguredAutomationMap>,
)

/** 같은 유형도 ID별로 독립 평가하기 위한 정규화된 실행 모듈 스냅샷이다. */
data class AutomationModuleSnapshot(
    val id: Long,
    val type: AutomationModuleType,
    val priority: Int,
    val thresholdPercent: Int?,
    val maps: List<ConfiguredAutomationMap>,
    val quests: List<ConfiguredAutomationQuest>,
)

/** HOF 상태 응답 중 자동화 정책이 실제로 사용하는 Time 값만 노출한다. */
data class AutomationAccountStatus(
    val timeCurrent: Int,
    val timeMax: Int,
)

/** 정적 맵 설정과 결합할 계정별 가시성·키·쿨타임·일일 횟수 상태다. */
data class AutomationMapState(
    val categoryId: String,
    val mapCode: String,
    val mapName: String,
    val visible: Boolean,
    val enabled: Boolean,
    val cooldownUntil: Instant?,
    val winRemaining: Int?,
    val attemptRemaining: Int?,
    val availableCount: Int?,
    val keyCount: Int?,
)

/** 한 번의 의사결정에 필요한 최신 계정 상태와 우선순위 모듈 전체를 담는다. */
data class AutomationSnapshot(
    val accountId: Long,
    val modules: List<AutomationModuleSnapshot>,
    val accountStatus: AutomationAccountStatus,
    val questState: List<QuestSnapshot>,
    val mapStates: List<AutomationMapState>,
    val now: Instant,
)

data class AutomationDecision(
    val type: AutomationDecisionType,
    val moduleType: AutomationModuleType?,
    val moduleConfigId: Long? = null,
    val questId: String? = null,
    val actionNo: String? = null,
    val map: AutomationMapCandidate? = null,
    val keyQuestBattle: QuestDecision.Battle? = null,
    val message: String? = null,
    val nextRunAt: Instant? = null,
)

/**
 * 활성·준비 완료 모듈을 DB 우선순위대로 평가해 이번 run에서 실행할 행동 하나만 선택한다.
 *
 * 한 모듈이 현재 실행 불가능해도 다음 모듈을 계속 평가한다. 모두 실행 불가능하면 각 모듈이 알려 준
 * 재확인 시각 중 가장 이른 값을 사용하고, 재시도로 해결할 수 없는 설정 문제만 남았을 때 설정 대기로
 * 전환한다. 같은 유형 모듈끼리 맵·퀘스트·임계치를 절대 합치지 않는다.
 */
@Component
class AutomationDecisionPolicy(
    private val keyQuestPolicy: KeyQuestPolicy,
    private val adventureMapPolicy: AdventureMapPolicy,
    private val selectedQuestPolicy: SelectedQuestPolicy,
) {
    /** 저장 우선순위의 첫 실행 가능 action을 선택하거나 가장 이른 재확인 시각을 반환한다. */
    fun decide(snapshot: AutomationSnapshot): AutomationDecision {
        val nextAvailability = mutableListOf<Instant>()
        var firstBlocked: ModuleEvaluation.Blocked? = null

        snapshot.modules
            .sortedWith(compareBy<AutomationModuleSnapshot> { it.priority }.thenBy { it.id })
            .forEach { module ->
                when (val evaluated = evaluate(module, snapshot)) {
                    is ModuleEvaluation.Runnable -> return evaluated.decision
                    is ModuleEvaluation.Unavailable -> nextAvailability += evaluated.nextRunAt
                    is ModuleEvaluation.Blocked -> if (firstBlocked == null) firstBlocked = evaluated
                    ModuleEvaluation.Skipped -> Unit
                }
            }

        if (nextAvailability.isNotEmpty()) {
            return AutomationDecision(
                type = AutomationDecisionType.SLEEP,
                moduleType = null,
                moduleConfigId = null,
                nextRunAt = nextAvailability.minOrNull(),
            )
        }
        firstBlocked?.let { blocked ->
            return AutomationDecision(
                type = AutomationDecisionType.WAITING_CONFIG,
                moduleType = blocked.module.type,
                moduleConfigId = blocked.module.id,
                message = blocked.message,
            )
        }
        return AutomationDecision(
            type = AutomationDecisionType.SLEEP,
            moduleType = null,
            moduleConfigId = null,
            nextRunAt = snapshot.now.plusSeconds(DEFAULT_RECHECK_SECONDS),
        )
    }

    /** 유형별 정책에는 현재 평가 중인 단일 모듈 설정만 전달한다. */
    private fun evaluate(
        module: AutomationModuleSnapshot,
        snapshot: AutomationSnapshot,
    ): ModuleEvaluation = when (module.type) {
        AutomationModuleType.KEY_QUEST -> evaluateKeyQuest(module, snapshot)
        AutomationModuleType.TIME_BURN -> evaluateTime(module, snapshot)
        AutomationModuleType.COOLDOWN_ADVENTURE -> evaluateCooldown(module, snapshot)
        AutomationModuleType.DAILY_ADVENTURE -> evaluateDaily(module, snapshot)
        AutomationModuleType.OTHER_QUEST -> evaluateSelectedQuest(module, snapshot)
        AutomationModuleType.UNION,
        AutomationModuleType.NORMAL_MAP,
        -> ModuleEvaluation.Skipped
    }

    /** 현재 모듈에 저장된 퀘스트와 맵만으로 수락·보상·전투를 결정한다. */
    private fun evaluateKeyQuest(
        module: AutomationModuleSnapshot,
        snapshot: AutomationSnapshot,
    ): ModuleEvaluation {
        if (module.quests.isEmpty()) {
            return ModuleEvaluation.Blocked(module, "실행할 열쇠 퀘스트를 선택해 주세요.")
        }
        val stateByMap = snapshot.mapStateByKey()
        val configs = module.quests
            .sortedWith(compareBy<ConfiguredAutomationQuest> { it.executionOrder }.thenBy { it.questCode })
            .associate { quest ->
                quest.questCode to QuestExecutionConfig(
                    quest.maps.mapNotNull { configured ->
                        val state = stateByMap[configured.key()] ?: return@mapNotNull null
                        if (!state.visible || !state.enabled) return@mapNotNull null
                        if (quest.questCode == "0563" &&
                            configured.mapCode != EastMansionMapPolicy.CORRIDOR_CODE &&
                            (state.keyCount ?: 0) <= 0
                        ) {
                            return@mapNotNull null
                        }
                        KeyQuestMapCandidate(
                            mapCode = configured.mapCode,
                            mapName = state.mapName,
                            keyCount = state.keyCount,
                            executionOrder = configured.executionOrder,
                            partyPresetId = configured.partyPresetId,
                            categoryId = configured.categoryId,
                        )
                    },
                )
            }
        val configuredQuestIds = configs.keys
        val activeQuest = snapshot.questState.firstOrNull {
            it.questId in configuredQuestIds && it.state == QuestState.ACTIVE
        }
        if (activeQuest != null && configs[activeQuest.questId]?.maps.isNullOrEmpty()) {
            return ModuleEvaluation.Unavailable(snapshot.now.plusSeconds(DEFAULT_RECHECK_SECONDS))
        }
        return keyQuestPolicy.decide(snapshot.questState, configs)
            ?.toEvaluation(module)
            ?: ModuleEvaluation.Unavailable(snapshot.now.plusSeconds(DEFAULT_RECHECK_SECONDS))
    }

    /** 모듈별 Time 임계값 이상일 때 해당 인스턴스의 첫 사용 가능 맵을 선택한다. */
    private fun evaluateTime(
        module: AutomationModuleSnapshot,
        snapshot: AutomationSnapshot,
    ): ModuleEvaluation {
        val threshold = module.thresholdPercent
            ?: return ModuleEvaluation.Blocked(module, "Time 기준을 설정해 주세요.")
        val status = snapshot.accountStatus
        if (status.timeMax <= 0 || status.timeCurrent.toLong() * 100 < status.timeMax.toLong() * threshold) {
            return ModuleEvaluation.Unavailable(snapshot.now.plusSeconds(DEFAULT_RECHECK_SECONDS))
        }
        val stateByMap = snapshot.mapStateByKey()
        val selected = module.maps
            .asSequence()
            .sortedWith(compareBy<ConfiguredAutomationMap> { it.executionOrder }.thenBy { it.mapCode })
            .firstOrNull { configured ->
                stateByMap[configured.key()]?.let { it.visible && it.enabled } == true
            }
            ?: return ModuleEvaluation.Unavailable(snapshot.now.plusSeconds(DEFAULT_RECHECK_SECONDS))
        return ModuleEvaluation.Runnable(module.battleDecision(selected, stateByMap.getValue(selected.key()).mapName))
    }

    /** 현재 인스턴스의 맵 중 쿨타임이 끝난 맵을 선택하고, 없으면 가장 빠른 종료 시각을 반환한다. */
    private fun evaluateCooldown(
        module: AutomationModuleSnapshot,
        snapshot: AutomationSnapshot,
    ): ModuleEvaluation {
        val candidates = module.adventureCandidates(snapshot)
        val selected = adventureMapPolicy.selectCooldown(candidates, snapshot.now)
        if (selected != null) {
            val configured = module.maps.first { it.categoryId == selected.categoryId && it.mapCode == selected.mapCode }
            return ModuleEvaluation.Runnable(module.battleDecision(configured, selected.mapName))
        }
        val next = candidates.mapNotNull(AdventureCandidate::cooldownUntil)
            .filter { it.isAfter(snapshot.now) }
            .minOrNull()
            ?: snapshot.now.plusSeconds(DEFAULT_RECHECK_SECONDS)
        return ModuleEvaluation.Unavailable(next)
    }

    /** 현재 인스턴스의 맵 중 일일 도전·승리 제한이 남은 첫 맵을 선택한다. */
    private fun evaluateDaily(
        module: AutomationModuleSnapshot,
        snapshot: AutomationSnapshot,
    ): ModuleEvaluation {
        val candidates = module.adventureCandidates(snapshot)
        val selected = adventureMapPolicy.selectDaily(candidates)
        if (selected != null) {
            val configured = module.maps.first { it.categoryId == selected.categoryId && it.mapCode == selected.mapCode }
            return ModuleEvaluation.Runnable(module.battleDecision(configured, selected.mapName))
        }
        return ModuleEvaluation.Unavailable(snapshot.now.plusSeconds(DEFAULT_RECHECK_SECONDS))
    }

    /** OTHER_QUEST가 명시한 questCode 순서 안에서만 보상 또는 수락 결정을 만든다. */
    private fun evaluateSelectedQuest(
        module: AutomationModuleSnapshot,
        snapshot: AutomationSnapshot,
    ): ModuleEvaluation = selectedQuestPolicy.decide(
        snapshot.questState,
        module.quests.sortedBy { it.executionOrder }.map { it.questCode },
    )?.toEvaluation(module) ?: ModuleEvaluation.Unavailable(snapshot.now.plusSeconds(DEFAULT_RECHECK_SECONDS))

    /** 퀘스트 하위 정책의 결과에 현재 모듈 인스턴스 ID를 결합한다. */
    private fun QuestDecision.toEvaluation(module: AutomationModuleSnapshot): ModuleEvaluation = when (this) {
        is QuestDecision.Accept -> ModuleEvaluation.Runnable(
            AutomationDecision(
                AutomationDecisionType.ACCEPT_QUEST,
                module.type,
                module.id,
                questId = questId,
                actionNo = actionNo,
            ),
        )
        is QuestDecision.Claim -> ModuleEvaluation.Runnable(
            AutomationDecision(
                AutomationDecisionType.CLAIM_QUEST,
                module.type,
                module.id,
                questId = questId,
                actionNo = actionNo,
            ),
        )
        is QuestDecision.Battle -> ModuleEvaluation.Runnable(
            AutomationDecision(
                AutomationDecisionType.RUN_BATTLE,
                module.type,
                module.id,
                questId = questId,
                map = AutomationMapCandidate(
                    map.mapCode,
                    map.mapName,
                    map.executionOrder,
                    map.partyPresetId,
                    map.categoryId,
                ),
                keyQuestBattle = this,
            ),
        )
        is QuestDecision.WaitingConfig -> ModuleEvaluation.Blocked(module, message)
    }

    /** 선택된 저장 맵을 모듈 ID가 포함된 실행 decision으로 변환한다. */
    private fun AutomationModuleSnapshot.battleDecision(
        map: ConfiguredAutomationMap,
        observedName: String,
    ) = AutomationDecision(
        type = AutomationDecisionType.RUN_BATTLE,
        moduleType = type,
        moduleConfigId = id,
        map = AutomationMapCandidate(
            mapCode = map.mapCode,
            mapName = observedName,
            executionOrder = map.executionOrder,
            partyPresetId = map.partyPresetId,
            categoryId = map.categoryId,
        ),
    )

    /** 모듈의 정적 맵 설정과 같은 계정의 최신 가용 상태를 키로 결합한다. */
    private fun AutomationModuleSnapshot.adventureCandidates(snapshot: AutomationSnapshot): List<AdventureCandidate> {
        val stateByMap = snapshot.mapStateByKey()
        return maps.mapNotNull { configured ->
            val state = stateByMap[configured.key()] ?: return@mapNotNull null
            AdventureCandidate(
                categoryId = configured.categoryId,
                mapCode = configured.mapCode,
                mapName = state.mapName,
                executionOrder = configured.executionOrder,
                visible = state.visible,
                enabled = state.enabled,
                cooldownUntil = state.cooldownUntil,
                winRemaining = state.winRemaining,
                attemptRemaining = state.attemptRemaining,
                availableCount = state.availableCount,
            )
        }
    }

    private fun AutomationSnapshot.mapStateByKey(): Map<Pair<String, String>, AutomationMapState> =
        mapStates.associateBy { it.categoryId to it.mapCode }

    private fun ConfiguredAutomationMap.key(): Pair<String, String> = categoryId to mapCode

    private sealed interface ModuleEvaluation {
        data class Runnable(val decision: AutomationDecision) : ModuleEvaluation
        data class Unavailable(val nextRunAt: Instant) : ModuleEvaluation
        data class Blocked(val module: AutomationModuleSnapshot, val message: String) : ModuleEvaluation
        data object Skipped : ModuleEvaluation
    }

    private companion object {
        const val DEFAULT_RECHECK_SECONDS = 30L
    }
}
