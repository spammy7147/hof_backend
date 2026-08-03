package app.spammy.hof.apprelease.repository

import app.spammy.hof.apprelease.entity.AppReleaseEntity
import app.spammy.hof.apprelease.entity.QAppReleaseEntity.appReleaseEntity
import app.spammy.hof.common.persistence.CommandRepository
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

interface AppReleaseRepository : CommandRepository<AppReleaseEntity, Long>

@Repository
class AppReleaseQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findLatest(platform: String): AppReleaseEntity? =
        queryFactory
            .selectFrom(appReleaseEntity)
            .where(appReleaseEntity.platform.eq(platform))
            .orderBy(appReleaseEntity.versionCode.desc())
            .fetchFirst()

    fun findByPlatformAndVersionCode(
        platform: String,
        versionCode: Long,
    ): AppReleaseEntity? =
        queryFactory
            .selectFrom(appReleaseEntity)
            .where(
                appReleaseEntity.platform.eq(platform),
                appReleaseEntity.versionCode.eq(versionCode),
            )
            .fetchOne()

    fun findByFileName(fileName: String): AppReleaseEntity? =
        queryFactory
            .selectFrom(appReleaseEntity)
            .where(appReleaseEntity.fileName.eq(fileName))
            .fetchOne()

    fun findAll(): List<AppReleaseEntity> = queryFactory.selectFrom(appReleaseEntity).fetch()
}
