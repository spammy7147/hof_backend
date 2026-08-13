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
    fun usesConcisePromptInsteadOfFlatteningTheWholePageAroundCaptchaForm() {
        val sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police"
        val document = Jsoup.parse(
            """
                <main>
                  <div>
                    체크포인트 자경단원과 긴 대화 내용입니다.
                    최근 검문 이력과 매뉴얼, 게임 데이터가 계속 이어집니다.
                    <form action="/ZeroHOF/index.php?menu=police" method="post">
                      <label>위의 글을 정확히 읽고 적어주세요.</label>
                      <img src="simple-php-captcha.php?_CAPTCHA=1">
                      <input type="text" name="AnswerV">
                    </form>
                    <footer>검문 통과 기록과 사이트 전체 푸터입니다.</footer>
                  </div>
                </main>
            """.trimIndent(),
            sourceUrl,
        )

        val metadata = parser.extractDocumentMetadata(document, document.text(), sourceUrl)

        assertEquals("이미지의 보안문자를 입력하세요.", metadata.prompt)
    }

    @Test
    fun distinguishesBattleFormsFromVigilanteCaptchaSignals() {
        val battle = Jsoup.parse("<form><input type='hidden' name='code' value='gb0'></form>")
        val vigilante = Jsoup.parse("<font color='red'>자경단</font>")

        assertFalse(parser.hasCaptchaSignal(battle, battle.text()))
        assertTrue(parser.hasCaptchaSignal(vigilante, vigilante.text()))
    }

    @Test
    fun readsRedPassAndActiveCountdownOnlyFromTheInternalMenu() {
        val required = Jsoup.parse("""
            <div id="menu2"><font color="red">통행증</font> Funds : ${'$'} 10 Time : 1/10</div>
            <div id="menu"><font color="red">통행증</font></div>
        """.trimIndent())
        val active = Jsoup.parse("""
            <div id="menu">Top | <span>0:26:09</span> | 전투 | 모험</div>
            <p>통행증 이용 안내</p>
        """.trimIndent())

        assertEquals(VigilantePassState(required = true, remainingSeconds = null), parser.parseVigilantePassState(required))
        assertTrue(parser.hasCaptchaSignal(required, required.text()))
        assertEquals(VigilantePassState(required = false, remainingSeconds = 1_569), parser.parseVigilantePassState(active))
        assertFalse(parser.hasCaptchaSignal(active, active.text()))
    }

    @Test
    fun doesNotTreatRedPassOutsideTheInternalMenuAsTheMenuGate() {
        val document = Jsoup.parse("<main><font color='red'>통행증</font></main><div id='menu'>Top · 전투</div>")

        assertEquals(VigilantePassState(required = false, remainingSeconds = null), parser.parseVigilantePassState(document))
        assertFalse(parser.hasCaptchaSignal(document, document.text()))
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
