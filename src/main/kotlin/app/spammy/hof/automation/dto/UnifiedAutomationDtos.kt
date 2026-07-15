package app.spammy.hof.automation.dto

import app.spammy.hof.automation.entity.AutomationModuleType
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 모듈이 실행할 맵과 해당 맵에 적용할 파티 프리셋을 지정한다.
 *
 * [categoryId]와 [mapCode]는 앱이 임의의 URL을 전달하지 못하도록 서버의 맵 카탈로그 식별자만 받는다.
 * [partyPresetId]는 설정을 단계적으로 저장할 수 있게 nullable이지만, 값이 없으면 모듈은 `ready=false`로
 * 응답되어 자동화 시작 대상으로 사용되지 않는다.
 */
data class AutomationModuleMapRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val categoryId: String,
    @field:NotBlank
    @field:Size(max = 100)
    val mapCode: String,
    val partyPresetId: Long?,
    @field:Min(0)
    val executionOrder: Int,
)

/** 퀘스트 코드 하나와 그 퀘스트를 수행할 맵 순서를 함께 전달한다. */
data class AutomationModuleQuestRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val questCode: String,
    @field:Min(0)
    val executionOrder: Int,
    @field:Valid
    @field:Size(max = 100)
    val maps: List<AutomationModuleMapRequest> = emptyList(),
)

/**
 * 사용자 구성형 자동화 모듈을 새로 만든다.
 *
 * 유형은 생성 시에만 받고 이후에는 변경할 수 없다. 유형별로 사용하지 않는 설정은 빈 목록 또는 null로
 * 전달하며, 서비스가 유형별 필수 조건과 참조 소유권을 다시 검증한다.
 */
data class CreateAutomationModuleRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val displayName: String,
    val moduleType: AutomationModuleType,
    val enabled: Boolean,
    @field:Min(1)
    @field:Max(100)
    val thresholdPercent: Int?,
    @field:Valid
    @field:Size(max = 100)
    val maps: List<AutomationModuleMapRequest> = emptyList(),
    @field:Valid
    @field:Size(max = 100)
    val quests: List<AutomationModuleQuestRequest> = emptyList(),
)

/** 기존 모듈의 이름, 활성 상태와 유형별 세부 설정을 교체한다. */
data class UpdateAutomationModuleRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val displayName: String,
    val enabled: Boolean,
    @field:Min(1)
    @field:Max(100)
    val thresholdPercent: Int?,
    @field:Valid
    @field:Size(max = 100)
    val maps: List<AutomationModuleMapRequest> = emptyList(),
    @field:Valid
    @field:Size(max = 100)
    val quests: List<AutomationModuleQuestRequest> = emptyList(),
)

/** 드래그가 끝난 시점의 전체 모듈 ID 순서를 저장한다. */
data class ReorderAutomationModulesRequest(
    @field:Size(max = 100)
    val moduleIds: List<Long>,
)

/** 서버가 검증하고 저장한 맵 설정을 실행 순서대로 반환한다. */
data class AutomationModuleMapResponse(
    val categoryId: String,
    val mapCode: String,
    val partyPresetId: Long?,
    val executionOrder: Int,
)

/** 서버가 검증하고 저장한 퀘스트와 퀘스트별 맵 설정을 반환한다. */
data class AutomationModuleQuestResponse(
    val questCode: String,
    val executionOrder: Int,
    val maps: List<AutomationModuleMapResponse>,
)

/**
 * 모듈 한 개의 영속 상태와 실행 가능 여부를 앱에 제공한다.
 *
 * [ready]는 저장 성공 여부가 아니라 현재 설정만으로 실제 행동을 만들 수 있는지를 뜻한다. [summary]는
 * 내부 코드나 예외 메시지를 노출하지 않고 목록에서 설정 상태를 바로 이해할 수 있는 문구다.
 */
data class AutomationModuleResponse(
    val id: Long,
    val displayName: String,
    val moduleType: AutomationModuleType,
    val enabled: Boolean,
    val priority: Int,
    val thresholdPercent: Int?,
    val maps: List<AutomationModuleMapResponse>,
    val quests: List<AutomationModuleQuestResponse>,
    val ready: Boolean,
    val summary: String,
)

/** 통합 자동화의 현재 작업 상태와 서버가 확정한 전체 모듈 순서를 함께 반환한다. */
data class UnifiedAutomationStatusResponse(
    val profileId: Long,
    val job: AutomationJobResponse?,
    val modules: List<AutomationModuleResponse>,
    val currentTitle: String?,
    val nextRunAt: String?,
)
