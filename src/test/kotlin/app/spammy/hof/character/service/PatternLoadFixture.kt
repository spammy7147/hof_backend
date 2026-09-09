package app.spammy.hof.character.service

import app.spammy.hof.external.model.HofHttpResponse

/** HOF 직접 불러오기 응답에서 현재 패턴과 위치·호위를 읽을 수 있는 최소 fixture다. */
fun currentPatternForm(skill: String = "9564"): String = """
    <form method="post">
      <select name="judge0"><option value="0" selected>항상</option></select>
      <input name="quantity0" value="0">
      <select name="skill0">${listOf("9564", "7777").joinToString("") {
        "<option value='$it' ${if (skill == it) "selected" else ""}>기술 $it</option>"
      }}</select>
      <input type="submit" name="ChangePattern" value="변경">
    </form>
    <form method="post">
      <input type="radio" name="position" value="front" checked>
      <input type="radio" name="position" value="back">
      <select name="guard"><option value="always" selected>항상</option></select>
      <input type="submit" name="ChangePosition" value="변경">
    </form>
""".trimIndent()

fun savedPatternLoadForm(slot: Int): String = """
    <form method="post"><input name="patternno" value="$slot" type="hidden">
      <input type="submit" name="loadpattern" value="LOAD"></form>
""".trimIndent()

fun acceptedPatternLoadResponse(characterId: String, slot: Int): HofHttpResponse = HofHttpResponse(
    200, "https://hof.zerosic.com/index.php?char=$characterId",
    "<div id='menu2'>Funds : $ 100 Time : 6000/6000</div>" + currentPatternForm() + savedPatternLoadForm(slot), emptyMap(),
)
