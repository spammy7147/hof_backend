package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.dto.CharacterDetailResponse
import app.spammy.hof.character.dto.CharacterResponse
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.entity.CharacterSection
import app.spammy.hof.character.entity.CharacterSectionSyncStatus
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.external.model.HofEquipmentCandidate
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterPageParseResult
import app.spammy.hof.town.common.service.AccountHofMutationFence
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service

/** 캐릭터 페이지 조회 정책과 구역별 snapshot 저장을 한 경계로 묶는다. */
@Service
class CharacterSnapshotSynchronizer(
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val characterQueryRepository: CharacterQueryRepository,
    private val characterService: CharacterService,
    private val snapshotWriter: CharacterSnapshotWriter,
    private val requestFactory: HofRequestFactory,
    private val gateway: AccountHofGateway,
    private val detailParser: CharacterDetailParser,
    private val timeProvider: TimeProvider,
    private val mutationFence: AccountHofMutationFence,
) {
    fun refresh(
        accountId: Long,
        characterId: Long,
        sections: Set<CharacterSection> = NORMAL_SECTIONS,
    ): CharacterDetailResponse = mutationFence.execute(accountId) {
        val account = accountQueryRepository.findById(accountId)
            ?: error("HOF 계정을 찾지 못했습니다.")
        val character = characterQueryRepository.findByAccountIdAndId(accountId, characterId)
            ?: error("캐릭터를 찾지 못했습니다: $characterId")
        check(character.lifecycle == CharacterLifecycle.ACTIVE) { "현재 HOF에서 사용 중인 캐릭터가 아닙니다." }
        val hofCharacterId = character.hofCharacterId
        val cookies = cookieQueryRepository.findValueMapByAccountId(accountId)
        check(cookies.isNotEmpty()) { "저장된 HOF 로그인 쿠키가 없습니다." }
        synchronize(
            account = account,
            cookies = cookies,
            rosterCharacter = HofCharacter(
                id = hofCharacterId,
                name = character.name,
                level = character.level,
                job = character.job,
            ),
            force = true,
            sections = sections,
        )
        characterService.findDetailById(accountId, characterId)
    }

    fun writeParsed(
        accountId: Long,
        hofCharacterId: String,
        parsed: CharacterPageParseResult,
        sections: Set<CharacterSection> = NORMAL_SECTIONS,
    ): CharacterDetailResponse = mutationFence.execute(accountId) {
        val character = characterQueryRepository.findByAccountIdAndHofCharacterId(accountId, hofCharacterId)
            ?: error("캐릭터를 찾지 못했습니다: $hofCharacterId")
        snapshotWriter.write(character, parsed, timeProvider.now(), sections)
        characterService.findDetail(accountId, hofCharacterId)
    }

    fun writeEquipmentCandidateSubset(
        accountId: Long,
        hofCharacterId: String,
        observed: List<HofEquipmentCandidate>,
        typeCodes: Set<String>,
    ): CharacterDetailResponse = mutationFence.execute(accountId) {
        val character = characterQueryRepository.findByAccountIdAndHofCharacterId(accountId, hofCharacterId)
            ?: error("캐릭터를 찾지 못했습니다: $hofCharacterId")
        snapshotWriter.writeEquipmentCandidateSubset(character, observed, typeCodes, timeProvider.now())
        characterService.findDetail(accountId, hofCharacterId)
    }

    fun synchronize(
        account: HofAccountEntity,
        cookies: Map<String, String>,
        rosterCharacter: HofCharacter,
        force: Boolean = false,
        sections: Set<CharacterSection> = NORMAL_SECTIONS,
    ): CharacterResponse = mutationFence.execute(account.id) {
        val before = characterQueryRepository.findByAccountIdAndHofCharacterId(account.id, rosterCharacter.id)
        val wasMissing = before?.lifecycle == CharacterLifecycle.MISSING

        // roster 관측은 상세 성공 여부와 무관하게 ACTIVE/lastSeenAt을 먼저 복원한다.
        characterService.upsertCharacterSnapshot(account, rosterCharacter, HofCharacter(id = rosterCharacter.id))
        val character = characterQueryRepository.findByAccountIdAndHofCharacterId(account.id, rosterCharacter.id)
            ?: error("캐릭터 기본 snapshot을 저장하지 못했습니다: ${rosterCharacter.id}")

        if (!force && !wasMissing && isFresh(character.id, sections, timeProvider.now())) {
            return@execute currentResponse(account.id, rosterCharacter.id)
        }

        val response = gateway.execute(account.id, requestFactory.characterPage(rosterCharacter.id), cookies)
        val parsed = detailParser.parsePage(rosterCharacter.id, response.body)
        snapshotWriter.write(character, parsed, timeProvider.now(), sections)
        currentResponse(account.id, rosterCharacter.id)
    }

    fun isFresh(
        characterId: Long,
        sections: Set<CharacterSection> = NORMAL_SECTIONS,
        now: Instant = timeProvider.now(),
    ): Boolean {
        if (sections.isEmpty()) return false
        val cutoff = now.minus(FRESHNESS)
        val states = characterQueryRepository.findSectionStates(characterId).associateBy { it.section }
        return sections.all { section ->
            val state = states[section]
            state?.status == CharacterSectionSyncStatus.SUCCESS &&
                state.lastSucceededAt?.isBefore(cutoff) == false
        }
    }

    private fun currentResponse(accountId: Long, hofCharacterId: String): CharacterResponse =
        characterService.findAll(accountId).single { it.hofCharacterId == hofCharacterId }

    companion object {
        val FRESHNESS: Duration = Duration.ofMinutes(30)
        val NORMAL_SECTIONS: Set<CharacterSection> = CharacterSection.entries
            .filterNotTo(linkedSetOf()) { it == CharacterSection.MANAGEMENT }
    }
}

