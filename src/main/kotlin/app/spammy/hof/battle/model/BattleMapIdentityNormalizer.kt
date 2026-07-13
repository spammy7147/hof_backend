package app.spammy.hof.battle.model

import java.util.Locale

/**
 * HOF 맵 이름을 DB 별칭과 관측값 비교에 공통으로 쓸 수 있는 형태로 정규화한다.
 *
 * 공백은 하나로 합치고 대소문자를 구분하지 않는다. `aliases`는 전체 이름과 한글 부분,
 * 마지막 하이픈 뒤의 짧은 이름을 제공해 후속 시드 생성에서도 같은 규칙을 재사용하게 한다.
 */
object BattleMapIdentityNormalizer {
    private val whitespacePattern = Regex("""\s+""")
    private val koreanPattern = Regex("""[가-힣]""")

    /** nullable 원문을 trim·공백 축약·소문자화한 비교 key로 변환한다. */
    fun normalize(value: String?): String =
        collapseWhitespace(value).lowercase(Locale.ROOT)

    /** 전체 이름, 첫 한글 이후 이름과 마지막 하이픈 뒤 이름을 정규화한 alias 집합으로 만든다. */
    fun aliases(value: String): LinkedHashSet<String> =
        aliasValues(value)
            .mapTo(linkedSetOf(), ::normalize)

    /**
     * 화면에 표시할 원형을 유지한 alias 후보를 생성한다.
     * 정규화 결과가 같은 후보는 첫 표현만 남겨 seed의 unique 제약과 동일한 결과를 보장한다.
     */
    fun aliasValues(value: String): LinkedHashSet<String> {
        val fullName = collapseWhitespace(value)
        if (fullName.isBlank()) return linkedSetOf()

        val koreanStart = koreanPattern.find(fullName)?.range?.first
        val candidates = listOfNotNull(
            fullName,
            koreanStart?.let(fullName::substring)?.let(::collapseWhitespace),
            fullName.substringAfterLast('-', missingDelimiterValue = "").let(::collapseWhitespace),
        )
        val valuesByNormalizedAlias = linkedMapOf<String, String>()
        candidates.forEach { candidate ->
            normalize(candidate)
                .takeIf(String::isNotBlank)
                ?.let { normalized -> valuesByNormalizedAlias.putIfAbsent(normalized, candidate) }
        }
        return LinkedHashSet(valuesByNormalizedAlias.values)
    }

    private fun collapseWhitespace(value: String?): String =
        value.orEmpty().replace(whitespacePattern, " ").trim()
}
