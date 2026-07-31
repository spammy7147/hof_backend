package app.spammy.hof.town.agency.parser

import app.spammy.hof.town.agency.model.RecruitmentGender
import app.spammy.hof.town.agency.model.RecruitmentJob
import app.spammy.hof.town.agency.model.RecruitmentSnapshot
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.model.TownSelectionType
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class RecruitmentPageParser {
    fun parse(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
    ): RecruitmentSnapshot {
        val document = Jsoup.parse(html, finalUrl)
        val domForms = document.select("form").filter(::isRecruitmentForm)
        val semanticForms = page.forms.filter(::isRecruitmentForm)
        val domForm = domForms.singleOrNull()
        val semanticForm = semanticForms.singleOrNull()
        val nameInput = domForm?.select("input")?.filter(::isObservedNameInput)?.singleOrNull()
        val radioGroups = semanticForm?.candidates.orEmpty()
            .filter { it.selectionType == TownSelectionType.RADIO }
            .groupBy { it.inputName }
        val genderGroup = radioGroups.entries.filter { (_, values) ->
            values.size == 2 && values.all { candidate -> genderMeaning(candidate.label) != null }
        }.singleOrNull()
        val jobGroup = radioGroups.entries.filter { (name, values) ->
            name != genderGroup?.key && values.isNotEmpty() && values.all { candidate ->
                price(candidate.label) != null && cleanJobName(candidate.label).isNotBlank()
            }
        }.singleOrNull()
        val jobs = jobGroup?.value.orEmpty().mapNotNull { candidate ->
            val price = price(candidate.label) ?: return@mapNotNull null
            val domRadio = domForm?.select("input[type=radio]")?.singleOrNull {
                it.attr("name") == candidate.inputName && it.attr("value").ifBlank { "on" } == candidate.inputValue
            } ?: return@mapNotNull null
            val owner = candidateOwner(domRadio)
            val image = owner.select("img[src]").singleOrNull()?.absUrl("src")?.takeIf(::safeImageUrl)
            RecruitmentJob(candidate.id, cleanJobName(candidate.label), price, image)
        }
        val genders = genderGroup?.value.orEmpty().mapNotNull { candidate ->
            genderMeaning(candidate.label)?.let { RecruitmentGender(candidate.id, it) }
        }
        val counts = parseCapacity(document.text())
        val available = domForm != null && semanticForm != null && nameInput != null && jobs.isNotEmpty() &&
            genders.size == 2 && jobs.size == jobGroup?.value?.size
        return RecruitmentSnapshot(
            currentCharacters = counts.first,
            capacity = counts.second,
            jobs = jobs,
            genders = genders,
            nameMinLength = 1,
            nameMaxLength = nameInput?.attr("maxlength")?.toIntOrNull()?.coerceIn(1, 16) ?: 16,
            recruitmentAvailable = available,
            actionId = semanticForm?.actionId?.takeIf { available },
            nameField = nameInput?.attr("name")?.takeIf { available },
            result = result,
        )
    }

    private fun isRecruitmentForm(element: Element): Boolean {
        val submits = element.select("input[type=submit],button[type=submit],button:not([type])")
        return submits.size == 1 && isRecruitSubmit(submits.single().attr("name"), submits.single().attr("value").ifBlank { submits.single().text() })
    }

    private fun isRecruitmentForm(form: ParsedTownForm): Boolean = form.submitFields.singleOrNull()?.let {
        isRecruitSubmit(it.name, it.value)
    } == true

    private fun isRecruitSubmit(name: String, value: String): Boolean =
        name.equals("Recruit", true) && RECRUIT_LABEL.matches(clean(value))

    private fun isObservedNameInput(input: Element): Boolean {
        val type = input.attr("type").lowercase()
        if (type !in setOf("", "text") || input.attr("name").isBlank() ||
            input.hasAttr("disabled") || input.hasAttr("readonly")
        ) return false
        val max = input.attr("maxlength").toIntOrNull() ?: return false
        if (max !in 1..16 || input.attr("style").contains("display:none", true)) return false
        val meaning = listOf(input.attr("name"), input.id(), input.attr("placeholder"), input.attr("title"),
            input.closest("label")?.text().orEmpty(), input.parent()?.text().orEmpty()).joinToString(" ")
        return NAME_MEANING.containsMatchIn(meaning)
    }

    private fun candidateOwner(radio: Element): Element = radio.closest("td") ?: radio.closest("label") ?: radio.parent() ?: radio
    private fun price(label: String): Long? = PRICE.find(label)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
    private fun cleanJobName(label: String): String = clean(label.replace(PRICE, " ").replace(GENDER_WORD, " "))
    private fun genderMeaning(label: String): String? = when {
        MALE.matches(clean(label)) -> "남성"
        FEMALE.matches(clean(label)) -> "여성"
        else -> null
    }
    private fun parseCapacity(text: String): Pair<Int?, Int?> {
        val current = CURRENT.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val maximum = MAXIMUM.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        return current to maximum
    }
    private fun safeImageUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val safePort = (uri.scheme == "http" && uri.port in setOf(-1, 80)) ||
            (uri.scheme == "https" && uri.port in setOf(-1, 443))
        safePort && uri.host.equals("sic.zerosic.com", true) &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.path.startsWith("/ZeroHOF/")
    }.getOrDefault(false)
    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        val RECRUIT_LABEL = Regex("(?:Recruit|모집|고용)", RegexOption.IGNORE_CASE)
        val NAME_MEANING = Regex("name|이름|성명", RegexOption.IGNORE_CASE)
        val PRICE = Regex("[$]\\s*([\\d,]+)")
        val MALE = Regex("(?:male|남성|남)", RegexOption.IGNORE_CASE)
        val FEMALE = Regex("(?:female|여성|여)", RegexOption.IGNORE_CASE)
        val GENDER_WORD = Regex("female|male|여성|남성", RegexOption.IGNORE_CASE)
        val CURRENT = Regex("(?:현재\\s*캐릭터|현재\\s*인원|Current(?:\\s*Characters?)?)\\s*[:：]?\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val MAXIMUM = Regex("(?:최대\\s*캐릭터|최대\\s*인원|Max(?:imum)?(?:\\s*Characters?)?)\\s*[:：]?\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
    }
}
