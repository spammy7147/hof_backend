package app.spammy.hof.character.transfer

import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.pattern.CharacterPatternSetting

/** 설정 가져오기 통합 검증과 실제 프로세스 재시작 검증이 사용하는 HOF 화면. */
internal object CharacterTransferFixture {
    fun page(
        characterId: String,
        current: CharacterPatternSetting,
        slots: Map<String, CharacterPatternSetting?>,
        equipment: Boolean? = null,
        skills: IntRange = if (equipment == false) 0..1 else 0..2,
        equipmentCandidateValue: String = "ring",
        equipmentName: String = "Focus Ring",
    ): String {
        return """
            <html><body><div id="menu2">Funds : ${'$'} 100 Time : 6000/6000</div>
            <div class="carpet_frame">검증 Lv.60 Knight</div>
            <form action="index.php?char=$characterId" method="post">
              ${current.rows.mapIndexed { index, row -> """
              <select name="judge$index"><option value="0" selected>Always</option></select>
              <input type="text" name="quantity$index" value="${row.quantity}">
              <select name="skill$index">${skills.joinToString("") { skill ->
                "<option value='$skill' ${if (row.skill == skill.toString()) "selected" else ""}>Skill $skill</option>"
              }}</select>""" }.joinToString("\n")}
              <input type="submit" name="ChangePattern" value="Save">
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
            ${equipment?.let { equipped -> """
              <form action="index.php?char=$characterId" method="post"><table>
                ${(1..11).joinToString("\n") { "<tr><td class='align-right'>Empty$it</td><td><input name='spot' value='empty$it'></td></tr>" }}
                <tr><td class="align-right">Accessory</td><td><input name="spot" value="accessory">
                ${if (equipped) "<img src='/ring.gif'>$equipmentName" else ""}</td></tr>
              </table><input type="submit" name="remove_all" value="Remove"></form>
              <script>function Listtype_equip(mode) { switch(mode) {
                case "accessory":
                html = '<input type="radio" name="item_no" value="$equipmentCandidateValue"><img src="/ring.gif">Focus Ring<span class="light"> (Accessory)</span> x1<br />' +
                '<input type="radio" name="item_no" value="guard-ring"><img src="/ring.gif">Guard Ring<span class="light"> (Accessory)</span> x1<br />'; break;
              } }</script>
              <form id="equip"><select name="type_equip"><option value="accessory">Accessory</option></select></form>
              <form action="index.php?char=$characterId" method="post"><div id="list0">None.</div><input type="submit" name="equip_item" value="Equip"></form>
              ${(1..2).joinToString("\n") { slot -> "<form action='index.php?char=$characterId' method='post'><input type='submit' name='Equip_S_$slot' value='Save'></form>" }}
              ${(1..2).joinToString("\n") { slot -> "<form action='index.php?char=$characterId' method='post'><input type='submit' name='Equip_L_$slot' value='Load'></form>" }}
            """ } ?: ""}
            </body></html>
        """.trimIndent()
    }

    fun setting(skill: String) = CharacterPatternSetting(listOf(CharacterPatternRowValue("0", "0", skill)), "front", "never")
}
