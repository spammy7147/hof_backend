package app.spammy.hof.persistence

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 현재 JPA 매핑과 승인 설계가 요구하는 빈 DB 메타데이터 계약이다. */
internal object FreshSchemaContract {
    fun assertColumns(metadata: DatabaseMetaData) {
        val actualTables = metadata.applicationTables()
        assertEquals(TABLES.keys, actualTables)

        TABLES.forEach { (tableName, table) ->
            val actualColumns = metadata.columns(tableName)
            assertEquals(table.columns.keys, actualColumns.keys, "$tableName column set")
            table.columns.forEach { (columnName, expected) ->
                expected.assertMatches(tableName, requireNotNull(actualColumns[columnName]))
            }
        }
        assertEquals(
            setOf("payload_json"),
            TABLES.values.flatMap { it.columns.keys }.filter { it.endsWith("_json", ignoreCase = true) }.toSet(),
        )
    }

    fun assertRelationalContracts(connection: Connection) {
        val expectedPrimaryKeys = TABLES.values.associate { table ->
            "${table.name}.pk_${table.name}" to KeyContract(table.name, "pk_${table.name}", table.primaryKey)
        }
        assertEquals(expectedPrimaryKeys, connection.keyConstraints("PRIMARY KEY"), "primary keys")
        assertEquals(UNIQUE_KEYS.associateBy(KeyContract::key), connection.keyConstraints("UNIQUE"), "unique keys")
        assertEquals(FOREIGN_KEYS.associateBy(ForeignKeyContract::key), connection.foreignKeys(), "foreign keys")
        assertEquals(INDEXES.associateBy(IndexContract::key), connection.indexes(), "indexes")

        val actualChecks = connection.checks()
        assertEquals(CHECKS.map(CheckContract::key).toSet(), actualChecks.keys, "check constraint names")
        CHECKS.forEach { expected ->
            assertEquals(
                expected.normalizedExpression,
                requireNotNull(actualChecks[expected.key]).normalizedSql(),
                expected.key,
            )
        }
    }

    private fun DatabaseMetaData.applicationTables(): Set<String> =
        getTables(null, "public", "%", arrayOf("TABLE")).use { rows ->
            buildSet {
                while (rows.next()) {
                    rows.getString("TABLE_NAME").lowercase()
                        .takeUnless { it == "flyway_schema_history" }
                        ?.let(::add)
                }
            }
        }

    private fun DatabaseMetaData.columns(table: String): Map<String, ColumnMetadata> =
        getColumns(null, "public", table, "%").use { rows ->
            buildMap {
                while (rows.next()) {
                    val name = rows.getString("COLUMN_NAME").lowercase()
                    put(
                        name,
                        ColumnMetadata(
                            dataType = rows.getInt("DATA_TYPE"),
                            size = rows.getInt("COLUMN_SIZE"),
                            nullable = rows.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                            autoIncrement = rows.getString("IS_AUTOINCREMENT").equals("YES", ignoreCase = true),
                        ),
                    )
                }
            }
        }

