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
            "latest_hof_status",
            serialId(), requiredBigint("account_id"), requiredVarchar("player_name"),
            requiredBigint("funds"), requiredInteger("time_current"), requiredInteger("time_max"),
            requiredVarchar("work"), requiredVarchar("auction"), requiredInstant("observed_at"),
            optionalInstant("character_roster_observed_at"),
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
            "app_releases",
            serialId(), requiredVarchar("platform", 20), requiredBigint("version_code"),
            requiredVarchar("version_name", 100), requiredVarchar("file_name"), requiredBigint("file_size"),
            requiredVarchar("sha256", 64), requiredVarchar("git_revision", 40), requiredBigint("jenkins_build"),
            requiredInstant("published_at"),
        ),
        table(
            "characters",
            serialId(), requiredBigint("account_id"), requiredVarchar("current_hof_character_id", 50),
            requiredVarchar("name", 100), requiredVarchar("job", 100), optionalInteger("level"),
            requiredInteger("pattern_slot_count"), optionalText("image_url"), requiredInstant("updated_at"),
            optionalInstant("detail_synced_at"), requiredVarchar("lifecycle", 20),
            optionalInstant("last_seen_at"), optionalInstant("missing_since"), optionalInstant("archived_at"),
            optionalInteger("roster_order"),
        ),
        table(
            "character_hof_id_history",
            serialId(), requiredBigint("character_id"), requiredBigint("account_id"),
            requiredVarchar("hof_character_id", 50), requiredInstant("valid_from"), optionalInstant("valid_to"),
            requiredVarchar("link_reason", 30), requiredBoolean("user_confirmed"), optionalInteger("open_marker"),
        ),
        table(
            "character_section_sync_states",
            requiredBigint("character_id"), requiredVarchar("section", 30), requiredVarchar("status", 20),
            requiredVarchar("parser_version", 40), requiredInstant("last_attempted_at"),
            optionalInstant("last_succeeded_at"), optionalVarchar("error_code", 60),
            optionalVarchar("error_message", 500), optionalInteger("observed_count"),
            primaryKey = listOf("character_id", "section"),
        ),
        table(
            "character_stats",
            requiredBigint("character_id"), optionalInteger("status_points"), optionalInteger("skill_points"),
            optionalInteger("atk"), optionalInteger("matk"),
            optionalInteger("def_base"), optionalInteger("def_bonus"), optionalInteger("mdef_base"),
            optionalInteger("mdef_bonus"), optionalInteger("handle_used"), optionalInteger("handle_max"),
            optionalInteger("cost_used"), optionalInteger("cost_max"),
            optionalBigint("exp_current"), optionalBigint("exp_max"), optionalBoolean("exp_maxed"),
            optionalInteger("hp_base"), optionalInteger("hp_bonus"), optionalInteger("sp_base"), optionalInteger("sp_bonus"),
            optionalInteger("str_real"), optionalInteger("str_bonus"), optionalInteger("int_real"), optionalInteger("int_bonus"),
            optionalInteger("dex_real"), optionalInteger("dex_bonus"), optionalInteger("spd_real"), optionalInteger("spd_bonus"),
            optionalInteger("luk_real"), optionalInteger("luk_bonus"), optionalText("exp_description"),
            optionalText("hp_description"), optionalText("sp_description"), optionalText("str_description"),
            optionalText("int_description"), optionalText("dex_description"), optionalText("spd_description"),
            optionalText("luk_description"),
            primaryKey = listOf("character_id"),
        ),
        table(
            "character_status_effects",
            serialId(), requiredBigint("character_id"), requiredInteger("effect_order"),
            requiredVarchar("effect_type", 20), requiredText("name"), requiredText("value_text"),
            requiredText("description"), optionalBoolean("active"),
        ),
        table(
            "character_faith",
            requiredBigint("character_id"), requiredText("god_name"), requiredBigint("current_value"),
            requiredBigint("max_value"), primaryKey = listOf("character_id"),
        ),
        table(
            "character_status_lines",
            serialId(), requiredBigint("character_id"), requiredInteger("line_order"), requiredText("content"),
        ),
        table(
            "character_pattern_slots",
            serialId(), requiredBigint("character_id"), requiredVarchar("slot_code"), requiredText("label"),
            requiredBoolean("can_load"), optionalText("selected_position"), optionalText("guard_value"),
            optionalText("guard_text"),
        ),
        table(
            "character_pattern_options",
            serialId(), requiredBigint("character_id"), requiredVarchar("option_type", 20),
            requiredInteger("option_order"), requiredText("source_value"), requiredText("label"), optionalText("category"),
        ),
        table(
            "character_saved_pattern_rows",
            serialId(), requiredBigint("pattern_slot_id"), requiredInteger("row_index"), requiredText("judge"),
            requiredText("judge_text"), requiredText("quantity"), requiredText("quantity_text"),
            requiredText("skill"), requiredText("skill_text"),
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
            "character_equipment_candidates",
            serialId(), requiredBigint("character_id"), requiredInteger("candidate_order"),
            requiredText("source_value"), requiredVarchar("type_code", 40), requiredText("name"),
            requiredText("icon_url"), requiredText("description"), optionalInteger("quantity"),
        ),
        table(
            "character_equipment_saved_slots",
            serialId(), requiredBigint("character_id"), requiredInteger("slot_number"), requiredInstant("observed_at"),
        ),
        table(
            "character_equipment_saved_items",
            serialId(), requiredBigint("equipment_saved_slot_id"), requiredInteger("item_order"),
            requiredText("equipment_part"), requiredText("name"), requiredText("icon_url"), requiredText("description"),
        ),
        table(
            "character_skills",
            serialId(), requiredBigint("character_id"), requiredVarchar("skill_type"),
            requiredInteger("skill_order"), requiredText("source_value"), requiredText("name"),
            requiredText("icon_url"), requiredText("category"), optionalText("target_text"), optionalText("scope_text"),
            optionalInteger("sp_cost"), optionalText("multiplier_text"), optionalText("description"),
        ),
        table(
            "character_sync_jobs",
            serialId(), requiredBigint("account_id"), requiredVarchar("status"), requiredInteger("roster_count"),
            requiredInteger("synced_count"), optionalText("message"), requiredInstant("started_at"),
            optionalInstant("finished_at"), requiredBoolean("stop_requested"),
            requiredInteger("last_completed_roster_index"), optionalVarchar("current_hof_character_id", 50),
        ),
        table(
            "character_sync_failures",
            serialId(), requiredBigint("sync_job_id"), requiredInteger("failure_order"),
            requiredVarchar("hof_character_id"),
        ),
        table(
            "character_operation_jobs",
            serialId(), requiredBigint("account_id"), requiredVarchar("operation_type", 30),
            requiredVarchar("status", 20), optionalBigint("source_character_id"),
            requiredBigint("target_character_id"), optionalText("request_payload"),
            requiredText("progress_payload"), requiredText("completed_step_ids"),
            optionalText("result_payload"), optionalText("message"), requiredInstant("started_at"),
            requiredInstant("updated_at"), optionalInstant("finished_at"),
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
            "party_preset_folders",
            serialId(), requiredBigint("account_id"), optionalBigint("parent_folder_id"),
            requiredVarchar("name", 100), requiredInteger("display_order"),
            requiredInstant("created_at"), requiredInstant("updated_at"),
        ),
        table(
            "party_presets",
            serialId(), requiredBigint("account_id"), optionalBigint("folder_id"),
            requiredVarchar("name"), requiredInstant("created_at"),
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
            optionalVarchar("hold_message", 1000),
            optionalInstant("last_verified_at"), requiredInstant("created_at"), requiredInstant("updated_at"),
            optionalInstant("finished_at"), requiredBigint("version"),
        ),
        table(
            "quest_automation_selections",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("quest_code", 100),
            requiredBoolean("enabled"), requiredInteger("source_order"),
            requiredVarchar("display_code", 100), requiredVarchar("quest_name", 255),
        ),
        table(
            "home_quest_automation_selections",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("quest_id", 64),
            requiredVarchar("quest_name", 300), requiredBoolean("enabled"), requiredInteger("source_order"),
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
            "union_automation_maps",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("category_id", 50),
            requiredVarchar("map_code", 100), requiredVarchar("preset_mode", 20),
            optionalBigint("party_preset_id"), requiredInteger("execution_order"),
        ),
        table(
            "raid_automation_targets",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("raid_id", 200),
            requiredVarchar("display_name", 255), requiredVarchar("preset_mode", 20),
            optionalBigint("party_preset_id"), requiredInteger("execution_order"),
        ),
        table(
            "fishing_automation_settings",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("preset_mode", 20),
            optionalBigint("party_preset_id"),
        ),
        table(
            "fishing_automation_maps",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("category_id", 50),
            requiredVarchar("map_code", 100), requiredVarchar("preset_mode", 20),
            optionalBigint("party_preset_id"), requiredInteger("execution_order"),
        ),
        table(
            "automation_rotation_states",
            serialId(), requiredBigint("automation_entry_id"), requiredVarchar("current_target_key", 255),
            requiredInstant("updated_at"), requiredBigint("version"),
        ),
        table(
            "raid_automation_cycles",
            serialId(), requiredBigint("account_id"), optionalBigint("automation_entry_id"),
            requiredVarchar("raid_id", 200), requiredVarchar("raid_name", 255), requiredVarchar("status", 32),
            optionalVarchar("last_observed_status", 32), optionalInstant("next_check_at"), optionalInteger("open_marker"),
            optionalVarchar("battle_recovery_chain_id", 64),
            optionalVarchar("battle_recovery_original_execution_identity", 128),
            optionalVarchar("battle_recovery_latest_execution_identity", 128),
            optionalInstant("battle_recovery_first_ambiguous_at"),
            optionalInstant("battle_recovery_last_submitted_at"),
            optionalInteger("battle_recovery_retransmission_count"),
            optionalInstant("battle_recovery_next_check_at"),
            optionalVarchar("battle_recovery_category_id", 50),
            optionalVarchar("battle_recovery_map_code", 100),
            optionalBoolean("battle_recovery_submitted_from_runnable"),
            optionalVarchar("battle_recovery_last_observation", 32),
            requiredInstant("started_at"), requiredInstant("updated_at"), optionalInstant("finished_at"), requiredBigint("version"),
        ),
        table(
            "automation_decision_cycles",
            serialId(), requiredBigint("account_id"), requiredVarchar("result", 32), optionalBigint("selected_entry_id"),
            requiredInstant("started_at"), requiredInstant("finished_at"),
        ),
        table(
            "automation_decision_events",
            serialId(), requiredBigint("decision_cycle_id"), requiredInteger("sequence_no"), optionalBigint("automation_entry_id"),
            optionalVarchar("automation_type", 30), requiredVarchar("event_kind", 32), requiredVarchar("reason_code", 100),
            requiredVarchar("message", 1000), optionalVarchar("target_key", 255), optionalVarchar("target_name", 255),
            optionalVarchar("action_kind", 64), optionalBigint("preset_id"), optionalVarchar("preset_name", 255),
            optionalInstant("next_run_at"), requiredInstant("occurred_at"),
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
            requiredInteger("retry_attempt"), optionalInstant("next_attempt_at"), optionalVarchar("wait_reason", 30),
            optionalVarchar("lease_token", 128),
            optionalInstant("lease_until"), optionalBigint("stop_action_id"), optionalText("warning_text"), optionalText("last_error"),
            optionalVarchar("requested_lifecycle", 20),
            requiredInstant("created_at"), requiredInstant("updated_at"), requiredBigint("version"),
            primaryKey = listOf("account_id"),
        ),
        table(
            "typed_automation_action_runs",
            serialId(), requiredBigint("account_id"), optionalBigint("automation_entry_id"),
            requiredVarchar("execution_identity", 128), requiredVarchar("action_kind", 30),
            requiredText("payload_json"), requiredVarchar("action_fingerprint", 64), requiredVarchar("status", 20),
            requiredInteger("retry_attempt"), optionalInstant("next_attempt_at"), requiredVarchar("lease_token", 128),
            optionalText("last_error"), requiredInstant("created_at"), optionalInstant("submitted_at"),
            optionalInstant("finished_at"), requiredInstant("updated_at"),
        ),
        table(
            "automation_action_attempts",
            serialId(), requiredBigint("account_id"), optionalBigint("automation_entry_id"),
            requiredVarchar("execution_identity", 128), requiredVarchar("action_kind", 50),
            requiredVarchar("scope_kind", 50), requiredVarchar("scope_key", 200),
            requiredVarchar("policy_version", 80), requiredVarchar("baseline_fingerprint", 128),
            requiredBoolean("observation_only"),
            requiredInstant("created_at"), optionalInstant("submitted_at"),
        ),
        table(
            "automation_action_convergences",
            serialId(), requiredBigint("attempt_id"), requiredBigint("account_id"),
            requiredVarchar("scope_kind", 50), requiredVarchar("scope_key", 200),
            requiredVarchar("result", 30), optionalInteger("active_marker"),
            requiredInteger("successful_observation_count"), optionalInstant("first_pending_at"),
            optionalInstant("next_probe_at"), optionalVarchar("reason_code", 100),
            optionalVarchar("evidence_case_id", 64), optionalInstant("suppression_released_at"),
            optionalInstant("finished_at"), requiredInstant("updated_at"), requiredBigint("version"),
        ),
        table(
            "automation_account_battle_gates",
            requiredBigint("account_id"), optionalBigint("challenge_id"), requiredVarchar("reason", 100),
            requiredInstant("opened_at"), optionalInstant("resolved_at"), requiredBigint("version"),
            primaryKey = listOf("account_id"),
        ),
        table(
            "automation_evidence_cases",
            requiredVarchar("id", 64), requiredBigint("attempt_id"), requiredVarchar("evidence_source", 40),
            optionalVarchar("observation_completeness", 30), optionalVarchar("observation_freshness", 20),
            optionalVarchar("state_fingerprint", 128), optionalVarchar("response_shape_fingerprint", 128),
            optionalVarchar("sanitized_snippet", 1000), requiredVarchar("reason_code", 100),
            requiredVarchar("policy_version", 80), requiredVarchar("build_version", 80),
            requiredInstant("created_at"), requiredInstant("expires_at"),
            primaryKey = listOf("id"),
        ),
        table(
            "automation_convergence_shadow_evaluations",
            requiredVarchar("id", 64), requiredBigint("account_id"),
            requiredVarchar("execution_identity_hash", 64), requiredVarchar("action_kind", 50),
            requiredVarchar("scope_kind", 50), requiredVarchar("scope_key_hash", 64),
            requiredVarchar("evidence_kind", 50), requiredVarchar("evidence_completeness", 40),
            requiredVarchar("response_shape_fingerprint", 64), requiredVarchar("sanitized_snippet", 1000),
            requiredVarchar("legacy_decision", 40),
            requiredVarchar("legacy_reason_code", 100), requiredVarchar("new_result", 30),
            requiredVarchar("new_reason_code", 100), requiredBoolean("result_differs"),
            requiredBoolean("reason_differs"), requiredBoolean("shape_differs"),
            requiredBoolean("completeness_differs"), requiredVarchar("policy_version", 80),
            requiredVarchar("build_version", 80), requiredInstant("created_at"), requiredInstant("expires_at"),
            primaryKey = listOf("id"),
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
        table(
            "town_feature_locations",
            requiredVarchar("feature_id", 50), optionalVarchar("href", 500), optionalInstant("observed_at"),
            primaryKey = listOf("feature_id"),
        ),
        table(
            "shop_catalog_item",
            requiredVarchar("shop_id", 20), requiredVarchar("item_key", 200), requiredVarchar("name", 300),
            optionalVarchar("item_type", 100), optionalText("description"), requiredBigint("price"),
            requiredBoolean("active"), requiredInstant("last_seen_at"),
            primaryKey = listOf("shop_id", "item_key"),
        ),
        table(
            "town_global_job_lease",
            requiredVarchar("job_key", 100), optionalVarchar("lease_owner", 100), optionalInstant("lease_until"),
            optionalInstant("last_attempt_at"), optionalInstant("last_success_at"),
            primaryKey = listOf("job_key"),
        ),
        table(
            "auction_observation",
            requiredVarchar("observation_key", 128), optionalVarchar("listing_id", 120),
            requiredVarchar("observation_kind", 20), requiredVarchar("item_key", 128),
            requiredVarchar("item_name", 300), optionalVarchar("item_type", 100), requiredInteger("quantity"),
            requiredBigint("total_price"), requiredBigint("unit_price"), requiredInstant("observed_at"),
            requiredInstant("last_seen_at"), primaryKey = listOf("observation_key"),
        ),
    )

    private val UNIQUE_KEYS = listOf(
        key("app_releases", "uk_app_releases_platform_version", "platform", "version_code"),
        key("app_releases", "uk_app_releases_file_name", "file_name"),
        key("hof_accounts", "uk_hof_accounts_login_id", "login_id"),
        key("latest_hof_status", "uk_latest_hof_status_account", "account_id"),
        key("hof_cookies", "uk_hof_cookies_account_name", "account_id", "name"),
        key("refresh_tokens", "uk_refresh_tokens_token_hash", "token_hash"),
        key("characters", "uk_characters_account_current_hof_character", "account_id", "current_hof_character_id"),
        key("characters", "uk_characters_id_account", "id", "account_id"),
        key(
            "character_hof_id_history", "uk_character_hof_id_history_account_hof_character",
            "account_id", "hof_character_id",
        ),
        key(
            "character_hof_id_history", "uk_character_hof_id_history_character_open",
            "character_id", "open_marker",
        ),
        key("character_status_lines", "uk_character_status_lines_character_order", "character_id", "line_order"),
        key("character_status_effects", "uk_character_status_effects_character_order", "character_id", "effect_order"),
        key("character_pattern_slots", "uk_character_pattern_slots_character_code", "character_id", "slot_code"),
        key(
            "character_pattern_options", "uk_character_pattern_options_character_type_order",
            "character_id", "option_type", "option_order",
        ),
        key("character_saved_pattern_rows", "uk_character_saved_pattern_rows_slot_row", "pattern_slot_id", "row_index"),
        key("character_action_patterns", "uk_character_action_patterns_character_row", "character_id", "row_index"),
        key("character_position_choices", "uk_character_position_choices_character_order", "character_id", "choice_order"),
        key("character_equipment", "uk_character_equipment_character_order", "character_id", "equipment_order"),
        key(
            "character_equipment_candidates", "uk_character_equipment_candidates_character_order",
            "character_id", "candidate_order",
        ),
        key(
            "character_equipment_saved_slots", "uk_character_equipment_saved_slots_character_number",
            "character_id", "slot_number",
        ),
        key(
            "character_equipment_saved_items", "uk_character_equipment_saved_items_slot_order",
            "equipment_saved_slot_id", "item_order",
        ),
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
        key("home_quest_automation_selections", "uk_home_quest_automation_selection", "automation_entry_id", "quest_id"),
        key("home_quest_automation_selections", "uk_home_quest_automation_order", "automation_entry_id", "source_order"),
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
        key("union_automation_maps", "uk_union_automation_maps_target", "automation_entry_id", "category_id", "map_code"),
        key("raid_automation_targets", "uk_raid_automation_targets_target", "automation_entry_id", "raid_id"),
        key("fishing_automation_settings", "uk_fishing_automation_settings_entry", "automation_entry_id"),
        key("fishing_automation_maps", "uk_fishing_automation_maps_target", "automation_entry_id", "category_id", "map_code"),
        key("automation_rotation_states", "uk_automation_rotation_states_entry", "automation_entry_id"),
        key("raid_automation_cycles", "uk_raid_automation_cycles_open", "account_id", "open_marker"),
        key("automation_decision_events", "uk_automation_decision_events_sequence", "decision_cycle_id", "sequence_no"),
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
        key("automation_action_attempts", "uk_automation_action_attempt_execution", "account_id", "execution_identity"),
        key("automation_action_convergences", "uk_automation_action_convergence_attempt", "attempt_id"),
        key(
            "automation_action_convergences",
            "uk_automation_action_convergence_active_scope",
            "account_id", "scope_kind", "scope_key", "active_marker",
        ),
    )

    private val FOREIGN_KEYS = listOf(
        fk("fk_hof_cookies_account", "hof_cookies.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_latest_hof_status_account", "latest_hof_status.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_refresh_tokens_account", "refresh_tokens.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_characters_account", "characters.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk(
            "fk_character_hof_id_history_character_account", "character_hof_id_history.account_id",
            "characters.account_id", DeleteAction.CASCADE,
        ),
        fk("fk_character_stats_character", "character_stats.character_id", "characters.id", DeleteAction.CASCADE),
        fk(
            "fk_character_section_sync_states_character", "character_section_sync_states.character_id",
            "characters.id", DeleteAction.CASCADE,
        ),
        fk("fk_character_status_effects_character", "character_status_effects.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_faith_character", "character_faith.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_status_lines_character", "character_status_lines.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_pattern_slots_character", "character_pattern_slots.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_pattern_options_character", "character_pattern_options.character_id", "characters.id", DeleteAction.CASCADE),
        fk(
            "fk_character_saved_pattern_rows_slot", "character_saved_pattern_rows.pattern_slot_id",
            "character_pattern_slots.id", DeleteAction.CASCADE,
        ),
        fk("fk_character_action_patterns_character", "character_action_patterns.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_guard_settings_character", "character_guard_settings.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_position_choices_character", "character_position_choices.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_equipment_character", "character_equipment.character_id", "characters.id", DeleteAction.CASCADE),
        fk(
            "fk_character_equipment_candidates_character", "character_equipment_candidates.character_id",
            "characters.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_character_equipment_saved_slots_character", "character_equipment_saved_slots.character_id",
            "characters.id", DeleteAction.CASCADE,
        ),
        fk(
            "fk_character_equipment_saved_items_slot", "character_equipment_saved_items.equipment_saved_slot_id",
            "character_equipment_saved_slots.id", DeleteAction.CASCADE,
        ),
        fk("fk_character_skills_character", "character_skills.character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_character_sync_jobs_account", "character_sync_jobs.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_character_sync_failures_job", "character_sync_failures.sync_job_id", "character_sync_jobs.id", DeleteAction.CASCADE),
        fk("fk_character_operation_jobs_account", "character_operation_jobs.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_character_operation_jobs_source", "character_operation_jobs.source_character_id", "characters.id", DeleteAction.SET_NULL),
        fk("fk_character_operation_jobs_target", "character_operation_jobs.target_character_id", "characters.id", DeleteAction.CASCADE),
        fk("fk_battle_maps_group", "battle_maps.group_id", "battle_map_groups.id", DeleteAction.SET_NULL),
        fk("fk_battle_map_aliases_map", "battle_map_aliases.battle_map_id", "battle_maps.id", DeleteAction.CASCADE),
        fk("fk_account_battle_map_states_account", "account_battle_map_states.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_account_battle_map_states_map", "account_battle_map_states.battle_map_id", "battle_maps.id", DeleteAction.CASCADE),
        fk("fk_unresolved_battle_maps_account", "unresolved_battle_maps.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_party_preset_folders_account", "party_preset_folders.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk(
            "fk_party_preset_folders_parent", "party_preset_folders.parent_folder_id",
            "party_preset_folders.id", DeleteAction.CASCADE,
        ),
        fk("fk_party_presets_account", "party_presets.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_party_presets_folder", "party_presets.folder_id", "party_preset_folders.id", DeleteAction.SET_NULL),
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
            "fk_home_quest_automation_selections_entry", "home_quest_automation_selections.automation_entry_id",
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
        fk("fk_union_automation_maps_entry", "union_automation_maps.automation_entry_id", "automation_entries.id", DeleteAction.CASCADE),
        fk("fk_union_automation_maps_preset", "union_automation_maps.party_preset_id", "party_presets.id", DeleteAction.SET_NULL),
        fk("fk_raid_automation_targets_entry", "raid_automation_targets.automation_entry_id", "automation_entries.id", DeleteAction.CASCADE),
        fk("fk_raid_automation_targets_preset", "raid_automation_targets.party_preset_id", "party_presets.id", DeleteAction.SET_NULL),
        fk("fk_fishing_automation_settings_entry", "fishing_automation_settings.automation_entry_id", "automation_entries.id", DeleteAction.CASCADE),
        fk("fk_fishing_automation_settings_preset", "fishing_automation_settings.party_preset_id", "party_presets.id", DeleteAction.SET_NULL),
        fk("fk_fishing_automation_maps_entry", "fishing_automation_maps.automation_entry_id", "automation_entries.id", DeleteAction.CASCADE),
        fk("fk_fishing_automation_maps_preset", "fishing_automation_maps.party_preset_id", "party_presets.id", DeleteAction.SET_NULL),
        fk("fk_automation_rotation_states_entry", "automation_rotation_states.automation_entry_id", "automation_entries.id", DeleteAction.CASCADE),
        fk("fk_raid_automation_cycles_account", "raid_automation_cycles.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_raid_automation_cycles_entry", "raid_automation_cycles.automation_entry_id", "automation_entries.id", DeleteAction.SET_NULL),
        fk("fk_automation_decision_cycles_account", "automation_decision_cycles.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_automation_decision_cycles_entry", "automation_decision_cycles.selected_entry_id", "automation_entries.id", DeleteAction.SET_NULL),
        fk("fk_automation_decision_events_cycle", "automation_decision_events.decision_cycle_id", "automation_decision_cycles.id", DeleteAction.CASCADE),
        fk("fk_automation_decision_events_entry", "automation_decision_events.automation_entry_id", "automation_entries.id", DeleteAction.SET_NULL),
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
        fk("fk_automation_action_attempt_account", "automation_action_attempts.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_automation_action_attempt_entry", "automation_action_attempts.automation_entry_id", "automation_entries.id", DeleteAction.SET_NULL),
        fk("fk_automation_action_convergence_attempt", "automation_action_convergences.attempt_id", "automation_action_attempts.id", DeleteAction.CASCADE),
        fk("fk_automation_action_convergence_account", "automation_action_convergences.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_automation_account_battle_gate_account", "automation_account_battle_gates.account_id", "hof_accounts.id", DeleteAction.CASCADE),
        fk("fk_automation_evidence_case_attempt", "automation_evidence_cases.attempt_id", "automation_action_attempts.id", DeleteAction.CASCADE),
        fk(
            "fk_automation_convergence_shadow_account",
            "automation_convergence_shadow_evaluations.account_id",
            "hof_accounts.id",
            DeleteAction.CASCADE,
        ),
        fk(
            "fk_captcha_form_fields_challenge", "captcha_form_fields.challenge_id",
            "captcha_challenges.id", DeleteAction.CASCADE,
        ),
    )

    private val INDEXES = listOf(
        index("app_releases", "idx_app_releases_platform_latest", "platform", "version_code"),
        index("auction_observation", "idx_auction_observation_market", "item_key", "observation_kind", "observed_at"),
        index("shop_catalog_item", "idx_shop_catalog_item_active_name", "shop_id", "active", "name", "item_key"),
        index("hof_cookies", "idx_hof_cookies_account_updated", "account_id", "updated_at", "id"),
        index("refresh_tokens", "idx_refresh_tokens_family_created", "family_id", "created_at", "id"),
        index("refresh_tokens", "idx_refresh_tokens_account_active", "account_id", "revoked_at", "expires_at", "id"),
        index("characters", "idx_characters_account_name", "account_id", "name", "id"),
        index("characters", "idx_characters_account_detail_synced", "account_id", "detail_synced_at", "id"),
        index(
            "characters", "idx_characters_account_lifecycle_roster",
            "account_id", "lifecycle", "roster_order", "id",
        ),
        index(
            "character_hof_id_history", "idx_character_hof_id_history_character_time",
            "character_id", "valid_from", "id",
        ),
        index(
            "character_section_sync_states", "idx_character_section_sync_states_status",
            "character_id", "status", "last_attempted_at",
        ),
        index(
            "character_status_effects", "idx_character_status_effects_character",
            "character_id", "effect_order", "id",
        ),
        index(
            "character_pattern_options", "idx_character_pattern_options_character",
            "character_id", "option_type", "option_order", "id",
        ),
        index(
            "character_equipment_candidates", "idx_character_equipment_candidates_character",
            "character_id", "type_code", "candidate_order", "id",
        ),
        index(
            "character_saved_pattern_rows", "idx_character_saved_pattern_rows_slot",
            "pattern_slot_id", "row_index", "id",
        ),
        index(
            "character_equipment_saved_items", "idx_character_equipment_saved_items_slot",
            "equipment_saved_slot_id", "item_order", "id",
        ),
        index("character_sync_jobs", "idx_character_sync_jobs_account_started", "account_id", "started_at", "id"),
        index("character_operation_jobs", "idx_character_operation_jobs_account_started", "account_id", "started_at", "id"),
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
        index(
            "party_preset_folders", "idx_party_preset_folders_account_parent_order",
            "account_id", "parent_folder_id", "display_order", "id",
        ),
        index(
            "party_presets", "idx_party_presets_account_folder_order",
            "account_id", "folder_id", "display_order", "id",
        ),
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
            "home_quest_automation_selections", "idx_home_quest_automation_entry_order",
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
        index("union_automation_maps", "idx_union_automation_maps_entry_order", "automation_entry_id", "execution_order", "id"),
        index("raid_automation_targets", "idx_raid_automation_targets_entry_order", "automation_entry_id", "execution_order", "id"),
        index("fishing_automation_maps", "idx_fishing_automation_maps_entry_order", "automation_entry_id", "execution_order", "id"),
        index("raid_automation_cycles", "idx_raid_automation_cycles_account_started", "account_id", "started_at", "id"),
        index("raid_automation_cycles", "idx_raid_automation_cycles_due", "open_marker", "next_check_at", "account_id", "id"),
        index("automation_decision_cycles", "idx_automation_decision_cycles_account_cursor", "account_id", "id"),
        index("automation_decision_cycles", "idx_automation_decision_cycles_account_time", "account_id", "started_at", "id"),
        index("automation_decision_events", "idx_automation_decision_events_cycle_sequence", "decision_cycle_id", "sequence_no", "id"),
        index("automation_decision_events", "idx_automation_decision_events_filters", "automation_type", "event_kind", "decision_cycle_id"),
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
        index(
            "automation_action_convergences",
            "idx_automation_action_convergence_due",
            "account_id", "active_marker", "next_probe_at", "id",
        ),
        index("automation_evidence_cases", "idx_automation_evidence_cases_expiry", "expires_at", "id"),
        index(
            "automation_convergence_shadow_evaluations",
            "idx_automation_convergence_shadow_gate",
            "created_at", "action_kind", "result_differs", "reason_differs", "shape_differs", "completeness_differs",
        ),
        index(
            "automation_convergence_shadow_evaluations",
            "idx_automation_convergence_shadow_expiry",
            "expires_at", "id",
        ),
    )

    private val CHECKS = listOf(
        check("app_releases", "ck_app_releases_platform", "platform = 'ANDROID'"),
        check("app_releases", "ck_app_releases_version_code", "version_code > cast(0 as bigint)"),
        check("app_releases", "ck_app_releases_file_size", "file_size > cast(0 as bigint)"),
        check("app_releases", "ck_app_releases_sha256", "char_length(sha256) = 64"),
        check("app_releases", "ck_app_releases_jenkins_build", "jenkins_build > cast(0 as bigint)"),
        check("auction_observation", "ck_auction_observation_kind", "observation_kind in ('CURRENT', 'SOLD')"),
        check("auction_observation", "ck_auction_observation_quantity", "quantity > 0"),
        check("auction_observation", "ck_auction_observation_prices", "total_price >= cast(0 as bigint) and unit_price >= cast(0 as bigint)"),
        check("shop_catalog_item", "ck_shop_catalog_item_shop", "shop_id in ('GENERAL', 'SUNDRIES', 'DARK')"),
        check("shop_catalog_item", "ck_shop_catalog_item_price", "price >= cast(0 as bigint)"),
        check("town_global_job_lease", "ck_town_global_job_lease_pair", "((lease_owner is null) and (lease_until is null)) or ((lease_owner is not null) and (lease_until is not null))"),
        check("characters", "ck_characters_pattern_slot_count", "pattern_slot_count >= 0"),
        check(
            "characters", "ck_characters_lifecycle",
            "case lifecycle when 'ACTIVE' then true when 'MISSING' then true when 'ARCHIVED' then true else false end",
        ),
        check("characters", "ck_characters_roster_order", "roster_order is null or roster_order >= 0"),
        check(
            "character_hof_id_history", "ck_character_hof_id_history_reason",
            "case link_reason when 'INITIAL_SYNC' then true when 'KNOCKBACK' then true " +
                "when 'MANUAL_LINK' then true when 'REAPPEARED' then true else false end",
        ),
        check(
            "character_hof_id_history", "ck_character_hof_id_history_open_marker",
            "(valid_to is null and open_marker = 1) or (valid_to is not null and open_marker is null)",
        ),
        check(
            "character_section_sync_states", "ck_character_section_sync_states_section",
            "case section when 'PROFILE' then true when 'STATS' then true when 'EFFECTS_FAITH' then true " +
                "when 'CURRENT_PATTERN' then true when 'POSITION_GUARD' then true when 'SAVED_PATTERNS' then true " +
                "when 'EQUIPMENT' then true when 'EQUIPMENT_CANDIDATES' then true when 'SKILLS' then true " +
                "when 'MANAGEMENT' then true else false end",
        ),
        check(
            "character_section_sync_states", "ck_character_section_sync_states_status",
            "case status when 'SUCCESS' then true when 'FAILED' then true else false end",
        ),
        check(
            "character_section_sync_states", "ck_character_section_sync_states_success",
            "(status = 'FAILED' and error_code is not null and error_message is not null) or " +
                "(status = 'SUCCESS' and last_succeeded_at is not null and error_code is null and error_message is null)",
        ),
        check(
            "character_section_sync_states", "ck_character_section_sync_states_count",
            "observed_count is null or observed_count >= 0",
        ),
        check("character_status_effects", "ck_character_status_effects_order", "effect_order >= 0"),
        check(
            "character_status_effects", "ck_character_status_effects_type",
            "case effect_type when 'SET' then true when 'EFFECT' then true else false end",
        ),
        check(
            "character_faith", "ck_character_faith_values",
            "current_value >= cast(0 as bigint) and max_value >= cast(0 as bigint)",
        ),
        check(
            "character_pattern_options", "ck_character_pattern_options_type",
            "case option_type when 'CONDITION' then true when 'SKILL' then true when 'CLASS' then true else false end",
        ),
        check("character_pattern_options", "ck_character_pattern_options_order", "option_order >= 0"),
        check("character_equipment_candidates", "ck_character_equipment_candidates_order", "candidate_order >= 0"),
        check(
            "character_equipment_candidates", "ck_character_equipment_candidates_quantity",
            "quantity is null or quantity >= 0",
        ),
        check("character_saved_pattern_rows", "ck_character_saved_pattern_rows_index", "row_index >= 0"),
        check(
            "character_equipment_saved_slots", "ck_character_equipment_saved_slots_number",
            "slot_number between 1 and 2",
        ),
        check("character_equipment_saved_items", "ck_character_equipment_saved_items_order", "item_order >= 0"),
        check(
            "automation_work_sessions", "ck_automation_work_type",
            "locate(',' || work_type || ',', ',QUEST,HOME_QUEST,BATTLE_MAP,ADVENTURE_MAP,RAID,UNION,FISHING,') > 0",
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
        check("typed_automation_runtime_states", "ck_typed_runtime_lifecycle", "locate(',' || lifecycle_status || ',', ',RUNNING,DRAINING,PAUSED,STOPPED,') > 0"),
        check("typed_automation_runtime_states", "ck_typed_runtime_stop", "(lifecycle_status = 'STOPPED' and stop_reason is not null) or (lifecycle_status <> 'STOPPED' and (stop_reason is null or stop_reason <> 'MANUAL_STOP'))"),
        check("typed_automation_runtime_states", "ck_typed_runtime_retry", "retry_attempt >= 0"),
        check(
            "typed_automation_runtime_states",
            "ck_typed_runtime_wait_reason",
            "wait_reason is null or locate(',' || wait_reason || ',', ',SCHEDULED,HOF_CONNECTION,') > 0",
        ),
        check("typed_automation_runtime_states", "ck_typed_runtime_lease", "(lease_token is null and lease_until is null) or (lease_token is not null and lease_until is not null)"),
        check("typed_automation_runtime_states", "ck_typed_runtime_stop_action", "lifecycle_status = 'STOPPED' or stop_action_id is null"),
        check("typed_automation_action_runs", "ck_typed_action_status", "locate(',' || status || ',', ',PREPARED,SUBMITTING,RECONCILING,SUCCEEDED,FAILED,AMBIGUOUS,') > 0"),
        check("typed_automation_action_runs", "ck_typed_action_retry", "retry_attempt >= 0"),
        check("typed_automation_action_runs", "ck_typed_action_fingerprint", "char_length(action_fingerprint) = 64"),
        check(
            "automation_action_attempts",
            "ck_automation_action_attempt_scope_key",
            "char_length(trim(scope_key)) between 1 and 200",
        ),
        check(
            "automation_action_attempts",
            "ck_automation_action_attempt_baseline",
            "char_length(trim(baseline_fingerprint)) between 1 and 128",
        ),
        check(
            "automation_action_convergences",
            "ck_automation_action_convergence_result",
            "result is null or locate(',' || result || ',', ',APPLIED,NOT_APPLIED,SUPERSEDED,PENDING,HELD,RESULT_UNOBSERVED,') > 0",
        ),
        check(
            "automation_action_convergences",
            "ck_automation_action_convergence_active",
            "(result = 'PENDING' and active_marker = 1 and finished_at is null) or " +
                "(result <> 'PENDING' and active_marker is null and finished_at is not null)",
        ),
        check(
            "automation_action_convergences",
            "ck_automation_action_convergence_observations",
            "successful_observation_count >= 0",
        ),
        check(
            "town_feature_locations", "ck_town_feature_locations_public_menu",
            "href regexp '^[?]menu=[A-Za-z0-9_-]{1,80}$'",
        ),
        check(
            "town_feature_locations", "ck_town_feature_locations_observation_pair",
            "(href is null and observed_at is null) or (href is not null and observed_at is not null)",
        ),
        check("character_status_lines", "ck_character_status_lines_order", "line_order >= 0"),
        check("character_action_patterns", "ck_character_action_patterns_row", "row_index >= 0"),
        check("character_position_choices", "ck_character_position_choices_order", "choice_order >= 0"),
        check("character_equipment", "ck_character_equipment_order", "equipment_order >= 0"),
        check("character_skills", "ck_character_skills_order", "skill_order >= 0"),
        check("character_stats", "ck_character_stats_status_points", "status_points is null or status_points >= 0"),
        check("character_stats", "ck_character_stats_skill_points", "skill_points is null or skill_points >= 0"),
        check("character_sync_jobs", "ck_character_sync_jobs_roster_count", "roster_count >= 0"),
        check("character_sync_jobs", "ck_character_sync_jobs_synced_count", "synced_count >= 0"),
        check(
            "character_sync_jobs",
            "ck_character_sync_jobs_last_completed_index",
            "last_completed_roster_index >= -1",
        ),
        check("character_sync_failures", "ck_character_sync_failures_order", "failure_order >= 0"),
        check(
            "character_operation_jobs",
            "ck_character_operation_jobs_type",
            "operation_type in ('DEEP_SYNC', 'RESTORE', 'TRANSFER')",
        ),
        check(
            "character_operation_jobs",
            "ck_character_operation_jobs_status",
            "status in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'STOPPED')",
        ),
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
        check("party_preset_folders", "ck_party_preset_folders_display_order", "display_order >= 0"),
        check(
            "party_presets", "ck_party_presets_primary_marker",
            "(is_primary and primary_marker = 1) or (is_primary = false and primary_marker is null)",
        ),
        check(
            "automation_entries", "ck_automation_entries_type",
            "locate(',' || automation_type || ',', ',QUEST,HOME_QUEST,BATTLE_MAP,ADVENTURE_MAP,RAID,UNION,FISHING,') > 0",
        ),
        check("automation_entries", "ck_automation_entries_priority", "priority >= 0"),
        check(
            "quest_automation_selections", "ck_quest_automation_selections_source_order", "source_order >= 0",
        ),
        check("home_quest_automation_selections", "ck_home_quest_automation_order", "source_order >= 0"),
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
        check("union_automation_maps", "ck_union_automation_maps_category", "category_id = 'union'"),
        check("union_automation_maps", "ck_union_automation_maps_preset_mode", "case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end"),
        check("union_automation_maps", "ck_union_automation_maps_order", "execution_order >= 0"),
        check("raid_automation_targets", "ck_raid_automation_targets_preset_mode", "case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end"),
        check("raid_automation_targets", "ck_raid_automation_targets_order", "execution_order >= 0"),
        check("fishing_automation_settings", "ck_fishing_automation_settings_preset_mode", "case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end"),
        check("fishing_automation_maps", "ck_fishing_automation_maps_preset_mode", "case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end"),
        check("fishing_automation_maps", "ck_fishing_automation_maps_order", "execution_order >= 0"),
        check("raid_automation_cycles", "ck_raid_automation_cycles_status", "locate(',' || status || ',', ',PREPARING,REGISTERED_WAITING,IN_BATTLE,REWARD_PENDING,POST_REWARD_CHECK,COMPLETED,ABORTED_CLOSED,ABORTED_REGISTRATION_LOST,HANDED_OFF_MANUAL,SUPERSEDED_BY_OBSERVED_RAID,') > 0"),
        check("raid_automation_cycles", "ck_raid_automation_cycles_open", "(open_marker is null and finished_at is not null and locate(',' || status || ',', ',COMPLETED,ABORTED_CLOSED,ABORTED_REGISTRATION_LOST,HANDED_OFF_MANUAL,SUPERSEDED_BY_OBSERVED_RAID,') > 0) or (open_marker = 1 and finished_at is null and locate(',' || status || ',', ',PREPARING,REGISTERED_WAITING,IN_BATTLE,REWARD_PENDING,POST_REWARD_CHECK,') > 0)"),
        check("raid_automation_cycles", "ck_raid_battle_recovery_count", "battle_recovery_retransmission_count is null or battle_recovery_retransmission_count >= 0"),
        check("automation_decision_cycles", "ck_automation_decision_cycles_result", "locate(',' || result || ',', ',ACTION_SELECTED,WAITING,IDLE,FATAL,') > 0"),
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
private fun optionalBoolean(name: String): ColumnContract = ColumnContract(name, TypeFamily.BOOLEAN, nullable = true)
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
