package app.spammy.hof.automation.service

import app.spammy.hof.battle.repository.AccountBattleMapStateCommandRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SharedBattleCooldownService(
    private val maps: BattleMapQueryRepository,
    private val mapCommands: BattleMapRepository,
    private val stateCommands: AccountBattleMapStateCommandRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun learnAndApply(
        accountId: Long,
        categoryId: String,
        mapCode: String,
        retryAt: Instant,
    ) {
        val current = requireNotNull(maps.findStateForExecution(accountId, categoryId, mapCode)) {
            "Cannot learn shared cooldown for an unresolved account map state."
        }
        if (!current.battleMap.sharesMinuteCooldown) {
            current.battleMap.sharesMinuteCooldown = true
            mapCommands.save(current.battleMap)
        }
        applyToKnownMembers(accountId, retryAt)
    }

    @Transactional
    fun applyAfterSuccessfulBattle(
        accountId: Long,
        categoryId: String,
        mapCode: String,
    ): Boolean {
        val current = maps.findStateForExecution(accountId, categoryId, mapCode) ?: return false
        if (!current.battleMap.sharesMinuteCooldown) return false
        applyToKnownMembers(accountId, timeProvider.now().plusSeconds(SHARED_COOLDOWN_SECONDS))
        return true
    }

    private fun applyToKnownMembers(accountId: Long, retryAt: Instant) {
        val changed = maps.findAllStatesForExecution(accountId)
            .filter { it.battleMap.sharesMinuteCooldown }
            .filter { state -> state.cooldownUntil == null || state.cooldownUntil!!.isBefore(retryAt) }
            .onEach { it.cooldownUntil = retryAt }
        if (changed.isNotEmpty()) stateCommands.saveAll(changed)
    }

    private companion object {
        const val SHARED_COOLDOWN_SECONDS = 60L
    }
}
