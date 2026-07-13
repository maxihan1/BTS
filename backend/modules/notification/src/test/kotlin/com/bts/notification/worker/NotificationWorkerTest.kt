// NotificationWorker 단위 테스트 — pgmq 폴링, 정책평가, 수신자해석, 채널발송, delete/archive (TDD RED)

package com.bts.notification.worker

import com.bts.notification.application.NotificationPolicyEvaluator
import com.bts.notification.application.PolicyMatch
import com.bts.notification.channel.NotificationChannelSender
import com.bts.notification.channel.NotificationTitleBuilder
import com.bts.notification.channel.SlackChannelBroadcaster
import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import com.bts.notification.domain.RecipientRole
import com.bts.notification.recipient.EventRecipientResolver
import com.bts.notification.recipient.NotificationSourceEvent
import com.bts.notification.recipient.ResolvedRecipient
import com.bts.notification.repository.NotificationRepository
import com.bts.notification.repository.UserSubscriptionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Instant
import java.util.UUID

/**
 * [NotificationWorker] 단위 테스트.
 *
 * ### 검증 항목
 * - POLL-1. 멘션 이벤트 JSON → 정책 평가 → 수신자 해석 → insertIfAbsent(true) → send 호출
 *           + Notification.actorUserId == event.actorId (BLOCKER-2 단언)
 * - POLL-2. insertIfAbsent=false (중복) → send 미호출 (멱등 S4)
 * - POLL-3. 정책 0건 → insert/send 미호출
 * - POLL-4. 성공 처리 후 pgmq.delete 호출
 * - POLL-5. 처리 중 예외 발생 → delete 미호출 (at-least-once)
 * - POLL-6. read_ct > MAX_RECEIVE_COUNT(poison) → archive 호출
 * - POLL-7. 미지원 type(fromWire = null) → 메시지 삭제(소임 없음), insert/send 미호출
 * - POLL-8. 빈 큐 → 아무 처리 없음
 * - POLL-9. occurredAt 누락(malformed) → 예외 → delete 미호출 (dedup 결정성 보호)
 * - POLL-10. actorId 없는 이벤트 → Notification.actorUserId == null (CONCERN-3 경계)
 * - BR-1. 슬랙 채널 브로드캐스트 — 정책 매치 0건(수신자 0명)이어도 정책평가 early-return 이전에 호출된다 (FR-SL-06 PR-B)
 * - BR-2. 브로드캐스터가 던진 DataAccessException 은 best-effort 로 흡수되어 dispatch 를 막지 않는다
 */
