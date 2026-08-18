// Version Aggregate Root 단위 테스트 — factory invariants + 도메인 메서드 검증
package com.bts.issue.version.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [Version] Aggregate Root 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 대상.
 * - name 빈/공백 문자열 거부
 * - name trim 정규화
 * - name 길이 255자 이하 불변식
 * - [Version.rename], [Version.changeDescription], [Version.changeDates], [Version.softDelete] 도메인 메서드
 * - softDelete 멱등성 — 이미 삭제된 버전 재삭제 거부
 * - changeDates — 순서 미강제(startDate > releaseDate 허용)
 * - 상태 전환 — release/unrelease/archive/unarchive 5종 + releasedAt 불변식
 * - 거부 케이스 — self-transition, 그래프 외 전환, ARCHIVED 읽기 전용
 */
class VersionTest : DescribeSpec({

    val projectId = UUID.fromString("00000000-0000-4000-8000-000000000001")

    describe("Version.create — factory invariants") {

        context("유효한 입력") {
            it("projectId + name 입력 시 Version 인스턴스를 반환한다") {
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "1.0.0",
                    )

                version.projectId shouldBe projectId
                version.name shouldBe "1.0.0"
            }

            it("id 는 null 이다 (DB 저장 전 미확정 상태)") {
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "1.0.0",
                    )

                version.id.shouldBeNull()
            }

            it("description 기본값은 null 이다") {
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "1.0.0",
                    )

                version.description.shouldBeNull()
            }

            it("startDate 기본값은 null 이다") {
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "1.0.0",
                    )

                version.startDate.shouldBeNull()
            }

            it("releaseDate 기본값은 null 이다") {
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "1.0.0",
                    )

                version.releaseDate.shouldBeNull()
            }

            it("deletedAt 기본값은 null 이다") {
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "1.0.0",
                    )

                version.deletedAt.shouldBeNull()
            }

            it("description 을 지정할 수 있다") {
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "1.0.0",
                        description = "첫 번째 정식 릴리스",
                    )

                version.description shouldBe "첫 번째 정식 릴리스"
            }

            it("startDate 와 releaseDate 를 지정할 수 있다") {
                val start = LocalDate.of(2026, 1, 1)
                val release = LocalDate.of(2026, 3, 31)
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "1.0.0",
                        startDate = start,
                        releaseDate = release,
                    )

                version.startDate shouldBe start
                version.releaseDate shouldBe release
            }
        }

        context("name 빈/공백 유효성 검증") {
            it("name 이 빈 문자열이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Version.create(projectId = projectId, name = "")
                }
            }

            it("name 이 공백-only 이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Version.create(projectId = projectId, name = "   ")
                }
            }
        }

        context("name trim 정규화") {
            it("name 양쪽 공백을 trim 한다") {
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "  1.0.0  ",
                    )

                version.name shouldBe "1.0.0"
            }

            it("trim 후 빈 문자열이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Version.create(projectId = projectId, name = "   ")
                }
            }
        }

        context("name 길이 불변식") {
            it("name 이 정확히 255자면 정상 생성된다") {
                val version =
                    Version.create(
                        projectId = projectId,
                        name = "A".repeat(255),
                    )

                version.name.length shouldBe 255
            }

            it("name 이 256자면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Version.create(projectId = projectId, name = "A".repeat(256))
                }
            }
        }
    }

    describe("Version.rename — 이름 변경") {

        it("유효한 이름으로 변경하면 새 인스턴스를 반환한다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")
            val renamed = original.rename("2.0.0")

            renamed.name shouldBe "2.0.0"
        }

        it("rename 도 trim 정규화를 적용한다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")
            val renamed = original.rename("  2.0.0  ")

            renamed.name shouldBe "2.0.0"
        }

        it("rename 에 빈 문자열을 전달하면 IllegalArgumentException 을 던진다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")

            shouldThrow<IllegalArgumentException> {
                original.rename("")
            }
        }

        it("rename 에 공백-only 문자열을 전달하면 IllegalArgumentException 을 던진다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")

            shouldThrow<IllegalArgumentException> {
                original.rename("   ")
            }
        }

        it("rename 에 256자 이름을 전달하면 IllegalArgumentException 을 던진다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")

            shouldThrow<IllegalArgumentException> {
                original.rename("A".repeat(256))
            }
        }

        it("원본 인스턴스는 변경되지 않는다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")
            original.rename("2.0.0")

            original.name shouldBe "1.0.0"
        }
    }

    describe("Version.changeDescription — 설명 변경") {

        it("설명을 변경하면 새 인스턴스를 반환한다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")
            val updated = original.changeDescription("새 설명")

            updated.description shouldBe "새 설명"
        }

        it("null 을 전달하면 설명을 클리어한다") {
            val original =
                Version.create(
                    projectId = projectId,
                    name = "1.0.0",
                    description = "기존 설명",
                )
            val updated = original.changeDescription(null)

            updated.description.shouldBeNull()
        }

        it("원본 인스턴스는 변경되지 않는다") {
            val original =
                Version.create(
                    projectId = projectId,
                    name = "1.0.0",
                    description = "기존 설명",
                )
            original.changeDescription("새 설명")

            original.description shouldBe "기존 설명"
        }
    }

    describe("Version.changeDates — 날짜 변경") {

        it("startDate 와 releaseDate 를 함께 변경하면 새 인스턴스를 반환한다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")
            val start = LocalDate.of(2026, 1, 1)
            val release = LocalDate.of(2026, 3, 31)
            val updated = original.changeDates(start, release)

            updated.startDate shouldBe start
            updated.releaseDate shouldBe release
        }

        it("null 을 전달하면 날짜를 클리어한다") {
            val original =
                Version.create(
                    projectId = projectId,
                    name = "1.0.0",
                    startDate = LocalDate.of(2026, 1, 1),
                    releaseDate = LocalDate.of(2026, 3, 31),
                )
            val updated = original.changeDates(null, null)

            updated.startDate.shouldBeNull()
            updated.releaseDate.shouldBeNull()
        }

        it("startDate > releaseDate 역순 날짜를 허용한다 (순서 미강제)") {
            val original = Version.create(projectId = projectId, name = "1.0.0")
            val start = LocalDate.of(2026, 6, 1)
            val release = LocalDate.of(2026, 1, 1)

            // 예외 없이 정상 처리되어야 한다
            val updated = original.changeDates(start, release)

            updated.startDate shouldBe start
            updated.releaseDate shouldBe release
        }

        it("원본 인스턴스는 변경되지 않는다") {
            val start = LocalDate.of(2026, 1, 1)
            val release = LocalDate.of(2026, 3, 31)
            val original =
                Version.create(
                    projectId = projectId,
                    name = "1.0.0",
                    startDate = start,
                    releaseDate = release,
                )
            original.changeDates(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30))

            original.startDate shouldBe start
            original.releaseDate shouldBe release
        }
    }

    describe("Version.softDelete — 소프트 삭제") {

        it("softDelete 호출 시 deletedAt 이 채워진 새 인스턴스를 반환한다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")
            val deleted = original.softDelete()

            deleted.deletedAt.shouldNotBeNull()
        }

        it("이미 삭제된 버전에 softDelete 를 호출하면 IllegalStateException 을 던진다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")
            val deleted = original.softDelete()

            shouldThrow<IllegalStateException> {
                deleted.softDelete()
            }
        }

        it("원본 인스턴스는 변경되지 않는다") {
            val original = Version.create(projectId = projectId, name = "1.0.0")
            original.softDelete()

            original.deletedAt.shouldBeNull()
        }
    }

    describe("Version.create — 상태 초기값") {

        it("create 로 생성된 버전의 status 는 UNRELEASED 이다") {
            val version = Version.create(projectId = projectId, name = "1.0.0")

            version.status shouldBe VersionStatus.UNRELEASED
        }

        it("create 로 생성된 버전의 releasedAt 은 null 이다") {
            val version = Version.create(projectId = projectId, name = "1.0.0")

            version.releasedAt.shouldBeNull()
        }
    }

    describe("Version.release — UNRELEASED → RELEASED") {

        it("UNRELEASED 상태에서 release 호출 시 status 가 RELEASED 로 변경된다") {
            val fixedNow = Instant.parse("2026-06-10T00:00:00Z")
            val version = Version.create(projectId = projectId, name = "1.0.0")
            val released = version.release(fixedNow)

            released.status shouldBe VersionStatus.RELEASED
        }

        it("UNRELEASED 상태에서 release 호출 시 releasedAt 이 전달한 now 로 설정된다") {
            val fixedNow = Instant.parse("2026-06-10T00:00:00Z")
            val version = Version.create(projectId = projectId, name = "1.0.0")
            val released = version.release(fixedNow)

            released.releasedAt shouldBe fixedNow
        }

        it("RELEASED 상태에서 release 호출 시 VersionTransitionNotAllowedException 을 던진다 (self-transition)") {
            val fixedNow = Instant.parse("2026-06-10T00:00:00Z")
            val version = Version.create(projectId = projectId, name = "1.0.0").release(fixedNow)

            shouldThrow<VersionTransitionNotAllowedException> {
                version.release(fixedNow)
            }
        }

        it("ARCHIVED 상태에서 release 호출 시 VersionTransitionNotAllowedException 을 던진다 (그래프 외 전환)") {
            val fixedNow = Instant.parse("2026-06-10T00:00:00Z")
            val version = Version.create(projectId = projectId, name = "1.0.0").archive()

            shouldThrow<VersionTransitionNotAllowedException> {
                version.release(fixedNow)
            }
        }
    }

    describe("Version.unrelease — RELEASED → UNRELEASED") {

        it("RELEASED 상태에서 unrelease 호출 시 status 가 UNRELEASED 로 변경된다") {
            val fixedNow = Instant.parse("2026-06-10T00:00:00Z")
            val version = Version.create(projectId = projectId, name = "1.0.0").release(fixedNow)
            val unreleased = version.unrelease()

            unreleased.status shouldBe VersionStatus.UNRELEASED
        }

        it("RELEASED 상태에서 unrelease 호출 시 releasedAt 이 null 로 클리어된다") {
            val fixedNow = Instant.parse("2026-06-10T00:00:00Z")
            val version = Version.create(projectId = projectId, name = "1.0.0").release(fixedNow)
            val unreleased = version.unrelease()

            unreleased.releasedAt.shouldBeNull()
        }

        it("UNRELEASED 상태에서 unrelease 호출 시 VersionTransitionNotAllowedException 을 던진다 (self-transition)") {
            val version = Version.create(projectId = projectId, name = "1.0.0")

            shouldThrow<VersionTransitionNotAllowedException> {
                version.unrelease()
            }
        }
    }

    describe("Version.archive — UNRELEASED/RELEASED → ARCHIVED") {

        it("UNRELEASED 상태에서 archive 호출 시 status 가 ARCHIVED 로 변경된다") {
            val version = Version.create(projectId = projectId, name = "1.0.0")
            val archived = version.archive()

            archived.status shouldBe VersionStatus.ARCHIVED
        }

        it("UNRELEASED 에서 archive 하면 releasedAt 은 null 로 유지된다") {
            val version = Version.create(projectId = projectId, name = "1.0.0")
            val archived = version.archive()

            archived.releasedAt.shouldBeNull()
        }

        it("RELEASED 에서 archive 하면 releasedAt 이 유지된다") {
            val fixedNow = Instant.parse("2026-06-10T00:00:00Z")
            val version = Version.create(projectId = projectId, name = "1.0.0").release(fixedNow)
            val archived = version.archive()

            archived.releasedAt shouldBe fixedNow
        }

        it("ARCHIVED 상태에서 archive 호출 시 VersionTransitionNotAllowedException 을 던진다 (self-transition)") {
            val version = Version.create(projectId = projectId, name = "1.0.0").archive()

            shouldThrow<VersionTransitionNotAllowedException> {
                version.archive()
            }
        }
    }

    describe("Version.unarchive — ARCHIVED → UNRELEASED") {

        it("ARCHIVED 상태에서 unarchive 호출 시 status 가 UNRELEASED 로 변경된다") {
            val version = Version.create(projectId = projectId, name = "1.0.0").archive()
            val unarchived = version.unarchive()

            unarchived.status shouldBe VersionStatus.UNRELEASED
        }

        it("ARCHIVED 상태에서 unarchive 호출 시 releasedAt 이 null 로 클리어된다") {
            val fixedNow = Instant.parse("2026-06-10T00:00:00Z")
            val version =
                Version.create(projectId = projectId, name = "1.0.0")
                    .release(fixedNow)
                    .archive()
            val unarchived = version.unarchive()

            unarchived.releasedAt.shouldBeNull()
        }

        it("UNRELEASED 상태에서 unarchive 호출 시 VersionTransitionNotAllowedException 을 던진다") {
            val version = Version.create(projectId = projectId, name = "1.0.0")

            shouldThrow<VersionTransitionNotAllowedException> {
                version.unarchive()
            }
        }
    }

    describe("ARCHIVED 읽기 전용 불변식") {

        it("ARCHIVED 버전에 rename 호출 시 VersionTransitionNotAllowedException 을 던진다") {
            val version = Version.create(projectId = projectId, name = "1.0.0").archive()

            shouldThrow<VersionTransitionNotAllowedException> {
                version.rename("2.0.0")
            }
        }

        it("ARCHIVED 버전에 changeDescription 호출 시 VersionTransitionNotAllowedException 을 던진다") {
            val version = Version.create(projectId = projectId, name = "1.0.0").archive()

            shouldThrow<VersionTransitionNotAllowedException> {
                version.changeDescription("새 설명")
            }
        }

        it("ARCHIVED 버전에 changeDates 호출 시 VersionTransitionNotAllowedException 을 던진다") {
            val version = Version.create(projectId = projectId, name = "1.0.0").archive()

            shouldThrow<VersionTransitionNotAllowedException> {
                version.changeDates(LocalDate.of(2026, 1, 1), null)
            }
        }

        it("ARCHIVED 버전에 softDelete 호출 시 VersionTransitionNotAllowedException 을 던진다") {
            val version = Version.create(projectId = projectId, name = "1.0.0").archive()

            shouldThrow<VersionTransitionNotAllowedException> {
                version.softDelete()
            }
        }
    }
})
