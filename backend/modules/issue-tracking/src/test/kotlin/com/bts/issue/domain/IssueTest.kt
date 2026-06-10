// Issue Aggregate Root 단위 테스트 — factory, invariants, version, deletedAt, typeId 필수, 5필드 불변식, assigneeId, componentIds, affectsVersionIds, fixVersionIds

package com.bts.issue.domain

import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [Issue] Aggregate Root 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - create_returns_issue — 유효한 인자로 Issue 를 생성하면 모든 필드가 기대 값과 일치한다.
 * - create_sets_version_to_one — 생성 직후 version 은 항상 1 이다.
 * - create_sets_deletedAt_null — 생성 직후 deletedAt 은 항상 null 이다.
 * - create_requires_typeId — typeId 를 IssueTypeId VO 로 받아 issue.typeId 에 반영한다.
 * - reject_blank_summary — summary 가 빈 문자열이면 IllegalArgumentException 을 던진다.
 * - reject_whitespace_only_summary — summary 가 공백만이면 IllegalArgumentException 을 던진다.
 * - reject_summary_over_255 — summary 가 256자 이상이면 IllegalArgumentException 을 던진다.
 * - accept_summary_exactly_255 — summary 가 정확히 255자이면 정상 생성한다.
 * - create_default_priority_is_3 — priority 기본값은 3 이다.
 * - create_default_labels_is_empty — labels 기본값은 빈 리스트다.
 * - create_default_optional_fields_null — description/environment/impact 기본값은 null 이다.
 * - labels_dedup_exact_match — 중복 라벨은 exact match(대소문자 구분) 기준으로 제거된다.
 * - labels_reject_blank — 빈 문자열 라벨은 제거된다.
 * - labels_reject_whitespace — 공백만으로 구성된 라벨은 예외를 던진다.
 * - labels_reject_over_50_chars — 51자 이상 라벨은 예외를 던진다.
 * - labels_reject_over_20_per_issue — 이슈당 21개 이상 라벨은 예외를 던진다.
 * - priority_reject_below_1 — priority 가 1 미만이면 예외를 던진다.
 * - priority_reject_above_5 — priority 가 5 초과이면 예외를 던진다.
 * - priority_accept_boundary_values — priority 1과 5는 정상 생성한다.
 * - impact_reject_below_1 — impact 가 1 미만이면 예외를 던진다.
 * - impact_reject_above_3 — impact 가 3 초과이면 예외를 던진다.
 * - impact_accept_boundary_values — impact 1과 3은 정상 생성한다.
 * - impact_accept_null — impact null 은 정상 생성한다.
 * - create_default_assigneeId_null — assigneeId 기본값은 null 이다.
 * - assignTo_sets_assigneeId — assignTo(actorId) 호출 후 반환된 Issue 의 assigneeId 가 그 actorId 와 일치한다.
 * - unassign_clears_assigneeId — unassign() 호출 후 반환된 Issue 의 assigneeId 는 null 이다.
 * - create_default_affectsVersionIds_empty — affectsVersionIds 미지정 시 기본값은 빈 리스트다.
 * - assignAffectsVersions_dedup — 중복 UUID 를 전달하면 distinct 정규화되어 반환된다.
 * - assignAffectsVersions_replaces_existing — 기존 affectsVersionIds 를 새 목록으로 교체한다.
 * - clearAffectsVersions_empties_list — clearAffectsVersions() 호출 후 affectsVersionIds 는 빈 리스트다.
 * - create_default_fixVersionIds_empty — fixVersionIds 미지정 시 기본값은 빈 리스트다.
 * - assignFixVersions_dedup — 중복 UUID 를 전달하면 distinct 정규화되어 반환된다.
 * - assignFixVersions_replaces_existing — 기존 fixVersionIds 를 새 목록으로 교체한다.
 * - clearFixVersions_empties_list — clearFixVersions() 호출 후 fixVersionIds 는 빈 리스트다.
 */
class IssueTest {
    private val validId = IssueId(UUID.randomUUID())
    private val validKey = IssueKey.of("PROJ", 1L)
    private val validProjectId: UUID = UUID.randomUUID()
    private val validSummary = "Fix login bug"
    private val validReporterId = ActorId(UUID.randomUUID())
    private val validStateKey = "open"
    private val validTypeId = IssueTypeId(1L)

