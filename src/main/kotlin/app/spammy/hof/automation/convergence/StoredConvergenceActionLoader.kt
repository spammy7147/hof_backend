package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.service.StoredTypedAutomationAction
import app.spammy.hof.automation.service.StoredTypedAutomationActionCodec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun interface StoredConvergenceActionLoader {
    fun load(accountId: Long, executionIdentity: String): StoredTypedAutomationAction?
}

@Service
class JpaStoredConvergenceActionLoader(
    private val queryRepository: TypedAutomationQueryRepository,
    private val codec: StoredTypedAutomationActionCodec,
) : StoredConvergenceActionLoader {
    @Transactional(readOnly = true)
    override fun load(accountId: Long, executionIdentity: String): StoredTypedAutomationAction? =
        queryRepository.findTypedActionByExecutionIdentity(accountId, executionIdentity)
            ?.let { codec.verifyPersisted(it, accountId) }
}
