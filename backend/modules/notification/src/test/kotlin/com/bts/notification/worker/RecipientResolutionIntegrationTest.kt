// worker 경로 end-to-end 통합 테스트 — pgmq 이벤트 → 정책평가 → 수신자해석 → visibility 필터 → notifications 기록

package com.bts.notification.worker

import com.bts.notification.NotificationTestBootApplication
import com.bts.notification.TestPermissionConfig
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * worker 경로 수신자 해석 end-to-end 통합 테스트 (FR-NT-03 Task 7).
 *
 * ## 검증 목적
 * pgmq 이벤트 발행 → NotificationWorker 소비 → 정책 평가 → EventRecipientResolver 해석
 * → IssueVisibilityPort 필터 → notifications 테이블 기록까지 통합 동작을 검증한다.
 *
 * ## 핵심 단언 — 누출 차단 (visibility 필터 배선 확인)
 * [RecipientResolutionTestPortsConfig.EXCLUDED_USER_ID] 는 IssueVisibilityPort 빈이 명시적으로
 * 제외하도록 구성된 userId 다. worker 경로가 visibility 필터를 실제로 통과하면
 * 이 userId 는 notifications 에 기록되지 않아야 한다.
 *
 * allow-all 빈을 썼다면 이 단언이 실패해 "가짜 그린" 을 잡아낸다.
 *
 * ## 포트 빈 출처
 * - [RecipientResolutionTestPortsConfig] — 이슈/프로젝트/visibility 포트 제어 가능 빈
 * - [RecipientResolutionTestcontainersConfig] — pgmq 이미지 컨테이너 + DataSource + JwtDecoder stub
 * - [TestPermissionConfig] — fake SystemPermissionResolver
 *
 * ★`NotificationDeliverySchedulingConfig` 는 **일부러 넣지 않는다** — 자동 폴링이 켜지면
 * 이 컨텍스트의 워커가 형제 테스트의 메시지를 가져가 버린다. 아래 `drainQueue` 주석 참조.
 *
 * ## BC 격리
 * issue-tracking/identity-access 모듈 클래스패스 불포함. 포트 구현체는 test 소스셋의
 * [RecipientResolutionTestPortsConfig] 에서 제공한다.
 */
