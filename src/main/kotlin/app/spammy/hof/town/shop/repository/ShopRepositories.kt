package app.spammy.hof.town.shop.repository

import app.spammy.hof.common.persistence.CommandRepository
import app.spammy.hof.town.shop.entity.ShopCatalogItemEntity
import app.spammy.hof.town.shop.entity.ShopCatalogItemId
import app.spammy.hof.town.shop.entity.TownGlobalJobLeaseEntity

interface ShopCatalogItemRepository : CommandRepository<ShopCatalogItemEntity, ShopCatalogItemId>
interface TownGlobalJobLeaseRepository : CommandRepository<TownGlobalJobLeaseEntity, String>
