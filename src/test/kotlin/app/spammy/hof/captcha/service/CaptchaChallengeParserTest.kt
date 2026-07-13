package app.spammy.hof.captcha.service

import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptchaChallengeParserTest {
    private val parser = CaptchaChallengeParser()

    @Test
    fun extractsTheSelectedFormWithoutPersistingHtmlAsJson() {
        val sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police"
        val document = Jsoup.parse(
            """
                <form action="/ZeroHOF/index.php?menu=police" method="post">
                  <img src="simple-php-captcha.php?_CAPTCHA=1">
                  <input type="hidden" name="menu" value="police">
                  <input type="text" name="AnswerV" value="old">
                  <input type="submit" name="AnswerOut" value="입니다.">
                </form>
            """.trimIndent(),
            sourceUrl,
        )

        val metadata = parser.extractDocumentMetadata(document, document.text(), sourceUrl)

        assertEquals("AnswerV", metadata.answerFieldName)
        assertEquals("POST", metadata.submitMethod)
        assertEquals("", metadata.formFields.single { it.fieldName == "AnswerV" }.fieldValue)
        assertEquals("입니다.", metadata.formFields.single { it.fieldName == "AnswerOut" }.fieldValue)
        assertEquals(
            "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1",
            metadata.imageUrl,
        )
    }

    @Test
    fun distinguishesBattleFormsFromVigilanteCaptchaSignals() {
        val battle = Jsoup.parse("<form><input type='hidden' name='code' value='gb0'></form>")
        val vigilante = Jsoup.parse("<font color='red'>자경단</font>")

        assertFalse(parser.hasCaptchaSignal(battle, battle.text()))
        assertTrue(parser.hasCaptchaSignal(vigilante, vigilante.text()))
    }

    @Test
    fun buildsPoliceAndSimpleCaptchaUrlsFromTheSameInstallationDirectory() {
        val sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?common=gb0"

        assertEquals(
            "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
            parser.buildPoliceUrl(sourceUrl),
        )
        assertEquals(
            "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1",
            parser.buildSimpleCaptchaImageUrl(sourceUrl),
        )
    }
}
