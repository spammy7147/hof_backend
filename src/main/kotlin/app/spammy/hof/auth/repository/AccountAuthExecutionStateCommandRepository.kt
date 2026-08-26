package app.spammy.hof.auth.repository

import app.spammy.hof.auth.entity.AccountAuthExecutionStateEntity
import app.spammy.hof.common.persistence.CommandRepository

interface AccountAuthExecutionStateCommandRepository : CommandRepository<AccountAuthExecutionStateEntity, Long>
