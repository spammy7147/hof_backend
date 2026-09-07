package app.spammy.hof.captcha.service

import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.net.URI

/** HTML input 한 개의 제출 순서와 name/value를 직렬화 없이 전달한다. */
data class CaptchaFormField(
    val fieldOrder: Int,
    val fieldName: String,
    val fieldValue: String,
)

/** HOF 캡차 form을 다시 제출하는 데 필요한 HTML snapshot이다. */
data class CaptchaChallengeMetadata(
    val prompt: String,
    val imageUrl: String?,
    val sourceUrl: String,
    val submitUrl: String?,
    val submitMethod: String,
    val answerFieldName: String,
    val formFields: List<CaptchaFormField>,
    val imageCookies: Map<String, String>? = null,
)

/** `#menu`에 표시되는 자경단 통행증 상태다. */
data class VigilantePassState(
    val required: Boolean,
    val remainingSeconds: Int?,
)

/**
 * HOF HTML에서 캡차·자경단 신호와 제출 form metadata를 추출하는 순수 parser다.
 *
 * 네트워크 호출과 DB 저장을 포함하지 않으므로 원본 HTML fixture만으로 검증할 수 있다.
 * HOF는 일반 캡차 form과 `simple-php-captcha` 기반 경찰서 form의 field 이름이 달라
 * 기본 답안 field와 fallback submit field를 호출자가 지정할 수 있게 한다.
 */
@Component
class CaptchaChallengeParser {
    /** 본문 키워드, 빨간 자경단 표시, input 이름, 이미지 src 중 하나라도 있으면 gate로 판단한다. */
    fun hasCaptchaSignal(
        element: Element,
        pageText: String,
    ): Boolean =
        CAPTCHA_SIGNAL.containsMatchIn(pageText) ||
            pageText.contains(VIGILANTE_PASS_PROMPT) ||
            parseVigilantePassState(element).required ||
            hasRedVigilanteSignal(element) ||
            findCaptchaNamedInput(element) != null ||
            findChallengeImage(element) != null

    /**
     * 게임 내부 메뉴인 `#menu`만 대상으로 빨간 `통행증`·`자경단`과 인증 유효시간 `H:MM:SS`를 읽는다.
     * 남은 시간은 인증 시각으로 계산하지 않고, 호출자가 전달한 매 HTML 응답의 표시값을 그대로 사용한다.
     * 계정 정보 영역 `#menu2`나 본문의 같은 문자열은 통행증 상태에 영향을 주지 않는다.
     */
    fun parseVigilantePassState(document: Element): VigilantePassState {
        val menu = document.selectFirst("#menu")
            ?: return VigilantePassState(required = false, remainingSeconds = null)
        val required = menu.getAllElements().any { element ->
            element.ownText().contains(PASS_LABEL) &&
                (hasRedCue(element) || element.parents().any(::hasRedCue))
        } || hasRedVigilanteSignal(menu)
        val remainingSeconds = PASS_REMAINING.find(menu.text())?.destructured?.let { (hours, minutes, seconds) ->
            hours.toIntOrNull()?.let { hour -> hour * 3_600 + minutes.toInt() * 60 + seconds.toInt() }
        }
        return VigilantePassState(required = required, remainingSeconds = remainingSeconds)
    }

    /**
     * 현재 문서에서 가장 가능성이 높은 form을 골라 이미지, action, method와 input snapshot을 만든다.
     *
     * form action과 이미지 src는 jsoup의 base URI를 이용해 절대 URL로 변환한다. 답안 input이
     * 명시되지 않은 오래된 HOF 화면도 제출할 수 있도록 [defaultAnswerField]를 마지막 fallback으로 쓴다.
     */
    fun extractDocumentMetadata(
        document: Element,
        pageText: String,
        sourceUrl: String,
        defaultAnswerField: String = DEFAULT_ANSWER_FIELD,
        fallbackImageUrl: String? = null,
        fallbackSubmitField: Pair<String, String>? = null,
    ): CaptchaChallengeMetadata {
        val challengeForm = selectChallengeForm(document)
        val answerScope = challengeForm ?: document
        val answerFieldName = findAnswerInput(answerScope)
            ?.attr("name")
            ?.trim()
            ?.ifBlank { null }
            ?: defaultAnswerField
        val imageUrl = (challengeForm?.let(::findChallengeImage) ?: findChallengeImage(document))
            ?.absUrl("src")
            ?.ifBlank { null }
            ?: fallbackImageUrl

        return CaptchaChallengeMetadata(
            prompt = extractPrompt(document, pageText),
            imageUrl = imageUrl,
            sourceUrl = sourceUrl,
            submitUrl = extractSubmitUrl(challengeForm, sourceUrl),
            submitMethod = extractSubmitMethod(challengeForm),
            answerFieldName = answerFieldName,
            formFields = buildFormFields(challengeForm, answerFieldName, fallbackSubmitField),
        )
    }