    private fun Connection.keyConstraints(type: String): Map<String, KeyContract> {
        val parts = linkedMapOf<String, MutableList<Pair<Int, String>>>()
        val tableByKey = mutableMapOf<String, String>()
        prepareStatement(
            """
            select lower(tc.table_name), lower(tc.constraint_name), lower(kcu.column_name), kcu.ordinal_position
            from information_schema.table_constraints tc
            join information_schema.key_column_usage kcu
              on tc.constraint_catalog = kcu.constraint_catalog
             and tc.constraint_schema = kcu.constraint_schema
             and tc.constraint_name = kcu.constraint_name
            where lower(tc.constraint_schema) = 'public'
              and upper(tc.constraint_type) = ?
              and lower(tc.table_name) <> 'flyway_schema_history'
            order by tc.table_name, tc.constraint_name, kcu.ordinal_position
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, type)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val table = rows.getString(1)
                    val name = rows.getString(2)
                    val key = "$table.$name"
                    tableByKey[key] = table
                    parts.getOrPut(key, ::mutableListOf) += rows.getInt(4) to rows.getString(3)
                }
            }
        }
        return parts.mapValues { (key, columns) ->
            KeyContract(
                table = requireNotNull(tableByKey[key]),
                name = key.substringAfter('.'),
                columns = columns.sortedBy(Pair<Int, String>::first).map(Pair<Int, String>::second),
            )
        }
    }

    private fun Connection.foreignKeys(): Map<String, ForeignKeyContract> =
        buildMap {
            TABLES.keys.forEach { table ->
                metaData.getImportedKeys(null, "public", table).use { rows ->
                    while (rows.next()) {
                        val contract = ForeignKeyContract(
                            name = rows.getString("FK_NAME").lowercase(),
                            table = rows.getString("FKTABLE_NAME").lowercase(),
                            column = rows.getString("FKCOLUMN_NAME").lowercase(),
                            targetTable = rows.getString("PKTABLE_NAME").lowercase(),
                            targetColumn = rows.getString("PKCOLUMN_NAME").lowercase(),
                            deleteAction = DeleteAction.fromJdbc(rows.getShort("DELETE_RULE").toInt()),
                        )
                        put(contract.key, contract)
                    }
                }
            }
        }

    private fun Connection.indexes(): Map<String, IndexContract> {
        val parts = linkedMapOf<String, MutableList<Pair<Int, String>>>()
        val tableByKey = mutableMapOf<String, String>()
        TABLES.keys.forEach { table ->
            metaData.getIndexInfo(null, "public", table, false, false).use { rows ->
                while (rows.next()) {
                    val name = rows.getString("INDEX_NAME")?.lowercase() ?: continue
                    if (!name.startsWith("idx_")) continue
                    val column = rows.getString("COLUMN_NAME")?.lowercase() ?: continue
                    val key = "$table.$name"
                    tableByKey[key] = table
                    parts.getOrPut(key, ::mutableListOf) += rows.getInt("ORDINAL_POSITION") to column
                }
            }
        }
        return parts.mapValues { (key, columns) ->
            IndexContract(
                table = requireNotNull(tableByKey[key]),
                name = key.substringAfter('.'),
                columns = columns.sortedBy(Pair<Int, String>::first).map(Pair<Int, String>::second),
            )
        }
    }

    private fun Connection.checks(): Map<String, String> =
        prepareStatement(
            """
            select lower(tc.table_name), lower(tc.constraint_name), cc.check_clause
            from information_schema.table_constraints tc
            join information_schema.check_constraints cc
              on tc.constraint_catalog = cc.constraint_catalog
             and tc.constraint_schema = cc.constraint_schema
             and tc.constraint_name = cc.constraint_name
            where lower(tc.constraint_schema) = 'public'
              and upper(tc.constraint_type) = 'CHECK'
            order by tc.table_name, tc.constraint_name
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) put("${rows.getString(1)}.${rows.getString(2)}", rows.getString(3))
                }
            }
        }

    private val TABLES = linkedMapOf(
        table(
            "hof_accounts",
            serialId(), requiredVarchar("login_id"), requiredVarchar("encrypted_password"),
            requiredInstant("created_at"), optionalInstant("last_login_at"),
        ),
        table(
            "hof_cookies",
            serialId(), requiredBigint("account_id"), requiredVarchar("name"), requiredText("cookie_value"),
            optionalVarchar("domain"), optionalVarchar("path"), optionalInstant("expires_at"),
            requiredInstant("updated_at"),
        ),
        table(
            "refresh_tokens",
            serialId(), requiredBigint("account_id"), requiredVarchar("token_hash", 64),
            requiredVarchar("family_id", 36), requiredVarchar("client_type", 20),
            requiredInstant("created_at"), requiredInstant("expires_at"), optionalInstant("rotated_at"),
            optionalInstant("revoked_at"),
        ),
        table(
            "characters",
            serialId(), requiredBigint("account_id"), requiredVarchar("hof_character_id", 50),
            requiredVarchar("name", 100), requiredVarchar("job", 100), optionalInteger("level"),
            requiredInteger("pattern_slot_count"), optionalText("image_url"), requiredInstant("updated_at"),
            optionalInstant("detail_synced_at"),
        ),
        table(
            "character_stats",
            requiredBigint("character_id"), optionalInteger("atk"), optionalInteger("matk"),
            optionalInteger("def_base"), optionalInteger("def_bonus"), optionalInteger("mdef_base"),
            optionalInteger("mdef_bonus"), optionalInteger("handle_used"), optionalInteger("handle_max"),
            optionalInteger("cost_used"), optionalInteger("cost_max"),
            primaryKey = listOf("character_id"),
        ),
        table(
            "character_status_lines",
            serialId(), requiredBigint("character_id"), requiredInteger("line_order"), requiredText("content"),
        ),
        table(
            "character_pattern_slots",
            serialId(), requiredBigint("character_id"), requiredVarchar("slot_code"), requiredText("label"),
            requiredBoolean("can_load"),
        ),
        table(
            "character_action_patterns",
            serialId(), requiredBigint("character_id"), requiredInteger("row_index"), requiredText("judge"),
            requiredText("judge_text"), requiredText("quantity"), requiredText("quantity_text"),
            requiredText("skill"), requiredText("skill_text"),
        ),
        table(
            "character_guard_settings",
            requiredBigint("character_id"), requiredText("selected_position"), requiredText("guard_value"),
            requiredText("guard_text"),
            primaryKey = listOf("character_id"),
        ),
        table(
            "character_position_choices",
            serialId(), requiredBigint("character_id"), requiredInteger("choice_order"), requiredText("value"),
            requiredBoolean("checked"),
        ),
        table(
            "character_equipment",
            serialId(), requiredBigint("character_id"), requiredInteger("equipment_order"), requiredText("slot"),
            requiredText("part"), requiredText("name"), requiredText("icon_url"), requiredText("description"),
            requiredBoolean("checked"),
        ),
        table(
            "character_skills",
            serialId(), requiredBigint("character_id"), requiredVarchar("skill_type"),
            requiredInteger("skill_order"), requiredText("source_value"), requiredText("name"),
            requiredText("icon_url"), requiredText("category"),
        ),
        table(
            "character_sync_jobs",
            serialId(), requiredBigint("account_id"), requiredVarchar("status"), requiredInteger("roster_count"),
            requiredInteger("synced_count"), optionalText("message"), requiredInstant("started_at"),
            optionalInstant("finished_at"),
        ),
        table(
            "character_sync_failures",
            serialId(), requiredBigint("sync_job_id"), requiredInteger("failure_order"),
            requiredVarchar("hof_character_id"),
        ),
        table(
            "battle_map_groups",
            serialId(), requiredVarchar("category_id", 50), requiredVarchar("name", 200),
            requiredInteger("display_order"), optionalVarchar("recommended_level", 50),
        ),
        table(
            "battle_maps",
            serialId(), requiredVarchar("category_id", 50), requiredVarchar("map_code", 100),
            optionalBigint("group_id"), requiredVarchar("name", 300), requiredVarchar("normalized_name", 300),
            requiredInteger("display_order"), optionalInteger("required_time"), optionalText("icon_url"),
            requiredBoolean("enabled"), requiredBoolean("shares_minute_cooldown"),
            requiredInstant("created_at"), requiredInstant("updated_at"),
        ),
        table(
            "battle_map_aliases",
            serialId(), requiredBigint("battle_map_id"), requiredVarchar("alias", 300),
            requiredVarchar("normalized_alias", 300),
        ),
        table(
            "account_battle_map_states",
            serialId(), requiredBigint("account_id"), requiredBigint("battle_map_id"),
            optionalInteger("key_count"), requiredVarchar("key_mode", 20),
            optionalInteger("available_count"), optionalInteger("attempt_remaining"), optionalInteger("win_remaining"),
            optionalInstant("cooldown_until"), requiredBoolean("supports_three_battles"), requiredText("raw_href"), requiredBoolean("visible"),
            requiredInstant("last_seen_at"),
        ),
        table(
            "unresolved_battle_maps",
            serialId(), requiredBigint("account_id"), requiredVarchar("category_id", 50),
            optionalVarchar("group_name", 200), requiredVarchar("group_normalized_name", 200),
            requiredInteger("group_display_order"), requiredInteger("map_display_order"),
            requiredVarchar("observed_name", 300), requiredVarchar("normalized_name", 300),
            optionalVarchar("recommended_level", 50), optionalInteger("key_count"), requiredVarchar("key_mode", 20),
            optionalInteger("available_count"), optionalInteger("attempt_remaining"), optionalInteger("win_remaining"),
            optionalInstant("cooldown_until"), optionalInteger("required_time"), optionalText("icon_url"),
            requiredText("raw_href"), requiredBoolean("visible"), requiredInstant("last_seen_at"),
        ),
        table(
            "party_presets",
            serialId(), requiredBigint("account_id"), requiredVarchar("name"), requiredInstant("created_at"),
            requiredInstant("updated_at"), requiredBoolean("is_primary"), optionalInteger("primary_marker"),
            requiredInteger("display_order"),
        ),
        table(
            "party_preset_members",
            requiredBigint("preset_id"), requiredInteger("slot_index"), optionalBigint("character_id"),
            optionalBigint("pattern_slot_id"),
            primaryKey = listOf("preset_id", "slot_index"),
        ),
        table(
            "automation_outbox",
            serialId(), requiredVarchar("event_id", 80), requiredBigint("account_id"), requiredVarchar("topic", 120),
            requiredVarchar("event_key", 80), requiredText("payload"), requiredInstant("created_at"),
            requiredInstant("available_at"), optionalInstant("published_at"),
        ),
        table(
            "automation_consumed_events",
            requiredVarchar("event_id", 80), requiredInstant("consumed_at"),
            primaryKey = listOf("event_id"),
        ),
        table(
            "automation_entries",
            serialId(), requiredBigint("account_id"), requiredVarchar("automation_type", 30),
            requiredInteger("priority"), requiredBoolean("enabled"), requiredInstant("created_at"),
            requiredInstant("updated_at"),
        ),
        table(
            "automation_work_sessions",
            serialId(), requiredBigint("account_id"), requiredBigint("automation_entry_id"),
            requiredVarchar("work_type", 24), requiredVarchar("target_key", 255),
            requiredVarchar("status", 24), requiredVarchar("config_version", 64),
            optionalInteger("target_count"), requiredInteger("confirmed_count"),
            optionalVarchar("quest_cycle", 64), optionalVarchar("mission_key", 255),
            optionalVarchar("mission_type", 32), optionalInteger("observed_current"),
            optionalInteger("observed_required"), optionalVarchar("material_name", 255),
            optionalInteger("material_missing"), optionalInstant("next_check_at"),
            optionalInstant("last_verified_at"), requiredInstant("created_at"), requiredInstant("updated_at"),
            optionalInstant("finished_at"), requiredBigint("version"),
        ),
        table(
            "quest_automation_selections",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("quest_code", 100),
            requiredBoolean("enabled"), requiredInteger("source_order"),
        ),
        table(
            "quest_automation_maps",
            serialId(), requiredBigint("quest_selection_id"), requiredVarchar("mission_key", 100),
            requiredVarchar("category_id", 50), requiredVarchar("map_code", 100),
            requiredVarchar("preset_mode", 20), optionalBigint("party_preset_id"),
            requiredInteger("execution_order"), requiredBoolean("manually_overridden"),
        ),
        table(
            "quest_automation_cycles",
            serialId(), requiredBigint("account_id"), requiredVarchar("quest_code", 100),
            requiredBigint("current_cycle"),
        ),
        table(
            "quest_automation_processed_results",
            serialId(), requiredBigint("account_id"), requiredVarchar("result_kind", 30),
            requiredVarchar("result_identity", 128), requiredVarchar("action_fingerprint", 64),
            optionalVarchar("result_value", 100), requiredInstant("processed_at"),
        ),
        table(
            "quest_map_execution_counters",
            serialId(), requiredBigint("account_id"), requiredVarchar("quest_code", 100),
            requiredVarchar("quest_cycle", 100), requiredVarchar("mission_key", 100),
            requiredVarchar("category_id", 50), requiredVarchar("map_code", 100),
            requiredInteger("successful_runs"),
        ),
        table(
            "battle_automation_maps",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("category_id", 50),
            requiredVarchar("map_code", 100), requiredInteger("daily_target_count"),
            requiredVarchar("preset_mode", 20), optionalBigint("party_preset_id"), requiredInteger("execution_order"),
        ),
        table(
            "battle_automation_daily_progress",
            serialId(), requiredBigint("account_id"), requiredDate("progress_date"),
            requiredVarchar("category_id", 50), requiredVarchar("map_code", 100), requiredVarchar("source", 50),
            requiredInteger("successful_runs"), requiredInstant("updated_at"),
        ),
        table(
            "battle_automation_processed_results",
            serialId(), requiredBigint("account_id"), requiredVarchar("result_identity", 128),
            requiredVarchar("execution_identity", 128), requiredVarchar("action_fingerprint", 64),
            requiredVarchar("outcome_fingerprint", 64), requiredInteger("victory_count"), requiredInstant("processed_at"),
        ),
        table(
            "adventure_automation_maps",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("category_id", 50),
            requiredVarchar("map_code", 100), requiredVarchar("preset_mode", 20),
            optionalBigint("party_preset_id"), requiredInteger("execution_order"),
        ),
        table(
            "adventure_daily_refresh",
            serialId(), requiredBigint("account_id"), requiredDate("refresh_date"), requiredInstant("refreshed_at"),
        ),
        table(
            "adventure_daily_preflight_states",
            serialId(), requiredBigint("account_id"), requiredDate("refresh_date"), requiredInteger("failed_attempts"),
            optionalInstant("next_attempt_at"), optionalVarchar("stop_reason", 30),
            optionalVarchar("in_flight_token", 36), optionalInstant("in_flight_until"), requiredInstant("updated_at"),
        ),
        table(
            "account_automation_leases",
            requiredBigint("account_id"), requiredVarchar("owner_id", 120), requiredInstant("lease_until"),
            requiredInstant("updated_at"),
            primaryKey = listOf("account_id"),
        ),
        table(
            "typed_automation_runtime_states",
            requiredBigint("account_id"), requiredVarchar("lifecycle_status", 20), optionalVarchar("stop_reason", 30),
            requiredInteger("retry_attempt"), optionalInstant("next_attempt_at"), optionalVarchar("lease_token", 128),
            optionalInstant("lease_until"), optionalBigint("stop_action_id"), optionalText("warning_text"), optionalText("last_error"),
            requiredInstant("created_at"), requiredInstant("updated_at"), requiredBigint("version"),
            primaryKey = listOf("account_id"),
        ),
        table(
            "typed_automation_action_runs",
            serialId(), requiredBigint("account_id"), optionalBigint("automation_entry_id"),
            requiredVarchar("execution_identity", 128), requiredVarchar("action_kind", 30), requiredInteger("schema_version"),
            requiredText("payload_json"), requiredVarchar("action_fingerprint", 64), requiredVarchar("status", 20),
            requiredInteger("retry_attempt"), optionalInstant("next_attempt_at"), requiredVarchar("lease_token", 128),
            optionalText("last_error"), requiredInstant("created_at"), optionalInstant("submitted_at"),
            optionalInstant("finished_at"), requiredInstant("updated_at"),
        ),
        table(
            "device_push_targets",
            serialId(), requiredBigint("account_id"), requiredVarchar("platform", 20),
            requiredVarchar("target_type", 20), requiredVarchar("installation_id", 160), requiredText("target_value"),
            requiredBoolean("active"), requiredInstant("last_seen_at"), requiredInstant("created_at"),
        ),
        table(
            "battle_logs",
            serialId(), requiredBigint("account_id"), optionalBigint("battle_map_id"),
            requiredVarchar("category_id", 50), requiredVarchar("map_code", 100),
            requiredVarchar("map_name_snapshot", 300), requiredVarchar("outcome"), requiredText("title"),
            optionalInteger("turns"), optionalInteger("funds"), optionalInteger("experience"), optionalText("quest"),
            optionalInteger("enemy_hp_current"), optionalInteger("enemy_hp_max"),
            optionalInteger("enemy_survivors_alive"), optionalInteger("enemy_survivors_max"),
            optionalInteger("enemy_total_damage"), optionalInteger("enemy_turn_current"), optionalInteger("enemy_turn_max"),
            optionalInteger("ally_hp_current"), optionalInteger("ally_hp_max"),
            optionalInteger("ally_survivors_alive"), optionalInteger("ally_survivors_max"),
            optionalInteger("ally_total_damage"), optionalInteger("ally_turn_current"), optionalInteger("ally_turn_max"),
            optionalText("raw_log_url"), requiredInstant("created_at"),
        ),
        table(
            "battle_log_participants",
            serialId(), requiredBigint("battle_log_id"), requiredInteger("slot_index"), optionalBigint("character_id"),
            requiredVarchar("hof_character_id_snapshot", 50), requiredVarchar("character_name_snapshot", 100),
        ),
        table(
            "battle_log_loots",
            serialId(), requiredBigint("battle_log_id"), requiredInteger("display_order"),
            requiredVarchar("name", 300), requiredInteger("quantity"), requiredText("raw_text"),
        ),
        table(
            "captcha_challenges",
            serialId(), requiredBigint("account_id"), requiredVarchar("status"), requiredText("prompt"),
            optionalText("image_url"), requiredText("source_url"), optionalText("answer"),
            requiredInstant("created_at"), optionalInstant("answered_at"), optionalText("submit_url"),
            requiredVarchar("submit_method", 10), requiredVarchar("answer_field_name", 100),
            requiredInteger("preparation_version"),
        ),
        table(
            "captcha_form_fields",
            serialId(), requiredBigint("challenge_id"), requiredInteger("field_order"),
            requiredVarchar("field_name"), requiredText("field_value"),
        ),
    )

    private val UNIQUE_KEYS = listOf(
        key("hof_accounts", "uk_hof_accounts_login_id", "login_id"),
        key("hof_cookies", "uk_hof_cookies_account_name", "account_id", "name"),
        key("refresh_tokens", "uk_refresh_tokens_token_hash", "token_hash"),
        key("characters", "uk_characters_account_hof_character", "account_id", "hof_character_id"),
        key("character_status_lines", "uk_character_status_lines_character_order", "character_id", "line_order"),
        key("character_pattern_slots", "uk_character_pattern_slots_character_code", "character_id", "slot_code"),
        key("character_action_patterns", "uk_character_action_patterns_character_row", "character_id", "row_index"),
        key("character_position_choices", "uk_character_position_choices_character_order", "character_id", "choice_order"),
        key("character_equipment", "uk_character_equipment_character_order", "character_id", "equipment_order"),
        key("character_skills", "uk_character_skills_character_type_order", "character_id", "skill_type", "skill_order"),
        key("character_sync_failures", "uk_character_sync_failures_job_character", "sync_job_id", "hof_character_id"),
        key("character_sync_failures", "uk_character_sync_failures_job_order", "sync_job_id", "failure_order"),
        key("battle_map_groups", "uk_battle_map_groups_category_name", "category_id", "name"),
        key("battle_maps", "uk_battle_maps_category_map_code", "category_id", "map_code"),
        key("battle_map_aliases", "uk_battle_map_aliases_map_normalized", "battle_map_id", "normalized_alias"),
        key("account_battle_map_states", "uk_account_battle_map_states_account_map", "account_id", "battle_map_id"),
        key(
            "unresolved_battle_maps", "uk_unresolved_battle_maps_identity",
            "account_id", "category_id", "group_normalized_name", "normalized_name",
        ),
        key("party_preset_members", "uk_party_preset_members_preset_slot", "preset_id", "slot_index"),
        key("party_presets", "uk_party_presets_account_primary_marker", "account_id", "primary_marker"),
        key("automation_outbox", "uk_automation_outbox_event_id", "event_id"),
        key("automation_entries", "uk_automation_entries_account_type", "account_id", "automation_type"),
        key(
            "quest_automation_selections", "uk_quest_automation_selections_entry_quest",
            "automation_entry_id", "quest_code",
        ),
        key(
            "quest_automation_maps", "uk_quest_automation_maps_selection_mission_map",
            "quest_selection_id", "mission_key", "category_id", "map_code",
        ),
        key(
            "quest_automation_cycles", "uk_quest_automation_cycles_account_quest",
            "account_id", "quest_code",
        ),
        key(
            "quest_automation_processed_results", "uk_quest_automation_processed_results_identity",
            "account_id", "result_identity",
        ),
        key(
            "quest_map_execution_counters", "uk_quest_map_execution_counters_identity",
            "account_id", "quest_code", "quest_cycle", "mission_key", "category_id", "map_code",
        ),
        key(
            "battle_automation_maps", "uk_battle_automation_maps_entry_map",
            "automation_entry_id", "category_id", "map_code",
        ),
        key(
            "battle_automation_daily_progress", "uk_battle_automation_daily_progress_identity",
            "account_id", "progress_date", "category_id", "map_code", "source",
        ),
        key(
            "battle_automation_processed_results", "uk_battle_automation_processed_results_identity",
            "account_id", "result_identity",
        ),
        key(
            "battle_automation_processed_results", "uk_battle_automation_processed_results_execution",
            "account_id", "execution_identity",
        ),
        key(
            "adventure_automation_maps", "uk_adventure_automation_maps_entry_map",
            "automation_entry_id", "category_id", "map_code",
        ),
        key("adventure_daily_refresh", "uk_adventure_daily_refresh_account_date", "account_id", "refresh_date"),
        key(
            "adventure_daily_preflight_states", "uk_adventure_daily_preflight_states_account", "account_id",
        ),
        key(
            "device_push_targets", "uk_device_push_targets_account_installation", "account_id", "installation_id",
        ),
        key("battle_log_participants", "uk_battle_log_participants_log_slot", "battle_log_id", "slot_index"),
        key(
            "battle_log_loots", "uk_battle_log_loots_log_display_order", "battle_log_id", "display_order",
        ),
        key("captcha_form_fields", "uk_captcha_form_fields_challenge_name", "challenge_id", "field_name"),
        key("typed_automation_action_runs", "uk_typed_action_execution", "account_id", "execution_identity"),
    )

    private val FOREIGN_KEYS = listOf(
        fk("fk_hof_cookies_account", "hof_cookies.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_refresh_tokens_account", "refresh_tokens.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_characters_account", "characters.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_character_stats_character", "character_stats.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_status_lines_character", "character_status_lines.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_pattern_slots_character", "character_pattern_slots.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_action_patterns_character", "character_action_patterns.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_guard_settings_character", "character_guard_settings.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_position_choices_character", "character_position_choices.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_equipment_character", "character_equipment.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_skills_character", "character_skills.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_sync_jobs_account", "character_sync_jobs.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_character_sync_failures_job", "character_sync_failures.sync_job_id", "character_sync_jobs.id", DeleteAction.CASCADE),
        fk("fk_battle_maps_group", "battle_maps.group_id", "battle_map_groups.id", DeleteAction.SET_NULL),
        fk("fk_battle_map_aliases_map", "battle_map_aliases.battle_map_id", "battle_maps.id", DeleteAction.CASCADE),
        fk("fk_account_battle_map_states_account", "account_battle_map_states.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_account_battle_map_states_map", "account_battle_map_states.battle_map_id", "battle_maps.id", DeleteAction.CASCADE),
        fk("fk_unresolved_battle_maps_account", "unresolved_battle_maps.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_party_presets_account", "party_presets.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_party_preset_members_preset", "party_preset_members.preset_id", "party_presets.id", DeleteAction.CASCADE),
        fk("fk_party_preset_members_character", "party_preset_members.character_id", "characters.id", DeleteAction.SET_NULL),
        fk(
            "fk_party_preset_members_pattern_slot", "party_preset_members.pattern_slot_id",
            "character_pattern_slots.id", DeleteAction.SET_NULL,
        ),
        fk(
            "fk_automation_outbox_account", "automation_outbox.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk("fk_automation_entries_account", "automation_entries.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_automation_work_account", "automation_work_sessions.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_automation_work_entry", "automation_work_sessions.automation_entry_id", "automation_entries.id", DeleteAction.CASCADE),
        fk(
            "fk_quest_automation_selections_entry", "quest_automation_selections.automation_entry_id",
            "automation_entries.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_quest_automation_maps_selection", "quest_automation_maps.quest_selection_id",
            "quest_automation_selections.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_quest_automation_maps_party_preset", "quest_automation_maps.party_preset_id",
            "party_presets.id", DeleteAction.SET_NULL,
        ),
        fk(
            "fk_quest_automation_cycles_account", "quest_automation_cycles.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_quest_automation_processed_results_account", "quest_automation_processed_results.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_quest_map_execution_counters_account", "quest_map_execution_counters.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_battle_automation_maps_entry", "battle_automation_maps.automation_entry_id",
            "automation_entries.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_battle_automation_maps_party_preset", "battle_automation_maps.party_preset_id",
            "party_presets.id", DeleteAction.SET_NULL,
        ),
        fk(
            "fk_battle_automation_daily_progress_account", "battle_automation_daily_progress.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_battle_automation_processed_results_account", "battle_automation_processed_results.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_adventure_automation_maps_entry", "adventure_automation_maps.automation_entry_id",
            "automation_entries.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_adventure_automation_maps_party_preset", "adventure_automation_maps.party_preset_id",
            "party_presets.id", DeleteAction.SET_NULL,
        ),
        fk(
            "fk_adventure_daily_refresh_account", "adventure_daily_refresh.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_adventure_daily_preflight_states_account", "adventure_daily_preflight_states.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_account_automation_leases_account", "account_automation_leases.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_device_push_targets_account", "device_push_targets.account_id",
            "hof_accounts.id", DeleteAction.CASCADE,
        ),
        fk("fk_battle_logs_account", "battle_logs.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_battle_logs_battle_map", "battle_logs.battle_map_id", "battle_maps.id", DeleteAction.SET_NULL),
        fk(
            "fk_battle_log_participants_log", "battle_log_participants.battle_log_id",
            "battle_logs.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_battle_log_participants_character", "battle_log_participants.character_id",
            "characters.id", DeleteAction.SET_NULL,
        ),
        fk("fk_battle_log_loots_log", "battle_log_loots.battle_log_id", "battle_logs.id", DeleteAction.CASCADE),
        fk("fk_captcha_challenges_account", "captcha_challenges.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_typed_runtime_account", "typed_automation_runtime_states.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_typed_runtime_stop_action", "typed_automation_runtime_states.stop_action_id", "typed_automation_action_runs.id", DeleteAction.SET_NULL),
        fk("fk_typed_action_account", "typed_automation_action_runs.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_typed_action_entry", "typed_automation_action_runs.automation_entry_id", "automation_entries.id", DeleteAction.SET_NULL),
        fk(
            "fk_captcha_form_fields_challenge", "captcha_form_fields.challenge_id",
            "captcha_challenges.id", DeleteAction.CASCADE,
        ),
    )

    private val INDEXES = listOf(
        index("hof_cookies", "idx_hof_cookies_account_updated", "account_id", "updated_at", "id"),
        index("refresh_tokens", "idx_refresh_tokens_family_created", "family_id", "created_at", "id"),
        index("refresh_tokens", "idx_refresh_tokens_account_active", "account_id", "revoked_at", "expires_at", "id"),
        index("characters", "idx_characters_account_name", "account_id", "name", "id"),
        index("characters", "idx_characters_account_detail_synced", "account_id", "detail_synced_at", "id"),
        index("character_sync_jobs", "idx_character_sync_jobs_account_started", "account_id", "started_at", "id"),
        index("battle_map_groups", "idx_battle_map_groups_category_display", "category_id", "display_order", "name", "id"),
        index("battle_maps", "idx_battle_maps_category_display", "category_id", "group_id", "display_order", "name", "id"),
        index("battle_maps", "idx_battle_maps_category_normalized_name", "category_id", "normalized_name", "id"),
        index("battle_map_aliases", "idx_battle_map_aliases_normalized", "normalized_alias", "battle_map_id"),
        index("account_battle_map_states", "idx_account_battle_map_states_tree", "account_id", "visible", "battle_map_id"),
        index(
            "unresolved_battle_maps", "idx_unresolved_battle_maps_tree", "account_id", "category_id", "visible",
            "group_display_order", "map_display_order", "observed_name", "id",
        ),
        index("party_presets", "idx_party_presets_account_updated", "account_id", "updated_at", "id"),
        index("party_presets", "idx_party_presets_account_order", "account_id", "display_order", "id"),
        index("party_preset_members", "idx_party_preset_members_character", "character_id"),
        index("party_preset_members", "idx_party_preset_members_pattern_slot", "pattern_slot_id"),
        index(
            "automation_outbox", "idx_automation_outbox_unpublished",
            "published_at", "available_at", "id",
        ),
        index("automation_entries", "idx_automation_entries_account_priority", "account_id", "priority", "id"),
        index(
            "automation_work_sessions", "idx_automation_work_account_status",
            "account_id", "status", "updated_at", "id",
        ),
        index(
            "automation_work_sessions", "idx_automation_work_due",
            "status", "next_check_at", "account_id", "id",
        ),
        index(
            "quest_automation_selections", "idx_quest_automation_selections_entry_order",
            "automation_entry_id", "source_order", "id",
        ),
        index(
            "quest_automation_maps", "idx_quest_automation_maps_selection_order",
            "quest_selection_id", "execution_order", "id",
        ),
        index("quest_automation_maps", "idx_quest_automation_maps_identity", "category_id", "map_code", "id"),
        index("quest_automation_maps", "idx_quest_automation_maps_party_preset", "party_preset_id", "id"),
        index(
            "quest_map_execution_counters", "idx_quest_map_execution_counters_account_cycle",
            "account_id", "quest_cycle", "quest_code", "id",
        ),
        index(
            "quest_map_execution_counters", "idx_quest_map_execution_counters_map_identity",
            "category_id", "map_code", "id",
        ),
        index(
            "battle_automation_maps", "idx_battle_automation_maps_entry_order",
            "automation_entry_id", "execution_order", "id",
        ),
        index("battle_automation_maps", "idx_battle_automation_maps_identity", "category_id", "map_code", "id"),
        index("battle_automation_maps", "idx_battle_automation_maps_party_preset", "party_preset_id", "id"),
        index(
            "battle_automation_daily_progress", "idx_battle_automation_daily_progress_account_date",
            "account_id", "progress_date", "source", "id",
        ),
        index(
            "battle_automation_daily_progress", "idx_battle_automation_daily_progress_map_identity",
            "category_id", "map_code", "progress_date", "id",
        ),
        index(
            "adventure_automation_maps", "idx_adventure_automation_maps_entry_order",
            "automation_entry_id", "execution_order", "id",
        ),
        index("adventure_automation_maps", "idx_adventure_automation_maps_identity", "category_id", "map_code", "id"),
        index("adventure_automation_maps", "idx_adventure_automation_maps_party_preset", "party_preset_id", "id"),
        index(
            "adventure_daily_refresh", "idx_adventure_daily_refresh_account_date",
            "account_id", "refresh_date", "id",
        ),
        index(
            "adventure_daily_preflight_states", "idx_adventure_daily_preflight_states_next_attempt",
            "next_attempt_at", "account_id",
        ),
        index(
            "adventure_daily_preflight_states", "idx_adventure_daily_preflight_states_in_flight",
            "in_flight_until", "account_id",
        ),
        index(
            "account_automation_leases", "idx_account_automation_leases_until",
            "lease_until", "account_id",
        ),
        index(
            "device_push_targets", "idx_device_push_targets_account_active",
            "account_id", "active", "last_seen_at", "id",
        ),
        index("battle_logs", "idx_battle_logs_account_created", "account_id", "created_at", "id"),
        index("battle_logs", "idx_battle_logs_account_outcome", "account_id", "outcome", "id"),
        index("battle_logs", "idx_battle_logs_battle_map", "battle_map_id", "id"),
        index(
            "battle_log_participants", "idx_battle_log_participants_log_order", "battle_log_id", "slot_index", "id",
        ),
        index("battle_log_participants", "idx_battle_log_participants_character", "character_id", "id"),
        index("battle_log_loots", "idx_battle_log_loots_log_order", "battle_log_id", "display_order", "id"),
        index(
            "captcha_challenges", "idx_captcha_challenges_account_status_created",
            "account_id", "status", "created_at", "id",
        ),
        index("captcha_form_fields", "idx_captcha_form_fields_challenge_order", "challenge_id", "field_order", "id"),
        index("typed_automation_action_runs", "idx_typed_action_account_status", "account_id", "status", "updated_at", "id"),
    )

    private val CHECKS = listOf(
        check("characters", "ck_characters_pattern_slot_count", "pattern_slot_count >= 0"),
        check(
            "automation_work_sessions", "ck_automation_work_type",
            "locate(',' || work_type || ',', ',QUEST,BATTLE_MAP,ADVENTURE_MAP,') > 0",
        ),
        check(
            "automation_work_sessions", "ck_automation_work_status",
            "locate(',' || status || ',', ',RUNNING,WAITING_COOLDOWN,WAITING_RESOURCE,YIELDED_PRIORITY,COMPLETED,STOPPED,') > 0",
        ),
        check(
            "automation_work_sessions", "ck_automation_work_count",
            "confirmed_count >= 0 and (target_count is null or target_count > 0)",
        ),
        check(
            "automation_work_sessions", "ck_automation_work_material_missing",
            "material_missing is null or material_missing >= 0",
        ),
        check(
            "automation_work_sessions", "ck_automation_work_finished",
            "(finished_at is not null and locate(',' || status || ',', ',COMPLETED,STOPPED,') > 0) or " +
                "(finished_at is null and locate(',' || status || ',', ',COMPLETED,STOPPED,') = 0)",
        ),
        check("typed_automation_runtime_states", "ck_typed_runtime_lifecycle", "locate(',' || lifecycle_status || ',', ',RUNNING,PAUSED,STOPPED,') > 0"),
        check("typed_automation_runtime_states", "ck_typed_runtime_stop", "(lifecycle_status = 'STOPPED' and stop_reason is not null) or (lifecycle_status <> 'STOPPED' and stop_reason is null)"),
        check("typed_automation_runtime_states", "ck_typed_runtime_retry", "retry_attempt >= 0"),
        check("typed_automation_runtime_states", "ck_typed_runtime_lease", "(lease_token is null and lease_until is null) or (lease_token is not null and lease_until is not null)"),
        check("typed_automation_runtime_states", "ck_typed_runtime_stop_action", "lifecycle_status = 'STOPPED' or stop_action_id is null"),
        check("typed_automation_action_runs", "ck_typed_action_status", "locate(',' || status || ',', ',PREPARED,SUBMITTING,SUCCEEDED,FAILED,AMBIGUOUS,') > 0"),
        check("typed_automation_action_runs", "ck_typed_action_schema", "schema_version > 0"),
        check("typed_automation_action_runs", "ck_typed_action_retry", "retry_attempt >= 0"),
        check("typed_automation_action_runs", "ck_typed_action_fingerprint", "char_length(action_fingerprint) = 64"),
        check("character_status_lines", "ck_character_status_lines_order", "line_order >= 0"),
        check("character_action_patterns", "ck_character_action_patterns_row", "row_index >= 0"),
        check("character_position_choices", "ck_character_position_choices_order", "choice_order >= 0"),
        check("character_equipment", "ck_character_equipment_order", "equipment_order >= 0"),
        check("character_skills", "ck_character_skills_order", "skill_order >= 0"),
        check("character_sync_jobs", "ck_character_sync_jobs_roster_count", "roster_count >= 0"),
        check("character_sync_jobs", "ck_character_sync_jobs_synced_count", "synced_count >= 0"),
        check("character_sync_failures", "ck_character_sync_failures_order", "failure_order >= 0"),
        check("battle_map_groups", "ck_battle_map_groups_display_order", "display_order >= 0"),
        check("battle_maps", "ck_battle_maps_display_order", "display_order >= 0"),
        check("battle_maps", "ck_battle_maps_required_time", "required_time is null or required_time >= 0"),
        check("account_battle_map_states", "ck_account_battle_map_states_key_count", "key_count is null or key_count >= 0"),
        check(
            "account_battle_map_states", "ck_account_battle_map_states_key_mode",
            "case key_mode when 'NOT_REQUIRED' then true when 'LIMITED' then true " +
                "when 'UNLIMITED' then true when 'UNKNOWN' then true else false end",
        ),
        check(
            "account_battle_map_states", "ck_account_battle_map_states_key_consistency",
            "(key_mode = 'LIMITED' and key_count is not null) or " +
                "(key_mode <> 'LIMITED' and key_count is null)",
        ),
        check(
            "account_battle_map_states", "ck_account_battle_map_states_available_count",
            "available_count is null or available_count >= 0",
        ),
        check(
            "account_battle_map_states", "ck_account_battle_map_states_attempt_remaining",
            "attempt_remaining is null or attempt_remaining >= 0",
        ),
        check(
            "account_battle_map_states", "ck_account_battle_map_states_win_remaining",
            "win_remaining is null or win_remaining >= 0",
        ),
        check("unresolved_battle_maps", "ck_unresolved_battle_maps_group_order", "group_display_order >= 0"),
        check("unresolved_battle_maps", "ck_unresolved_battle_maps_map_order", "map_display_order >= 0"),
        check("unresolved_battle_maps", "ck_unresolved_battle_maps_key_count", "key_count is null or key_count >= 0"),
        check(
            "unresolved_battle_maps", "ck_unresolved_battle_maps_key_mode",
            "case key_mode when 'NOT_REQUIRED' then true when 'LIMITED' then true " +
                "when 'UNLIMITED' then true when 'UNKNOWN' then true else false end",
        ),
        check(
            "unresolved_battle_maps", "ck_unresolved_battle_maps_key_consistency",
            "(key_mode = 'LIMITED' and key_count is not null) or " +
                "(key_mode <> 'LIMITED' and key_count is null)",
        ),
        check(
            "unresolved_battle_maps", "ck_unresolved_battle_maps_available_count",
            "available_count is null or available_count >= 0",
        ),
        check(
            "unresolved_battle_maps", "ck_unresolved_battle_maps_attempt_remaining",
            "attempt_remaining is null or attempt_remaining >= 0",
        ),
        check(
            "unresolved_battle_maps", "ck_unresolved_battle_maps_win_remaining",
            "win_remaining is null or win_remaining >= 0",
        ),
        check(
            "unresolved_battle_maps", "ck_unresolved_battle_maps_required_time",
            "required_time is null or required_time >= 0",
        ),
        check("party_preset_members", "ck_party_preset_members_slot", "slot_index between 0 and 4"),
        check("party_presets", "ck_party_presets_display_order", "display_order >= 0"),
        check(
            "party_presets", "ck_party_presets_primary_marker",
            "(is_primary and primary_marker = 1) or (is_primary = false and primary_marker is null)",
        ),
        check(
            "automation_entries", "ck_automation_entries_type",
            "case automation_type when 'QUEST' then true when 'BATTLE_MAP' then true " +
                "when 'ADVENTURE_MAP' then true else false end",
        ),
        check("automation_entries", "ck_automation_entries_priority", "priority >= 0"),
        check(
            "quest_automation_selections", "ck_quest_automation_selections_source_order", "source_order >= 0",
        ),
        check(
            "quest_automation_maps", "ck_quest_automation_maps_preset_mode",
            "case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end",
        ),
        check("quest_automation_maps", "ck_quest_automation_maps_execution_order", "execution_order >= 0"),
        check(
            "quest_automation_cycles", "ck_quest_automation_cycles_current_cycle",
            "current_cycle > cast(0 as bigint)",
        ),
        check(
            "quest_automation_processed_results", "ck_quest_automation_processed_results_identity",
            "char_length(trim(result_identity)) between 1 and 128",
        ),
        check(
            "quest_automation_processed_results", "ck_quest_automation_processed_results_fingerprint",
            "char_length(action_fingerprint) = 64",
        ),
        check(
            "quest_automation_processed_results", "ck_quest_automation_processed_results_kind_value",
            "case result_kind when 'ACCEPT' then result_value is not null and cast(result_value as bigint) > 0 " +
                "when 'BATTLE_VICTORY' then result_value is null else false end",
        ),
        check(
            "quest_map_execution_counters", "ck_quest_map_execution_counters_successful_runs",
            "successful_runs >= 0",
        ),
        check("battle_automation_maps", "ck_battle_automation_maps_daily_target", "daily_target_count > 0"),
        check(
            "battle_automation_maps", "ck_battle_automation_maps_preset_mode",
            "case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end",
        ),
        check("battle_automation_maps", "ck_battle_automation_maps_execution_order", "execution_order >= 0"),
        check(
            "battle_automation_daily_progress", "ck_battle_automation_daily_progress_successful_runs",
            "successful_runs >= 0",
        ),
        check(
            "battle_automation_processed_results", "ck_battle_automation_processed_results_victories",
            "victory_count between 0 and 3",
        ),
        check(
            "battle_automation_processed_results", "ck_battle_automation_processed_results_result_identity",
            "char_length(trim(result_identity)) between 1 and 128",
        ),
        check(
            "battle_automation_processed_results", "ck_battle_automation_processed_results_execution_identity",
            "char_length(trim(execution_identity)) between 1 and 128",
        ),
        check(
            "battle_automation_processed_results", "ck_battle_automation_processed_results_action_fingerprint",
            "char_length(action_fingerprint) = 64",
        ),
        check(
            "battle_automation_processed_results", "ck_battle_automation_processed_results_outcome_fingerprint",
            "char_length(outcome_fingerprint) = 64",
        ),
        check(
            "adventure_automation_maps", "ck_adventure_automation_maps_preset_mode",
            "case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end",
        ),
        check("adventure_automation_maps", "ck_adventure_automation_maps_execution_order", "execution_order >= 0"),
        check(
            "adventure_daily_preflight_states", "ck_adventure_daily_preflight_states_attempts",
            "failed_attempts >= 0",
        ),
        check(
            "adventure_daily_preflight_states", "ck_adventure_daily_preflight_states_in_flight",
            "case when in_flight_token is null and in_flight_until is null then true " +
                "when in_flight_token is not null and in_flight_until is not null then true else false end",
        ),
        check("battle_log_participants", "ck_battle_log_participants_slot", "slot_index >= 0"),
        check("battle_log_loots", "ck_battle_log_loots_order", "display_order >= 0"),
        check("battle_log_loots", "ck_battle_log_loots_quantity", "quantity > 0"),
        check(
            "captcha_challenges",
            "ck_captcha_challenges_preparation_version",
            "preparation_version >= 0",
        ),
        check("captcha_form_fields", "ck_captcha_form_fields_order", "field_order >= 0"),
    )
}

