package app.spammy.hof.status.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.external.parser.HofMainStatusParser
import app.spammy.hof.status.dto.HofObservedStatusResponse
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.status.repository.HofStatusSnapshotCommandRepository
import app.spammy.hof.status.repository.HofStatusSnapshotQueryRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 인증된 HOF HTML의 완전한 상태 영역만 계정별 최신 관측값으로 보존한다. */
@Service
class HofStatusSnapshotService(
    private val accounts: AccountQueryRepository,
    private val queries: HofStatusSnapshotQueryRepository,
    private val commands: HofStatusSnapshotCommandRepository,
    private val parser: HofMainStatusParser,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun observe(accountId: Long, html: String, observedAt: Instant): Boolean {
        val parsed = parser.parse(html)
        val funds = parsed.funds ?: return false
        val timeCurrent = parsed.timeCurrent ?: return false
        val timeMax = parsed.timeMax ?: return false
        if (
            parsed.playerName == UNKNOWN_VALUE ||
            parsed.work == UNKNOWN_VALUE ||
            parsed.auction == UNKNOWN_VALUE
        ) {
            return false
        }

        val account = accounts.findByIdForUpdate(accountId) ?: return false
        val existing = queries.findByAccountId(accountId)
        if (existing != null && existing.observedAt > observedAt) return false

        val snapshot = existing?.apply {
            playerName = parsed.playerName
            this.funds = funds
            this.timeCurrent = timeCurrent
            this.timeMax = timeMax
            work = parsed.work
            auction = parsed.auction
            this.observedAt = observedAt
        } ?: HofStatusSnapshotEntity(
            account = account,
            playerName = parsed.playerName,
            funds = funds,
            timeCurrent = timeCurrent,
            timeMax = timeMax,
            work = parsed.work,
            auction = parsed.auction,
            observedAt = observedAt,
        )
        commands.save(snapshot)
        return true
    }

    @Transactional(readOnly = true)
    fun findLatest(accountId: Long): HofObservedStatusResponse? =
        queries.findByAccountId(accountId)?.let(HofObservedStatusResponse::from)

    private companion object {
        const val UNKNOWN_VALUE = "Unknown"
    }
}
