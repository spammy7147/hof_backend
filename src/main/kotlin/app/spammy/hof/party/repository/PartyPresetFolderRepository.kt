package app.spammy.hof.party.repository

import app.spammy.hof.common.persistence.CommandRepository
import app.spammy.hof.party.entity.PartyPresetFolderEntity

/** 파티 프리셋 폴더 행의 저장·삭제·flush를 담당한다. */
interface PartyPresetFolderRepository : CommandRepository<PartyPresetFolderEntity, Long>
