package app.spammy.hof.automation.service

import app.spammy.hof.automation.port.AutomationWakeupPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 설정 트랜잭션이 커밋된 뒤 실행기 wake를 독립 트랜잭션 경계에서 전달한다.
 *
 * docker 프로필의 Kafka adapter는 호출 과정에서 outbox 행을 저장한다. after-commit 콜백이 종료된
 * 기존 트랜잭션 자원에 참여하면 outbox flush가 보장되지 않으므로, 이 public 프록시 메서드가 항상
 * 새 트랜잭션을 열어 adapter 호출과 outbox 저장을 하나의 독립된 commit으로 완료한다.
 */
@Service
class AutomationAfterCommitWakeupService(
    private val wakeupPort: AutomationWakeupPort,
) {
    /** 로컬 실행기와 Kafka outbox adapter 모두 동일한 새 트랜잭션 경계를 거쳐 깨운다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun wake(
        accountId: Long,
        reason: String,
    ) {
        wakeupPort.wake(accountId, reason)
    }
}
