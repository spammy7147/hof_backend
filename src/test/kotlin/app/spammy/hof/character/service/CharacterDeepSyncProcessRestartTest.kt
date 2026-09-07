package app.spammy.hof.character.service

import app.spammy.hof.external.model.HofActionPatternRow
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.external.model.HofEquipment
import app.spammy.hof.external.model.HofPatternSlot
import app.spammy.hof.external.model.HofPositionGuard
import app.spammy.hof.external.parser.CharacterPageParseResult
import app.spammy.hof.external.parser.CharacterPageSection
import app.spammy.hof.external.parser.CharacterSectionParseResult
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 별도 JVM을 halt해 finally와 shutdown hook이 실행되지 않는 실제 프로세스 종료를 검증한다. */
class CharacterDeepSyncProcessRestartTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `process termination after a slot load keeps the first original on restart`() {
        assertEquals(71, runProcess("crash"))
        assertEquals("temporary", directory.resolve("hof-state").readText())
        assertFalse(directory.resolve("restored").toFile().exists(), "halt must bypass finally restoration")

        assertEquals(0, runProcess("resume"))

        assertEquals("original", directory.resolve("hof-state").readText())
    }

    private fun runProcess(mode: String): Int {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }
            .map { Path.of(it.toURI()).toString() }
            .toList()
            .plus(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .distinct().joinToString(java.io.File.pathSeparator)
        val output = directory.resolve("$mode.log").toFile()
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, DeepSyncCrashProcess::class.java.name, directory.toString(), mode,
        ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "child JVM did not terminate: ${output.readText()}")
            return process.exitValue().also { code ->
                assertTrue(code in setOf(0, 71), "child JVM failed: ${output.readText()}")
            }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

object DeepSyncCrashProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[0])
        val state = directory.resolve("hof-state")
        if (!Files.exists(state)) state.writeText("original")
        val remote = object : CharacterDeepSyncRemote {
            override fun captureCurrent() = page(state.readText())
            override fun loadSavedPattern(slotCode: String, beforeChange: CharacterSyncBeforeChange): CharacterPageParseResult {
                beforeChange(CharacterRestoreState.capture(captureCurrent()), CharacterSyncChange.LOAD_PATTERN)
                state.writeText("temporary")
                if (args[1] == "crash") Runtime.getRuntime().halt(71)
                return captureCurrent()
            }
            override fun loadEquipmentPreset(slotNumber: Int, beforeChange: CharacterSyncBeforeChange) = captureCurrent()
            override fun restoreCurrent(original: CharacterRestoreState, beforeChange: CharacterSyncBeforeChange): CharacterPageParseResult {
                beforeChange(CharacterRestoreState.capture(captureCurrent()), CharacterSyncChange.RESTORE_PATTERN)
                state.writeText(original.patterns.single().skill)
                directory.resolve("restored").writeText("yes")
                return captureCurrent()
            }
        }
        val checkpointFile = directory.resolve("checkpoint.json")
        val mapper = jacksonObjectMapper()
        val archive = object : CharacterDeepSyncStore {
            override fun loadCheckpoint(): CharacterDeepSyncCheckpoint? =
                if (Files.exists(checkpointFile)) mapper.readValue(checkpointFile.readText(), CharacterDeepSyncCheckpoint::class.java) else null
            override fun saveCheckpoint(checkpoint: CharacterDeepSyncCheckpoint) {
                checkpointFile.writeText(mapper.writeValueAsString(checkpoint))
            }
            override fun saveCurrent(snapshot: CharacterPageParseResult) = Unit
            override fun savePatternSlot(slotCode: String, snapshot: CharacterPageParseResult) = Unit
            override fun saveEquipmentPreset(slotNumber: Int, snapshot: CharacterPageParseResult) = Unit
        }
        CharacterDeepSyncOrchestrator().synchronize(remote, archive)
    }

    private fun page(skill: String) = CharacterPageParseResult(
        HofCharacter(
            id = "character-1", name = "fixture",
            actionPatterns = listOf(HofActionPatternRow(0, judge = "always", quantity = "0", skill = skill)),
            equipment = listOf(HofEquipment(slot = "weapon", part = "Weapon", name = "Sword")),
            positionGuard = HofPositionGuard(selectedPosition = "front", guardValue = "0"),
            patternSlots = listOf(HofPatternSlot("0", "fixture", canLoad = true)),
        ),
        CharacterPageSection.entries.associateWith { CharacterSectionParseResult.Success(1) },
    )
}
