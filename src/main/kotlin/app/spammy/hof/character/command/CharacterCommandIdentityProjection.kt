package app.spammy.hof.character.command

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.character.repository.CharacterIdentityQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.service.CharacterService
import app.spammy.hof.status.repository.HofStatusSnapshotCommandRepository
import app.spammy.hof.status.repository.HofStatusSnapshotQueryRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 위험 명령의 권위 roster 반영과 안정 identity 변경을 한 트랜잭션으로 확정한다. */
@Service
class CharacterCommandIdentityProjection(
    private val accounts: AccountQueryRepository,
    private val characters: CharacterQueryRepository,
    private val identities: CharacterIdentityQueryRepository,
    private val characterService: CharacterService,
    private val lifecycle: CharacterLifecycleService,
    private val statusQueries: HofStatusSnapshotQueryRepository,
    private val statusCommands: HofStatusSnapshotCommandRepository,
) {
    @Transactional
    fun recordKick(
        accountId: Long,
        characterId: Long,
        expectedHofCharacterId: String,
        rosterIds: Set<String>,
        rosterObservedAt: Instant = Instant.EPOCH,
    ): CharacterCommandIdentityProjectionResult {
        if (!lockAccountAndAdvanceRosterWatermark(accountId, rosterObservedAt)) {
            return CharacterCommandIdentityProjectionResult.Conflict
        }
        val target = currentTarget(accountId, characterId, expectedHofCharacterId)
            ?: return CharacterCommandIdentityProjectionResult.Conflict
        characterService.deleteCharactersAbsentFromRoster(accountId, rosterIds)
        lifecycle.archive(accountId, target.id)
        return CharacterCommandIdentityProjectionResult.Applied
    }

    @Transactional
    fun recordObservedRoster(
        accountId: Long,
        characterId: Long,
        expectedHofCharacterId: String,
        rosterIds: Set<String>,
        rosterObservedAt: Instant = Instant.EPOCH,
    ): CharacterCommandIdentityProjectionResult {
        if (!lockAccountAndAdvanceRosterWatermark(accountId, rosterObservedAt)) {
            return CharacterCommandIdentityProjectionResult.Conflict
        }
        currentTarget(accountId, characterId, expectedHofCharacterId)
            ?: return CharacterCommandIdentityProjectionResult.Conflict
        characterService.deleteCharactersAbsentFromRoster(accountId, rosterIds)
        return CharacterCommandIdentityProjectionResult.Applied
    }

    @Transactional
    fun recordConfirmedKnockback(
        accountId: Long,
        characterId: Long,
        expectedHofCharacterId: String,
        replacementHofCharacterId: String,
        localHofIdsBefore: Set<String>,
        rosterIds: Set<String>,
        rosterObservedAt: Instant = Instant.EPOCH,
    ): CharacterCommandIdentityProjectionResult {
        if (!lockAccountAndAdvanceRosterWatermark(accountId, rosterObservedAt)) {
            return CharacterCommandIdentityProjectionResult.Conflict
        }
        val target = currentTarget(accountId, characterId, expectedHofCharacterId)
            ?: return CharacterCommandIdentityProjectionResult.Conflict
        val occupied = characters.findByAccountIdAndHofCharacterId(accountId, replacementHofCharacterId)
            ?.takeIf { it.id != target.id }
        val provisional = occupied?.takeIf { character ->
            replacementHofCharacterId !in localHofIdsBefore &&
                character.lifecycle == CharacterLifecycle.ACTIVE &&
                identities.findOpenHistoryEvidence(character.id)?.let { history ->
                    history.linkReason == CharacterHofIdLinkReason.INITIAL_SYNC &&
                        !history.validFrom.isAfter(rosterObservedAt)
                } == true
        }
        if (occupied != null && provisional == null) {
            characterService.deleteCharactersAbsentFromRoster(accountId, rosterIds)
            return CharacterCommandIdentityProjectionResult.Occupied
        }

        characterService.deleteCharactersAbsentFromRoster(accountId, rosterIds)
        lifecycle.linkObservedReplacement(
            accountId,
            target.id,
            replacementHofCharacterId,
            provisionalCharacterId = provisional?.id,
        )
        return CharacterCommandIdentityProjectionResult.Applied
    }

    @Transactional
    fun recordUnconfirmedIdentityAction(
        accountId: Long,
        characterId: Long,
        expectedHofCharacterId: String,
        rosterObservedAt: Instant,
    ): CharacterCommandIdentityProjectionResult {
        if (!lockAccountAndAdvanceRosterWatermark(accountId, rosterObservedAt)) {
            return CharacterCommandIdentityProjectionResult.Conflict
        }
        val target = currentTarget(accountId, characterId, expectedHofCharacterId)
            ?: return CharacterCommandIdentityProjectionResult.Conflict
        target.lifecycle = CharacterLifecycle.MISSING
        target.missingSince = target.missingSince ?: rosterObservedAt
        if (rosterObservedAt.isAfter(target.updatedAt)) target.updatedAt = rosterObservedAt
        return CharacterCommandIdentityProjectionResult.Applied
    }

    private fun currentTarget(accountId: Long, characterId: Long, expectedHofCharacterId: String) =
        characters.findByAccountIdAndIdForUpdate(accountId, characterId)
            ?.takeIf { it.hofCharacterId == expectedHofCharacterId && it.lifecycle != CharacterLifecycle.ARCHIVED }

    private fun lockAccountAndAdvanceRosterWatermark(accountId: Long, observedAt: Instant): Boolean {
        accounts.findByIdForUpdate(accountId) ?: return false
        statusQueries.findByAccountId(accountId)?.let { status ->
            val previous = status.characterRosterObservedAt
            if (previous != null && !observedAt.isAfter(previous)) return false
            status.characterRosterObservedAt = observedAt
            statusCommands.save(status)
        }
        return true
    }
}

enum class CharacterCommandIdentityProjectionResult {
    Applied,
    Conflict,
    Occupied,
}
