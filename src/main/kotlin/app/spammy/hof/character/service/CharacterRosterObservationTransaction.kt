package app.spammy.hof.character.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.status.repository.HofStatusSnapshotCommandRepository
import app.spammy.hof.status.repository.HofStatusSnapshotQueryRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** account mutation fence를 획득한 뒤 roster와 watermark를 함께 반영하는 짧은 transaction이다. */
@Service
class CharacterRosterObservationTransaction(
    private val accounts: AccountQueryRepository,
    private val characters: CharacterService,
    private val statusQueries: HofStatusSnapshotQueryRepository,
    private val statusCommands: HofStatusSnapshotCommandRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(accountId: Long, roster: List<HofCharacter>, observedAt: Instant): Boolean {
        val account = accounts.findByIdForUpdate(accountId) ?: return false
        val status = statusQueries.findByAccountId(accountId) ?: return false
        val previousObservation = status.characterRosterObservedAt
        if (previousObservation != null && !observedAt.isAfter(previousObservation)) return false

        characters.reconcileObservedRoster(account, roster, observedAt)
        status.characterRosterObservedAt = observedAt
        statusCommands.save(status)
        return true
    }
}
