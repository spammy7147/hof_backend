package app.spammy.hof.town.agency.dto

import app.spammy.hof.town.agency.model.RecruitmentSnapshot
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RecruitmentJobResponse(val id: String, val name: String, val price: Long, val imageUrl: String?)
data class RecruitmentGenderResponse(val id: String, val label: String)

data class RecruitmentResponse(
    val currentCharacters: Int?,
    val capacity: Int?,
    val jobs: List<RecruitmentJobResponse>,
    val genders: List<RecruitmentGenderResponse>,
    val nameMinLength: Int,
    val nameMaxLength: Int,
    val recruitmentAvailable: Boolean,
    val result: TownActionResultResponse?,
) {
    companion object {
        fun from(value: RecruitmentSnapshot) = RecruitmentResponse(
            currentCharacters = value.currentCharacters,
            capacity = value.capacity,
            jobs = value.jobs.map { RecruitmentJobResponse(it.id, it.name, it.price, it.imageUrl) },
            genders = value.genders.map { RecruitmentGenderResponse(it.id, it.label) },
            nameMinLength = value.nameMinLength,
            nameMaxLength = value.nameMaxLength,
            recruitmentAvailable = value.recruitmentAvailable,
            result = value.result?.let(TownActionResultResponse::from),
        )
    }
}

data class RecruitCharacterRequest(
    @field:NotBlank @field:Size(max = 256) val jobId: String,
    @field:Size(min = 1, max = 16) val name: String,
    @field:NotBlank @field:Size(max = 256) val genderId: String,
)
