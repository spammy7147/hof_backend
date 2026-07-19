# Accept All Selected Quests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept every selected, enabled quest that is currently available, regardless of mission type or completion state.

**Architecture:** Keep the existing quest priority pipeline and broaden only the available-quest predicate. Preserve claim priority, action-number validation, post-accept cycle creation, and the fresh-snapshot wakeup flow.

**Tech Stack:** Kotlin, Spring Boot, kotlin.test, Gradle

---

### Task 1: Broaden quest acceptance

**Files:**
- Modify: `src/test/kotlin/app/spammy/hof/automation/service/QuestAutomationHandlerTest.kt`
- Modify: `src/main/kotlin/app/spammy/hof/automation/service/QuestAutomationHandler.kt:258`

- [x] **Step 1: Write the failing tests**

Change the existing incomplete item and unsupported mission expectations to require `QuestAction.Accept`, and add a combat regression proving an available map-clear quest is accepted before active combat.

- [x] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'app.spammy.hof.automation.service.QuestAutomationHandlerTest'`

Expected: the new acceptance assertions fail because available non-immediate quests currently return `Skipped` or allow another combat action.

- [x] **Step 3: Implement the minimal predicate change**

Replace the `AVAILABLE && isImmediatelyCompletable()` predicate with `AVAILABLE`, then remove the unused helper.

- [x] **Step 4: Run focused and full verification**

Run the focused handler test followed by `./gradlew test`. Both commands must exit successfully with no failed tests.

- [x] **Step 5: Commit**

Stage the two implementation files and these design records, then commit with `fix: accept all selected quests`.
