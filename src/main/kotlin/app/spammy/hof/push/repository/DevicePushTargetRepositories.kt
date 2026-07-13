package app.spammy.hof.push.repository

import app.spammy.hof.common.persistence.CommandRepository
import app.spammy.hof.push.entity.DevicePushTargetEntity
import app.spammy.hof.push.entity.QDevicePushTargetEntity.devicePushTargetEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

interface DevicePushTargetRepository : CommandRepository<DevicePushTargetEntity, Long>

@Repository
class DevicePushTargetQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findOwnedByInstallation(
        accountId: Long,
        installationId: String,
    ): DevicePushTargetEntity? = queryFactory.selectFrom(devicePushTargetEntity)
        .where(
            devicePushTargetEntity.account.id.eq(accountId),
            devicePushTargetEntity.installationId.eq(installationId),
        )
        .fetchOne()

    fun findActiveByAccountId(accountId: Long): List<DevicePushTargetEntity> =
        queryFactory.selectFrom(devicePushTargetEntity)
            .where(
                devicePushTargetEntity.account.id.eq(accountId),
                devicePushTargetEntity.active.isTrue,
            )
            .orderBy(devicePushTargetEntity.lastSeenAt.desc(), devicePushTargetEntity.id.desc())
            .fetch()

    fun findOwnedById(accountId: Long, id: Long): DevicePushTargetEntity? =
        queryFactory.selectFrom(devicePushTargetEntity)
            .where(devicePushTargetEntity.account.id.eq(accountId), devicePushTargetEntity.id.eq(id))
            .fetchOne()
}
