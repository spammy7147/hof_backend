package app.spammy.hof.quest.controller

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.common.security.CurrentAccountIdArgumentResolver
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.ServletWebRequest
import tools.jackson.module.kotlin.jacksonObjectMapper

class QuestControllerTest {
    private val gateway = Mockito.mock(QuestGatewayService::class.java)
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val controller = QuestController(
        recovery = HofSessionRecoveryService(accountService),
        gateway = gateway,
    )

    @Test
    fun authenticatedCurrentAccountLoadsStructuredQuestResponse() {
        Mockito.`when`(gateway.load(42L)).thenReturn(listOf(snapshot()))

        val response = controller.findAll(42L)

        assertEquals("0571", response.single().questId)
        Mockito.verify(gateway).load(42L)
        Mockito.verifyNoInteractions(accountService)
        val json = jacksonObjectMapper().writeValueAsString(response)
        assertContains(json, "\"section\":\"ACTIVE\"")
        assertContains(json, "\"type\":\"MONSTER_KILL\"")
        assertContains(json, "\"current\":12")
        assertContains(json, "\"required\":30")
    }

    @Test
    fun wrapsGatewayCallInSessionRecovery() {
        Mockito.`when`(gateway.load(42L))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .thenReturn(listOf(snapshot()))

        val response = controller.findAll(42L)

        assertEquals(QuestState.ACTIVE, response.single().state)
        Mockito.verify(accountService).reauthenticate(42L)
        Mockito.verify(gateway, Mockito.times(2)).load(42L)
    }

    @Test
    fun endpointRequiresJwtDerivedCurrentAccountId() {
        val method = QuestController::class.java.getDeclaredMethod("findAll", Long::class.javaPrimitiveType)
        val parameter = MethodParameter(method, 0)

        assertNotNull(parameter.getParameterAnnotation(CurrentAccountId::class.java))
        assertTrue(method.parameterAnnotations.single().none { it.annotationClass.simpleName == "RequestParam" })

        SecurityContextHolder.clearContext()
        val error = assertFailsWith<ApiException> {
            CurrentAccountIdArgumentResolver().resolveArgument(
                parameter,
                null,
                ServletWebRequest(MockHttpServletRequest()),
                null,
            )
        }
        assertEquals(ErrorCode.AUTH_TOKEN_INVALID, error.errorCode)
    }

    private fun snapshot() = QuestSnapshot(
        questId = "0571",
        name = "저택 서관 열쇠 수집",
        state = QuestState.ACTIVE,
        section = QuestSection.ACTIVE,
        sourceOrder = 0,
        missions = listOf(
            QuestMission(
                key = "0571:0",
                type = QuestMissionType.MONSTER_KILL,
                target = "Killer Maid",
                progress = QuestProgress(12, 30),
                completable = false,
            ),
        ),
        actionNo = null,
    )
}
