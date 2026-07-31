package app.spammy.hof.quest.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.service.QuestGatewayService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/quests")
class QuestController(
    private val recovery: HofSessionRecoveryService,
    private val gateway: QuestGatewayService,
) {
    @GetMapping
    fun findAll(@CurrentAccountId accountId: Long): List<QuestSnapshot> =
        recovery.execute(accountId) {
            gateway.load(accountId)
        }

    @PostMapping("/{actionNo}/accept")
    fun accept(@CurrentAccountId accountId: Long, @PathVariable actionNo: String): List<QuestSnapshot> =
        recovery.execute(accountId) { gateway.accept(accountId, actionNo) }

    @PostMapping("/{actionNo}/claim")
    fun claim(@CurrentAccountId accountId: Long, @PathVariable actionNo: String): List<QuestSnapshot> =
        recovery.execute(accountId) { gateway.claim(accountId, actionNo) }
}
