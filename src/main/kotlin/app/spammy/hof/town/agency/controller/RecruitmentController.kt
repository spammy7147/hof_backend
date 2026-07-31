package app.spammy.hof.town.agency.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.agency.dto.RecruitCharacterRequest
import app.spammy.hof.town.agency.service.RecruitmentService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/town/agency/recruitment")
class RecruitmentController(
    private val service: RecruitmentService,
    private val recovery: HofSessionRecoveryService,
) {
    @GetMapping
    fun load(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.load(accountId) }

    @PostMapping
    fun recruit(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: RecruitCharacterRequest,
    ) = recovery.execute(accountId) { service.recruit(accountId, request) }
}
