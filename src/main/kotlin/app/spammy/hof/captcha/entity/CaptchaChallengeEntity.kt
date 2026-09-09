package app.spammy.hof.captcha.entity

import app.spammy.hof.account.entity.HofAccountEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * 캡차 발생 시점의 이미지 경로와 제출 URL 같은 단일 값을 저장하는 JPA 엔티티다.
 *
 * 제출 form의 반복 name/value는 [CaptchaFormFieldEntity] row로 분리한다. challenge row와 field row는
 * [app.spammy.hof.captcha.service.CaptchaService.detectAndRecord]의 `REQUIRES_NEW` 트랜잭션에서 함께
 * 기록되므로, 호출한 전투 트랜잭션이 이후 실패하더라도 사용자가 풀어야 할 캡차 상태는 독립적으로 남는다.
 */
@Entity
@Table(name = "captcha_challenges")
class CaptchaChallengeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @Column(name = "status", nullable = false)
    var status: String,

    @Column(name = "prompt", nullable = false, columnDefinition = "text")
    var prompt: String,

    /** 사용자 안내문과 독립적으로 통행증 challenge를 식별하는 영속 분류다. */
    @Column(name = "challenge_kind", nullable = false, length = 20)
    var challengeKind: String = KIND_CAPTCHA,

    @Column(name = "image_url", columnDefinition = "text")
    var imageUrl: String?,

    @Column(name = "source_url", nullable = false, columnDefinition = "text")
    var sourceUrl: String,

    @Column(name = "answer", columnDefinition = "text")
    var answer: String?,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "answered_at")
    var answeredAt: Instant?,

    @Column(name = "submit_url", columnDefinition = "text")
    var submitUrl: String? = null,

    @Column(name = "submit_method", nullable = false, length = 10)
    var submitMethod: String = "POST",

    @Column(name = "answer_field_name", nullable = false, length = 100)
    var answerFieldName: String = "captcha",

    @Column(name = "preparation_version", nullable = false)
    var preparationVersion: Int = 0,

    /** 답안 commit과 함께 남기고 관문 해제·후속 판단 예약 transaction에서 소비한다. */
    @Column(name = "automation_resume_pending", nullable = false)
    var automationResumePending: Boolean = false,
) {
    companion object {
        const val KIND_CAPTCHA = "CAPTCHA"
        const val KIND_VIGILANTE_PASS = "VIGILANTE_PASS"
    }
}
