package app.spammy.hof.character.service

import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.identity.CharacterObservedIdentityService
import app.spammy.hof.character.repository.CharacterOperationJobCommandRepository
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import java.time.Instant
import org.mockito.Mockito

/** 원격·복구 단위 검사에서는 기존 완료 저장만 수행하고 실제 ID 연결은 Spring 통합 검사로 확인한다. */
fun characterOperationCompletion(queries: CharacterOperationJobQueryRepository, commands: CharacterOperationJobCommandRepository): CharacterObservedIdentityService =
    Mockito.mock(CharacterObservedIdentityService::class.java).also { completion ->
        Mockito.doAnswer { invocation ->
            queries.findById(invocation.getArgument(1))?.let { job ->
                job.status = CharacterOperationStatus.COMPLETED
                job.resultPayload = invocation.getArgument(2)
                job.updatedAt = invocation.getArgument<Instant>(3)
                job.finishedAt = job.updatedAt
                commands.save(job)
            }
            null
        }.`when`(completion).completeOperation(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(), Mockito.any(Instant::class.java) ?: Instant.EPOCH)
    }