    @Test
    fun `create_returns_issue — 유효한 인자로 Issue 를 생성하면 모든 필드가 기대 값과 일치한다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.id).isEqualTo(validId)
        assertThat(issue.key).isEqualTo(validKey)
        assertThat(issue.projectId).isEqualTo(validProjectId)
        assertThat(issue.summary).isEqualTo(validSummary)
        assertThat(issue.reporterId).isEqualTo(validReporterId)
        assertThat(issue.currentStateKey).isEqualTo(validStateKey)
    }

    @Test
    fun `create_sets_version_to_one — 생성 직후 version 은 항상 1 이다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.version).isEqualTo(1L)
    }

    @Test
    fun `create_sets_deletedAt_null — 생성 직후 deletedAt 은 항상 null 이다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.deletedAt).isNull()
    }

    @Test
    fun `create_requires_typeId — typeId 를 IssueTypeId VO 로 받아 issue 의 typeId 에 반영한다`() {
        val typeId = IssueTypeId(42L)
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = typeId,
            )

        assertThat(issue.typeId).isEqualTo(typeId)
    }

    @Test
    fun `reject_blank_summary — summary 가 빈 문자열이면 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = "",
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reject_whitespace_only_summary — summary 가 공백만이면 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = "   ",
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reject_summary_over_255 — summary 가 256자 이상이면 IllegalArgumentException 을 던진다`() {
        val tooLong = "a".repeat(256)

        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = tooLong,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accept_summary_exactly_255 — summary 가 정확히 255자이면 정상 생성한다`() {
        val exactly255 = "a".repeat(255)

        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = exactly255,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.summary).hasSize(255)
    }

    // ─── Task 2: 5필드 기본값 불변식 ───────────────────────────────────────────

    @Test
    fun `create_default_priority_is_3 — priority 기본값은 3 이다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.priority).isEqualTo(3)
    }

    @Test
    fun `create_default_labels_is_empty — labels 기본값은 빈 리스트다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.labels).isEmpty()
    }

    @Test
    fun `create_default_optional_fields_null — description, environment, impact 기본값은 null 이다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.description).isNull()
        assertThat(issue.environment).isNull()
        assertThat(issue.impact).isNull()
    }

    // ─── 라벨 불변식 ──────────────────────────────────────────────────────────

    @Test
    fun `labels_dedup_exact_match — 중복 라벨은 대소문자 구분 exact match 기준으로 제거된다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                labels = listOf("regression", "regression", "Regression"),
            )

        // "regression"과 "Regression"은 서로 다른 라벨 — 대소문자 보존
        assertThat(issue.labels).containsExactlyInAnyOrder("regression", "Regression")
    }

    @Test
    fun `labels_reject_blank — 빈 문자열 라벨은 제거된다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                labels = listOf("bug", "", "feature"),
            )

        assertThat(issue.labels).containsExactlyInAnyOrder("bug", "feature")
    }

    @Test
    fun `labels_reject_whitespace — 공백만으로 구성된 라벨은 예외를 던진다`() {
        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                labels = listOf("bug", "   "),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `labels_reject_over_50_chars — 51자 이상 라벨은 예외를 던진다`() {
        val tooLong = "a".repeat(51)

        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                labels = listOf(tooLong),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `labels_reject_over_20_per_issue — 이슈당 21개 이상 라벨은 예외를 던진다`() {
        val tooMany = (1..21).map { "label-$it" }

        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                labels = tooMany,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ─── priority 범위 불변식 ──────────────────────────────────────────────────

    @Test
    fun `priority_reject_below_1 — priority 가 1 미만이면 예외를 던진다`() {
        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                priority = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `priority_reject_above_5 — priority 가 5 초과이면 예외를 던진다`() {
        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                priority = 6,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `priority_accept_boundary_values — priority 1과 5는 정상 생성한다`() {
        val issueMin =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                priority = 1,
            )
        val issueMax =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("PROJ", 2L),
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                priority = 5,
            )

        assertThat(issueMin.priority).isEqualTo(1)
        assertThat(issueMax.priority).isEqualTo(5)
    }

    // ─── impact 범위 불변식 ────────────────────────────────────────────────────

    @Test
    fun `impact_reject_below_1 — impact 가 1 미만이면 예외를 던진다`() {
        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                impact = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `impact_reject_above_3 — impact 가 3 초과이면 예외를 던진다`() {
        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                impact = 4,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `impact_accept_boundary_values — impact 1과 3은 정상 생성한다`() {
        val issueMin =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                impact = 1,
            )
        val issueMax =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("PROJ", 3L),
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                impact = 3,
            )

        assertThat(issueMin.impact).isEqualTo(1)
        assertThat(issueMax.impact).isEqualTo(3)
    }

    @Test
    fun `impact_accept_null — impact null 은 정상 생성한다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                impact = null,
            )

        assertThat(issue.impact).isNull()
    }

    // ─── Task 2: assigneeId 불변식 ────────────────────────────────────────────

    @Test
    fun `create_default_assigneeId_null — assigneeId 기본값은 null 이다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.assigneeId).isNull()
    }

    @Test
    fun `assignTo_sets_assigneeId — assignTo(actorId) 호출 후 반환된 Issue 의 assigneeId 가 그 actorId 와 일치한다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )
        val assignee = ActorId(UUID.randomUUID())

        val assigned = issue.assignTo(assignee)

        assertThat(assigned.assigneeId).isEqualTo(assignee)
    }

    @Test
    fun `unassign_clears_assigneeId — unassign() 호출 후 반환된 Issue 의 assigneeId 는 null 이다`() {
        val assignee = ActorId(UUID.randomUUID())
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            ).assignTo(assignee)

        val unassigned = issue.unassign()

        assertThat(unassigned.assigneeId).isNull()
    }

    // ─── Task 4: securityLevelId 불변식 ──────────────────────────────────────

    @Test
    fun `create_default_securityLevelId_null — securityLevelId 기본값은 null(공개)이다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.securityLevelId).isNull()
    }

    @Test
    fun `create_with_securityLevelId — 생성 시 securityLevelId 를 지정하면 해당 값을 보유한다`() {
        val levelId = UUID.randomUUID()
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                securityLevelId = levelId,
            )

        assertThat(issue.securityLevelId).isEqualTo(levelId)
    }

    @Test
    fun `create_with_securityLevelId_version_is_one — securityLevelId 지정 생성도 version=1 이다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                securityLevelId = UUID.randomUUID(),
            )

        assertThat(issue.version).isEqualTo(1L)
    }

    @Test
    fun `assignSecurityLevel_sets_levelId_and_bumps_version — levelId 설정 및 version+1`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )
        val levelId = UUID.randomUUID()

        val updated = issue.assignSecurityLevel(levelId)

        assertThat(updated.securityLevelId).isEqualTo(levelId)
        assertThat(updated.version).isEqualTo(issue.version + 1)
    }

    @Test
    fun `assignSecurityLevel_null_clears_levelId_and_bumps_version — null 전달 시 levelId=null, version+1`() {
        val levelId = UUID.randomUUID()
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                securityLevelId = levelId,
            )

        val updated = issue.assignSecurityLevel(null)

        assertThat(updated.securityLevelId).isNull()
        assertThat(updated.version).isEqualTo(issue.version + 1)
    }

    @Test
    fun `assignSecurityLevel_returns_new_instance — 원본 Issue 는 불변, 반환값만 변경된다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )
        val levelId = UUID.randomUUID()

        val updated = issue.assignSecurityLevel(levelId)

        assertThat(issue.securityLevelId).isNull()
        assertThat(updated).isNotSameAs(issue)
    }

    // ─── Task 2: componentIds 불변식 ──────────────────────────────────────────

    @Test
    fun `create_componentIds_dedup — componentIds 에 중복 UUID 가 포함되면 distinct 정규화되어 반환된다`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
                componentIds = listOf(a, a, b),
            )

        assertThat(issue.componentIds).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `create_default_componentIds_empty — componentIds 미지정 시 기본값은 빈 리스트다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.componentIds).isEmpty()
    }

    // ─── Task 2 (FR-VR-03): affectsVersionIds 불변식 ─────────────────────────

    @Test
    fun `create_default_affectsVersionIds_empty — affectsVersionIds 미지정 시 기본값은 빈 리스트다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.affectsVersionIds).isEmpty()
    }

    @Test
    fun `assignAffectsVersions_dedup — 중복 UUID 를 전달하면 distinct 정규화되어 반환된다`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        val updated = issue.assignAffectsVersions(listOf(a, a, b))

        assertThat(updated.affectsVersionIds).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `assignAffectsVersions_replaces_existing — 기존 affectsVersionIds 를 새 목록으로 교체한다`() {
        val old = UUID.randomUUID()
        val new1 = UUID.randomUUID()
        val new2 = UUID.randomUUID()
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            ).assignAffectsVersions(listOf(old))

        val updated = issue.assignAffectsVersions(listOf(new1, new2))

        assertThat(updated.affectsVersionIds).containsExactlyInAnyOrder(new1, new2)
        assertThat(updated.affectsVersionIds).doesNotContain(old)
    }

    @Test
    fun `clearAffectsVersions_empties_list — clearAffectsVersions() 호출 후 affectsVersionIds 는 빈 리스트다`() {
        val a = UUID.randomUUID()
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            ).assignAffectsVersions(listOf(a))

        val cleared = issue.clearAffectsVersions()

        assertThat(cleared.affectsVersionIds).isEmpty()
    }

    // ─── Task 2 (FR-VR-03): fixVersionIds 불변식 ─────────────────────────────

    @Test
    fun `create_default_fixVersionIds_empty — fixVersionIds 미지정 시 기본값은 빈 리스트다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        assertThat(issue.fixVersionIds).isEmpty()
    }

    @Test
    fun `assignFixVersions_dedup — 중복 UUID 를 전달하면 distinct 정규화되어 반환된다`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            )

        val updated = issue.assignFixVersions(listOf(a, a, b))

        assertThat(updated.fixVersionIds).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `assignFixVersions_replaces_existing — 기존 fixVersionIds 를 새 목록으로 교체한다`() {
        val old = UUID.randomUUID()
        val new1 = UUID.randomUUID()
        val new2 = UUID.randomUUID()
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            ).assignFixVersions(listOf(old))

        val updated = issue.assignFixVersions(listOf(new1, new2))

        assertThat(updated.fixVersionIds).containsExactlyInAnyOrder(new1, new2)
        assertThat(updated.fixVersionIds).doesNotContain(old)
    }

    @Test
    fun `clearFixVersions_empties_list — clearFixVersions() 호출 후 fixVersionIds 는 빈 리스트다`() {
        val a = UUID.randomUUID()
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
                typeId = validTypeId,
            ).assignFixVersions(listOf(a))

        val cleared = issue.clearFixVersions()

        assertThat(cleared.fixVersionIds).isEmpty()
    }
}