private data class TableContract(
    val name: String,
    val columns: Map<String, ColumnContract>,
    val primaryKey: List<String>,
)

private data class ColumnContract(
    val name: String,
    val type: TypeFamily,
    val nullable: Boolean,
    val length: Int? = null,
    val autoIncrement: Boolean = false,
) {
    fun assertMatches(
        table: String,
        actual: ColumnMetadata,
    ) {
        val label = "$table.$name"
        assertTrue(type.accepts(actual.dataType, actual.size), "$label type=${actual.dataType} size=${actual.size}")
        assertEquals(nullable, actual.nullable, "$label nullable")
        length?.let { expectedLength -> assertEquals(expectedLength, actual.size, "$label length") }
        assertEquals(autoIncrement, actual.autoIncrement, "$label auto increment")
    }
}

private data class ColumnMetadata(
    val dataType: Int,
    val size: Int,
    val nullable: Boolean,
    val autoIncrement: Boolean,
)

private enum class TypeFamily {
    BIGINT,
    INTEGER,
    VARCHAR,
    TEXT,
    BOOLEAN,
    DATE,
    TIMESTAMP_WITH_TIME_ZONE,
    ;

    fun accepts(
        dataType: Int,
        size: Int,
    ): Boolean = when (this) {
        BIGINT -> dataType == Types.BIGINT
        INTEGER -> dataType == Types.INTEGER
        VARCHAR -> dataType == Types.VARCHAR || dataType == Types.NVARCHAR
        TEXT -> (dataType == Types.VARCHAR || dataType == Types.LONGVARCHAR) && size > 300
        BOOLEAN -> dataType == Types.BOOLEAN || dataType == Types.BIT
        DATE -> dataType == Types.DATE
        TIMESTAMP_WITH_TIME_ZONE -> dataType == Types.TIMESTAMP_WITH_TIMEZONE
    }
}

