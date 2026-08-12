package app.spammy.hof.town.common.model

/** 앱의 승인된 33개 마을 메뉴와 HOF에서 직접 확인된 위치를 연결하는 안정적인 식별자다. */
enum class TownFeatureId(
    val displayName: String,
    val menuCode: String? = null,
    internal vararg val aliases: String,
) {
    FISHING("낚시터", "fishing", "Fishing"),
    FISHING_EXCHANGE("낚시 교환소", "createF", "Fishing Shop"),
    REST_ROOM("휴식처", "restroom", "Rest Room"),

    GENERAL_STORE("일반상점", "buy", "일반 상점", "Buy"),
    SUNDRIES_STORE("잡화점", "buy2", "산다 - 잡화점", "Sundries Shop"),
    DARK_STORE("암흑상점", "sbuy", "암흑 상점", "Dark Shop"),
    SELL("판매", "sell", "판다", "Sell"),
    COMBINE("조합소", "combine", "Combine"),
    AUCTION("옥션", "auction", "Auction"),
    AUCTION_MARKET("낙찰 시세", null, "Auction Market", "Market Price"),

    COLOSSEUM_BATTLE("콜로세움 전투", "colosseum", "Colosseum Battle"),
    COLOSSEUM_EXCHANGE("콜로세움 교환소", "colosseumshop", "Colosseum Shop"),

    ADVENTURE_AGENCY("모험 알선소", "quest", "Quest", "Quest Agency"),
    TALENT_AGENCY("인재 알선소", "recruit", "Recruit", "Talent Agency"),

    HOME_MANAGEMENT("자택 관리", "housing", "Housing"),
    WORKBASE("작업장-재봉틀", "workbase", "작업장", "Work", "Sewing Work"),

    REFINE_WORKSHOP("제련공방", "refine", "Refine"),
    CREATE_WORKSHOP("제작공방", "create", "Create"),
    VETERAN_SMITHY("장로대장간", "refine2", "Veterant Refine", "Veteran Smithy"),

    EMBLEM_SHOP("교환상점", "create2", "교환 상점", "Emblem Shop"),
    EVENT_SHOP("특별 교환상점", null, "특별 교환 상점", "Event Shop"),
    SEWING_SHOP("클라리스의 재봉실", "sewingshop", "재봉실", "Claris's Sewing Shop", "Sewing Shop"),
    LEGACY_SHOP("유물 가게", "legacy", "Legacy Shop"),
    ANN_SHOP("앤의 가게", "ann", "Ann's Shop"),

    CARD_IDENTIFY("카드 감정", "cardshop", "Card Identify"),
    CARD_UPGRADE("카드 강화", "cardmix", "Card Upgrade"),
    CARD_CHANGE("카드 변화", "cardmix2", "카드 변환", "Card Change"),
    CARD_SELL("카드 판매", "cardsell", "Card Sell"),
    SOUL_ECHO("소울 에코 교환", "soulecho", "Echo Trade", "Soul Echo"),

    ORB_EXCHANGE("오브 교환소", "orbboxshop", "Alchemy Lab", "오브 교환"),
    STASH("상자 열기", "stash", "상자열기", "Stash"),
    RAID_INFO("전투 정보실", "raidpub", "Raid Battle"),
    PANTHEON("신전 거리", "pantheon", "Pantheon"),
    ;

    val allAliases: Set<String>
        get() = setOf(displayName, *aliases)
}
