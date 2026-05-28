// IssueType Aggregate Root 단위 테스트 — factory invariants + 5 표준 키 검증
package com.bts.issue.type.domain

import com.bts.shared.issue.IssueTypeKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * [IssueType] Aggregate Root 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 대상.
 * - name 빈 문자열 시 [IllegalArgumentException] 발생
 * - [IssueType.create] 로 생성 시 isStandard 기본값 false
 * - description, iconName null 허용
 * - 5 표준 IssueType key 일치 (epic / story / task / subtask / bug)
 */
class IssueTypeTest : DescribeSpec({

    describe("IssueType.create — factory invariants") {

        context("유효한 입력") {
            it("key/name 입력 시 IssueType 인스턴스를 반환한다") {
                val issueType =
                    IssueType.create(
                        key = IssueTypeKey("task"),
                        name = "Task",
                    )

                issueType.key.value shouldBe "task"
                issueType.name shouldBe "Task"
            }

            it("isStandard 기본값은 false 다") {
                val issueType =
                    IssueType.create(
                        key = IssueTypeKey("task"),
                        name = "Task",
                    )

                issueType.isStandard shouldBe false
            }

            it("isStandard = true 로 명시 가능하다") {
                val issueType =
                    IssueType.create(
                        key = IssueTypeKey("task"),
                        name = "Task",
                        isStandard = true,
                    )

                issueType.isStandard shouldBe true
            }

            it("description 은 null 허용이다") {
                val issueType =
                    IssueType.create(
                        key = IssueTypeKey("task"),
                        name = "Task",
                        description = null,
                    )

                issueType.description.shouldBeNull()
            }

            it("iconName 은 null 허용이다") {
                val issueType =
                    IssueType.create(
                        key = IssueTypeKey("task"),
                        name = "Task",
                        iconName = null,
                    )

                issueType.iconName.shouldBeNull()
            }

            it("id 는 null 허용이다 (신규 생성 전 DB PK 미확정 상태)") {
                val issueType =
                    IssueType.create(
                        key = IssueTypeKey("task"),
                        name = "Task",
                    )

                issueType.id.shouldBeNull()
            }
        }

        context("name 유효성 검증") {
            it("name 이 빈 문자열이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    IssueType.create(
                        key = IssueTypeKey("task"),
                        name = "",
                    )
                }
            }

            it("name 이 공백 전용 문자열이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    IssueType.create(
                        key = IssueTypeKey("task"),
                        name = "   ",
                    )
                }
            }
        }
    }

    describe("IssueType 표준 타입 — 5 표준 key 검증") {

        it("EPIC 의 key 는 'epic' 이다") {
            IssueType.EPIC.key.value shouldBe "epic"
        }

        it("STORY 의 key 는 'story' 이다") {
            IssueType.STORY.key.value shouldBe "story"
        }

        it("TASK 의 key 는 'task' 이다") {
            IssueType.TASK.key.value shouldBe "task"
        }

        it("SUBTASK 의 key 는 'subtask' 이다") {
            IssueType.SUBTASK.key.value shouldBe "subtask"
        }

        it("BUG 의 key 는 'bug' 이다") {
            IssueType.BUG.key.value shouldBe "bug"
        }

        it("표준 5종은 모두 isStandard = true 다") {
            val standards =
                listOf(
                    IssueType.EPIC,
                    IssueType.STORY,
                    IssueType.TASK,
                    IssueType.SUBTASK,
                    IssueType.BUG,
                )

            standards.forEach { it.isStandard shouldBe true }
        }
    }
})
