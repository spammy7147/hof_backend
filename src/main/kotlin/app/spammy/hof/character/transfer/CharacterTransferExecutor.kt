package app.spammy.hof.character.transfer

enum class CharacterTransferStepStatus { COMPLETED, FAILED, SKIPPED }

data class CharacterTransferStepResult(
    val stepId: String,
    val status: CharacterTransferStepStatus,
    val message: String = "",
)

data class CharacterTransferExecutionResult(
    val targetCharacterId: Long,
    val results: List<CharacterTransferStepResult>,
    val nextStepIndex: Int,
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
            val result = if (step.id in completedStepIds && step !is CharacterTransferStep.ApplyCurrentPattern) {
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
