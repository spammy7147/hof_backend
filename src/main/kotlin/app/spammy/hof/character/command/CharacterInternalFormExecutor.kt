package app.spammy.hof.character.command

import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import org.springframework.stereotype.Component

/** 패턴과 깊은 동기화만 사용하는 내부 form 경계. 제품/API 명령에는 노출하지 않는다. */
@Component
class CharacterInternalFormExecutor(
    private val executor: TownAuthenticatedExecutor,
    private val requestFactory: HofRequestFactory,
) {
    fun execute(
        accountId: Long,
        hofCharacterId: String,
        resolve: (ParsedTownPage) -> TownActionRequest,
    ) {
        executor.executeProjected(
            accountId = accountId,
            pageUrl = requestFactory.characterPage(hofCharacterId).url,
            resolveAction = { _, _, page -> resolve(page).also { page.requireSafeInternalAction(it) } },
        ) { _, _, _, _ -> Unit }
    }

    fun executeIfPresent(
        accountId: Long,
        hofCharacterId: String,
        resolve: (ParsedTownPage) -> TownActionRequest?,
    ): Boolean = try {
        execute(accountId, hofCharacterId) { page -> resolve(page) ?: throw OptionalFormMissing() }
        true
    } catch (_: OptionalFormMissing) {
        false
    }

    private fun ParsedTownPage.requireSafeInternalAction(action: TownActionRequest) {
        val source = forms.singleOrNull { it.actionId == action.actionId }?.submitSource
            ?: error("최신 HOF 내부 form을 다시 확인하지 못했습니다.")
        check(source.lowercase() !in IDENTITY_ACTIONS) {
            "캐릭터 identity 명령은 내부 form 경계에서 실행할 수 없습니다."
        }
    }

    private class OptionalFormMissing : RuntimeException()

    private companion object {
        val IDENTITY_ACTIONS = setOf("knockback", "knockback2", "byebye", "byebye2", "kick")
    }
}
