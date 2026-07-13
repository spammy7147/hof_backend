package app.spammy.hof.character.controller

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class CharacterControllerLegacyEndpointTest {
    @Test
    fun controllerDoesNotExposeBlockingCharacterSyncEndpoints() {
        val source = Path.of(
            "src/main/kotlin/app/spammy/hof/character/controller/CharacterController.kt",
        ).readText()

        assertFalse(source.contains("@PostMapping(\"/sync\")"))
        assertFalse(source.contains("@PostMapping(\"/sync-if-needed\")"))
    }
}
