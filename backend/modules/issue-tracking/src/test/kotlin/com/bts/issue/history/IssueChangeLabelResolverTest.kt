// IssueChangeLabelResolver 단위 테스트 — lookup mock, 라벨 채우기 및 graceful 정책 검증

package com.bts.issue.history

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.component.domain.Component
import com.bts.issue.resolution.domain.Resolution
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.domain.Version
import com.bts.issue.version.domain.VersionStatus
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

/**
 * [IssueChangeLabelResolver] 단위 테스트.
 *
 * Spring 컨텍스트 없이 MockK 로 repository 를 mock 하여 순수 단위 테스트로 실행한다.
 * lookup 실패(null 반환) 시 해당 item 의 label 이 null 로 유지됨(graceful)을 함께 검증한다.
 */
class IssueChangeLabelResolverTest : DescribeSpec({

    val issueTypeRepo = mockk<IssueTypeRepository>()
    val resolutionRepo = mockk<ResolutionRepository>()
    val componentRepo = mockk<ComponentRepository>()
    val versionRepo = mockk<VersionRepository>()

    val sut = IssueChangeLabelResolver(issueTypeRepo, resolutionRepo, componentRepo, versionRepo)

    val projectId = UUID.randomUUID()

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────────────────────

    fun makeIssueType(id: Long, name: String): IssueType =
        IssueType(
            id = IssueTypeId(id),
            key = IssueTypeKey("key-$id"),
            name = name,
            description = null,
            iconName = null,
            isStandard = false,
            hierarchyLevel = 0,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            deletedAt = null,
        )

    fun makeResolution(id: UUID, name: String): Resolution =
        Resolution(
            id = id,
            key = "key",
            name = name,
            description = null,
            displayOrder = 1,
            isStandard = false,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            deletedAt = null,
        )

    fun makeComponent(id: UUID, name: String): Component =
        Component(
            id = id,
            projectId = projectId,
            name = name,
            description = null,
            leadUserId = null,
            deletedAt = null,
        )

    fun makeVersion(id: UUID, name: String): Version =
        Version(
            id = id,
            projectId = projectId,
            name = name,
            description = null,
            startDate = null,
            releaseDate = null,
            status = VersionStatus.UNRELEASED,
            releasedAt = null,
            deletedAt = null,
        )

    // ── type 필드 ────────────────────────────────────────────────────────────────

    describe("field=type") {
        it("typeId 로 IssueType.name 을 채운다") {
            val typeId = 7L
            val issueType = makeIssueType(typeId, "Bug")
            every { issueTypeRepo.findById(IssueTypeId(typeId)) } returns issueType

            val items = listOf(
                IssueChangeItem(field = "type", fromValue = null, toValue = typeId.toString()),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
            result[0].toLabel shouldBe "Bug"
        }

        it("lookup 실패 시 label 은 null(graceful)") {
            val typeId = 99L
            every { issueTypeRepo.findById(IssueTypeId(typeId)) } returns null

            val items = listOf(
                IssueChangeItem(field = "type", fromValue = typeId.toString(), toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
            result[0].toLabel shouldBe null
        }

        it("값이 숫자가 아닌 경우 label 은 null(graceful)") {
            val items = listOf(
                IssueChangeItem(field = "type", fromValue = "not-a-number", toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
        }
    }

    // ── resolution 필드 ──────────────────────────────────────────────────────────

    describe("field=resolution") {
        it("resolutionId 로 Resolution.name 을 채운다") {
            val resId = UUID.randomUUID()
            val resolution = makeResolution(resId, "Fixed")
            every { resolutionRepo.findById(resId) } returns resolution

            val items = listOf(
                IssueChangeItem(field = "resolution", fromValue = null, toValue = resId.toString()),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].toLabel shouldBe "Fixed"
        }

        it("lookup 실패 시 label 은 null(graceful)") {
            val resId = UUID.randomUUID()
            every { resolutionRepo.findById(resId) } returns null

            val items = listOf(
                IssueChangeItem(field = "resolution", fromValue = resId.toString(), toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
        }

        it("값이 UUID 가 아닌 경우 label 은 null(graceful)") {
            val items = listOf(
                IssueChangeItem(field = "resolution", fromValue = "invalid-uuid", toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
        }
    }

    // ── components 필드 ──────────────────────────────────────────────────────────

    describe("field=components") {
        it("component id 배열을 이름 정렬 배열 문자열로 채운다") {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            every { componentRepo.findById(id1, projectId) } returns makeComponent(id1, "Backend")
            every { componentRepo.findById(id2, projectId) } returns makeComponent(id2, "API")

            val idsJson = """["$id1","$id2"]"""
            val items = listOf(
                IssueChangeItem(field = "components", fromValue = null, toValue = idsJson),
            )
            val result = sut.resolveLabels(items, projectId)

            // 이름 정렬 배열 — "API", "Backend" 순
            result[0].toLabel shouldNotBe null
            result[0].toLabel shouldBe "[API, Backend]"
        }

        it("일부 id lookup 실패 시 나머지만 포함") {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            every { componentRepo.findById(id1, projectId) } returns makeComponent(id1, "Frontend")
            every { componentRepo.findById(id2, projectId) } returns null

            val idsJson = """["$id1","$id2"]"""
            val items = listOf(
                IssueChangeItem(field = "components", fromValue = idsJson, toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe "[Frontend]"
        }

        it("JSON 파싱 실패 시 label 은 null(graceful)") {
            val items = listOf(
                IssueChangeItem(field = "components", fromValue = "not-json", toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
        }
    }

    // ── affectsVersions / fixVersions 필드 ──────────────────────────────────────

    describe("field=affectsVersions") {
        it("version id 배열을 이름 정렬 배열 문자열로 채운다") {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            every { versionRepo.findById(id1, projectId) } returns makeVersion(id1, "v2.0")
            every { versionRepo.findById(id2, projectId) } returns makeVersion(id2, "v1.0")

            val idsJson = """["$id1","$id2"]"""
            val items = listOf(
                IssueChangeItem(field = "affectsVersions", fromValue = null, toValue = idsJson),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].toLabel shouldBe "[v1.0, v2.0]"
        }
    }

    describe("field=fixVersions") {
        it("version id 배열을 이름 정렬 배열 문자열로 채운다") {
            val verId = UUID.randomUUID()
            every { versionRepo.findById(verId, projectId) } returns makeVersion(verId, "v3.0")

            val idsJson = """["$verId"]"""
            val items = listOf(
                IssueChangeItem(field = "fixVersions", fromValue = idsJson, toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe "[v3.0]"
        }
    }

    // ── label=null 유지 필드 ─────────────────────────────────────────────────────

    describe("label=null 유지 필드") {
        it("assignee 필드는 fromLabel/toLabel 이 null") {
            val items = listOf(
                IssueChangeItem(field = "assignee", fromValue = "alice", toValue = "bob"),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
            result[0].toLabel shouldBe null
        }

        it("securityLevel 필드는 fromLabel/toLabel 이 null") {
            val items = listOf(
                IssueChangeItem(field = "securityLevel", fromValue = UUID.randomUUID().toString(), toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
        }

        it("status 필드는 fromLabel/toLabel 이 null") {
            val items = listOf(
                IssueChangeItem(field = "status", fromValue = "open", toValue = "in_progress"),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
            result[0].toLabel shouldBe null
        }

        it("summary 스칼라 필드는 fromLabel/toLabel 이 null") {
            val items = listOf(
                IssueChangeItem(field = "summary", fromValue = "old", toValue = "new"),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
            result[0].toLabel shouldBe null
        }

        it("customField:* 필드는 fromLabel/toLabel 이 null") {
            val items = listOf(
                IssueChangeItem(field = "customField:my_field", fromValue = "x", toValue = "y"),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
            result[0].toLabel shouldBe null
        }

        it("lifecycle 필드는 fromLabel/toLabel 이 null") {
            val items = listOf(
                IssueChangeItem(field = "lifecycle", fromValue = "created", toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].fromLabel shouldBe null
        }
    }

    // ── 복합 리스트 ──────────────────────────────────────────────────────────────

    describe("복합 리스트") {
        it("여러 필드 혼합 시 각 필드 정책을 독립 적용한다") {
            val typeId = 3L
            val issueType = makeIssueType(typeId, "Task")
            every { issueTypeRepo.findById(IssueTypeId(typeId)) } returns issueType

            val items = listOf(
                IssueChangeItem(field = "type", fromValue = null, toValue = typeId.toString()),
                IssueChangeItem(field = "summary", fromValue = "old", toValue = "new"),
                IssueChangeItem(field = "assignee", fromValue = "alice", toValue = null),
            )
            val result = sut.resolveLabels(items, projectId)

            result[0].toLabel shouldBe "Task"
            result[1].toLabel shouldBe null
            result[2].fromLabel shouldBe null
        }
    }
})