private data class KeyContract(
    val table: String,
    val name: String,
    val columns: List<String>,
) {
    val key: String = "$table.$name"
}

private data class ForeignKeyContract(
    val name: String,
    val table: String,
    val column: String,
    val targetTable: String,
    val targetColumn: String,
    val deleteAction: DeleteAction,
) {
    val key: String = "$table.$name"
}

private data class IndexContract(
    val table: String,
    val name: String,
    val columns: List<String>,
) {
    val key: String = "$table.$name"
}

private data class CheckContract(
    val table: String,
    val name: String,
    val expression: String,
) {
    val key: String = "$table.$name"
    val normalizedExpression: String = expression.normalizedSql()
}

private enum class DeleteAction {
    CASCADE,
    SET_NULL,
    RESTRICT,
    ;

    companion object {
        fun fromJdbc(rule: Int): DeleteAction = when (rule) {
            DatabaseMetaData.importedKeyCascade -> CASCADE
            DatabaseMetaData.importedKeySetNull -> SET_NULL
            DatabaseMetaData.importedKeyNoAction,
            DatabaseMetaData.importedKeyRestrict,
            -> RESTRICT
            else -> error("Unsupported JDBC delete rule: $rule")
        }
    }
}

private fun table(
    name: String,
    vararg columns: ColumnContract,
    primaryKey: List<String> = listOf("id"),
): Pair<String, TableContract> = name to TableContract(name, columns.associateBy(ColumnContract::name), primaryKey)

