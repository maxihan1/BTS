// 사용자별 알림 구독 매트릭스 서비스 단위 테스트 — mockk repo + 고정 Clock

package com.bts.notification.application

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.UserSubscription
import com.bts.notification.repository.UserSubscriptionRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class UserSubscriptionServiceTest : DescribeSpec({

    val repository: UserSubscriptionRepository = mockk()

    // 고정 시각 주입 — AuthController revokeSession time-bomb 교훈 (Clock 의존성 주입)
    val fixedInstant: Instant = Instant.parse("2026-06-11T12:00:00Z")
    val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    val service =
        UserSubscriptionService(
            repository = repository,
            clock = fixedClock,
        )

    val userId: UUID = UUID.randomUUID()

    // mockk 호출 기록을 각 테스트마다 초기화 — 이전 테스트 잔여 호출이 verify 에 영향을 주지 않도록
    beforeEach { clearAllMocks() }

    /** 테스트용 UserSubscription 빌더 */
    fun buildSub(
        eventType: NotificationEventType,
        channel: Channel,
        enabled: Boolean,
    ): UserSubscription =
        UserSubscription(
            userId = userId,
            eventType = eventType,
            channel = channel,
            enabled = enabled,
            createdAt = fixedInstant,
            updatedAt = fixedInstant,
        )

    describe("getMatrix — 저장 이력 0건(opt-out 기본)") {

        it("findByUser 가 빈 리스트 반환 시 22셀 모두 enabled=true") {
            every { repository.findByUser(userId) } returns emptyList()

            val result: List<SubscriptionCell> = service.getMatrix(userId)

            result shouldHaveSize 22
            result.all { cell -> cell.enabled } shouldBe true
        }

        it("10가지 eventType × 2가지 채널(IN_APP, EMAIL) 조합이 빠짐없이 포함됨") {
            every { repository.findByUser(userId) } returns emptyList()

            val result: List<SubscriptionCell> = service.getMatrix(userId)

            val expectedPairs =
                NotificationEventType.entries.flatMap { et ->
                    listOf(et to Channel.IN_APP, et to Channel.EMAIL)
                }.toSet()

            val actualPairs = result.map { cell -> cell.eventType to cell.channel }.toSet()
            actualPairs shouldBe expectedPairs
        }

        it("정렬 결정성 — NotificationEventType 선언 순서 × (IN_APP, EMAIL) 고정 순서(EC11)") {
            every { repository.findByUser(userId) } returns emptyList()

            val result: List<SubscriptionCell> = service.getMatrix(userId)

            val expected =
                NotificationEventType.entries.flatMap { et ->
                    listOf(et to Channel.IN_APP, et to Channel.EMAIL)
                }

            result.map { cell -> cell.eventType to cell.channel } shouldBe expected
        }
    }

    describe("getMatrix — 저장 행으로 오버레이") {

        it("(ISSUE_COMMENTED, EMAIL, false) 저장 시 해당 셀만 false, 나머지 21개는 true") {
            val storedSub = buildSub(NotificationEventType.ISSUE_COMMENTED, Channel.EMAIL, false)
            every { repository.findByUser(userId) } returns listOf(storedSub)

            val result: List<SubscriptionCell> = service.getMatrix(userId)

            result shouldHaveSize 22
            val targetCell =
                result.find { cell ->
                    cell.eventType == NotificationEventType.ISSUE_COMMENTED && cell.channel == Channel.EMAIL
                }
            targetCell!!.enabled shouldBe false
            result.filter { cell -> cell != targetCell }.all { cell -> cell.enabled } shouldBe true
        }

        it("여러 행 오버레이 — 저장된 셀은 저장 값으로, 나머지는 true") {
            val subs =
                listOf(
                    buildSub(NotificationEventType.ISSUE_CREATED, Channel.IN_APP, false),
                    buildSub(NotificationEventType.SPRINT_STARTED, Channel.EMAIL, false),
                )
            every { repository.findByUser(userId) } returns subs

            val result: List<SubscriptionCell> = service.getMatrix(userId)

            result shouldHaveSize 22
            result.find { cell ->
                cell.eventType == NotificationEventType.ISSUE_CREATED && cell.channel == Channel.IN_APP
            }!!.enabled shouldBe false

            result.find { cell ->
                cell.eventType == NotificationEventType.SPRINT_STARTED && cell.channel == Channel.EMAIL
            }!!.enabled shouldBe false

            val overriddenPairs =
                setOf(
                    NotificationEventType.ISSUE_CREATED to Channel.IN_APP,
                    NotificationEventType.SPRINT_STARTED to Channel.EMAIL,
                )
            result
                .filter { cell -> (cell.eventType to cell.channel) !in overriddenPairs }
                .all { cell -> cell.enabled } shouldBe true
        }
    }

    describe("patch — 정상 경로") {

        it("빈 entries → upsert 0건, 현재 매트릭스 반환(EC10)") {
            every { repository.findByUser(userId) } returns emptyList()

            val result: List<SubscriptionCell> = service.patch(userId, emptyList())

            verify(exactly = 0) { repository.upsert(any()) }
            result shouldHaveSize 22
            result.all { cell -> cell.enabled } shouldBe true
        }

        it("1건 entry → upsert 1회 호출, userId·eventType·channel·enabled·now 검증") {
            val entry =
                SubscriptionPatchEntry(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    channel = Channel.IN_APP,
                    enabled = false,
                )
            every { repository.upsert(any()) } returns Unit
            every { repository.findByUser(userId) } returns
                listOf(
                    buildSub(NotificationEventType.ISSUE_ASSIGNED, Channel.IN_APP, false),
                )

            val result: List<SubscriptionCell> = service.patch(userId, listOf(entry))

            verify(exactly = 1) {
                repository.upsert(
                    match { sub ->
                        sub.userId == userId &&
                            sub.eventType == NotificationEventType.ISSUE_ASSIGNED &&
                            sub.channel == Channel.IN_APP &&
                            !sub.enabled &&
                            sub.createdAt == fixedInstant &&
                            sub.updatedAt == fixedInstant
                    },
                )
            }
            result shouldHaveSize 22
        }

        it("여러 entry → 각각 upsert 호출 후 최신 매트릭스 반환") {
            val entries =
                listOf(
                    SubscriptionPatchEntry(NotificationEventType.ISSUE_CREATED, Channel.EMAIL, false),
                    SubscriptionPatchEntry(NotificationEventType.ISSUE_COMMENTED, Channel.IN_APP, false),
                )
            every { repository.upsert(any()) } returns Unit
            every { repository.findByUser(userId) } returns
                listOf(
                    buildSub(NotificationEventType.ISSUE_CREATED, Channel.EMAIL, false),
                    buildSub(NotificationEventType.ISSUE_COMMENTED, Channel.IN_APP, false),
                )

            val result: List<SubscriptionCell> = service.patch(userId, entries)

            verify(exactly = 2) { repository.upsert(any()) }
            result shouldHaveSize 22
            result.find { cell ->
                cell.eventType == NotificationEventType.ISSUE_CREATED && cell.channel == Channel.EMAIL
            }!!.enabled shouldBe false
        }
    }

    describe("patch — channel 검증(EC1/EC8)") {

        it("CONFIGURABLE_CHANNELS 밖 채널(WEBHOOK) → IllegalArgumentException, upsert 0건") {
            val entry =
                SubscriptionPatchEntry(
                    eventType = NotificationEventType.ISSUE_CREATED,
                    channel = Channel.WEBHOOK,
                    enabled = false,
                )

            shouldThrow<IllegalArgumentException> {
                service.patch(userId, listOf(entry))
            }

            verify(exactly = 0) { repository.upsert(any()) }
        }

        it("유효한 entry와 무효한 entry 혼합 시 — 전체 거부, upsert 0건(EC8 부분 적용 금지)") {
            val entries =
                listOf(
                    SubscriptionPatchEntry(NotificationEventType.ISSUE_CREATED, Channel.EMAIL, false),
                    SubscriptionPatchEntry(NotificationEventType.ISSUE_ASSIGNED, Channel.SLACK, true),
                )

            shouldThrow<IllegalArgumentException> {
                service.patch(userId, entries)
            }

            verify(exactly = 0) { repository.upsert(any()) }
        }

        it("TEAMS 채널 → IllegalArgumentException") {
            val entry =
                SubscriptionPatchEntry(
                    eventType = NotificationEventType.SPRINT_STARTED,
                    channel = Channel.TEAMS,
                    enabled = true,
                )

            shouldThrow<IllegalArgumentException> {
                service.patch(userId, listOf(entry))
            }

            verify(exactly = 0) { repository.upsert(any()) }
        }
    }
})
