// NotificationWorker 구독 필터 통합 테스트 — user_notification_subs enabled=false 수신자 차단 검증 (FR-NT-04 T6)

package com.bts.notification.worker

import com.bts.notification.NotificationDeliverySchedulingConfig
import com.bts.notification.NotificationTestBootApplication
import com.bts.notification.TestPermissionConfig
import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.UserSubscription
import com.bts.notification.repository.UserSubscriptionRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [NotificationWorker] 구독 필터 통합 테스트 (FR-NT-04 Task 6 TDD RED).
 *
 * ## 검증 항목
 * - **SUB-1**: 수신자 A 가 (eventType, EMAIL) enabled=false 행 보유
 *   → 메시지 처리 후 A 의 EMAIL 알림 미생성, A 의 IN_APP 는 정상 생성 (FR7).
 * - **SUB-2**: 수신자 B 는 구독 행 없음 → 전원 발송 (EC5).
 * - **SUB-3(EC9)**: 설정 불가 채널(IN_APP/EMAIL 외) 수신자는 구독 필터 통과 — 이 테스트에서는
 *   정책이 IN_APP/EMAIL 만 지원하므로, EC9 는 NotificationWorkerTest(단위) 에서 mockk 로 커버.
 * - **SUB-4(C3 S3 AND 결합)**: 관리자 정책 OFF 면 사용자 enabled=true 행이 있어도 미발송.
 *   PolicyEvaluator 가 PolicyMatch 0 → recipient 0 → 필터 무관하게 미발송.
 *
 * ## 선행 완료 의존
 * - Task 3: [com.bts.notification.repository.UserSubscriptionRepository.fetchDisabled]
 * - Task 2: [com.bts.notification.domain.UserSubscription.isConfigurable]
 *
 * ## RED 단계 예상 실패
 * 현재 [NotificationWorker.dispatch] 에 구독 필터가 없으므로
 * SUB-1 의 "A EMAIL 미생성" 단언이 실패한다 (A 의 EMAIL 이 발송돼버림).
 *
 * ## 인프라
 * [SubscriptionFilterTestcontainersConfig] — pgmq 이미지 컨테이너 + V400~V404 마이그레이션.
 * [NotificationDeliverySchedulingConfig] — @EnableScheduling + poll-interval 50ms.
 * [SubscriptionFilterTestPortsConfig] — cross-BC 포트 stub 빈.
 *
 * ## 주의 (memory: concurrent-testcontainers-suite-flaky)
 * 다른 Testcontainers 클래스와 동시 실행 시 워커 크래시 가능.
 * test-results XML 0-failure 면 단독 재실행으로 확정.
 */
