// 이슈 템플릿 도메인 객체 단위 테스트 — create 팩토리 불변식 검증 + withChanges 변경 검증
package com.bts.issue.template.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class IssueTemplateTest {
    private val projectId = UUID.randomUUID()
    private val issueTypeId = 1L

    private fun existingTemplate() =
        IssueTemplate(
            id = UUID.randomUUID(),
            projectId = projectId,
            issueTypeId = issueTypeId,
            name = "기존 이름",
            content = "기존 내용",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            deletedAt = null,
        )

    @Test
    fun `create 성공 — 유효한 인자로 IssueTemplate 반환`() {
        val template =
            IssueTemplate.create(
                projectId = projectId,
                issueTypeId = issueTypeId,
                name = "기본 버그 리포트",
                content = "## 재현 방법\n\n## 기대 결과\n\n## 실제 결과",
            )

        assertNotNull(template.id)
        assertEquals(projectId, template.projectId)
        assertEquals(issueTypeId, template.issueTypeId)
        assertEquals("기본 버그 리포트", template.name)
        assertEquals("## 재현 방법\n\n## 기대 결과\n\n## 실제 결과", template.content)
        assertNotNull(template.createdAt)
        assertNotNull(template.updatedAt)
        assertEquals(null, template.deletedAt)
    }

    @Test
    fun `create 실패 — name 이 공백이면 InvalidIssueTemplateException 을 던진다`() {
        assertThrows<InvalidIssueTemplateException> {
            IssueTemplate.create(
                projectId = projectId,
                issueTypeId = issueTypeId,
                name = "   ",
                content = "유효한 내용",
            )
        }
    }

    @Test
    fun `create 실패 — name 이 101자이면 InvalidIssueTemplateException 을 던진다`() {
        val longName = "a".repeat(101)
        assertThrows<InvalidIssueTemplateException> {
            IssueTemplate.create(
                projectId = projectId,
                issueTypeId = issueTypeId,
                name = longName,
                content = "유효한 내용",
            )
        }
    }

    @Test
    fun `create 성공 — name 이 정확히 100자이면 생성된다`() {
        val maxName = "a".repeat(100)
        val template =
            IssueTemplate.create(
                projectId = projectId,
                issueTypeId = issueTypeId,
                name = maxName,
                content = "유효한 내용",
            )
        assertEquals(maxName, template.name)
    }

    @Test
    fun `create 실패 — content 가 공백이면 InvalidIssueTemplateException 을 던진다`() {
        assertThrows<InvalidIssueTemplateException> {
            IssueTemplate.create(
                projectId = projectId,
                issueTypeId = issueTypeId,
                name = "유효한 이름",
                content = "   ",
            )
        }
    }

    // ── withChanges ────────────────────────────────────────────────────────────

    @Test
    fun `withChanges 성공 — name 만 변경하면 content 는 기존 유지`() {
        val template = existingTemplate()
        val updated = template.withChanges(name = "새 이름")
        assertEquals("새 이름", updated.name)
        assertEquals("기존 내용", updated.content)
    }

    @Test
    fun `withChanges 성공 — content 만 변경하면 name 은 기존 유지`() {
        val template = existingTemplate()
        val updated = template.withChanges(content = "새 내용")
        assertEquals("기존 이름", updated.name)
        assertEquals("새 내용", updated.content)
    }

    @Test
    fun `withChanges 성공 — null null 이면 모든 값 기존 유지`() {
        val template = existingTemplate()
        val updated = template.withChanges()
        assertEquals("기존 이름", updated.name)
        assertEquals("기존 내용", updated.content)
    }

    @Test
    fun `withChanges 실패 — name 이 빈 문자열이면 InvalidIssueTemplateException 을 던진다`() {
        assertThrows<InvalidIssueTemplateException> {
            existingTemplate().withChanges(name = "")
        }
    }

    @Test
    fun `withChanges 실패 — name 이 공백이면 InvalidIssueTemplateException 을 던진다`() {
        assertThrows<InvalidIssueTemplateException> {
            existingTemplate().withChanges(name = "   ")
        }
    }

    @Test
    fun `withChanges 실패 — name 이 101자이면 InvalidIssueTemplateException 을 던진다`() {
        assertThrows<InvalidIssueTemplateException> {
            existingTemplate().withChanges(name = "a".repeat(101))
        }
    }

    @Test
    fun `withChanges 실패 — content 가 빈 문자열이면 InvalidIssueTemplateException 을 던진다`() {
        assertThrows<InvalidIssueTemplateException> {
            existingTemplate().withChanges(content = "")
        }
    }

    @Test
    fun `withChanges 실패 — content 가 공백이면 InvalidIssueTemplateException 을 던진다`() {
        assertThrows<InvalidIssueTemplateException> {
            existingTemplate().withChanges(content = "   ")
        }
    }
}
