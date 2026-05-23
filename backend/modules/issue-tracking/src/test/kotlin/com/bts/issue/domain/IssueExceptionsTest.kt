// IssueDomainException sealed 계층 — 각 서브클래스 인스턴스화 + message 포함 여부 검증

package com.bts.issue.domain

import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssueScope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class IssueExceptionsTest : DescribeSpec({

    describe("IssueNotFoundException") {
        it("message 에 IssueKey.value 가 포함된다") {
            val key = IssueKey("ATLAS-42")
            val ex = IssueNotFoundException(key)

            ex.shouldBeInstanceOf<IssueDomainException>()
            ex.message shouldContain "ATLAS-42"
        }
    }

    describe("IssueAccessDeniedException") {
        it("message 에 actor, permission, scope 가 모두 포함된다") {
            val actor = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
            val permission = IssuePermission.UPDATE
            val scope = IssueScope.Project("ATLAS")
            val ex = IssueAccessDeniedException(actor, permission, scope)

            ex.shouldBeInstanceOf<IssueDomainException>()
            ex.message shouldContain actor.value.toString()
            ex.message shouldContain permission.name
            ex.message shouldContain "ATLAS"
        }
    }

    describe("IssueVersionConflictException") {
        it("message 에 IssueKey.value 와 currentVersion 이 포함된다") {
            val key = IssueKey("PROJ-7")
            val ex = IssueVersionConflictException(key, currentVersion = 3L)

            ex.shouldBeInstanceOf<IssueDomainException>()
            ex.message shouldContain "PROJ-7"
            ex.message shouldContain "3"
        }
    }

    describe("IssueProjectNotFoundException") {
        it("message 에 projectKey 가 포함된다") {
            val ex = IssueProjectNotFoundException("MYPROJ")

            ex.shouldBeInstanceOf<IssueDomainException>()
            ex.message shouldContain "MYPROJ"
        }
    }

    describe("IssueKeyPrefixReservedException") {
        it("message 에 prefix 가 포함된다") {
            val ex = IssueKeyPrefixReservedException("ADMIN")

            ex.shouldBeInstanceOf<IssueDomainException>()
            ex.message shouldContain "ADMIN"
        }
    }
})
