package app.spammy.hof.town.common.entity

import app.spammy.hof.town.common.model.TownFeatureId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 모든 계정이 공유하는 발견형 마을 메뉴의 공개 위치다. action이나 계정별 form 값은 보존하지 않는다. */
@Entity
@Table(name = "town_feature_locations")
class TownFeatureLocationEntity(
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "feature_id", nullable = false, length = 50)
    val featureId: TownFeatureId,

    @Column(name = "href", nullable = false, length = 500)
    var href: String,

    @Column(name = "observed_at", nullable = false)
    var observedAt: Instant,
)
