package app.spammy.hof.automation.raid

import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service

sealed interface RaidBattlePreSubmitResult {
    data object Ready : RaidBattlePreSubmitResult
    data class Changed(val message: String) : RaidBattlePreSubmitResult
    data class Incomplete(val message: String) : RaidBattlePreSubmitResult
}

fun interface RaidBattlePreSubmitGuard {
    fun validate(
        accountId: Long,
        raidId: String,
        categoryId: String,
        mapCode: String,
    ): RaidBattlePreSubmitResult

    data object AllowAll : RaidBattlePreSubmitGuard {
        override fun validate(
            accountId: Long,
            raidId: String,
            categoryId: String,
            mapCode: String,
        ) = RaidBattlePreSubmitResult.Ready
    }
}

@Service
class DefaultRaidBattlePreSubmitGuard(
    private val store: RaidCycleStore,
    private val observations: RaidObservationReader,
    private val timeProvider: TimeProvider,
) : RaidBattlePreSubmitGuard {
    override fun validate(
        accountId: Long,
        raidId: String,
        categoryId: String,
        mapCode: String,
    ): RaidBattlePreSubmitResult {
        val cycle = store.load(accountId).openCycle
            ?: return RaidBattlePreSubmitResult.Changed("열린 레이드 사이클이 사라졌습니다.")
        if (cycle.raidId != raidId || cycle.status != RaidAutomationCycleStatus.IN_BATTLE) {
            return RaidBattlePreSubmitResult.Changed("레이드 사이클 단계가 선택 시점과 달라졌습니다.")
        }
        cycle.battleSafetyGate?.let { gate ->
            val detail = if (gate.held) "수동 확인 보류 상태입니다." else
                "${gate.notBefore} 뒤 최신 판단에서 해제되어야 합니다."
            return RaidBattlePreSubmitResult.Changed("레이드 전투 안전 게이트가 활성 상태입니다. $detail")
        }
        val observation = observations.read(accountId)
        if (!observation.fresh) {
            return RaidBattlePreSubmitResult.Incomplete("제출 직전 레이드 관측이 최신 상태가 아닙니다.")
        }
        val target = observation.raids.singleOrNull { it.id == raidId }
            ?: return RaidBattlePreSubmitResult.Changed("최신 레이드 공유 상태에서 대상이 사라졌습니다.")
        if (target.battleAvailability == RaidBattleAvailability.INCOMPLETE) {
            return RaidBattlePreSubmitResult.Incomplete("최신 레이드 전투 가능 창을 완전하게 관측하지 못했습니다.")
        }
        val battle = target.battle
        val runnable = target.joined && target.status == RaidObservedStatus.IN_BATTLE &&
            target.battleAvailability == RaidBattleAvailability.RUNNABLE &&
            battle?.categoryId == categoryId && battle.mapCode == mapCode
        return if (runnable) {
            RaidBattlePreSubmitResult.Ready
        } else {
            RaidBattlePreSubmitResult.Changed(
                "최신 레이드 공유 상태 또는 전투 대상이 선택 시점과 달라졌습니다 (${timeProvider.now()}).",
            )
        }
    }
}