class NotificationWorkerTest : DescribeSpec({

    val dsl = mockk<DSLContext>()
    val policyEvaluator = mockk<NotificationPolicyEvaluator>()
    val recipientResolver = mockk<EventRecipientResolver>()
    val repository = mockk<NotificationRepository>()
    val userSubscriptionRepository = mockk<UserSubscriptionRepository>()
    val channelSender = mockk<NotificationChannelSender>()
    val objectMapper = ObjectMapper()
    val titleBuilder = NotificationTitleBuilder()
    val slackChannelBroadcaster = mockk<SlackChannelBroadcaster>(relaxed = true)

    val worker =
        NotificationWorker(
            dsl = dsl,
            policyEvaluator = policyEvaluator,
            recipientResolver = recipientResolver,
            repository = repository,
            userSubscriptionRepository = userSubscriptionRepository,
            channelSenders = listOf(channelSender),
            objectMapper = objectMapper,
            titleBuilder = titleBuilder,
            slackChannelBroadcaster = slackChannelBroadcaster,
        )

    val actorId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    val mentionedId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    val fixedNow: Instant = Instant.parse("2026-06-12T10:00:00Z")

    afterEach {
        clearMocks(
            dsl,
            policyEvaluator,
            recipientResolver,
            repository,
            userSubscriptionRepository,
            channelSender,
            slackChannelBroadcaster,
        )
    }

    // ── POLL-8: 빈 큐 ─────────────────────────────────────────────────────────

    describe("POLL-8 빈 큐") {
        it("메시지가 없으면 아무 처리도 하지 않는다") {
            stubEmptyQueue(dsl)

            worker.pollAndProcess()

            verify(exactly = 0) { policyEvaluator.evaluate(any(), any()) }
            verify(exactly = 0) { repository.insertIfAbsent(any()) }
        }
    }

    // ── POLL-1: 멘션 이벤트 정상 처리 ─────────────────────────────────────────

    describe("POLL-1 멘션 이벤트 정상 처리") {
        val msgId = 1L
        val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))
        val recipient = ResolvedRecipient(userId = mentionedId, channel = Channel.IN_APP)

        beforeEach {
            stubMentionMessage(dsl, actorId, mentionedId, msgId, fixedNow)

            every { policyEvaluator.evaluate(NotificationEventType.ISSUE_MENTIONED, "ATLAS") } returns matches
            every { recipientResolver.resolve(any<NotificationSourceEvent>(), matches) } returns listOf(recipient)
            every { userSubscriptionRepository.fetchDisabled(any(), any(), any()) } returns emptySet()
            every { repository.insertIfAbsent(any()) } returns true
            every { channelSender.supports(Channel.IN_APP) } returns true
            justRun { channelSender.send(any()) }
            justRun { repository.markSent(any()) }
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("policyEvaluator.evaluate 가 호출된다") {
            worker.pollAndProcess()
            verify(exactly = 1) { policyEvaluator.evaluate(NotificationEventType.ISSUE_MENTIONED, "ATLAS") }
        }

        it("recipientResolver.resolve 가 호출된다") {
            worker.pollAndProcess()
            verify(exactly = 1) { recipientResolver.resolve(any<NotificationSourceEvent>(), matches) }
        }

        it("repository.insertIfAbsent 가 호출된다") {
            worker.pollAndProcess()
            verify(exactly = 1) { repository.insertIfAbsent(any()) }
        }

        it("channelSender.send 가 호출된다") {
            worker.pollAndProcess()
            verify(exactly = 1) { channelSender.send(any()) }
        }

        it("Notification 의 eventType 이 ISSUE_MENTIONED 이다") {
            val notificationSlot = slot<Notification>()
            every { repository.insertIfAbsent(capture(notificationSlot)) } returns true

            worker.pollAndProcess()

            assertThat(notificationSlot.captured.eventType).isEqualTo(NotificationEventType.ISSUE_MENTIONED)
        }

        it("Notification 의 recipientUserId 가 mentionedId 이다") {
            val notificationSlot = slot<Notification>()
            every { repository.insertIfAbsent(capture(notificationSlot)) } returns true

            worker.pollAndProcess()

            assertThat(notificationSlot.captured.recipientUserId).isEqualTo(mentionedId)
        }

        it("Notification 은 PENDING 으로 삽입되고 발송 성공 후 markSent 로 SENT 전이된다") {
            val notificationSlot = slot<Notification>()
            every { repository.insertIfAbsent(capture(notificationSlot)) } returns true

            worker.pollAndProcess()

            assertThat(notificationSlot.captured.status).isEqualTo(NotificationStatus.PENDING)
            verify(exactly = 1) { repository.markSent(notificationSlot.captured.id) }
        }

        it("push 실패 시 markSent 미호출(PENDING 유지)·메시지 delete (best-effort, Inbox fallback)") {
            every { channelSender.send(any()) } throws RuntimeException("push failed")

            worker.pollAndProcess()

            verify(exactly = 0) { repository.markSent(any()) }
            verify(exactly = 1) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, NotificationWorker.QUEUE_NAME, msgId)
            }
        }

        it("실제 프로듀서의 nested actorId({value:uuid})를 파싱해 NotificationSourceEvent.actorId 로 전달한다 (B1 회귀)") {
            val sourceSlot = slot<NotificationSourceEvent>()
            every { recipientResolver.resolve(capture(sourceSlot), matches) } returns listOf(recipient)

            worker.pollAndProcess()

            // ActorId 는 issue-tracking 의 일반 data class 라 nested {"value":...} 로 직렬화된다.
            // flat 문자열로 파싱하면 actorId 가 null 이 되어 resolver 의 actor 자기제외가 무력화된다.
            assertThat(sourceSlot.captured.actorId).isEqualTo(actorId)
        }

        it("멘션 이벤트 처리 시 Notification 의 actorUserId 가 이벤트의 actorId 로 저장된다 (BLOCKER-2)") {
            val notificationSlot = slot<Notification>()
            every { repository.insertIfAbsent(capture(notificationSlot)) } returns true

            worker.pollAndProcess()

            // buildNotification 이 event.actorId 를 actorUserId 에 전달하지 않으면 null 이 되어 실패한다.
            assertThat(notificationSlot.captured.actorUserId).isEqualTo(actorId)
        }
    }

    // ── POLL-4: 성공 후 delete ─────────────────────────────────────────────────

    describe("POLL-4 성공 처리 후 pgmq.delete 호출") {
        val msgId = 4L
        val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))
        val recipient = ResolvedRecipient(userId = mentionedId, channel = Channel.IN_APP)

        beforeEach {
            stubMentionMessage(dsl, actorId, mentionedId, msgId, fixedNow)
            every { policyEvaluator.evaluate(any(), any()) } returns matches
            every { recipientResolver.resolve(any<NotificationSourceEvent>(), any()) } returns listOf(recipient)
            every { userSubscriptionRepository.fetchDisabled(any(), any(), any()) } returns emptySet()
            every { repository.insertIfAbsent(any()) } returns true
            every { channelSender.supports(Channel.IN_APP) } returns true
            justRun { channelSender.send(any()) }
            justRun { repository.markSent(any()) }
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("처리 성공 후 pgmq.delete 가 호출된다") {
            worker.pollAndProcess()

            verify(exactly = 1) {
                dsl.execute(
                    match<String> { it.contains("pgmq.delete") },
                    NotificationWorker.QUEUE_NAME,
                    msgId,
                )
            }
        }
    }

    // ── POLL-2: 중복 (insertIfAbsent=false) → send 미호출 ─────────────────────

    describe("POLL-2 중복 알림 (insertIfAbsent=false)") {
        val msgId = 2L
        val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))
        val recipient = ResolvedRecipient(userId = mentionedId, channel = Channel.IN_APP)

        beforeEach {
            stubMentionMessage(dsl, actorId, mentionedId, msgId, fixedNow)
            every { policyEvaluator.evaluate(any(), any()) } returns matches
            every { recipientResolver.resolve(any<NotificationSourceEvent>(), any()) } returns listOf(recipient)
            every { userSubscriptionRepository.fetchDisabled(any(), any(), any()) } returns emptySet()
            every { repository.insertIfAbsent(any()) } returns false
            every { channelSender.supports(Channel.IN_APP) } returns true
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("send 를 호출하지 않는다 (멱등 S4)") {
            worker.pollAndProcess()
            verify(exactly = 0) { channelSender.send(any()) }
        }

        it("pgmq.delete 는 여전히 호출된다 (중복이어도 소임 완료)") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, NotificationWorker.QUEUE_NAME, msgId)
            }
        }
    }

    // ── POLL-3: 정책 0건 → insert/send 미호출 ────────────────────────────────

    describe("POLL-3 정책 0건") {
        val msgId = 3L

        beforeEach {
            stubMentionMessage(dsl, actorId, mentionedId, msgId, fixedNow)
            every { policyEvaluator.evaluate(any(), any()) } returns emptyList()
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("insert 와 send 를 호출하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) { repository.insertIfAbsent(any()) }
            verify(exactly = 0) { channelSender.send(any()) }
        }

        it("정책 0건이어도 pgmq.delete 는 호출된다 (소임 완료)") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, NotificationWorker.QUEUE_NAME, msgId)
            }
        }
    }

    // ── POLL-5: 예외 발생 → delete 미호출 ────────────────────────────────────

    describe("POLL-5 처리 중 예외 발생") {
        val msgId = 5L
        val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))

        beforeEach {
            stubMentionMessage(dsl, actorId, mentionedId, msgId, fixedNow)
            every { policyEvaluator.evaluate(any(), any()) } returns matches
            every { recipientResolver.resolve(any<NotificationSourceEvent>(), any()) } throws RuntimeException("예외 발생")
        }

        it("delete 를 호출하지 않는다 (vt 만료 후 재전달 허용 — at-least-once)") {
            worker.pollAndProcess()
            verify(exactly = 0) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, any(), any<Long>())
            }
        }
    }

    // ── POLL-6: poison (read_ct > MAX) → archive 호출 ─────────────────────────

    describe("POLL-6 read_ct 초과 poison 메시지") {
        val msgId = 6L
        val poisonReadCt = NotificationWorker.MAX_RECEIVE_COUNT + 1

        beforeEach {
            stubMentionMessage(dsl, actorId, mentionedId, msgId, fixedNow, readCt = poisonReadCt)
            every { policyEvaluator.evaluate(any(), any()) } throws RuntimeException("의도적 예외 — poison 경로 유도")
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("pgmq.archive 를 호출한다 (dead-letter)") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(
                    match<String> { it.contains("pgmq.archive") },
                    NotificationWorker.QUEUE_NAME,
                    msgId,
                )
            }
        }

        it("pgmq.delete 는 호출하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, any(), any<Long>())
            }
        }
    }

    // ── POLL-7: 미지원 type → skip(delete) ───────────────────────────────────

    describe("POLL-7 미지원 이벤트 type") {
        val msgId = 7L

        beforeEach {
            stubUnknownTypeMessage(dsl, msgId)
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("insert 와 send 를 호출하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) { repository.insertIfAbsent(any()) }
            verify(exactly = 0) { channelSender.send(any()) }
        }

        it("pgmq.delete 를 호출한다 (소임 없음, 무한 재전달 차단)") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, NotificationWorker.QUEUE_NAME, msgId)
            }
        }
    }

    // ── POLL-10: actorId 없는 이벤트 → Notification.actorUserId == null ─────────

    describe("POLL-10 actorId 없는 이벤트 처리 시 actorUserId 가 null 이다") {
        val msgId = 11L
        val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))
        val recipient = ResolvedRecipient(userId = mentionedId, channel = Channel.IN_APP)

        beforeEach {
            stubMentionMessageWithoutActorId(dsl, mentionedId, msgId, fixedNow)
            every { policyEvaluator.evaluate(any(), any()) } returns matches
            every { recipientResolver.resolve(any<NotificationSourceEvent>(), any()) } returns listOf(recipient)
            every { userSubscriptionRepository.fetchDisabled(any(), any(), any()) } returns emptySet()
            every { channelSender.supports(Channel.IN_APP) } returns true
            justRun { channelSender.send(any()) }
            justRun { repository.markSent(any()) }
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("actorId 없는 이벤트에서 생성된 Notification 의 actorUserId 는 null 이다") {
            val notificationSlot = slot<Notification>()
            every { repository.insertIfAbsent(capture(notificationSlot)) } returns true

            worker.pollAndProcess()

            assertThat(notificationSlot.captured.actorUserId).isNull()
        }
    }

    // ── EC9: 비설정 채널(SLACK) 수신자 — fetchDisabled 미호출, sendToRecipient 도달 ─

    describe("EC9 비설정 채널(SLACK) 수신자는 구독 필터를 통과한다") {
        val msgId = 10L
        // IN_APP 정책 매치 1건 + SLACK 정책 매치 1건
        val matches =
            listOf(
                PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP),
                PolicyMatch(RecipientRole.MENTIONED, Channel.SLACK),
            )
        val inAppRecipient = ResolvedRecipient(userId = mentionedId, channel = Channel.IN_APP)
        val slackRecipient = ResolvedRecipient(userId = mentionedId, channel = Channel.SLACK)

        beforeEach {
            stubMentionMessage(dsl, actorId, mentionedId, msgId, fixedNow)
            every { policyEvaluator.evaluate(any(), any()) } returns matches
            every {
                recipientResolver.resolve(any<NotificationSourceEvent>(), matches)
            } returns listOf(inAppRecipient, slackRecipient)
            // IN_APP fetchDisabled — 설정 가능 채널이므로 호출됨, disabled 없음
            every {
                userSubscriptionRepository.fetchDisabled(any(), eq(Channel.IN_APP), any())
            } returns emptySet()
            every { repository.insertIfAbsent(any()) } returns true
            // channelSender: IN_APP 만 supports, SLACK 은 supports=false (sender 부재 시뮬레이션)
            every { channelSender.supports(Channel.IN_APP) } returns true
            every { channelSender.supports(Channel.SLACK) } returns false
            justRun { channelSender.send(any()) }
            justRun { repository.markSent(any()) }
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("SLACK 채널로 fetchDisabled 가 호출되지 않는다 (isConfigurable=false 라 그룹화에서 제외 — EC9)") {
            worker.pollAndProcess()

            verify(exactly = 0) {
                userSubscriptionRepository.fetchDisabled(any(), eq(Channel.SLACK), any())
            }
        }

        it("SLACK 수신자도 repository.insertIfAbsent 경로까지 도달한다 (필터 통과 2건)") {
            worker.pollAndProcess()

            // IN_APP + SLACK 수신자 2건 모두 insertIfAbsent 호출
            verify(exactly = 2) { repository.insertIfAbsent(any()) }
        }
    }

    // ── POLL-9: occurredAt 누락(malformed) → 예외 → delete 미호출 ────────────────

    describe("POLL-9 occurredAt 누락 이벤트 (malformed)") {
        val msgId = 9L

        beforeEach {
            stubMentionMessageWithoutOccurredAt(dsl, actorId, mentionedId, msgId)
        }

        it("occurredAt 부재 시 예외 → dispatch·delete 미호출 (재전달 — dedup 결정성 보호)") {
            worker.pollAndProcess()

            verify(exactly = 0) { policyEvaluator.evaluate(any(), any()) }
            verify(exactly = 0) { repository.insertIfAbsent(any()) }
            verify(exactly = 0) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, any(), any<Long>())
            }
        }
    }

    // ── BR-1: 슬랙 채널 브로드캐스트 — 정책평가(early-return) 이전에 이벤트당 1회 호출 ─────

    describe("BR-1 슬랙 채널 브로드캐스트는 정책 매치 0건(수신자 0명)이어도 호출된다") {
        val msgId = 12L

        beforeEach {
            stubMentionMessage(dsl, actorId, mentionedId, msgId, fixedNow)
            every { policyEvaluator.evaluate(any(), any()) } returns emptyList()
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("정책 평가가 빈 목록을 반환해 early-return 하더라도 broadcaster 가 1회 호출된다") {
            worker.pollAndProcess()

            verify(exactly = 1) { slackChannelBroadcaster.broadcastIfApplicable(any()) }
        }
    }

    // ── BR-2: 브로드캐스터 DataAccessException 은 best-effort 로 흡수되어 dispatch 를 막지 않는다 ─

    describe("BR-2 슬랙 채널 브로드캐스트 DataAccessException 은 dispatch 를 막지 않는다") {
        val msgId = 13L
        val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))
        val recipient = ResolvedRecipient(userId = mentionedId, channel = Channel.IN_APP)

        beforeEach {
            stubMentionMessage(dsl, actorId, mentionedId, msgId, fixedNow)
            every {
                slackChannelBroadcaster.broadcastIfApplicable(any())
            } throws DataAccessResourceFailureException("db down")
            every { policyEvaluator.evaluate(any(), any()) } returns matches
            every { recipientResolver.resolve(any<NotificationSourceEvent>(), any()) } returns listOf(recipient)
            every { userSubscriptionRepository.fetchDisabled(any(), any(), any()) } returns emptySet()
            every { repository.insertIfAbsent(any()) } returns true
            every { channelSender.supports(Channel.IN_APP) } returns true
            justRun { channelSender.send(any()) }
            justRun { repository.markSent(any()) }
            every { dsl.execute(any<String>(), NotificationWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("broadcaster 예외가 발생해도 정책평가~발송이 정상 진행된다") {
            worker.pollAndProcess()

            verify(exactly = 1) { repository.insertIfAbsent(any()) }
            verify(exactly = 1) { channelSender.send(any()) }
        }

        it("broadcaster 예외가 발생해도 메시지는 정상 delete 된다 (dispatch 미차단)") {
            worker.pollAndProcess()

            verify(exactly = 1) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, NotificationWorker.QUEUE_NAME, msgId)
            }
        }
    }
})

