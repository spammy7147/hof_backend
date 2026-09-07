package app.spammy.hof.character.identity

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.entity.CharacterHofIdHistoryEntity
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.repository.CharacterHofIdHistoryRepository
import app.spammy.hof.character.repository.CharacterIdentityQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** account mutation fence를 획득한 뒤에만 시작하는 짧은 캐릭터 identity transaction이다. */
@Service
class CharacterLifecycleTransaction(
    private val accounts: AccountQueryRepository,
    private val query: CharacterQueryRepository,
    private val identityQuery: CharacterIdentityQueryRepository,
    private val characters: CharacterRepository,
    private val histories: CharacterHofIdHistoryRepository,
    private val timeProvider: TimeProvider,
    private val jobs: CharacterOperationJobQueryRepository,
) {
    @Transactional
    fun link(
        accountId: Long,
        characterId: Long,
        newHofCharacterId: String,
        reason: CharacterHofIdLinkReason,
        userConfirmed: Boolean,
        provisionalCharacterId: Long?,
    ) {
        val character = owned(accountId, characterId)
        if (character.hofCharacterId == newHofCharacterId) {
            val now = timeProvider.now()
            character.lifecycle = CharacterLifecycle.ACTIVE
            character.missingSince = null
            character.archivedAt = null
            character.lastSeenAt = now
            character.updatedAt = now
            return
        }
        requireNoSync(accountId)
        query.findByAccountIdAndHofCharacterId(accountId, newHofCharacterId)
            ?.takeIf { it.id != character.id }
            ?.let { provisional ->
                require(userConfirmed || provisional.id == provisionalCharacterId) {
                    "이미 다른 캐릭터로 관측된 HOF ID는 사용자 확인 없이 연결할 수 없습니다."
                }
                characters.delete(provisional)
                characters.flush()
            }
        val now = timeProvider.now()
        identityQuery.findOpenHistory(character.id)?.close(now)
        histories.flush()
        character.hofCharacterId = newHofCharacterId
        character.lifecycle = CharacterLifecycle.ACTIVE
        character.missingSince = null
        character.archivedAt = null
        character.lastSeenAt = now
        character.updatedAt = now
        characters.save(character)
        histories.save(
            CharacterHofIdHistoryEntity(
                character = character,
                account = character.account,
                hofCharacterId = newHofCharacterId,
                validFrom = now,
                linkReason = reason,
                userConfirmed = userConfirmed,
            ),
        )
    }

    @Transactional
    fun archive(accountId: Long, characterId: Long) {
        val character = owned(accountId, characterId)
        requireNoSync(accountId)
        val now = timeProvider.now()
        character.lifecycle = CharacterLifecycle.ARCHIVED
        character.archivedAt = now
        character.missingSince = character.missingSince ?: now
        character.updatedAt = now
    }

    @Transactional
    fun restore(accountId: Long, characterId: Long) {
        val character = owned(accountId, characterId)
        requireNoSync(accountId)
        character.lifecycle = CharacterLifecycle.MISSING
        character.archivedAt = null
        character.missingSince = timeProvider.now()
        character.updatedAt = timeProvider.now()
    }

    @Transactional
    fun deletePermanently(accountId: Long, characterId: Long) {
        val character = owned(accountId, characterId)
        requireNoSync(accountId)
        require(character.lifecycle == CharacterLifecycle.ARCHIVED) { "보관된 캐릭터만 영구 삭제할 수 있습니다." }
        characters.delete(character)
    }

    private fun requireNoSync(accountId: Long) {
        if (jobs.findConflictingSync(accountId) != null) {
            throw ApiException(ErrorCode.CHARACTER_RECOVERY_REQUIRED, "진행 중이거나 복원이 필요한 캐릭터 작업을 먼저 확인해 주세요.")
        }
    }

    private fun owned(accountId: Long, characterId: Long) =
        accounts.findByIdForUpdate(accountId)?.let {
            query.findByAccountIdAndIdForUpdate(accountId, characterId)
        }
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터를 찾지 못했습니다.")
}
