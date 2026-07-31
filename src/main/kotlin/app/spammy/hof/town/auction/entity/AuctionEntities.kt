package app.spammy.hof.town.auction.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "auction_observation")
class AuctionObservationEntity(
    @Id @Column(name = "observation_key", length = 128) var observationKey: String = "",
    @Column(name = "listing_id", length = 120) var listingId: String? = null,
    @Column(name = "observation_kind", nullable = false, length = 20) var observationKind: String = "CURRENT",
    @Column(name = "item_key", nullable = false, length = 128) var itemKey: String = "",
    @Column(name = "item_name", nullable = false, length = 300) var itemName: String = "",
    @Column(name = "item_type", length = 100) var itemType: String? = null,
    @Column(nullable = false) var quantity: Int = 1,
    @Column(name = "total_price", nullable = false) var totalPrice: Long = 0,
    @Column(name = "unit_price", nullable = false) var unitPrice: Long = 0,
    @Column(name = "observed_at", nullable = false) var observedAt: Instant = Instant.EPOCH,
    @Column(name = "last_seen_at", nullable = false) var lastSeenAt: Instant = Instant.EPOCH,
)
