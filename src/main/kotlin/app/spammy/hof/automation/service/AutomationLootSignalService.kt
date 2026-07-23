package app.spammy.hof.automation.service

import java.text.Normalizer
import java.util.Locale
import org.springframework.stereotype.Service

@Service
class AutomationLootSignalService {
    fun matches(requiredMaterial: String, lootName: String): Boolean =
        normalize(requiredMaterial) == normalize(lootName)

    fun matchesLootDisplay(requiredMaterial: String, lootDisplay: String): Boolean =
        normalize(requiredMaterial) == normalize(lootDisplay).replace(LOOT_QUANTITY_SUFFIX, "")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(WHITESPACE, " ")
        .lowercase(Locale.ROOT)

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val LOOT_QUANTITY_SUFFIX = Regex("\\s+x\\s*\\d+$", RegexOption.IGNORE_CASE)
    }
}
