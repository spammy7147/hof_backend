package app.spammy.hof.character.transfer

import app.spammy.hof.character.pattern.CharacterPatternSetting
import app.spammy.hof.external.model.HofEquipment

enum class CharacterTransferStepStatus { COMPLETED, FAILED, SKIPPED }
enum class CharacterTransferOutcome { COMPLETED, PARTIALLY_APPLIED, RECHECK_REQUIRED, PREVIEW_CHANGED }

data class CharacterTransferCurrentSettings(
    val pattern: CharacterPatternSetting,
    /** 장비를 가져오지 않은 작업은 이 영역을 최종 검증 대상으로 삼지 않는다. */
    val equipment: List<HofEquipment>? = null,
)

data class CharacterTransferStepResult(
    val stepId: String,
    val status: CharacterTransferStepStatus,
    val message: String = "",
)

data class CharacterTransferExecutionResult(
    val targetCharacterId: Long,
    val results: List<CharacterTransferStepResult>,
    val nextStepIndex: Int,
    /** null은 진행 중인 결과 또는 최종 관측을 지원하기 전의 작업 기록이다. */
    val outcome: CharacterTransferOutcome? = null,
    val currentSettings: CharacterTransferCurrentSettings? = null,
    val finalSettingsConfirmed: Boolean = false,
    val message: String? = null,
    /** 최초 변경 전에 확인한 내용이 달라졌을 때만 반환하는 재확인용 미리보기다. */
    val preview: CharacterTransferPreview? = null,
)

fun interface CharacterTransferTargetPort {
    /** source ID를 받지 않는 인터페이스로 원본 캐릭터 변경을 구조적으로 차단한다. */
    fun execute(targetCharacterId: Long, step: CharacterTransferStep): Result<Unit>
}

class CharacterTransferExecutor(private val target: CharacterTransferTargetPort) {
    fun execute(
        preview: CharacterTransferPreview,
        completedStepIds: Set<String> = emptySet(),
        onStepResult: (CharacterTransferStepResult) -> Unit = {},
    ): CharacterTransferExecutionResult {
        require(preview.executable) { "차단된 항목을 해결한 뒤 실행해 주세요." }
        val results = mutableListOf<CharacterTransferStepResult>()
        val failed = mutableSetOf<String>()
        preview.steps.forEach { step ->
            // 현재 설정은 다른 슬롯 생성·외부 변경으로 달라질 수 있다. 장비 묶음도
            // 해제부터 저장까지 다시 확인해야 중간 checkpoint의 일부 장비만 남지 않는다.
            val canReuseCompletion = step is CharacterTransferStep.AllocateStats ||
                step is CharacterTransferStep.LearnSkill || step is CharacterTransferStep.SavePatternSlot
            val result = if (step.id in completedStepIds && canReuseCompletion) {
                CharacterTransferStepResult(step.id, CharacterTransferStepStatus.COMPLETED, "이전 실행에서 완료됨")
            } else if (step.dependsOn.any { it in failed }) {
                CharacterTransferStepResult(step.id, CharacterTransferStepStatus.SKIPPED, "실패한 의존 항목 때문에 건너뛰었습니다.")
            } else {
                target.execute(preview.targetCharacterId, step).fold(
                    onSuccess = { CharacterTransferStepResult(step.id, CharacterTransferStepStatus.COMPLETED) },
                    onFailure = { error ->
                        failed += step.id
                        CharacterTransferStepResult(step.id, CharacterTransferStepStatus.FAILED, error.message.orEmpty())
                    },
                )
            }
            results += result
            onStepResult(result)
        }
        return CharacterTransferExecutionResult(
            preview.targetCharacterId,
            results,
            results.indexOfFirst { it.status != CharacterTransferStepStatus.COMPLETED }.let { if (it < 0) results.size else it },
        )
    }
}
