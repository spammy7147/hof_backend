package app.spammy.hof.common.persistence

import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.Repository

@NoRepositoryBean
/**
 * Spring Data 구현체의 쓰기 기능만 노출하는 공통 repository 계약이다.
 * 조회 메서드는 의도적으로 제공하지 않으며 모든 read는 기능별 QueryDSL repository가 담당한다.
 */
interface CommandRepository<T : Any, ID : Any> : Repository<T, ID> {
    /** 신규 entity를 저장하거나 managed entity 변경을 flush 대상으로 등록한다. */
    fun <S : T> save(entity: S): S

    /** 동일 aggregate에서 검증이 끝난 여러 entity를 일괄 저장한다. */
    fun <S : T> saveAll(entities: Iterable<S>): List<S>

    /** QueryDSL로 조회한 단일 managed entity를 삭제한다. */
    fun delete(entity: T)

    /** 교체 저장 전에 QueryDSL로 읽은 기존 자식 row들을 삭제한다. */
    fun deleteAll(entities: Iterable<T>)

    /** unique 제약이 있는 자식 row 교체처럼 SQL 순서를 확정해야 할 때 즉시 반영한다. */
    fun flush()
}