@SpringBootTest(
    classes = [
        NotificationTestBootApplication::class,
        SubscriptionFilterTestcontainersConfig::class,
        NotificationDeliverySchedulingConfig::class,
        TestPermissionConfig::class,
        SubscriptionFilterTestPortsConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationWorkerSubscriptionFilterTest {
    @Autowired
    lateinit var dsl: DSLContext

    @Autowired
    lateinit var userSubscriptionRepository: UserSubscriptionRepository

    companion object {
        /** pgmq 큐 이름 — NotificationWorker.QUEUE_NAME 과 동일 */
        const val QUEUE_NAME = "q_issue_events"

        /** 테스트 전용 프로젝트 키 */
        const val PROJECT_KEY = "SUBF"

        /**
         * 수신자 A — (issue.transitioned, EMAIL) enabled=false 행을 가진 사용자.
         * EMAIL 알림은 차단돼야 하고, IN_APP 는 정책이 있을 경우 정상 수신.
         */
        val USER_A: UUID = UUID.fromString("aaaaaaaa-0004-0006-aaaa-aaaaaaaaaaaa")

        /**
         * 수신자 B — 구독 행 없음 (opt-out 기본 = 수신).
         * 전원 발송이어야 한다.
         */
        val USER_B: UUID = UUID.fromString("bbbbbbbb-0004-0006-bbbb-bbbbbbbbbbbb")

        /**
         * 이벤트 발화자 — actor 자기 제외로 notifications 에 미기록.
         */
        val ACTOR: UUID = UUID.fromString("a0a00004-0006-a0a0-a0a0-a0a0a0a0a0a0")
    }

    @AfterEach
    fun cleanUp() {
        dsl.execute("DELETE FROM notifications")
        dsl.execute("DELETE FROM user_notification_subs")
        dsl.execute("DELETE FROM notification_policies WHERE project_key = ?", PROJECT_KEY)
        runCatching { dsl.execute("SELECT pgmq.purge_queue(?)", QUEUE_NAME) }
    }

    // ── SUB-1: A EMAIL opt-out → EMAIL 미생성, IN_APP 정상 생성 ───────────────

    /**
     * Given issue.transitioned × WATCHER × EMAIL + IN_APP 정책이 [PROJECT_KEY] 에 존재.
     * And USER_A 의 (issue.transitioned, EMAIL) enabled=false 구독 행 삽입.
     * And IssueRecipientLookupPort 가 watcher=[USER_A, USER_B] 반환.
     * When pgmq q_issue_events 에 issue.transitioned 이벤트 발행.
     * Then NotificationWorker 소비 후.
     *   USER_A 의 EMAIL 알림 미생성 (구독 필터 차단).
     *   USER_A 의 IN_APP 알림 정상 생성 (EMAIL opt-out 은 IN_APP 에 영향 없음).
     *   USER_B 의 EMAIL 알림 정상 생성 (구독 행 없음 = 수신).
     *   USER_B 의 IN_APP 알림 정상 생성.
     */
    @Test
    fun `SUB-1 USER_A EMAIL opt-out 시 EMAIL 미생성, IN_APP는 정상 생성된다`() {
        val issueKey = "SUBF-${System.currentTimeMillis()}"
        val occurredAt = Instant.now().toString()

        // 정책 시드 — WATCHER × EMAIL 과 WATCHER × IN_APP 두 종
        seedPolicy(PROJECT_KEY, "IN_APP")
        seedPolicy(PROJECT_KEY, "EMAIL")

        // USER_A 의 EMAIL opt-out 구독 행 삽입
        seedSubscription(USER_A, "issue.transitioned", "EMAIL", enabled = false)

        // 이벤트 발행
        publishTransitionedEvent(issueKey, occurredAt)

        // notifications 기록 대기 (B 에게 발송되면 총 건수 > 0)
        await()
            .atMost(15, TimeUnit.SECONDS)
            .untilAsserted {
                val count =
                    dsl.fetchOne(
                        "SELECT COUNT(*) FROM notifications WHERE issue_key = ?",
                        issueKey,
                    )!!.get(0, Long::class.java)
                assertThat(count).isGreaterThan(0L)
            }

        // 핵심 단언 — USER_A 의 EMAIL 미생성
        val userAEmailCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND channel = ? AND issue_key = ?",
                USER_A,
                "EMAIL",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(userAEmailCount)
            .describedAs("USER_A 는 EMAIL opt-out 이므로 EMAIL notifications 가 생성되지 않아야 한다")
            .isEqualTo(0L)

        // USER_A 의 IN_APP 정상 생성
        val userAInAppCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND channel = ? AND issue_key = ?",
                USER_A,
                "IN_APP",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(userAInAppCount)
            .describedAs("USER_A 의 IN_APP 알림은 EMAIL opt-out 과 무관하게 정상 생성되어야 한다")
            .isEqualTo(1L)

        // USER_B 의 EMAIL 정상 생성 (구독 행 없음)
        val userBEmailCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND channel = ? AND issue_key = ?",
                USER_B,
                "EMAIL",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(userBEmailCount)
            .describedAs("USER_B 는 구독 행이 없으므로 EMAIL 알림이 정상 생성되어야 한다")
            .isEqualTo(1L)

        // USER_B 의 IN_APP 정상 생성
        val userBInAppCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND channel = ? AND issue_key = ?",
                USER_B,
                "IN_APP",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(userBInAppCount)
            .describedAs("USER_B 의 IN_APP 알림도 정상 생성되어야 한다")
            .isEqualTo(1L)
    }

    // ── SUB-2: 구독 행 없음 → 전원 발송 (EC5) ────────────────────────────────

    /**
     * Given 구독 행이 하나도 없음.
     * And USER_A, USER_B 가 모두 watcher.
     * When pgmq q_issue_events 에 이벤트 발행.
     * Then 두 사람 모두 알림 생성 (전원 발송).
     */
    @Test
    fun `SUB-2 구독 행 없으면 전원 발송된다`() {
        val issueKey = "SUBF-EC5-${System.currentTimeMillis()}"
        val occurredAt = Instant.now().toString()

        seedPolicy(PROJECT_KEY, "IN_APP")

        publishTransitionedEvent(issueKey, occurredAt)

        await()
            .atMost(15, TimeUnit.SECONDS)
            .untilAsserted {
                val count =
                    dsl.fetchOne(
                        "SELECT COUNT(*) FROM notifications WHERE issue_key = ?",
                        issueKey,
                    )!!.get(0, Long::class.java)
                assertThat(count).isGreaterThan(0L)
            }

        // USER_A, USER_B 모두 IN_APP 기록
        val userACount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND channel = ? AND issue_key = ?",
                USER_A,
                "IN_APP",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(userACount)
            .describedAs("구독 행 없는 USER_A 는 전원 발송이어야 한다")
            .isEqualTo(1L)

        val userBCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND channel = ? AND issue_key = ?",
                USER_B,
                "IN_APP",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(userBCount)
            .describedAs("구독 행 없는 USER_B 는 전원 발송이어야 한다")
            .isEqualTo(1L)
    }

    // ── SUB-4(C3 S3 AND 결합): 관리자 정책 OFF + 사용자 ON → 0건 ──────────────

    /**
     * Given sprint.started 이벤트 타입에 대한 관리자 정책이 없음 (V401 시드 미포함 타입 사용).
     * And USER_A 에 (sprint.started, IN_APP) enabled=true 구독 행 존재 (사용자 ON).
     * When pgmq q_issue_events 에 sprint.started 이벤트 발행.
     * Then PolicyEvaluator 가 PolicyMatch 0 → recipient 0 → 필터 무관하게 미발송.
     * 관리자 정책 AND 사용자 구독 이중 게이트 확인.
     *
     * ## 설계 노트 (C3 AND 결합 근거)
     * [com.bts.notification.domain.UserSubscription] KDoc 이 명시한 AND 결합:
     * "최종 발송 여부는 NotificationPolicy(관리자 정책) AND UserSubscription(사용자 구독) 으로 결정".
     * PolicyEvaluator 매치 0 → recipients 0 → 구독 필터 호출 자체 없음 → 미발송.
     * 이는 NotificationWorkerTest POLL-3 과 동일 로직을 실 DB 정책 부재 환경에서 재확인한다.
     */
    @Test
    fun `SUB-4 C3 관리자 정책 OFF이면 사용자 opt-in이 있어도 미발송된다`() {
        val issueKey = "SUBF-C3-${System.currentTimeMillis()}"
        val occurredAt = Instant.now().toString()

        // sprint.started 는 V401 시드에 없으므로 전역 정책도 없음 → PolicyEvaluator match 0 보장
        // 사용자는 opt-in (enabled=true) — 정책 OFF 에 사용자 ON 이어도 미발송임을 확인
        seedSubscription(USER_A, "sprint.started", "IN_APP", enabled = true)
        seedSubscription(USER_B, "sprint.started", "IN_APP", enabled = true)

        publishSprintStartedEvent(issueKey, occurredAt)

        // 충분한 대기 후에도 notifications 가 없어야 함
        Thread.sleep(2_000)

        val count =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE issue_key = ?",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(count)
            .describedAs(
                "관리자 정책이 없으면 사용자 opt-in 이 있어도 알림이 생성되지 않아야 한다 " +
                    "(AND 결합: PolicyEvaluator match 0 → recipient 0 → 구독 필터 무관)",
            )
            .isEqualTo(0L)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * issue.transitioned × WATCHER × [channel] 정책을 [projectKey] 범위로 삽입한다.
     *
     * @param projectKey 프로젝트 키
     * @param channel 채널 이름 문자열 ("IN_APP" / "EMAIL")
     */
    private fun seedPolicy(
        projectKey: String,
        channel: String,
    ) {
        dsl.execute(
            """
            INSERT INTO notification_policies (event_type, recipient_role, channel, enabled, project_key, created_by)
            VALUES ('issue.transitioned', 'WATCHER', ?, TRUE, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_notification_policy DO NOTHING
            """.trimIndent(),
            channel,
            projectKey,
            UUID.randomUUID(),
        )
    }

    /**
     * user_notification_subs 테이블에 구독 행을 삽입한다.
     *
     * [UserSubscriptionRepository.upsert] 를 사용해 타입 안전하게 저장한다.
     *
     * @param userId 구독 소유자
     * @param eventType 이벤트 유형 wire 값 (예: "issue.transitioned")
     * @param channelName 채널 이름 문자열 ("IN_APP" / "EMAIL")
     * @param enabled 수신 여부
     */
    private fun seedSubscription(
        userId: UUID,
        eventType: String,
        channelName: String,
        enabled: Boolean,
    ) {
        val now = Instant.now()
        val sub =
            UserSubscription(
                userId = userId,
                eventType = requireNotNull(NotificationEventType.fromWire(eventType)) {
                    "알 수 없는 eventType wire 값: $eventType"
                },
                channel = requireNotNull(Channel.fromWire(channelName)) {
                    "알 수 없는 channel wire 값: $channelName"
                },
                enabled = enabled,
                createdAt = now,
                updatedAt = now,
            )
        userSubscriptionRepository.upsert(sub)
    }

    /**
     * pgmq `q_issue_events` 큐에 issue.transitioned 이벤트를 발행한다.
     *
     * watcher 수신자로 [USER_A], [USER_B] 를 주도록
     * [SubscriptionFilterTestPortsConfig] 가 IssueRecipientLookupPort 를 제어한다.
     *
     * @param issueKey 이슈 키
     * @param occurredAt 이벤트 발생 시각 ISO-8601 문자열
     */
    private fun publishTransitionedEvent(
        issueKey: String,
        occurredAt: String,
    ) {
        val payload =
            """
            {
              "type": "issue.transitioned",
              "issueKey": "$issueKey",
              "projectKey": "$PROJECT_KEY",
              "actorId": { "value": "$ACTOR" },
              "occurredAt": "$occurredAt"
            }
            """.trimIndent()

        dsl.execute("SELECT pgmq.create(?)", QUEUE_NAME)
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
    }

    /**
     * pgmq `q_issue_events` 큐에 sprint.started 이벤트를 발행한다.
     *
     * sprint.started 는 V401 시드에 없으므로 전역 관리자 정책이 없는 이벤트 타입이다.
     * C3 AND 결합 단언을 위해 사용한다.
     *
     * @param sprintId sprint 식별자 (issueKey 필드에 사용)
     * @param occurredAt 이벤트 발생 시각 ISO-8601 문자열
     */
    private fun publishSprintStartedEvent(
        sprintId: String,
        occurredAt: String,
    ) {
        val payload =
            """
            {
              "type": "sprint.started",
              "issueKey": "$sprintId",
              "projectKey": "$PROJECT_KEY",
              "actorId": { "value": "$ACTOR" },
              "occurredAt": "$occurredAt"
            }
            """.trimIndent()

        dsl.execute("SELECT pgmq.create(?)", QUEUE_NAME)
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
    }
}