@SpringBootTest(
    classes = [
        NotificationTestBootApplication::class,
        RecipientResolutionTestcontainersConfig::class,
        TestPermissionConfig::class,
        RecipientResolutionTestPortsConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecipientResolutionIntegrationTest {
    @Autowired
    private lateinit var worker: NotificationWorker

    @Autowired
    lateinit var dsl: DSLContext

    /**
     * 큐가 빌 때까지 워커를 **동기로** 돌린다.
     *
     * ## 왜 awaitility 대기를 버렸나 — 근본 원인은 느림이 아니라 **메시지 도둑질**이었다
     * 이 클래스와 형제 통합테스트는 `RecipientResolutionTestcontainersConfig` 의 **싱글턴 컨테이너**를
     * 공유한다. 각자 Spring 컨텍스트를 띄우므로 `@Scheduled` 워커도 **두 벌**이 되고,
     * 둘이 **같은 pgmq 큐(`q_issue_events`)를 동시 폴링**한다. 먼저 읽은 쪽이 메시지를 가져가면
     * 다른 쪽 테스트는 영원히 0건이라 15초를 다 쓰고 타임아웃한다.
     *
     * 그래서 실패 대상이 회차마다 바뀌었다(2026-07-27 실측 — 동일 코드 6회 실행에서
     * SUCCESS/RR-1/SUCCESS/IC-1/RR-1+RR-2/SUCCESS). **타임아웃을 늘려도 해결되지 않는다** —
     * 메시지가 늦게 오는 게 아니라 **아예 오지 않기** 때문이다.
     *
     * ## 처방 — 스케줄러를 끄고 이 테스트가 직접 돌린다
     * `NotificationDeliverySchedulingConfig` 를 `@SpringBootTest classes` 에서 뺐다.
     * `NotificationTestBootApplication` 은 `@EnableScheduling` 을 선언하지 않으므로
     * **이 컨텍스트에는 자동 폴링이 없다** = 남의 메시지를 훔치지 않는다.
     * 대신 발행 직후 [NotificationWorker.pollAndProcess] 를 직접 호출한다 — 완전히 결정적이다.
     *
     * 배치 크기 때문에 1회 호출로 다 못 비울 수 있어 **빌 때까지** 반복한다.
     */
    private fun drainQueue() {
        // 빈 큐면 pollAndProcess 가 즉시 반환하므로 고정 횟수 반복이 안전하다.
        // (큐 실체 테이블명은 pgmq 내부 구현이라 직접 세지 않는다 — 구현 세부에 결합하지 않기 위해.)
        repeat(DRAIN_MAX_ROUNDS) { worker.pollAndProcess() }
    }

    companion object {
        /** 큐를 비우기 위한 최대 폴링 횟수 — 배치 크기 때문에 1회로 못 비울 수 있다. */
        private const val DRAIN_MAX_ROUNDS = 20

        /** pgmq 큐 이름 — NotificationWorker.QUEUE_NAME 과 동일 */
        const val QUEUE_NAME = "q_issue_events"

        /** 테스트 전용 프로젝트 키 (V401 시드 정책과 독립 범위 보장) */
        const val PROJECT_KEY = "RRIT"

        /**
         * issue.transitioned 이벤트 정책 시드 여부 확인용 issueKey 고정 접두사.
         * System.currentTimeMillis() 를 붙여 테스트 간 충돌을 방지한다.
         */
        const val ISSUE_KEY_PREFIX = "RRIT-"
    }

    @AfterEach
    fun cleanUp() {
        dsl.execute("DELETE FROM notifications")
        runCatching { dsl.execute("SELECT pgmq.purge_queue(?)", QUEUE_NAME) }
    }

    // ── RR-1: 정책 시드 + 이벤트 발행 → 통과 수신자만 기록 ───────────────────

    /**
     * Given issue.transitioned × WATCHER × IN_APP 정책이 [PROJECT_KEY] 에 존재.
     * And IssueRecipientLookupPort 가 3명의 watcher 를 반환
     *   — [RecipientResolutionTestPortsConfig.VISIBLE_USER_ID],
     *     [RecipientResolutionTestPortsConfig.EXCLUDED_USER_ID],
     *     [RecipientResolutionTestPortsConfig.ACTOR_USER_ID]
     * When pgmq q_issue_events 에 issue.transitioned 이벤트 발행.
     * Then NotificationWorker 가 소비 후 notifications 에 [VISIBLE_USER_ID] 만 기록.
     * And [EXCLUDED_USER_ID] 는 미기록 (visibility 필터 작동).
     * And [ACTOR_USER_ID] 는 미기록 (actor 자기 제외 작동).
     */
    @Test
    fun `RR-1 visibility 필터가 worker 경로에서 작동 — 제외 대상자는 notifications에 미기록된다`() {
        val issueKey = "$ISSUE_KEY_PREFIX${System.currentTimeMillis()}"
        val occurredAt = Instant.now().toString()

        // issue.transitioned × WATCHER × IN_APP 정책 삽입
        seedTransitionWatcherPolicy(PROJECT_KEY)

        // pgmq 이벤트 발행
        publishTransitionedEvent(issueKey, occurredAt)

        // NotificationWorker 폴링 후 DB 기록 대기 (poll-interval=50ms, 최대 15초)
        // ★비동기 대기 대신 **동기 폴링**한다 — 아래 KDoc §결정성 참조.
        drainQueue()

        val count =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE event_type = ? AND issue_key = ?",
                "issue.transitioned",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(count).isGreaterThan(0L)

        // 핵심 단언 — VISIBLE_USER_ID 가 기록됐는지
        val visibleCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND issue_key = ?",
                RecipientResolutionTestPortsConfig.VISIBLE_USER_ID,
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(visibleCount)
            .describedAs("VISIBLE_USER_ID 는 visibility 필터를 통과해 notifications 에 기록되어야 한다")
            .isEqualTo(1L)

        // 핵심 단언 — EXCLUDED_USER_ID 가 미기록인지 (visibility 필터 누출 차단)
        val excludedCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND issue_key = ?",
                RecipientResolutionTestPortsConfig.EXCLUDED_USER_ID,
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(excludedCount)
            .describedAs(
                "EXCLUDED_USER_ID 는 IssueVisibilityPort 에 의해 제외되어 notifications 에 미기록이어야 한다" +
                    " (visibility 필터 미배선 시 이 단언이 실패해 누출을 탐지한다)",
            )
            .isEqualTo(0L)

        // actor 자기 제외 단언
        val actorCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND issue_key = ?",
                RecipientResolutionTestPortsConfig.ACTOR_USER_ID,
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(actorCount)
            .describedAs("ACTOR_USER_ID 는 actor 자기 제외 로직으로 notifications 에 미기록이어야 한다")
            .isEqualTo(0L)

        // 총 기록 건수 — VISIBLE_USER_ID 단 1건만 존재해야 함
        val totalCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE issue_key = ?",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(totalCount)
            .describedAs("해당 이슈에 대한 notifications 는 VISIBLE_USER_ID 단 1건이어야 한다")
            .isEqualTo(1L)
    }

    // ── RR-2: dedup — 같은 이벤트 2회 발행 → 1건만 기록 ────────────────────

    /**
     * Given 같은 issue.transitioned 이벤트를 pgmq 에 2회 발행 (동일 occurredAt).
     * When NotificationWorker 가 각 메시지를 소비.
     * Then notifications 테이블에 VISIBLE_USER_ID 에 대한 행 1건만 존재 (dedup_key UNIQUE 제약).
     */
    @Test
    fun `RR-2 같은 이벤트 2회 발행 시 dedup으로 1건만 기록된다`() {
        val issueKey = "$ISSUE_KEY_PREFIX${System.currentTimeMillis()}-dedup"
        val occurredAt = Instant.parse("2026-06-19T00:00:00Z").toString()

        seedTransitionWatcherPolicy(PROJECT_KEY)

        publishTransitionedEvent(issueKey, occurredAt)
        publishTransitionedEvent(issueKey, occurredAt)

        // 첫 번째 알림 기록 대기
        // ★비동기 대기 대신 **동기 폴링**한다 — 아래 KDoc §결정성 참조.
        drainQueue()

        val count =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND issue_key = ?",
                RecipientResolutionTestPortsConfig.VISIBLE_USER_ID,
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(count).isEqualTo(1L)

        // 충분한 시간 후에도 1건 유지
        Thread.sleep(500)
        val finalCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND issue_key = ?",
                RecipientResolutionTestPortsConfig.VISIBLE_USER_ID,
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(finalCount)
            .describedAs("dedup_key UNIQUE 제약으로 2회 발행해도 1건만 기록되어야 한다")
            .isEqualTo(1L)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /**
     * issue.transitioned × WATCHER × IN_APP 정책을 [projectKey] 범위로 삽입한다.
     *
     * V401 전역 시드에 이미 issue.transitioned × WATCHER × IN_APP 가 포함되어 있으므로,
     * 프로젝트 전용 정책을 삽입해 "replace" 방식 (전역 무시) 을 명시한다.
     * 이 방식으로 다른 역할(REPORTER/ASSIGNEE 등) 이 전역 시드를 통해 추가로 포함되는 것을 막는다.
     *
     * @param projectKey 프로젝트 키
     */
    private fun seedTransitionWatcherPolicy(projectKey: String) {
        dsl.execute(
            """
            INSERT INTO notification_policies (event_type, recipient_role, channel, enabled, project_key, created_by)
            VALUES ('issue.transitioned', 'WATCHER', 'IN_APP', TRUE, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_notification_policy DO NOTHING
            """.trimIndent(),
            projectKey,
            UUID.randomUUID(),
        )
    }

    /**
     * pgmq `q_issue_events` 큐에 issue.transitioned 이벤트를 발행한다.
     *
     * JSON 구조는 issue-tracking BC 의 IssueTransitioned 이벤트 페이로드 형식.
     * BC 격리상 IssueDomainEvent 를 직접 import 하지 않고 문자열로 구성.
     *
     * actorId 는 [RecipientResolutionTestPortsConfig.ACTOR_USER_ID] 를 사용해
     * actor 자기 제외 로직이 작동하는지 확인한다.
     *
     * @param issueKey 이슈 키
     * @param occurredAt 이벤트 발생 시각 ISO-8601 문자열
     */
    private fun publishTransitionedEvent(
        issueKey: String,
        occurredAt: String,
    ) {
        val actorId = RecipientResolutionTestPortsConfig.ACTOR_USER_ID
        val payload =
            """
            {
              "type": "issue.transitioned",
              "issueKey": "$issueKey",
              "projectKey": "$PROJECT_KEY",
              "actorId": { "value": "$actorId" },
              "occurredAt": "$occurredAt"
            }
            """.trimIndent()

        dsl.execute("SELECT pgmq.create(?)", QUEUE_NAME)
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
    }
}
