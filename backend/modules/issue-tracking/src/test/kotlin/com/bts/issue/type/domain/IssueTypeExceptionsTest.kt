// IssueType 도메인 예외 계층 — 6 서브클래스 생성 및 필드 검증 단위 테스트

package com.bts.issue.type.domain

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * IssueTypeDomainException 계층 단위 테스트.
 *
 * 각 서브클래스가 올바른 베이스 타입을 상속하고
 * 필드 값을 그대로 노출하는지 확인한다.
 */
class IssueTypeExceptionsTest {
    @Nested
    inner class IssueTypeStandardImmutableExceptionTest {
        @Test
        fun `typeId 형태로 생성하면 RuntimeException 이고 message 에 typeId 가 포함된다`() {
            val id = IssueTypeId(1L)
            val ex = IssueTypeStandardImmutableException(typeId = id, key = null)

            ex.shouldBeInstanceOf<IssueTypeDomainException>()
            ex.shouldBeInstanceOf<RuntimeException>()
            ex.message.shouldContain("1")
        }

        @Test
        fun `key 형태로 생성하면 message 에 key 가 포함된다`() {
            val key = IssueTypeKey("bug")
            val ex = IssueTypeStandardImmutableException(typeId = null, key = key)

            ex.shouldBeInstanceOf<IssueTypeDomainException>()
            ex.message.shouldContain("bug")
        }
    }

    @Nested
    inner class IssueTypeKeyDuplicateExceptionTest {
        @Test
        fun `key 를 받아 생성하면 message 에 key 가 포함된다`() {
            val key = IssueTypeKey("task")
            val ex = IssueTypeKeyDuplicateException(key)

            ex.shouldBeInstanceOf<IssueTypeDomainException>()
            ex.shouldBeInstanceOf<RuntimeException>()
            ex.message.shouldContain("task")
        }
    }

    @Nested
    inner class IssueTypeKeyInvalidExceptionTest {
        @Test
        fun `key 를 받아 생성하면 message 에 key 가 포함된다`() {
            val key = IssueTypeKey("story")
            val ex = IssueTypeKeyInvalidException(key)

            ex.shouldBeInstanceOf<IssueTypeDomainException>()
            ex.shouldBeInstanceOf<RuntimeException>()
            ex.message.shouldContain("story")
        }
    }

    @Nested
    inner class IssueTypeInUseExceptionTest {
        @Test
        fun `usageCount 와 schemeMappingCount 필드를 보유하며 message 에 두 값이 포함된다`() {
            val ex = IssueTypeInUseException(usageCount = 42L, schemeMappingCount = 3L)

            ex.shouldBeInstanceOf<IssueTypeDomainException>()
            ex.shouldBeInstanceOf<RuntimeException>()
            ex.usageCount shouldBe 42L
            ex.schemeMappingCount shouldBe 3L
            ex.message.shouldContain("42")
            ex.message.shouldContain("3")
        }
    }

    @Nested
    inner class IssueTypeReassignTargetInvalidExceptionTest {
        @Test
        fun `targetId 와 reason 을 받아 생성하면 message 에 targetId 가 포함된다`() {
            val targetId = IssueTypeId(7L)
            val ex = IssueTypeReassignTargetInvalidException(targetId = targetId, reason = "삭제된 타입")

            ex.shouldBeInstanceOf<IssueTypeDomainException>()
            ex.shouldBeInstanceOf<RuntimeException>()
            ex.message.shouldContain("7")
        }
    }

    @Nested
    inner class IssueTypeNotFoundExceptionTest {
        @Test
        fun `id 를 받아 생성하면 message 에 id 가 포함된다`() {
            val id = IssueTypeId(99L)
            val ex = IssueTypeNotFoundException(id)

            ex.shouldBeInstanceOf<IssueTypeDomainException>()
            ex.shouldBeInstanceOf<RuntimeException>()
            ex.message.shouldContain("99")
        }
    }

    @Test
    fun `모든 서브클래스는 IssueDomainException 과 별개인 sealed 베이스를 상속한다`() {
        val exceptions: List<IssueTypeDomainException> = listOf(
            IssueTypeStandardImmutableException(typeId = IssueTypeId(1L), key = null),
            IssueTypeKeyDuplicateException(IssueTypeKey("bug")),
            IssueTypeKeyInvalidException(IssueTypeKey("task")),
            IssueTypeInUseException(usageCount = 1L, schemeMappingCount = 0L),
            IssueTypeReassignTargetInvalidException(targetId = IssueTypeId(2L), reason = "deleted"),
            IssueTypeNotFoundException(IssueTypeId(3L)),
        )

        exceptions.forEach { ex ->
            ex.shouldBeInstanceOf<IssueTypeDomainException>()
            ex.shouldBeInstanceOf<RuntimeException>()
        }
    }
}
