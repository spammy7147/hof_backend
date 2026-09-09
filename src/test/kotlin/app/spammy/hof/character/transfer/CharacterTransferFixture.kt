package app.spammy.hof.character.transfer

import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.pattern.CharacterPatternSetting

/** 설정 가져오기 통합 검증과 실제 프로세스 재시작 검증이 사용하는 HOF 화면. */
internal object CharacterTransferFixture {
    fun page(characterId: String, current: CharacterPatternSetting, slots: Map<String, CharacterPatternSetting?>): String {
        val row = current.rows.single()
        return """
            <html><body><div id="menu2">Funds : ${'$'} 100 Time : 6000/6000</div>
            <div class="carpet_frame">검증 Lv.60 Knight</div>
            <form action="index.php?char=$characterId" method="post">
              <select name="judge0"><option value="0" selected>Always</option></select>
              <input type="text" name="quantity0" value="${row.quantity}">
              <select name="skill0">${(0..2).joinToString("") { skill ->
                "<option value='$skill' ${if (row.skill == skill.toString()) "selected" else ""}>Skill $skill</option>"
              }}</select><input type="submit" name="ChangePattern" value="Save">
            </form>
            <form action="index.php?char=$characterId" method="post">
              <input type="radio" name="position" value="front" ${if (current.position == "front") "checked" else ""}>
              <input type="radio" name="position" value="back" ${if (current.position == "back") "checked" else ""}>
              <select name="guard">
                <option value="never" ${if (current.guard == "never") "selected" else ""}>Never</option>
                <option value="always" ${if (current.guard == "always") "selected" else ""}>Always</option>
              </select>
              <input type="submit" name="ChangePosition" value="Save">
            </form>
            ${slots.entries.joinToString("\n") { (slot, saved) -> """
              <form action="index.php?char=$characterId" method="post">
                <input type="button" value="복사"><input type="hidden" name="patternno" value="$slot">
                ${if (saved == null) """<input type="text" name="patternname" maxlength="6"><input type="submit" name="savepattern" value="SAVE">"""
                  else """<input type="submit" name="loadpattern" value="LOAD"><input type="submit" name="delpattern" value="DEL">"""}
              </form>
            """ }}
            </body></html>
        """.trimIndent()
    }

    fun setting(skill: String) = CharacterPatternSetting(listOf(CharacterPatternRowValue("0", "0", skill)), "front", "never")
}