/** 깊은 동기화가 HOF에서 수행해야 하는 상태 전환. Task 6의 typed 명령 실행기가 이 port를 구현한다. */
interface CharacterDeepSyncRemote {
    fun captureCurrent(): CharacterPageParseResult
    fun loadSavedPattern(slotCode: String): CharacterPageParseResult
    fun loadEquipmentPreset(slotNumber: Int): CharacterPageParseResult
    fun restoreCurrent(original: CharacterPageParseResult): CharacterPageParseResult
}

/** 깊은 동기화에서 관측한 현재/저장 설정을 영속화하는 port. */
interface CharacterDeepSyncStore {
    fun saveCurrent(snapshot: CharacterPageParseResult)
    fun savePatternSlot(slotCode: String, snapshot: CharacterPageParseResult)
    fun saveEquipmentPreset(slotNumber: Int, snapshot: CharacterPageParseResult)
}

data class CharacterDeepSyncProgress(
    val phase: CharacterDeepSyncPhase,
    val completedSteps: Int,
    val totalSteps: Int,
    val patternSlotCode: String? = null,
    val equipmentSlotNumber: Int? = null,
)

enum class CharacterDeepSyncPhase { CURRENT, SAVED_PATTERN, EQUIPMENT_PRESET, RESTORE, COMPLETED }

/**
 * 원격에는 transaction이 없으므로 항상 원본 상태를 먼저 보관하고, 모든 관측 후 finally에서 복원을 시도한다.
 * load 응답을 곧바로 저장해 9개 슬롯이면 정확히 9회의 load/capture가 일어난다.
 */
class CharacterDeepSyncOrchestrator {
    fun synchronize(
        remote: CharacterDeepSyncRemote,
        store: CharacterDeepSyncStore,
        report: (CharacterDeepSyncProgress) -> Unit = {},
    ) {
        val original = remote.captureCurrent()
        val patternSlots = original.snapshot.patternSlots.filter { it.canLoad }
        val equipmentSlots = listOf(1, 2)
        val total = 1 + patternSlots.size + equipmentSlots.size + 1
        var completed = 0

        store.saveCurrent(original)
        report(CharacterDeepSyncProgress(CharacterDeepSyncPhase.CURRENT, ++completed, total))
        try {
            patternSlots.forEach { slot ->
                store.savePatternSlot(slot.slot, remote.loadSavedPattern(slot.slot))
                report(
                    CharacterDeepSyncProgress(
                        CharacterDeepSyncPhase.SAVED_PATTERN,
                        ++completed,
                        total,
                        patternSlotCode = slot.slot,
                    ),
                )
            }
            equipmentSlots.forEach { slot ->
                store.saveEquipmentPreset(slot, remote.loadEquipmentPreset(slot))
                report(
                    CharacterDeepSyncProgress(
                        CharacterDeepSyncPhase.EQUIPMENT_PRESET,
                        ++completed,
                        total,
                        equipmentSlotNumber = slot,
                    ),
                )
            }
        } finally {
            store.saveCurrent(remote.restoreCurrent(original))
            report(CharacterDeepSyncProgress(CharacterDeepSyncPhase.RESTORE, ++completed, total))
        }
        report(CharacterDeepSyncProgress(CharacterDeepSyncPhase.COMPLETED, completed, total))
    }
}
