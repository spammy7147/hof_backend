package app.spammy.hof.character.service

import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.CharacterDetailParser
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 실제 앱/API 검증에서 외부 HOF만 대체한다. 계정별 상태와 실제 제출 결과를 보존한다. */
class CharacterSyncHofFixture : HofGateway {
    data class Submission(val at: Instant, val fields: Map<String, String>)
    class State {
        val equipped = linkedSetOf("seal", "shield")
        var patterns = (0..9).map { row -> Triple("0", "0", if (row == 0) "9564" else "0") }
        var position = "front"
        var guard = "1"
        var failSecondPreset = false
        var acceptedQuest = false
        val submissions = mutableListOf<Submission>()
    }

    private val accounts = ConcurrentHashMap<Long, State>()
    var beforeCharacterMutation: (Long) -> Unit = {}
    fun state(accountId: Long) = accounts.computeIfAbsent(accountId) { State() }

    override fun execute(accountId: Long, request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
        val state = state(accountId)
        return synchronized(state) {
            val fields = request.formFields
            val character = request.url.contains("char=10")
            if (character && request.method == HofHttpMethod.POST) {
                beforeCharacterMutation(accountId)
                state.submissions += Submission(Instant.now(), fields)
                if (state.failSecondPreset && "Equip_L_2" in fields) {
                    return@synchronized HofHttpResponse(200, request.url, characterPage(state) + "<div class='error'>저장 불러오기 거부</div>", emptyMap())
                }
                when {
                    "loadpattern" in fields -> {
                        rewritePattern(state, "7")
                        state.position = "back"
                        state.guard = "0"
                    }
                    "Equip_L_1" in fields -> {
                        state.equipped.clear()
                        state.equipped += "shield"
                        rewritePattern(state, "2")
                    }
                    "Equip_L_2" in fields || "remove_all" in fields -> {
                        state.equipped.clear()
                        rewritePattern(state, "3")
                    }
                    "equip_item" in fields -> {
                        state.equipped += requireNotNull(fields["item_no"])
                        rewritePattern(state, "5")
                    }
                    "ChangePattern" in fields -> state.patterns = state.patterns.indices.map { row ->
                        Triple(requireNotNull(fields["judge$row"]), requireNotNull(fields["quantity$row"]), requireNotNull(fields["skill$row"]))
                    }
                    "ChangePosition" in fields -> {
                        state.position = requireNotNull(fields["position"])
                        state.guard = requireNotNull(fields["guard"])
                    }
                    else -> error("처리하지 않은 캐릭터 명령: ${fields.keys}")
                }
            }
            if (request.url.contains("menu=quest2")) {
                if (fields["action"] == "get") {
                    state.acceptedQuest = true
                    state.submissions += Submission(Instant.now(), fields)
                }
                return@synchronized HofHttpResponse(200, request.url, homePage(state), emptyMap())
            }
            HofHttpResponse(200, request.url, if (character) characterPage(state) else rosterPage(), mapOf("PHPSESSID" to "isolated-fixture"))
        }
    }

    fun current(accountId: Long) = synchronized(state(accountId)) {
        CharacterDetailParser().parsePage("10", characterPage(state(accountId)))
    }

    private fun rewritePattern(state: State, quantity: String) {
        val count = if ("shield" in state.equipped) 10 else 8
        state.patterns = (0 until count).map { Triple("1", quantity, "0") }
    }

    fun rosterPage() = """
        <div id="menu2">Funds : $ 1 Time : 100/100</div>
        <a href="?char=10">동기화 검증 Lv.60 Knight</a>
        <div id="contents"><a href="?menu=quest2">자택</a></div>
        <div id="foot"><h5>Copy Right fixture</h5><h6>H.O.F Korean Ver fixture</h6><img src="zerohof.gif"></div>
    """.trimIndent()

    fun homePage(state: State) = """
        <div id="menu2">Funds : $ 1 Time : 100/100</div><h4>수락 가능한 퀘스트</h4><table>
        <tr><td>[A] 복구 후 확인</td><td>미션 0/1</td><td>-</td><td>-</td>
        <td>${if (state.acceptedQuest) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"}</td></tr></table>
        ${if (state.acceptedQuest) "<div id='result'>수락했습니다.</div>" else ""}
    """.trimIndent()

    private fun characterPage(state: State) = """
        <div id="menu2">Funds : $ 1 Time : 100/100</div>
        <div class="carpet_frame">동기화 검증 Lv.60 Knight</div>
        <form action="?char=10" method="post"><input type="hidden" name="patternno" value="0">
          <input type="button" value="검증 패턴"><input type="submit" name="loadpattern" value="Load"></form>
        <form action="?char=10" method="post">
          ${state.patterns.mapIndexed { row, values -> """
          <select name="judge$row">
            <option value="0" ${if (values.first == "0") "selected" else ""}>Always</option>
            <option value="1" ${if (values.first == "1") "selected" else ""}>Changed</option></select>
          <input name="quantity$row" value="${values.second}">
          <select name="skill$row"><option value="0" ${if (values.third == "0") "selected" else ""}>Attack</option>
            ${if ("seal" in state.equipped) "<option value='9564' ${if (values.third == "9564") "selected" else ""}>Equipment Skill</option>" else ""}</select>
          """ }.joinToString("\n")}
          <input type="submit" name="ChangePattern" value="Save"></form>
        <form action="?char=10" method="post">
          <input type="radio" name="position" value="front" ${if (state.position == "front") "checked" else ""}>Front
          <input type="radio" name="position" value="back" ${if (state.position == "back") "checked" else ""}>Back
          <select name="guard"><option value="0" ${if (state.guard == "0") "selected" else ""}>None</option>
          <option value="1" ${if (state.guard == "1") "selected" else ""}>Protect</option></select>
          <input type="submit" name="ChangePosition" value="Save"></form>
        <form action="?char=10" method="post"><input type="submit" name="Equip_L_1" value="Load"></form>
        <form action="?char=10" method="post"><input type="submit" name="Equip_L_2" value="Load"></form>
        <form action="?char=10" method="post"><table>
          ${(1..10).joinToString("\n") { "<tr><td class='align-right'>Empty$it</td><td><input name='spot' value='empty$it'></td></tr>" }}
          <tr><td class="align-right">SkillSeal</td><td><input name="spot" value="skillseal">
            ${if ("seal" in state.equipped) "<img src='/seal.gif'>Skill Seal" else ""}</td></tr>
          <tr><td class="align-right">Shield</td><td><input name="spot" value="shield">
            ${if ("shield" in state.equipped) "<img src='/shield.gif'>Pattern Shield" else ""}</td></tr>
          </table><input type="submit" name="remove_all" value="Remove"></form>
        <script>function Listtype_equip(mode) { switch(mode) {
          case "skillseal": html = '<input type="radio" name="item_no" value="seal"><img src="/seal.gif">Skill Seal (SkillSeal) x1<br />'; break;
          case "shield": html = '<input type="radio" name="item_no" value="shield"><img src="/shield.gif">Pattern Shield (Shield) x1<br />'; break;
        } }</script>
        <form id="equip"><select name="type_equip"><option value="skillseal">SkillSeal</option><option value="shield">Shield</option></select></form>
        <form action="?char=10" method="post"><div id="list0">None.</div><input type="submit" name="equip_item" value="Equip"></form>
    """.trimIndent()
}
