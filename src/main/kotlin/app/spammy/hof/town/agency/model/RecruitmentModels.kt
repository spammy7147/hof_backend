package app.spammy.hof.town.agency.model

import app.spammy.hof.town.common.model.ParsedTownResult

data class RecruitmentJob(
    val id: String,
    val name: String,
    val price: Long,
    val imageUrl: String?,
)

data class RecruitmentGender(
    val id: String,
    val label: String,
)

data class RecruitmentSnapshot(
    val currentCharacters: Int?,
    val capacity: Int?,
    val jobs: List<RecruitmentJob>,
    val genders: List<RecruitmentGender>,
    val nameMinLength: Int,
    val nameMaxLength: Int,
    val recruitmentAvailable: Boolean,
    internal val actionId: String?,
    internal val nameField: String?,
    val result: ParsedTownResult? = null,
)
