package app.spammy.hof.town.shop.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Embeddable
data class ShopCatalogItemId(
    @Column(name = "shop_id", length = 20) var shopId: String = "",
    @Column(name = "item_key", length = 200) var itemKey: String = "",
) : Serializable

@Entity
@Table(name = "shop_catalog_item")
class ShopCatalogItemEntity(
    @EmbeddedId var id: ShopCatalogItemId = ShopCatalogItemId(),
    @Column(nullable = false, length = 300) var name: String = "",
    @Column(name = "item_type", length = 100) var itemType: String? = null,
    @Column(columnDefinition = "text") var description: String? = null,
    @Column(nullable = false) var price: Long = 0,
    @Column(nullable = false) var active: Boolean = true,
    @Column(name = "last_seen_at", nullable = false) var lastSeenAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "town_global_job_lease")
class TownGlobalJobLeaseEntity(
    @Id @Column(name = "job_key", length = 100) var jobKey: String = "",
    @Column(name = "lease_owner", length = 100) var leaseOwner: String? = null,
    @Column(name = "lease_until") var leaseUntil: Instant? = null,
    @Column(name = "last_attempt_at") var lastAttemptAt: Instant? = null,
    @Column(name = "last_success_at") var lastSuccessAt: Instant? = null,
)
