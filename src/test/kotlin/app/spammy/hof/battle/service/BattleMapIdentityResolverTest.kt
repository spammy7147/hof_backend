package app.spammy.hof.battle.service

import app.spammy.hof.battle.entity.BattleMapAliasEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.entity.BattleMapGroupEntity
import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.battle.repository.BattleMapAliasCommandRepository
import app.spammy.hof.battle.repository.BattleMapGroupCommandRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.external.model.HofBattleMap
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, BattleMapQueryRepository::class, BattleMapIdentityResolver::class)
class BattleMapIdentityResolverTest {
    @Autowired
    private lateinit var resolver: BattleMapIdentityResolver

    @Autowired
    private lateinit var groupRepository: BattleMapGroupCommandRepository

    @Autowired
    private lateinit var mapRepository: BattleMapRepository

    @Autowired
    private lateinit var aliasRepository: BattleMapAliasCommandRepository

    @Test
    fun followsDirectExactGroupCategoryAndSuffixPriorityWithoutGuessingAmbiguity() {
        val firstGroup = group("First", 1)
        val secondGroup = group("Second", 2)
        val direct = map("direct01", "Direct", firstGroup)
        val firstShared = map("shared01", "First Shared", firstGroup)
        val secondShared = map("shared02", "Second Shared", secondGroup)
        val categoryUnique = map("unique01", "Unique", firstGroup)
        val suffixInGroup = map("suffix01", "Suffix", secondGroup)
        val ambiguousOne = map("ambiguous01", "Ambiguous One", firstGroup)
        val ambiguousTwo = map("ambiguous02", "Ambiguous Two", firstGroup)
        saveAlias(firstShared, "공통")
        saveAlias(secondShared, "공통")
        saveAlias(categoryUnique, "유일")
        saveAlias(suffixInGroup, "Realm- 깊은 숲")
        saveAlias(ambiguousOne, "중복")
        saveAlias(ambiguousTwo, "중복")

        assertEquals(direct.id, resolver.resolve(observation(mapCode = "direct01", name = "아무 이름", groupName = null))?.id)
        assertNull(resolver.resolve(observation(mapCode = "future-direct", name = "Future", groupName = "First")))
        assertEquals(
            secondShared.id,
            resolver.resolve(observation(mapCode = null, name = "Common- 공통", groupName = "Second"))?.id,
        )
        assertEquals(
            categoryUnique.id,
            resolver.resolve(observation(mapCode = null, name = "Unique- 유일", groupName = null))?.id,
        )
        assertNull(resolver.resolve(observation(mapCode = null, name = "Unique- 유일", groupName = "Missing")))
        assertEquals(
            suffixInGroup.id,
            resolver.resolve(
                observation(mapCode = null, name = "Special Realm- Realm- 깊은 숲", groupName = "Second"),
            )?.id,
        )
        assertEquals(
            suffixInGroup.id,
            resolver.resolve(
                observation(mapCode = null, name = "Special Realm- Realm- 깊은 숲", groupName = null),
            )?.id,
        )
        assertNull(
            resolver.resolve(
                observation(mapCode = null, name = "Special Realm- Realm- 깊은 숲", groupName = "First"),
            ),
        )
        assertNull(resolver.resolve(observation(mapCode = null, name = "Ambiguous- 중복", groupName = "First")))
        assertNull(resolver.resolve(observation(mapCode = null, name = "Unknown", groupName = "First")))
    }

    @Test
    fun resolvesQuestTargetsAcrossCaseWhitespaceAndKoreanEnglishSeparatorsWithoutGuessing() {
        val group = group("Quest", 1)
        val frost = map("frost", "Frost Forest", group)
        saveAlias(frost, "Frost - 서리 숲")

        val resolved = assertIs<BattleMapAliasResolution.Resolved>(
            resolver.resolveAlias(CATEGORY, "  frost /  서리   숲 "),
        )
        assertEquals("frost", resolved.mapCode)
        val nameOnly = map("name-only", "Map Name Only", group)
        assertEquals(
            nameOnly.mapCode,
            assertIs<BattleMapAliasResolution.Resolved>(resolver.resolveAlias(CATEGORY, " map NAME only ")).mapCode,
        )
        assertIs<BattleMapAliasResolution.Missing>(resolver.resolveAlias(CATEGORY, "없는 맵"))

        val other = map("other-frost", "Other Frost", group)
        saveAlias(other, "FROST · 서리 숲")
        assertIs<BattleMapAliasResolution.Ambiguous>(resolver.resolveAlias(CATEGORY, "Frost-서리 숲"))
    }

    private fun group(
        name: String,
        order: Int,
    ): BattleMapGroupEntity =
        groupRepository.save(
            BattleMapGroupEntity(
                categoryId = CATEGORY,
                name = name,
                displayOrder = order,
            ),
        )

    private fun map(
        code: String,
        name: String,
        group: BattleMapGroupEntity,
    ): BattleMapEntity =
        mapRepository.save(
            BattleMapEntity(
                categoryId = CATEGORY,
                mapCode = code,
                group = group,
                name = name,
                normalizedName = BattleMapIdentityNormalizer.normalize(name),
                displayOrder = 0,
                enabled = true,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun saveAlias(
        map: BattleMapEntity,
        value: String,
    ) {
        aliasRepository.save(
            BattleMapAliasEntity(
                battleMap = map,
                alias = value,
                normalizedAlias = BattleMapIdentityNormalizer.normalize(value),
            ),
        )
        aliasRepository.flush()
    }

    private fun observation(
        mapCode: String?,
        name: String,
        groupName: String?,
    ): HofBattleMap =
        HofBattleMap(
            categoryId = CATEGORY,
            mapCode = mapCode,
            name = name,
            groupName = groupName,
            rawHref = "index.php?sp_hunt#",
        )

    private companion object {
        const val CATEGORY = "adventure_map"
        val NOW: Instant = Instant.parse("2026-07-08T00:00:00Z")
    }
}
