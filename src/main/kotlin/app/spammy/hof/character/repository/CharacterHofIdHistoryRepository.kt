package app.spammy.hof.character.repository

import app.spammy.hof.character.entity.CharacterHofIdHistoryEntity
import app.spammy.hof.common.persistence.CommandRepository

interface CharacterHofIdHistoryRepository : CommandRepository<CharacterHofIdHistoryEntity, Long>
