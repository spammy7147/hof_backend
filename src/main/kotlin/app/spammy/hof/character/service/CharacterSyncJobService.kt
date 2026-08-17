package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.dto.CharacterResponse
import app.spammy.hof.character.dto.CharacterSyncEventResponse
import app.spammy.hof.character.dto.CharacterSyncJobResponse
import app.spammy.hof.character.entity.CharacterSyncFailureEntity
import app.spammy.hof.character.entity.CharacterSyncJobEntity
import app.spammy.hof.character.entity.CharacterSyncJobStatus
import app.spammy.hof.character.repository.CharacterSyncFailureCommandRepository
import app.spammy.hof.character.repository.CharacterSyncJobQueryRepository
import app.spammy.hof.character.repository.CharacterSyncJobRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.CharacterRosterParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
/**
 * 캐릭터 동기화 job의 생성, 실행, 정규화된 실패 기록, SSE 이벤트 발행을 담당한다.
 */
class CharacterSyncJobService(
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val syncJobRepository: CharacterSyncJobRepository,
    private val syncFailureRepository: CharacterSyncFailureCommandRepository,
    private val syncJobQueryRepository: CharacterSyncJobQueryRepository,
    private val characterService: CharacterService,
    private val requestFactory: HofRequestFactory,
    private val gateway: AccountHofGateway,
    private val rosterParser: CharacterRosterParser,
    private val snapshotSynchronizer: CharacterSnapshotSynchronizer,
    private val eventService: CharacterSyncEventService,
    @Qualifier("characterSyncTaskExecutor")
    private val taskExecutor: TaskExecutor,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(CharacterSyncJobService::class.java)
    private val eventIds = AtomicLong(0)
    private val startingJobIds = ConcurrentHashMap.newKeySet<Long>()

    /**
     * 실패 행이 없는 대기 상태의 캐릭터 동기화 job을 먼저 생성한다.
     *
     * 실제 작업은 SSE 구독 여부와 무관하게 생성 직후 시작한다.
     */
    @Transactional
    fun startSyncJob(accountId: Long): CharacterSyncJobResponse {
        val account = accountQueryRepository.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val cookies = cookieQueryRepository.findValueMapByAccountId(accountId)
        if (cookies.isEmpty()) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }

        syncJobQueryRepository.findNewestActiveByAccountId(accountId)?.let { active ->
            startAfterCommit(accountId = accountId, jobId = active.id)
            return active.toResponse(
                characters = characterService.findAll(accountId),
                failedCharacterIds = syncJobQueryRepository.findFailuresByJobId(active.id).toFailedCharacterIds(),
            )
        }

        val job = syncJobRepository.save(
            CharacterSyncJobEntity(
                account = account,
                status = CharacterSyncJobStatus.PENDING,
                startedAt = timeProvider.now(),
            ),
        )
        log.info("Character sync job created accountId={} jobId={}", accountId, job.id)

        startAfterCommit(accountId = accountId, jobId = job.id)

        return job.toResponse(characters = emptyList(), failedCharacterIds = emptyList())
    }

    /** 현재 처리 중인 캐릭터까지 마친 뒤 다음 캐릭터 진입을 막는다. */
    @Transactional
    fun stopSyncJob(accountId: Long, jobId: Long): CharacterSyncJobResponse {
        val job = loadJob(accountId, jobId)
        if (job.status == CharacterSyncJobStatus.PENDING || job.status == CharacterSyncJobStatus.RUNNING) {
            job.stopRequested = true
            syncJobRepository.save(job)
        }
        return job.toResponse(
            characters = characterService.findAll(accountId),
            failedCharacterIds = syncJobQueryRepository.findFailuresByJobId(job.id).toFailedCharacterIds(),
        )
    }

    /** 중단된 동일 job의 체크포인트 다음 캐릭터부터 다시 시작한다. */
    @Transactional
    fun resumeSyncJob(accountId: Long, jobId: Long): CharacterSyncJobResponse {
        val job = loadJob(accountId, jobId)
        if (job.status != CharacterSyncJobStatus.STOPPED) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "중단된 캐릭터 동기화 작업만 재개할 수 있습니다.")
        }
        job.stopRequested = false
        job.status = CharacterSyncJobStatus.PENDING
        job.finishedAt = null
        job.currentHofCharacterId = null
        syncJobRepository.save(job)
        startAfterCommit(accountId, job.id)
        return job.toResponse(
            characters = characterService.findAll(accountId),
            failedCharacterIds = syncJobQueryRepository.findFailuresByJobId(job.id).toFailedCharacterIds(),
        )
    }

    /**
     * job과 실패 행을 QueryDSL로 읽고 현재 저장된 캐릭터 목록을 함께 반환한다.
     */
    fun findSyncJob(
        accountId: Long,
        jobId: Long,
    ): CharacterSyncJobResponse {
        val job = loadJob(accountId, jobId)
        return job.toResponse(
            characters = characterService.findAll(accountId),
            failedCharacterIds = syncJobQueryRepository.findFailuresByJobId(job.id).toFailedCharacterIds(),
        )
    }

    /**
     * SSE 연결은 실행을 시작하지 않고 현재 상태와 이후 이벤트만 관찰한다.
     */
    fun streamSyncJobEvents(
        accountId: Long,
        jobId: Long,
    ): SseEmitter {
        val job = loadJob(accountId, jobId)
        val failedCharacterIds = syncJobQueryRepository.findFailuresByJobId(job.id).toFailedCharacterIds()
        val initialEventType = when (job.status) {
            CharacterSyncJobStatus.COMPLETED -> "completed"
            CharacterSyncJobStatus.FAILED -> "failed"
            CharacterSyncJobStatus.STOPPED -> "stopped"
            else -> "started"
        }
        val emitter = eventService.connect(
            jobId = job.id,
            initialEvent = job.toEvent(
                eventType = initialEventType,
                character = null,
                failedCharacterIds = failedCharacterIds,
            ),
        )
        if (job.status in TERMINAL_STATUSES) {
            eventService.complete(job.id)
        }

        return emitter
    }

    private fun startPendingJob(
        accountId: Long,
        jobId: Long,
    ) {
        if (!startingJobIds.add(jobId)) return

        taskExecutor.execute {
            runCatching { runJob(jobId) }
                .onFailure {
                    log.warn("Character sync job failed accountId={} jobId={} error={}", accountId, jobId, it.message)
                    failJob(jobId, it.message ?: "캐릭터 동기화 작업이 실패했습니다.")
                }
                .also {
                    startingJobIds.remove(jobId)
                }
        }
    }

    private fun startAfterCommit(accountId: Long, jobId: Long) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            startPendingJob(accountId, jobId)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = startPendingJob(accountId, jobId)
            },
        )
    }

    /**
     * roster를 순차 처리하고 상세 실패는 순번 행으로 기록한 뒤 다음 캐릭터를 계속 처리한다.
     */
    private fun runJob(jobId: Long) {
        val job = syncJobQueryRepository.findById(jobId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터 동기화 작업을 찾지 못했습니다.")
        val accountId = job.account.id
        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val cookies = cookieQueryRepository.findValueMapByAccountId(accountId)
        if (cookies.isEmpty()) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }
        val failures = syncJobQueryRepository.findFailuresByJobId(job.id).toMutableList()

        job.status = CharacterSyncJobStatus.RUNNING
        syncJobRepository.save(job)
        eventService.publish(
            job.toEvent(
                eventType = "started",
                character = null,
                failedCharacterIds = failures.toFailedCharacterIds(),
            ),
        )

        val homeResponse = gateway.execute(account.id, requestFactory.home(), cookies)
        val roster = rosterParser.parse(homeResponse.body)
        val existingCharacters = characterService.findAll(accountId)
        if (roster.isEmpty() && existingCharacters.isNotEmpty()) {
            error("HOF 홈에서 캐릭터 명단을 확인하지 못했습니다.")
        }
        if (roster.isNotEmpty()) {
            characterService.deleteCharactersAbsentFromRoster(accountId, roster.mapTo(linkedSetOf()) { it.id })
        }
        job.rosterCount = roster.size
        syncJobRepository.save(job)
        eventService.publish(
            job.toEvent(
                eventType = "rosterParsed",
                character = null,
                failedCharacterIds = failures.toFailedCharacterIds(),
            ),
        )

        roster.withIndex()
            .drop(job.lastCompletedRosterIndex + 1)
            .forEach { (rosterIndex, rosterCharacter) ->
            job.currentHofCharacterId = rosterCharacter.id
            syncJobRepository.save(job)
            val character = runCatching {
                snapshotSynchronizer.synchronize(
                    account = account,
                    cookies = cookies,
                    rosterCharacter = rosterCharacter,
                )
            }.getOrElse {
                log.warn(
                    "Character sync job detail failed accountId={} jobId={} characterId={} error={}",
                    accountId,
                    jobId,
                    rosterCharacter.id,
                    it.message,
                )
                appendFailure(job, failures, rosterCharacter.id)
                characterService.findAll(accountId)
                    .singleOrNull { saved -> saved.hofCharacterId == rosterCharacter.id }
                    .also { savedCharacter ->
                        eventService.publish(
                            job.toEvent(
                                eventType = "characterFailed",
                            character = savedCharacter,
                            failedCharacterIds = failures.toFailedCharacterIds(),
                        ),
                    )
                }
            }

            if (rosterCharacter.id !in failures.toFailedCharacterIds()) {
                job.syncedCount += 1
                eventService.publish(
                    job.toEvent(
                        eventType = "characterSynced",
                        character = character,
                        failedCharacterIds = failures.toFailedCharacterIds(),
                    ),
                )
            }
            job.lastCompletedRosterIndex = rosterIndex
            job.currentHofCharacterId = null
            syncJobRepository.save(job)

            val control = syncJobQueryRepository.findById(job.id)
            if (control?.stopRequested == true) {
                job.stopRequested = true
                job.status = CharacterSyncJobStatus.STOPPED
                job.finishedAt = timeProvider.now()
                syncJobRepository.save(job)
                eventService.publish(
                    job.toEvent("stopped", character = null, failedCharacterIds = failures.toFailedCharacterIds()),
                )
                eventService.complete(job.id)
                return
            }
        }

        job.status = CharacterSyncJobStatus.COMPLETED
        job.finishedAt = timeProvider.now()
        syncJobRepository.save(job)
        eventService.publish(
            job.toEvent(
                eventType = "completed",
                character = null,
                failedCharacterIds = failures.toFailedCharacterIds(),
            ),
        )
        eventService.complete(job.id)
        log.info("Character sync job complete accountId={} jobId={} rosterCount={}", accountId, job.id, job.rosterCount)
    }

    private fun appendFailure(
        job: CharacterSyncJobEntity,
        failures: MutableList<CharacterSyncFailureEntity>,
        hofCharacterId: String,
    ) {
        if (failures.any { it.hofCharacterId == hofCharacterId }) return

        val persisted = syncJobQueryRepository.findFailureByJobIdAndHofCharacterId(job.id, hofCharacterId)
        if (persisted != null) {
            failures += persisted
            failures.sortWith(compareBy(CharacterSyncFailureEntity::failureOrder, CharacterSyncFailureEntity::id))
            return
        }

        val nextOrder = (failures.maxOfOrNull { it.failureOrder } ?: -1) + 1
        failures += syncFailureRepository.save(
            CharacterSyncFailureEntity(
                syncJob = job,
                failureOrder = nextOrder,
                hofCharacterId = hofCharacterId,
            ),
        )
    }

    /**
     * job을 실패 상태로 저장하고 현재까지의 실패 순서를 유지한 SSE 이벤트를 발행한다.
     */
    private fun failJob(
        jobId: Long,
        message: String,
    ) {
        val job = syncJobQueryRepository.findById(jobId) ?: return
        val failedCharacterIds = syncJobQueryRepository.findFailuresByJobId(job.id).toFailedCharacterIds()
        job.status = CharacterSyncJobStatus.FAILED
        job.message = message
        job.finishedAt = timeProvider.now()
        syncJobRepository.save(job)
        eventService.publish(
            job.toEvent(
                eventType = "failed",
                character = null,
                failedCharacterIds = failedCharacterIds,
            ),
        )
        eventService.complete(job.id)
    }

    private fun loadJob(
        accountId: Long,
        jobId: Long,
    ): CharacterSyncJobEntity =
        syncJobQueryRepository.findByAccountIdAndId(accountId, jobId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터 동기화 작업을 찾지 못했습니다.")


    private fun CharacterSyncJobEntity.toResponse(
        characters: List<CharacterResponse>,
        failedCharacterIds: List<String>,
    ): CharacterSyncJobResponse =
        CharacterSyncJobResponse(
            jobId = id,
            accountId = account.id,
            status = status.apiValue(),
            rosterCount = rosterCount,
            syncedCount = syncedCount,
            failedCharacterIds = failedCharacterIds,
            characters = characters,
            message = message,
            startedAt = startedAt,
            finishedAt = finishedAt,
            stopRequested = stopRequested,
            lastCompletedRosterIndex = lastCompletedRosterIndex,
            currentHofCharacterId = currentHofCharacterId,
        )

    private fun CharacterSyncJobEntity.toEvent(
        eventType: String,
        character: CharacterResponse?,
        failedCharacterIds: List<String>,
    ): CharacterSyncEventResponse =
        CharacterSyncEventResponse(
            eventId = eventIds.incrementAndGet(),
            eventType = eventType,
            jobId = id,
            accountId = account.id,
            status = status.apiValue(),
            rosterCount = rosterCount,
            syncedCount = syncedCount,
            failedCharacterIds = failedCharacterIds,
            character = character,
            message = message,
            emittedAt = timeProvider.now(),
            stopRequested = stopRequested,
            lastCompletedRosterIndex = lastCompletedRosterIndex,
            currentHofCharacterId = currentHofCharacterId,
        )

    private fun List<CharacterSyncFailureEntity>.toFailedCharacterIds(): List<String> =
        map { it.hofCharacterId }

    private fun CharacterSyncJobStatus.apiValue(): String =
        name.lowercase()

    private companion object {
        val TERMINAL_STATUSES = setOf(
            CharacterSyncJobStatus.COMPLETED,
            CharacterSyncJobStatus.FAILED,
            CharacterSyncJobStatus.STOPPED,
        )
    }
}