    /** 자경단 통행증 문구 또는 빨간 자경단 메뉴가 있으면 경찰서 추가 호출이 필요한 gate다. */
    fun isVigilantePassGate(
        document: Element,
        pageText: String,
    ): Boolean = isVigilantePassText(pageText) ||
        parseVigilantePassState(document).required ||
        hasRedVigilanteSignal(document)

    /** 안내 문구가 축약된 화면도 `통행증`과 `자경단`이 함께 있으면 같은 gate로 처리한다. */
    fun isVigilantePassText(text: String): Boolean =
        text.contains(VIGILANTE_PASS_PROMPT) ||
            (text.contains("통행증") && text.contains("자경단"))

    /** HOF가 사용하는 성공 문구 중 하나가 있으면 답안이 통과한 페이지로 판단한다. */
    fun isCaptchaSuccessPage(pageText: String): Boolean =
        CAPTCHA_SUCCESS_MARKERS.any(pageText::contains)

    /** 발생 URL과 같은 HOF 설치 경로의 경찰서 URL을 만든다. */
    fun buildPoliceUrl(sourceUrl: String): String =
        runCatching {
            val uri = URI.create(sourceUrl)
            val basePath = uri.path
                .substringBeforeLast("/", missingDelimiterValue = "")
                .ifBlank { "/" }
            val indexPath = if (basePath.endsWith("/")) "${basePath}index.php" else "$basePath/index.php"
            URI(uri.scheme, uri.authority, indexPath, "menu=police", null).toString()
        }.getOrElse {
            val baseUrl = sourceUrl.substringBefore("#").substringBefore("?")
            baseUrl.substringBeforeLast("/", missingDelimiterValue = baseUrl) + "/index.php?menu=police"
        }

    /** 경찰서 문서에 img 태그가 누락돼도 호출할 수 있는 simple-php-captcha 절대 URL을 만든다. */
    fun buildSimpleCaptchaImageUrl(sourceUrl: String): String =
        runCatching {
            URI.create(sourceUrl).resolve(SIMPLE_CAPTCHA_IMAGE_PATH).toString()
        }.getOrElse {
            sourceUrl.substringBefore("#").substringBeforeLast("/", missingDelimiterValue = sourceUrl) +
                "/$SIMPLE_CAPTCHA_IMAGE_PATH"
        }

    private fun extractPrompt(
        document: Element,
        pageText: String,
    ): String {
        if (pageText.contains(VIGILANTE_PASS_PROMPT)) return VIGILANTE_PASS_PROMPT

        val conciseInstruction = promptCandidates(document)
            .firstOrNull { text ->
                text.length <= MAX_PROMPT_LENGTH &&
                    CAPTCHA_SIGNAL.containsMatchIn(text) &&
                    CAPTCHA_PROMPT_ACTION.containsMatchIn(text)
            }
        if (conciseInstruction != null) return conciseInstruction

        if (findChallengeImage(document) != null || findCaptchaNamedInput(document) != null) {
            return CAPTCHA_ENTRY_PROMPT
        }

        return promptCandidates(document)
            .firstOrNull { text ->
                text.length <= MAX_PROMPT_LENGTH &&
                    (CAPTCHA_SIGNAL.containsMatchIn(text) || text.contains("자경단"))
            }
            ?.ifBlank { null }
            ?: "캡차 또는 통행증 입력이 필요합니다."
    }

    private fun promptCandidates(document: Element): Sequence<String> =
        document.select("p, div, td, span, font, label")
            .asSequence()
            .map { element -> element.ownText().replace(WHITESPACE, " ").trim() }
            .filter(String::isNotBlank)

    private fun selectChallengeForm(document: Element): Element? =
        document.select("form").asSequence().maxByOrNull(::challengeFormScore)

    private fun challengeFormScore(form: Element): Int {
        var score = 0
        val formText = form.text().trim()
        if (
            CAPTCHA_SIGNAL.containsMatchIn(formText) ||
            formText.contains(VIGILANTE_PASS_PROMPT) ||
            hasRedVigilanteSignal(form)
        ) {
            score += 8
        }
        if (findChallengeImage(form) != null) score += 4
        if (findAnswerInput(form) != null) score += 2
        return score
    }

    private fun findChallengeImage(scope: Element): Element? =
        scope.select("img[src]")
            .asSequence()
            .firstOrNull { image ->
                val source = image.attr("src").lowercase()
                IMAGE_SOURCE_MARKERS.any(source::contains)
            }

    private fun findAnswerInput(scope: Element): Element? =
        findNamedAnswerInput(scope)
            ?: inputCandidates(scope).firstOrNull { input -> input.isTextInput() }
            ?: inputCandidates(scope).firstOrNull()

