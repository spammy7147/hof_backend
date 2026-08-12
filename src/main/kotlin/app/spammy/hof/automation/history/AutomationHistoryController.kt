package app.spammy.hof.automation.history

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.common.security.CurrentAccountId
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/api/automation/unified/history")
class AutomationHistoryController(private val journal: AutomationDecisionJournal) {
    @GetMapping
    fun page(
        @CurrentAccountId accountId: Long,
        @RequestParam(required = false) cursor: Long?, @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) type: AutomationType?, @RequestParam(required = false) kind: AutomationHistoryEventKind?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): AutomationHistoryPage = journal.page(accountId, AutomationHistoryQuery(cursor, limit, type, kind, from, to))
}
