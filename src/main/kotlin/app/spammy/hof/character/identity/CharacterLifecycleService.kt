package app.spammy.hof.character.identity

import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.town.common.service.AccountHofMutationFence
import org.springframework.stereotype.Service

/** 안정 캐릭터 행을 보존한 채 HOF ID 이력과 사라짐/보관 수명주기를 변경한다. */
@Service
class CharacterLifecycleService(
    private val mutationFence: AccountHofMutationFence,
    private val transactions: CharacterLifecycleTransaction,
) {
    fun link(
        accountId: Long,
        characterId: Long,
        newHofCharacterId: String,
        reason: CharacterHofIdLinkReason,
        userConfirmed: Boolean,
    ) = mutationFence.execute(accountId) {
        transactions.link(accountId, characterId, newHofCharacterId, reason, userConfirmed, null)
    }

    /** 같은 Knockback 관측에서 생긴 provisional 행만 제거하고 안정 캐릭터 기록에 새 ID를 잇는다. */
    fun linkObservedReplacement(
        accountId: Long,
        characterId: Long,
        newHofCharacterId: String,
        provisionalCharacterId: Long?,
    ) = mutationFence.execute(accountId) {
        transactions.link(
            accountId,
            characterId,
            newHofCharacterId,
            CharacterHofIdLinkReason.KNOCKBACK,
            userConfirmed = false,
            provisionalCharacterId = provisionalCharacterId,
        )
    }

    fun archive(accountId: Long, characterId: Long) = mutationFence.execute(accountId) {
        transactions.archive(accountId, characterId)
    }

    fun restore(accountId: Long, characterId: Long) = mutationFence.execute(accountId) {
        transactions.restore(accountId, characterId)
    }

    fun deletePermanently(accountId: Long, characterId: Long) = mutationFence.execute(accountId) {
        transactions.deletePermanently(accountId, characterId)
    }
}
