package app.spammy.hof.common.persistence

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DB 조회는 QueryDSL 전용 repository, 저장은 command repository로 분리한다는 규칙을 고정한다.
 *
 * Spring Data의 상위 repository를 다시 상속하면 `findById`, `findAll` 같은 조회 API가 command
 * repository에도 조용히 노출된다. 이 테스트는 소스 수준에서 해당 회귀를 잡아 코드 리뷰에서 놓쳐도
 * 전체 테스트가 실패하도록 한다.
 */
class PersistenceArchitectureTest {
    /** 모든 repository에서 Spring Data JPA 조회 구현과 문자열 JPQL 사용을 금지한다. */
    @Test
    fun databaseReadsUseQueryDslOnly() {
        val violations = repositorySources()
            .filter { (_, source) ->
                FORBIDDEN_REPOSITORY_TYPES.any(source::contains) ||
                    source.contains("org.springframework.data.jpa.repository.Query") ||
                    source.contains("@Query(")
            }
            .map { (path, _) -> path.toString() }
            .sorted()

        assertEquals(emptyList(), violations)
    }

    /**
     * QueryDSL 구현 파일이 아닌 repository에는 CommandRepository가 허용한 쓰기 메서드만 둘 수 있다.
     *
     * 파일명 대신 `QueryRepository` 클래스 선언을 기준으로 제외하므로 여러 command interface를 한
     * 파일에 모아 둔 현재 구조도 빠짐없이 검사한다.
     */
    @Test
    fun commandRepositoriesExposeWriteMethodsOnly() {
        val violations = repositorySources()
            .filterNot { (path, source) ->
                path.name == "CommandRepository.kt" || QUERY_REPOSITORY_DECLARATION.containsMatchIn(source)
            }
            .flatMap { (path, source) ->
                FUNCTION_DECLARATION.findAll(source)
                    .map { match -> match.groupValues[1] }
                    .filterNot(ALLOWED_COMMAND_METHODS::contains)
                    .map { method -> "${path}:$method" }
                    .toList()
            }
            .sorted()

        assertEquals(emptyList(), violations)
    }

    /** repository 디렉터리의 Kotlin 소스를 경로와 본문 쌍으로 읽는다. */
    private fun repositorySources(): List<Pair<Path, String>> =
        Files.walk(SOURCE_ROOT).use { paths ->
            paths
                .filter { path ->
                    Files.isRegularFile(path) &&
                        path.toString().contains("/repository/") &&
                        path.name.endsWith(".kt")
                }
                .map { path -> path to Files.readString(path) }
                .toList()
        }

    private companion object {
        val SOURCE_ROOT: Path = Path.of("src/main/kotlin/app/spammy/hof")
        val FORBIDDEN_REPOSITORY_TYPES = listOf(
            "JpaRepository<",
            "CrudRepository<",
            "PagingAndSortingRepository<",
        )
        val QUERY_REPOSITORY_DECLARATION = Regex("class\\s+\\w+QueryRepository\\b")
        val FUNCTION_DECLARATION = Regex("fun\\s+(?:<[^>]+>\\s*)?(\\w+)\\s*\\(")
        val ALLOWED_COMMAND_METHODS = setOf("save", "saveAll", "delete", "deleteAll", "flush")
    }
}
