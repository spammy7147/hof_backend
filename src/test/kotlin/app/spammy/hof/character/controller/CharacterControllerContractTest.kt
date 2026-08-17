package app.spammy.hof.character.controller

import app.spammy.hof.common.security.CurrentAccountId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping

class CharacterControllerContractTest {
    @Test
    fun `dedicated character endpoints expose stable records typed commands lifecycle and transfer`() {
        val routes = CharacterController::class.java.declaredMethods.flatMap { method ->
            method.getAnnotation(GetMapping::class.java)?.value?.map { "GET $it" }.orEmpty() +
                method.getAnnotation(PostMapping::class.java)?.value?.map { "POST $it" }.orEmpty()
        }.toSet()

        assertTrue("GET /records/{characterId}" in routes)
        assertTrue("POST /records/{characterId}/refresh" in routes)
        assertTrue("POST /records/{characterId}/deep-sync-jobs" in routes)
        assertTrue("GET /operation-jobs/{jobId}" in routes)
        assertTrue("POST /commands" in routes)
        assertTrue("POST /patterns/apply" in routes)
        assertTrue("POST /identity/link" in routes)
        assertTrue("POST /archive" in routes)
        assertTrue("POST /restore-jobs" in routes)
        assertTrue("POST /transfers/preview" in routes)
        assertTrue("POST /transfers/jobs" in routes)
        assertFalse(routes.any { "/management" in it || "/management/actions" in it })
    }

    @Test
    fun `every controller endpoint receives the authenticated account boundary`() {
        CharacterController::class.java.declaredMethods
            .filter { it.isAnnotationPresent(GetMapping::class.java) || it.isAnnotationPresent(PostMapping::class.java) }
            .forEach { method ->
                assertNotNull(
                    method.parameters.firstOrNull()?.getAnnotation(CurrentAccountId::class.java),
                    "${method.name} must receive @CurrentAccountId first",
                )
            }
    }
}
