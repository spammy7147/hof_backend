package app.spammy.hof.town.crafting.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.crafting.dto.*
import app.spammy.hof.town.crafting.model.CraftingMode
import app.spammy.hof.town.crafting.service.CraftingService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/town/crafting")
class CraftingController(private val service: CraftingService, private val recovery: HofSessionRecoveryService) {
    @GetMapping("/workbase") fun workbase(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.load(accountId, CraftingMode.WORKBASE) }
    @PostMapping("/workbase") fun startWorkbase(@CurrentAccountId accountId: Long, @Valid @RequestBody request: WorkbaseStartRequest) = recovery.execute(accountId) { service.startWorkbase(accountId, request) }
    @PostMapping("/workbase/complete") fun completeWorkbase(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.completeWorkbase(accountId) }
    @GetMapping("/claris") fun claris(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.load(accountId, CraftingMode.CLARIS) }
    @PostMapping("/claris") fun craftClaris(@CurrentAccountId accountId: Long, @Valid @RequestBody request: ClarisCraftRequest) = recovery.execute(accountId) { service.craftClaris(accountId, request) }
    @GetMapping("/refine") fun refine(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.load(accountId, CraftingMode.REFINE) }
    @PostMapping("/refine") fun refine(@CurrentAccountId accountId: Long, @Valid @RequestBody request: RefineRequest) = recovery.execute(accountId) { service.refine(accountId, CraftingMode.REFINE, request) }
    @GetMapping("/create") fun create(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.load(accountId, CraftingMode.CREATE) }
    @PostMapping("/create") fun create(@CurrentAccountId accountId: Long, @Valid @RequestBody request: CreateCraftRequest) = recovery.execute(accountId) { service.create(accountId, request) }
    @GetMapping("/veteran") fun veteran(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.load(accountId, CraftingMode.VETERAN) }
    @PostMapping("/veteran") fun veteran(@CurrentAccountId accountId: Long, @Valid @RequestBody request: RefineRequest) = recovery.execute(accountId) { service.refine(accountId, CraftingMode.VETERAN, request) }
}
