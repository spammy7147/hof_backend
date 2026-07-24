package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.repository.AutomationWorkSessionCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionView
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class AutomationDueIndexTest {
    private val now = Instant.parse("2026-07-23T00:00:00Z")
    private val account = HofAccountEntity(7, "due-index", "encrypted", now)
    private val entry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
    private val session = AutomationWorkSessionEntity(
        id = 21,
        account = account,
        entry = entry,
        workType = AutomationWorkType.QUEST,
        targetKey = "quest-1",
        status = AutomationWorkStatus.WAITING_RESOURCE,
        configVersion = "config-v1",
        createdAt = now,
        updatedAt = now,
    )
    private val queries = Mockito.mock(AutomationWorkSessionQueryRepository::class.java)
    private val commands = Mockito.mock(AutomationWorkSessionCommandRepository::class.java)
    private val store = DatabaseAutomationDueStore(queries, commands)

    @Test
    fun `schedule persists authoritative next check time`() {
        val dueAt = now.plusSeconds(1_800)
        Mockito.`when`(queries.lockById(7, 21)).thenReturn(session)

        store.schedule(AutomationDueTarget(7, 21, AutomationWorkType.QUEST, "quest-1"), dueAt)

        assertEquals(dueAt, session.nextCheckAt)
        Mockito.verify(commands).save(session)
    }

    @Test
    fun `due query maps persistent sessions to targets`() {
        session.nextCheckAt = now
        Mockito.`when`(queries.findDue(now, 100)).thenReturn(
            listOf(
                AutomationWorkSessionView(
                    id = session.id,
                    accountId = account.id,
                    entryId = entry.id,
                    entryPriority = entry.priority,
                    workType = session.workType,
                    targetKey = session.targetKey,
                    status = session.status,
                    missionKey = null,
                    missionType = null,
                    observedCurrent = null,
                    observedRequired = null,
                    materialName = null,
                    nextCheckAt = now,
                ),
            ),
        )

        assertEquals(
            listOf(AutomationDueTarget(7, 21, AutomationWorkType.QUEST, "quest-1")),
            store.dueAtOrBefore(now),
        )
    }
}
