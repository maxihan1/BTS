// Issue Aggregate의 컴포넌트 다중 할당 도메인 동작 테스트

package com.bts.issue.domain

import com.bts.shared.issue.IssueTypeId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueComponentsTest {

    private val c1 = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val c2 = UUID.fromString("00000000-0000-4000-8000-000000000002")

    private fun baseIssue(): Issue = Issue(
        id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000010")),
        key = IssueKey("PROJ", 1),
        projectId = UUID.fromString("00000000-0000-4000-8000-000000000020"),
        summary = "test issue",
        reporterId = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000030")),
        currentStateKey = "open",
        version = 1L,
        deletedAt = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        typeId = IssueTypeId(1),
    )

    @Test
    fun `기본값은 빈 목록`() {
        val issue = baseIssue()
        assertTrue(issue.componentIds.isEmpty())
    }

    @Test
    fun `assignComponents distinct로 중복 제거`() {
        val issue = baseIssue().assignComponents(listOf(c1, c1, c2))
        assertEquals(listOf(c1, c2), issue.componentIds)
    }

    @Test
    fun `assignComponents null 요소 제외`() {
        // List<UUID>는 컴파일 타임상 null 불가지만, 방어적 filterNotNull이 동작해야 한다.
        // unchecked cast로 null 주입하여 런타임 방어 검증.
        @Suppress("UNCHECKED_CAST")
        val withNull = listOf(c1, null, c2) as List<UUID>
        val issue = baseIssue().assignComponents(withNull)
        assertEquals(listOf(c1, c2), issue.componentIds)
    }

    @Test
    fun `clearComponents 빈 목록으로 비움`() {
        val issue = baseIssue()
            .assignComponents(listOf(c1, c2))
            .clearComponents()
        assertTrue(issue.componentIds.isEmpty())
    }
}