    private fun findNamedAnswerInput(scope: Element): Element? =
        inputCandidates(scope).firstOrNull { input ->
            val name = input.attr("name").lowercase()
            ANSWER_FIELD_MARKERS.any(name::contains)
        }

    private fun findCaptchaNamedInput(scope: Element): Element? =
        inputCandidates(scope).firstOrNull { input ->
            input.attr("name").contains("captcha", ignoreCase = true)
        }

    private fun inputCandidates(scope: Element): Sequence<Element> =
        scope.select("input[name]")
            .asSequence()
            .filter { input -> input.attr("name").isNotBlank() }

    private fun Element.isTextInput(): Boolean {
        val type = attr("type").trim().lowercase()
        return type.isBlank() || type in TEXT_INPUT_TYPES
    }

    private fun extractSubmitUrl(
        form: Element?,
        sourceUrl: String,
    ): String? = form?.absUrl("action")?.ifBlank { sourceUrl }?.ifBlank { null }

    private fun extractSubmitMethod(form: Element?): String =
        form?.attr("method")?.trim()?.uppercase()?.ifBlank { null } ?: DEFAULT_SUBMIT_METHOD

    private fun buildFormFields(
        form: Element?,
        answerFieldName: String,
        fallbackSubmitField: Pair<String, String>?,
    ): List<CaptchaFormField> {
        val fields = linkedMapOf<String, String>()
        var hasSubmitField = false
        form?.select("input[name]")?.forEach { input ->
            val name = input.attr("name").trim()
            if (name.isNotBlank()) {
                if (input.attr("type").trim().equals("submit", ignoreCase = true)) hasSubmitField = true
                fields[name] = if (name == answerFieldName) "" else input.attr("value")
            }
        }
        if (fallbackSubmitField != null && !hasSubmitField) {
            fields[fallbackSubmitField.first] = fallbackSubmitField.second
        }
        fields[answerFieldName] = ""
        return fields.entries.mapIndexed { fieldOrder, (fieldName, fieldValue) ->
            CaptchaFormField(fieldOrder, fieldName, fieldValue)
        }
    }

    private fun hasRedVigilanteSignal(scope: Element): Boolean =
        scope.getAllElements().any { element ->
            element.ownText().contains("자경단") &&
                (hasRedCue(element) || element.parents().any(::hasRedCue))
        }

    private fun hasRedCue(element: Element): Boolean =
        RED_VALUES.contains(element.attr("color").trim().lowercase()) ||
            RED_STYLE.containsMatchIn(element.attr("style"))

    companion object {
        const val VIGILANTE_PASS_PROMPT = "자경단에서 통행증을 발급받아주세요."
        const val PASS_LABEL = "통행증"
        const val CAPTCHA_ENTRY_PROMPT = "이미지의 보안문자를 입력하세요."
        const val DEFAULT_ANSWER_FIELD = "captcha"
        const val SIMPLE_CAPTCHA_SCRIPT = "simple-php-captcha"
        const val SIMPLE_CAPTCHA_IMAGE_PATH = "simple-php-captcha.php?_CAPTCHA=1"
        const val SIMPLE_CAPTCHA_ANSWER_FIELD = "AnswerV"
        const val SIMPLE_CAPTCHA_SUBMIT_FIELD = "AnswerOut"
        const val SIMPLE_CAPTCHA_SUBMIT_VALUE = "입니다."

        private const val DEFAULT_SUBMIT_METHOD = "POST"
        private val CAPTCHA_SIGNAL = Regex(
            """(captcha|캡차|인증\s*문자|자동\s*입력\s*방지)""",
            RegexOption.IGNORE_CASE,
        )
        private val CAPTCHA_PROMPT_ACTION = Regex("""(입력|적어|작성|enter|type)""", RegexOption.IGNORE_CASE)
        private val RED_STYLE = Regex(
            """color\s*:\s*(red|#f00\b|#ff0000\b|rgb\(\s*255\s*,\s*0\s*,\s*0\s*\))""",
            RegexOption.IGNORE_CASE,
        )
        private val WHITESPACE = Regex("""\s+""")
        private val ANSWER_FIELD_MARKERS = listOf("captcha", "pass", "auth", "code")
        private val IMAGE_SOURCE_MARKERS = listOf("captcha", "pass", "auth")
        private val CAPTCHA_SUCCESS_MARKERS = listOf("통행증이 발급되었습니다", "정답입니다")
        private val PASS_REMAINING = Regex("""(?<!\d)(\d{1,3}):([0-5]\d):([0-5]\d)(?!\d)""")
        private val TEXT_INPUT_TYPES = setOf("text", "password", "tel", "number", "search")
        private val RED_VALUES = setOf("red", "#f00", "#ff0000")
        private const val MAX_PROMPT_LENGTH = 160
    }
}
