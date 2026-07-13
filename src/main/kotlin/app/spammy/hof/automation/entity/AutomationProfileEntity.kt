package app.spammy.hof.automation.entity

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
 * 홈 화면에 사용자가 추가한 자동전투 카드의 이름, 모드, 활성 상태를 보관하는 부모 엔티티다.
 *
 * 실행할 맵과 파티 프리셋 조합은 [AutomationProfileMapEntity]가 별도 행으로 소유한다. 부모 컬렉션을
 * 매핑하지 않는 이유는 목록 조회에서 모든 프로필의 맵을 QueryDSL 한 번으로 읽고, 수정 트랜잭션에서
 * 기존 행 삭제와 flush, 새 행 삽입 순서를 command repository로 명시하기 위해서다.
 */
@Entity
@Table(name = "automation_profiles")
class AutomationProfileEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @Column(name = "name", nullable = false)
    var name: String,

    @Column(name = "mode", nullable = false)
    var mode: String,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