private fun serialId(): ColumnContract = ColumnContract("id", TypeFamily.BIGINT, nullable = false, autoIncrement = true)
private fun requiredBigint(name: String): ColumnContract = ColumnContract(name, TypeFamily.BIGINT, nullable = false)
private fun optionalBigint(name: String): ColumnContract = ColumnContract(name, TypeFamily.BIGINT, nullable = true)
private fun requiredInteger(name: String): ColumnContract = ColumnContract(name, TypeFamily.INTEGER, nullable = false)
private fun optionalInteger(name: String): ColumnContract = ColumnContract(name, TypeFamily.INTEGER, nullable = true)
private fun requiredVarchar(name: String, length: Int = 255): ColumnContract =
    ColumnContract(name, TypeFamily.VARCHAR, nullable = false, length = length)
private fun optionalVarchar(name: String, length: Int = 255): ColumnContract =
    ColumnContract(name, TypeFamily.VARCHAR, nullable = true, length = length)
private fun requiredText(name: String): ColumnContract = ColumnContract(name, TypeFamily.TEXT, nullable = false)
private fun optionalText(name: String): ColumnContract = ColumnContract(name, TypeFamily.TEXT, nullable = true)
private fun requiredBoolean(name: String): ColumnContract = ColumnContract(name, TypeFamily.BOOLEAN, nullable = false)
private fun requiredDate(name: String): ColumnContract = ColumnContract(name, TypeFamily.DATE, nullable = false)
private fun requiredInstant(name: String): ColumnContract =
    ColumnContract(name, TypeFamily.TIMESTAMP_WITH_TIME_ZONE, nullable = false)
private fun optionalInstant(name: String): ColumnContract =
    ColumnContract(name, TypeFamily.TIMESTAMP_WITH_TIME_ZONE, nullable = true)

private fun key(table: String, name: String, vararg columns: String): KeyContract =
    KeyContract(table, name, columns.toList())

private fun fk(
    name: String,
    source: String,
    target: String,
    deleteAction: DeleteAction,
): ForeignKeyContract =
    ForeignKeyContract(
        name = name,
        table = source.substringBeforeLast('.'),
        column = source.substringAfterLast('.'),
        targetTable = target.substringBeforeLast('.'),
        targetColumn = target.substringAfterLast('.'),
        deleteAction = deleteAction,
    )

private fun index(table: String, name: String, vararg columns: String): IndexContract =
    IndexContract(table, name, columns.toList())

private fun check(table: String, name: String, expression: String): CheckContract =
    CheckContract(table, name, expression)

private fun String.normalizedSql(): String =
    lowercase().replace(Regex("""[\s\"()]"""), "")
