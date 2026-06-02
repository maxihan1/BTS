// Component Aggregate Root 단위 테스트 — factory invariants + 도메인 메서드 검증
package com.bts.issue.component.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * [Component] Aggregate Root 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 대상.
 * - name 빈/공백 문자열 거부
 * - name trim 정규화
 * - name 길이 255자 이하 불변식
 * - [Component.rename], [Component.changeLead], [Component.changeDescription], [Component.softDelete] 도메인 메서드
 * - softDelete 멱등성 — 이미 삭제된 컴포넌트 재삭제 거부
 */
class ComponentTest : DescribeSpec({

    val projectId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val leadId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    describe("Component.create — factory invariants") {

        context("유효한 입력") {
            it("projectId + name 입력 시 Component 인스턴스를 반환한다") {
                val component =
                    Component.create(
                        projectId = projectId,
                        name = "Backend",
                    )

                component.projectId shouldBe projectId
                component.name shouldBe "Backend"
            }

            it("id 는 null 이다 (DB 저장 전 미확정 상태)") {
                val component =
                    Component.create(
                        projectId = projectId,
                        name = "Backend",
                    )

                component.id.shouldBeNull()
            }

            it("description 기본값은 null 이다") {
                val component =
                    Component.create(
                        projectId = projectId,
                        name = "Backend",
                    )

                component.description.shouldBeNull()
            }

            it("leadUserId 기본값은 null 이다") {
                val component =
                    Component.create(
                        projectId = projectId,
                        name = "Backend",
                    )

                component.leadUserId.shouldBeNull()
            }

            it("deletedAt 기본값은 null 이다") {
                val component =
                    Component.create(
                        projectId = projectId,
                        name = "Backend",
                    )

                component.deletedAt.shouldBeNull()
            }

            it("leadUserId 를 지정할 수 있다") {
                val component =
                    Component.create(
                        projectId = projectId,
                        name = "Backend",
                        leadUserId = leadId,
                    )

                component.leadUserId shouldBe leadId
            }

            it("description 을 지정할 수 있다") {
                val component =
                    Component.create(
                        projectId = projectId,
                        name = "Backend",
                        description = "백엔드 관련 이슈",
                    )

                component.description shouldBe "백엔드 관련 이슈"
            }
        }

        context("name 빈/공백 유효성 검증") {
            it("name 이 빈 문자열이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Component.create(projectId = projectId, name = "")
                }
            }

            it("name 이 공백-only 이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Component.create(projectId = projectId, name = "   ")
                }
            }
        }

        context("name trim 정규화") {
            it("name 양쪽 공백을 trim 한다") {
                val component =
                    Component.create(
                        projectId = projectId,
                        name = "  Backend  ",
                    )

                component.name shouldBe "Backend"
            }

            it("trim 후 빈 문자열이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Component.create(projectId = projectId, name = "   ")
                }
            }
        }

        context("name 길이 불변식") {
            it("name 이 정확히 255자면 정상 생성된다") {
                val component =
                    Component.create(
                        projectId = projectId,
                        name = "A".repeat(255),
                    )

                component.name.length shouldBe 255
            }

            it("name 이 256자면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Component.create(projectId = projectId, name = "A".repeat(256))
                }
            }
        }
    }

    describe("Component.rename — 이름 변경") {

        it("유효한 이름으로 변경하면 새 인스턴스를 반환한다") {
            val original = Component.create(projectId = projectId, name = "Backend")
            val renamed = original.rename("Frontend")

            renamed.name shouldBe "Frontend"
        }

        it("rename 도 trim 정규화를 적용한다") {
            val original = Component.create(projectId = projectId, name = "Backend")
            val renamed = original.rename("  Frontend  ")

            renamed.name shouldBe "Frontend"
        }

        it("rename 에 빈 문자열을 전달하면 IllegalArgumentException 을 던진다") {
            val original = Component.create(projectId = projectId, name = "Backend")

            shouldThrow<IllegalArgumentException> {
                original.rename("")
            }
        }

        it("rename 에 공백-only 문자열을 전달하면 IllegalArgumentException 을 던진다") {
            val original = Component.create(projectId = projectId, name = "Backend")

            shouldThrow<IllegalArgumentException> {
                original.rename("   ")
            }
        }

        it("rename 에 256자 이름을 전달하면 IllegalArgumentException 을 던진다") {
            val original = Component.create(projectId = projectId, name = "Backend")

            shouldThrow<IllegalArgumentException> {
                original.rename("A".repeat(256))
            }
        }

        it("원본 인스턴스는 변경되지 않는다") {
            val original = Component.create(projectId = projectId, name = "Backend")
            original.rename("Frontend")

            original.name shouldBe "Backend"
        }
    }

    describe("Component.changeLead — 리드 변경") {

        it("leadUserId 를 변경하면 새 인스턴스를 반환한다") {
            val original = Component.create(projectId = projectId, name = "Backend")
            val updated = original.changeLead(leadId)

            updated.leadUserId shouldBe leadId
        }

        it("null 을 전달하면 리드를 해제한다") {
            val original =
                Component.create(
                    projectId = projectId,
                    name = "Backend",
                    leadUserId = leadId,
                )
            val updated = original.changeLead(null)

            updated.leadUserId.shouldBeNull()
        }

        it("원본 인스턴스는 변경되지 않는다") {
            val original =
                Component.create(
                    projectId = projectId,
                    name = "Backend",
                    leadUserId = leadId,
                )
            original.changeLead(null)

            original.leadUserId shouldBe leadId
        }
    }

    describe("Component.changeDescription — 설명 변경") {

        it("설명을 변경하면 새 인스턴스를 반환한다") {
            val original = Component.create(projectId = projectId, name = "Backend")
            val updated = original.changeDescription("새 설명")

            updated.description shouldBe "새 설명"
        }

        it("null 을 전달하면 설명을 클리어한다") {
            val original =
                Component.create(
                    projectId = projectId,
                    name = "Backend",
                    description = "기존 설명",
                )
            val updated = original.changeDescription(null)

            updated.description.shouldBeNull()
        }

        it("원본 인스턴스는 변경되지 않는다") {
            val original =
                Component.create(
                    projectId = projectId,
                    name = "Backend",
                    description = "기존 설명",
                )
            original.changeDescription("새 설명")

            original.description shouldBe "기존 설명"
        }
    }

    describe("Component.softDelete — 소프트 삭제") {

        it("softDelete 호출 시 deletedAt 이 채워진 새 인스턴스를 반환한다") {
            val original = Component.create(projectId = projectId, name = "Backend")
            val deleted = original.softDelete()

            deleted.deletedAt.shouldNotBeNull()
        }

        it("이미 삭제된 컴포넌트에 softDelete 를 호출하면 IllegalStateException 을 던진다") {
            val original = Component.create(projectId = projectId, name = "Backend")
            val deleted = original.softDelete()

            shouldThrow<IllegalStateException> {
                deleted.softDelete()
            }
        }

        it("원본 인스턴스는 변경되지 않는다") {
            val original = Component.create(projectId = projectId, name = "Backend")
            original.softDelete()

            original.deletedAt.shouldBeNull()
        }
    }
})