// ── test helpers ───────────────────────────────────────────────────────────────

/**
 * 멘션 이벤트 메시지 1건을 반환하도록 dsl.fetch 를 스텁한다.
 *
 * message JSON 구조: issue.mentioned 이벤트 페이로드.
 */
@Suppress("LongParameterList") // 테스트 스텁 헬퍼 — 모든 파라미터가 필수 검증 요소
private fun stubMentionMessage(
    dsl: DSLContext,
    actorId: UUID,
    mentionedId: UUID,
    msgId: Long,
    occurredAt: Instant,
    readCt: Int = 1,
) {
    val json =
        """
        {
          "type": "issue.mentioned",
          "issueKey": "ATLAS-1",
          "projectKey": "ATLAS",
          "actorId": { "value": "$actorId" },
          "mentionedUserIds": ["$mentionedId"],
          "occurredAt": "$occurredAt"
        }
        """.trimIndent()

    stubReadResult(dsl, msgId, json, readCt)
}

/**
 * actorId 필드가 없는 멘션 이벤트 메시지 1건을 반환하도록 dsl.fetch 를 스텁한다.
 *
 * POLL-10 — actorId 부재 이벤트 처리 시 Notification.actorUserId == null 검증용.
 */
private fun stubMentionMessageWithoutActorId(
    dsl: DSLContext,
    mentionedId: UUID,
    msgId: Long,
    occurredAt: Instant,
    readCt: Int = 1,
) {
    val json =
        """
        {
          "type": "issue.mentioned",
          "issueKey": "ATLAS-1",
          "projectKey": "ATLAS",
          "mentionedUserIds": ["$mentionedId"],
          "occurredAt": "$occurredAt"
        }
        """.trimIndent()

    stubReadResult(dsl, msgId, json, readCt)
}

