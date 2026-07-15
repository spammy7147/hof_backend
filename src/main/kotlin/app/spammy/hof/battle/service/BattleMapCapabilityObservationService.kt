package app.spammy.hof.battle.service

import app.spammy.hof.battle.repository.AccountBattleMapStateCommandRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.external.parser.BattleMapParser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Persistence seam for an authenticated map-detail GET performed by a future coordinator.
 * It never performs or retries a battle request, and preserves prior state when the page has no matching form.
 */
@Service
class BattleMapCapabilityObservationService(
    private val parser: BattleMapParser,
    private val queryRepository: BattleMapQueryRepository,
    private val stateRepository: AccountBattleMapStateCommandRepository,
) {
    @Transactional
    fun observeAuthenticatedDetail(
        accountId: Long,
        categoryId: String,
        queryName: String,
        mapCode: String,
        html: String,
    ): Boolean? {
        val observed = parser.observeThreeBattleCapability(queryName, mapCode, html) ?: return null
        val state = queryRepository.findStateForExecution(accountId, categoryId, mapCode) ?: return null
        state.supportsThreeBattles = observed
        stateRepository.save(state)
        return observed
    }
}
