// IssueChangeDetector 순수 함수 단위 테스트 — 필드별 변경 감지 및 라이프사이클 마커 검증

package com.bts.issue.history

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID

/**
 * [IssueChangeDetector] 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 모든 시나리오에서 [IssueChangeItem.fromLabel] / [IssueChangeItem.toLabel] 은 null 임을 검증한다
 * (라벨 해석은 T3 resolver 책임).
 */
class IssueChangeDetectorTest : DescribeSpec({

    val detector = IssueChangeDetector()

    // ── 테스트 픽스처 ──────────────────────────────────────────────────────────

    val projectId = UUID.randomUUID()
    val reporterId = ActorId(UUID.randomUUID())
    val typeId = IssueTypeId(1L)

    /** 기본 Issue 픽스처. */
    fun baseIssue(
        id: UUID = UUID.randomUUID(),
        summary: String = "기본 요약",
        currentStateKey: String = "open",
        priority: Int = 3,
        description: String? = null,
        labels: List<String> = emptyList(),
        environment: String? = null,
        impact: Int? = null,
        assigneeId: UUID? = null,
        resolutionId: UUID? = null,
        componentIds: List<UUID> = emptyList(),
        affectsVersionIds: List<UUID> = emptyList(),
        fixVersionIds: List<UUID> = emptyList(),
        securityLevelId: UUID? = null,
        typeIdValue: Long = 1L,
        customFields: Map<String, Any?> = emptyMap(),
    ): Issue = Issue(
        id = IssueId(id),
        key = IssueKey("ATLAS-1"),
        projectId = projectId,
        summary = summary,
        reporterId = reporterId,
        currentStateKey = currentStateKey,
        version = 1L,
        deletedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        typeId = IssueTypeId(typeIdValue),
        description = description,
        priority = priority,
        labels = labels,
        environment = environment,
        impact = impact,
        assigneeId = if (assigneeId != null) ActorId(assigneeId) else null,
        resolutionId = resolutionId,
        componentIds = componentIds,
        affectsVersionIds = affectsVersionIds,
        fixVersionIds = fixVersionIds,
        securityLevelId = securityLevelId,
        customFields = customFields,
    )

    // ── 라이프사이클 마커 ───────────────────────────────────────────────────────

    describe("lifecycle 마커") {

        it("(h) before=null → created 마커 1개") {
            val issue = baseIssue()
            val items = detector.created(issue)

            items shouldHaveSize 1
            items[0].field shouldBe "lifecycle"
            items[0].fromValue shouldBe null
            items[0].toValue shouldBe "created"
            items[0].fromLabel shouldBe null
            items[0].toLabel shouldBe null
        }

        it("(h) after=null → deleted 마커 1개") {
            val issue = baseIssue()
            val items = detector.deleted(issue)

            items shouldHaveSize 1
            items[0].field shouldBe "lifecycle"
            items[0].fromValue shouldBe null
            items[0].toValue shouldBe "deleted"
            items[0].fromLabel shouldBe null
            items[0].toLabel shouldBe null
        }
    }

    // ── 스칼라 필드 변경 ────────────────────────────────────────────────────────

    describe("스칼라 필드 변경") {

        it("(a) priority 3→1 → item 1개 (field=priority, from=3, to=1)") {
            val before = baseIssue(priority = 3)
            val after = before.copy(priority = 1)

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            val item = items[0]
            item.field shouldBe "priority"
            item.fromValue shouldBe "3"
            item.toValue shouldBe "1"
            item.fromLabel shouldBe null
            item.toLabel shouldBe null
        }

        it("(b) summary + priority 동시 변경 → item 2개") {
            val before = baseIssue(summary = "원래 요약", priority = 3)
            val after = before.copy(summary = "새 요약", priority = 2)

            val items = detector.detect(before, after)

            items shouldHaveSize 2
            val fields = items.map { it.field }.toSet()
            fields shouldBe setOf("summary", "priority")
            items.forEach { item ->
                item.fromLabel shouldBe null
                item.toLabel shouldBe null
            }
        }

        it("(c) 변경 없음(동일) → 빈 리스트") {
            val before = baseIssue(priority = 3, summary = "동일 요약")
            val after = before.copy() // 완전히 동일

            val items = detector.detect(before, after)

            items.shouldBeEmpty()
        }

        it("status 변경 감지 (currentStateKey)") {
            val before = baseIssue(currentStateKey = "open")
            val after = before.copy(currentStateKey = "in_progress")

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "status"
            items[0].fromValue shouldBe "open"
            items[0].toValue shouldBe "in_progress"
        }

        it("typeId 변경 감지") {
            val before = baseIssue(typeIdValue = 1L)
            val after = before.copy(typeId = IssueTypeId(2L))

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "type"
            items[0].fromValue shouldBe "1"
            items[0].toValue shouldBe "2"
        }

        it("assigneeId null→UUID 변경 감지") {
            val assigneeId = UUID.randomUUID()
            val before = baseIssue(assigneeId = null)
            val after = before.copy(assigneeId = ActorId(assigneeId))

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "assignee"
            items[0].fromValue shouldBe null
            items[0].toValue shouldBe assigneeId.toString()
        }

        it("resolutionId UUID→null 변경 감지") {
            val resId = UUID.randomUUID()
            val before = baseIssue(resolutionId = resId)
            val after = before.copy(resolutionId = null)

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "resolution"
            items[0].fromValue shouldBe resId.toString()
            items[0].toValue shouldBe null
        }

        it("securityLevelId 변경 감지") {
            val levelId = UUID.randomUUID()
            val before = baseIssue(securityLevelId = null)
            val after = before.copy(securityLevelId = levelId)

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "securityLevel"
            items[0].fromValue shouldBe null
            items[0].toValue shouldBe levelId.toString()
        }
    }

    // ── description null↔"" 구분 ───────────────────────────────────────────────

    describe("description null↔empty 구분") {

        it("(g) description null→null → 변경 없음") {
            val before = baseIssue(description = null)
            val after = before.copy(description = null)

            detector.detect(before, after).shouldBeEmpty()
        }

        it("(g) description null→\"\" → 변경 감지 (fromValue=null, toValue=empty-string)") {
            val before = baseIssue(description = null)
            val after = before.copy(description = "")

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "description"
            items[0].fromValue shouldBe null
            items[0].toValue shouldBe ""
        }

        it("(g) description \"\"→null → 변경 감지") {
            val before = baseIssue(description = "")
            val after = before.copy(description = null)

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "description"
            items[0].fromValue shouldBe ""
            items[0].toValue shouldBe null
        }

        it("description 동일 문자열 → 변경 없음") {
            val before = baseIssue(description = "동일 내용")
            val after = before.copy(description = "동일 내용")

            detector.detect(before, after).shouldBeEmpty()
        }
    }

    // ── 컬렉션 필드 ────────────────────────────────────────────────────────────

    describe("컬렉션 필드 변경") {

        it("(d) 컬렉션 순서만 다르고 집합 동일 → 변경 없음") {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            val before = baseIssue(componentIds = listOf(id1, id2))
            val after = before.copy(componentIds = listOf(id2, id1))

            detector.detect(before, after).shouldBeEmpty()
        }

        it("(e) components [A]→[A,B] → item 1개 (JSON 배열 형식)") {
            val idA = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val idB = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val before = baseIssue(componentIds = listOf(idA))
            val after = before.copy(componentIds = listOf(idA, idB))

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "components"
            items[0].fromValue shouldBe "[\"${idA}\"]"
            items[0].toValue shouldBe "[\"${idA}\",\"${idB}\"]"
            items[0].fromLabel shouldBe null
            items[0].toLabel shouldBe null
        }

        it("labels 집합 변경 → item 1개 (JSON 배열 형식)") {
            val before = baseIssue(labels = listOf("bug"))
            val after = before.copy(labels = listOf("bug", "critical"))

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "labels"
            // 정렬 후 비교이므로 fromValue/toValue 내부도 정렬됨
            items[0].fromValue shouldNotBe null
            items[0].toValue shouldNotBe null
        }

        it("affectsVersionIds 변경 감지") {
            val vId = UUID.randomUUID()
            val before = baseIssue(affectsVersionIds = emptyList())
            val after = before.copy(affectsVersionIds = listOf(vId))

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "affectsVersions"
        }

        it("fixVersionIds 변경 감지") {
            val vId = UUID.randomUUID()
            val before = baseIssue(fixVersionIds = emptyList())
            val after = before.copy(fixVersionIds = listOf(vId))

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "fixVersions"
        }
    }

    // ── customFields 키별 분해 ─────────────────────────────────────────────────

    describe("customFields 키별 분해") {

        it("(f) customFields 키 값 변경 → field=customField:<key>") {
            val before = baseIssue(customFields = mapOf("priority_cf" to "high"))
            val after = before.copy(customFields = mapOf("priority_cf" to "low"))

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "customField:priority_cf"
            items[0].fromValue shouldBe "high"
            items[0].toValue shouldBe "low"
            items[0].fromLabel shouldBe null
            items[0].toLabel shouldBe null
        }

        it("(f) customFields 키 추가 → from=null") {
            val before = baseIssue(customFields = emptyMap())
            val after = before.copy(customFields = mapOf("new_key" to "value"))

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "customField:new_key"
            items[0].fromValue shouldBe null
            items[0].toValue shouldBe "value"
        }

        it("(f) customFields 키 삭제 → to=null") {
            val before = baseIssue(customFields = mapOf("old_key" to "value"))
            val after = before.copy(customFields = emptyMap())

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "customField:old_key"
            items[0].fromValue shouldBe "value"
            items[0].toValue shouldBe null
        }

        it("(f) customFields 값 동일 → 변경 없음") {
            val before = baseIssue(customFields = mapOf("key" to "same"))
            val after = before.copy(customFields = mapOf("key" to "same"))

            detector.detect(before, after).shouldBeEmpty()
        }

        it("(f) customFields 여러 키 중 일부만 변경 → 변경된 키만 item 생성") {
            val before = baseIssue(customFields = mapOf("key1" to "v1", "key2" to "v2"))
            val after = before.copy(customFields = mapOf("key1" to "v1", "key2" to "changed"))

            val items = detector.detect(before, after)

            items shouldHaveSize 1
            items[0].field shouldBe "customField:key2"
        }
    }

    // ── IssueChangeGroup 모델 ──────────────────────────────────────────────────

    describe("IssueChangeGroup 모델") {

        it("IssueChangeGroup 생성 — actorId nullable") {
            val issue = baseIssue()
            val items = listOf(IssueChangeItem(field = "priority", fromValue = "3", toValue = "1"))
            val group = IssueChangeGroup(
                issueId = issue.id.value,
                issueKey = issue.key.value,
                actorId = null,
                items = items,
            )

            group.issueId shouldBe issue.id.value
            group.issueKey shouldBe issue.key.value
            group.actorId shouldBe null
            group.items shouldHaveSize 1
        }

        it("IssueChangeGroup 생성 — actorId 지정") {
            val actorId = UUID.randomUUID()
            val group = IssueChangeGroup(
                issueId = UUID.randomUUID(),
                issueKey = "ATLAS-1",
                actorId = actorId,
                items = emptyList(),
            )

            group.actorId shouldBe actorId
        }
    }
})