/**
 * occurredAt 필드가 없는 멘션 이벤트 메시지 1건을 반환하도록 dsl.fetch 를 스텁한다.
 *
 * P2#3 회귀 — 워커가 now() 폴백 대신 예외를 던져 재전달되는지 검증한다.
 */
private fun stubMentionMessageWithoutOccurredAt(
    dsl: DSLContext,
    actorId: UUID,
    mentionedId: UUID,
    msgId: Long,
    readCt: Int = 1,
) {
    val json =
        """
        {
          "type": "issue.mentioned",
          "issueKey": "ATLAS-1",
          "projectKey": "ATLAS",
          "actorId": { "value": "$actorId" },
          "mentionedUserIds": ["$mentionedId"]
        }
        """.trimIndent()

    stubReadResult(dsl, msgId, json, readCt)
}

/**
 * 미지원 type 을 가진 메시지 1건을 반환하도록 dsl.fetch 를 스텁한다.
 */
private fun stubUnknownTypeMessage(
    dsl: DSLContext,
    msgId: Long,
    readCt: Int = 1,
) {
    val json =
        """
        {
          "type": "unknown.event",
          "issueKey": "ATLAS-1",
          "projectKey": "ATLAS",
          "occurredAt": "2026-06-12T10:00:00Z"
        }
        """.trimIndent()

    stubReadResult(dsl, msgId, json, readCt)
}

