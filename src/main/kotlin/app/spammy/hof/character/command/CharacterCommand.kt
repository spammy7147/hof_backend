package app.spammy.hof.character.command

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(CharacterCommand.Rename::class, name = "RENAME"),
    JsonSubTypes.Type(CharacterCommand.Kick::class, name = "KICK"),
    JsonSubTypes.Type(CharacterCommand.Knockback::class, name = "KNOCKBACK"),
    JsonSubTypes.Type(CharacterCommand.Pray::class, name = "PRAY"),
    JsonSubTypes.Type(CharacterCommand.PrepareItems::class, name = "PREPARE_ITEMS"),
    JsonSubTypes.Type(CharacterCommand.UseItem::class, name = "USE_ITEM"),
    JsonSubTypes.Type(CharacterCommand.LearnSkill::class, name = "LEARN_SKILL"),
    JsonSubTypes.Type(CharacterCommand.AllocateStat::class, name = "ALLOCATE_STAT"),
    JsonSubTypes.Type(CharacterCommand.AllocateStats::class, name = "ALLOCATE_STATS"),
    JsonSubTypes.Type(CharacterCommand.ChangeClass::class, name = "CHANGE_CLASS"),
    JsonSubTypes.Type(CharacterCommand.EquipItem::class, name = "EQUIP_ITEM"),
    JsonSubTypes.Type(CharacterCommand.RemoveEquipment::class, name = "REMOVE_EQUIPMENT"),
    JsonSubTypes.Type(CharacterCommand.RemoveAllEquipment::class, name = "REMOVE_ALL_EQUIPMENT"),
    JsonSubTypes.Type(CharacterCommand.SaveEquipmentPreset::class, name = "SAVE_EQUIPMENT_PRESET"),
    JsonSubTypes.Type(CharacterCommand.LoadEquipmentPreset::class, name = "LOAD_EQUIPMENT_PRESET"),
)
sealed interface CharacterCommand {
    val characterId: Long
    val expectedRevision: Instant

    data class Rename(override val characterId: Long, override val expectedRevision: Instant, val newName: String) : CharacterCommand
    data class Kick(override val characterId: Long, override val expectedRevision: Instant, val confirmationName: String) : CharacterCommand
    data class Knockback(override val characterId: Long, override val expectedRevision: Instant, val confirmationName: String) : CharacterCommand
    data class Pray(override val characterId: Long, override val expectedRevision: Instant) : CharacterCommand
    data class PrepareItems(override val characterId: Long, override val expectedRevision: Instant) : CharacterCommand
    data class UseItem(override val characterId: Long, override val expectedRevision: Instant, val itemValue: String) : CharacterCommand
    data class LearnSkill(override val characterId: Long, override val expectedRevision: Instant, val skillValue: String) : CharacterCommand
    data class AllocateStat(
        override val characterId: Long,
        override val expectedRevision: Instant,
        val stat: CharacterStat,
        val amount: Int,
    ) : CharacterCommand
    data class AllocateStats(
        override val characterId: Long,
        override val expectedRevision: Instant,
        val amounts: Map<CharacterStat, Int>,
    ) : CharacterCommand
    data class ChangeClass(override val characterId: Long, override val expectedRevision: Instant, val classValue: String) : CharacterCommand
    data class EquipItem(override val characterId: Long, override val expectedRevision: Instant, val itemValue: String) : CharacterCommand
    data class RemoveEquipment(override val characterId: Long, override val expectedRevision: Instant, val equipmentPart: String) : CharacterCommand
    data class RemoveAllEquipment(override val characterId: Long, override val expectedRevision: Instant) : CharacterCommand
    data class SaveEquipmentPreset(override val characterId: Long, override val expectedRevision: Instant, val slotNumber: Int) : CharacterCommand
    data class LoadEquipmentPreset(override val characterId: Long, override val expectedRevision: Instant, val slotNumber: Int) : CharacterCommand
}

enum class CharacterStat { STR, INT, DEX, SPD, LUK }

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(CharacterCommandResult.Completed::class, name = "Completed"),
    JsonSubTypes.Type(CharacterCommandResult.Conflict::class, name = "Conflict"),
    JsonSubTypes.Type(CharacterCommandResult.PartiallyApplied::class, name = "PartiallyApplied"),
    JsonSubTypes.Type(CharacterCommandResult.RefreshRequired::class, name = "RefreshRequired"),
    JsonSubTypes.Type(CharacterCommandResult.IdentityResolutionRequired::class, name = "IdentityResolutionRequired"),
    JsonSubTypes.Type(CharacterCommandResult.Rejected::class, name = "Rejected"),
)
sealed interface CharacterCommandResult {
    data class Completed(val characterId: Long, val revision: Instant, val messages: List<String> = emptyList()) : CharacterCommandResult
    data class Conflict(val characterId: Long, val expectedRevision: Instant, val currentRevision: Instant) : CharacterCommandResult
    data class PartiallyApplied(
        val characterId: Long,
        val completedSteps: Int,
        val nextStep: String,
        val message: String,
    ) : CharacterCommandResult
    data class RefreshRequired(val characterId: Long, val message: String) : CharacterCommandResult
    data class IdentityResolutionRequired(
        val characterId: Long,
        val candidates: List<CharacterCommandIdentityCandidate>,
        val message: String,
    ) : CharacterCommandResult
    data class Rejected(val characterId: Long, val code: String, val message: String) : CharacterCommandResult
}

data class CharacterCommandIdentityCandidate(
    val hofCharacterId: String,
    val name: String,
    val job: String,
    val level: Int?,
    val matchingFields: Set<String> = emptySet(),
)

data class CharacterCommandContext(
    val accountId: Long,
    val characterId: Long,
    val hofCharacterId: String,
)

interface CharacterCommandRemote {
    fun <T> withSession(accountId: Long, operation: (CharacterCommandRemoteSession) -> T): T
}

fun interface CharacterCommandRemoteSession {
    fun execute(context: CharacterCommandContext, command: CharacterCommand): CharacterCommandObservation
}

sealed interface CharacterCommandObservation {
    data class Applied(val messages: List<String> = emptyList()) : CharacterCommandObservation
    data class RefreshRequired(val message: String) : CharacterCommandObservation
    data class IdentityResolutionRequired(
        val candidates: List<CharacterCommandIdentityCandidate>,
        val message: String,
    ) : CharacterCommandObservation
    data class Rejected(val code: String, val message: String) : CharacterCommandObservation
}

interface CharacterAutomationGate {
    fun <T> execute(accountId: Long, unavailable: () -> T, operation: () -> T): T
}
