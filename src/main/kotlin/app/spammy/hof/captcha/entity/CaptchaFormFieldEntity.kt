package app.spammy.hof.captcha.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * HOF 캡차 form의 name/value 한 쌍을 원본 input 순서와 함께 저장한다.
 *
 * [fieldOrder]는 HTML에 나타난 순서를 그대로 보존한다. 브라우저 form 제출 순서는 서버가 같은
 * 이름의 값을 해석하는 방식과 디버깅 재현성에 영향을 줄 수 있으므로, 조회 시에는 이 값과 [id]를
 * 차례로 정렬해 항상 같은 요청 Map을 만든다. 답안 input도 빈 [fieldValue]를 가진 일반 row로 저장하고,
 * 실제 제출 직전에만 challenge의 답안 필드 이름에 해당하는 값을 사용자 답안으로 덮어쓴다.
 *
 * challenge 하나에서 같은 [fieldName]은 하나만 허용한다. 이는 제출 자료를 name/value Map으로
 * 재구성한다는 도메인 규칙을 DB unique 제약과 일치시키며, challenge가 삭제될 때 DB FK cascade로
 * 이 row도 함께 정리된다.
 */
@Entity
@Table(
    name = "captcha_form_fields",
    uniqueConstraints = [UniqueConstraint(columnNames = ["challenge_id", "field_name"])],
)
class CaptchaFormFieldEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_id", nullable = false)
    var challenge: CaptchaChallengeEntity,

    @Column(name = "field_order", nullable = false)
    var fieldOrder: Int,

    @Column(name = "field_name", nullable = false, length = 255)
    var fieldName: String,

    @Column(name = "field_value", nullable = false, columnDefinition = "text")
    var fieldValue: String,
)