/**
 * 빈 큐를 반환하도록 dsl.fetch 를 스텁한다.
 */
private fun stubEmptyQueue(dsl: DSLContext) {
    val result =
        mockk<org.jooq.Result<org.jooq.Record>>(relaxed = true) {
            every { isEmpty() } returns true
        }
    every {
        dsl.fetch(
            any<String>(),
            NotificationWorker.QUEUE_NAME,
            NotificationWorker.VT_SECONDS,
            NotificationWorker.BATCH_SIZE,
        )
    } returns result
}

/**
 * pgmq.read 결과를 단건 메시지로 스텁하는 공통 헬퍼.
 */
private fun stubReadResult(
    dsl: DSLContext,
    msgId: Long,
    messageJson: String,
    readCt: Int,
) {
    val row =
        mockk<org.jooq.Record>(relaxed = true) {
            every { get("msg_id", Long::class.java) } returns msgId
            every { get("message", String::class.java) } returns messageJson
            every { get("read_ct", Int::class.java) } returns readCt
        }
    val result =
        mockk<org.jooq.Result<org.jooq.Record>>(relaxed = true) {
            every { isEmpty() } returns false
            every { iterator() } answers { mutableListOf(row).iterator() }
        }
    every {
        dsl.fetch(
            any<String>(),
            NotificationWorker.QUEUE_NAME,
            NotificationWorker.VT_SECONDS,
            NotificationWorker.BATCH_SIZE,
        )
    } returns result
}
