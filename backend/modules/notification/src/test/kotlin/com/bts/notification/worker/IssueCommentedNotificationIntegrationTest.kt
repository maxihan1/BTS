// worker 경로 end-to-end 통합 테스트 — issue.commented 이벤트 → V401 REPORTER/ASSIGNEE/WATCHER/
// MENTIONED 정책 매트릭스가 실제로 인앱 알림을 발송하는지 검증 (FR-AT-01 게이트2 옵션A)

package com.bts.notification.worker

import com.bts.notification.NotificationDeliverySchedulingConfig
import com.bts.notification.NotificationTestBootApplication
import com.bts.notification.TestPermissionConfig
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
import java.util.concurrent.TimeUnit

/**
 * issue.commented 이벤트 인앱 알림 전달 end-to-end 통합 테스트 (FR-AT-01 게이트2 옵션A).
 *
 * ## 배경
 * issue-tracking `CommentApplicationService` 가 신규 `IssueCommented` 이벤트를 발행하면서
 * `NotificationEventType.ISSUE_COMMENTED` 와 V401 시드(issue.commented → REPORTER/ASSIGNEE/
 * WATCHER/MENTIONED, IN_APP, enabled)가 실제로 활성화되었다. 이 활성화가 올바른지 검증한다.
 *
 * ## 검증 목적
 * pgmq 이벤트 발행 → NotificationWorker 소비 → 정책 평가 → EventRecipientResolver 해석
 * → IssueVisibilityPort 필터 → notifications 테이블 기록까지 통합 동작을 검증한다.
 * [RecipientResolutionIntegrationTest] 와 동일 구조를 issue.commented 이벤트/역할 조합으로 미러링한다.
 *
 * ## 핵심 단언
 * - REPORTER/ASSIGNEE/WATCHER 3역할 각각 알림 기록 (V401 매트릭스)
 * - actor 자기 제외: watcher 이면서 동시에 actorId 인 사용자는 미기록
 * - MENTIONED 정책은 0명 매치: issue.commented 이벤트는 mentionedUserIds 를 담지 않으므로
 *   (buildSourceEvent 가 ISSUE_MENTIONED 타입에만 파싱) 추가 알림이 생기지 않는다 — 총 기록 건수 3건으로 확인
 * - visibility 필터: [IssueCommentedRecipientTestPortsConfig.VISIBILITY_EXCLUDED_USER_ID] 는 미기록
 *
 * ## 포트 빈 출처
 * - [IssueCommentedRecipientTestPortsConfig] — 리포터/담당자/워처 고정 반환 + visibility 제외 빈
 * - [RecipientResolutionTestcontainersConfig] — pgmq 이미지 컨테이너 + DataSource + JwtDecoder stub
 * - [NotificationDeliverySchedulingConfig] — @EnableScheduling + poll-interval 50ms
 * - [TestPermissionConfig] — fake SystemPermissionResolver
 *
 * ## BC 격리
 * issue-tracking/identity-access 모듈 클래스패스 불포함. 이벤트 payload 는 issue-tracking
 * `IssueCommented`(issueKey, projectKey, commentId, actorId, occurredAt) 와 동일 필드명·형식의
 * JSON 문자열로 직접 구성한다.
 *
 * ## 주의 (memory: concurrent-testcontainers-suite-flaky)
 * 이 클래스를 단독으로 실행할 때는 안정적이나 다른 Testcontainers 클래스와 동시 실행 시
 * 워커 크래시가 발생할 수 있다. test-results XML 0-failure 면 단독 재실행으로 확정.
 */
@SpringBootTest(
    classes = [
        NotificationTestBootApplication::class,
        RecipientResolutionTestcontainersConfig::class,
        NotificationDeliverySchedulingConfig::class,
        TestPermissionConfig::class,
        IssueCommentedRecipientTestPortsConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueCommentedNotificationIntegrationTest {
    @Autowired
    lateinit var dsl: DSLContext

    companion object {
        /** pgmq 큐 이름 — NotificationWorker.QUEUE_NAME 과 동일 */
        const val QUEUE_NAME = "q_issue_events"

        /** 테스트 전용 프로젝트 키 (V401 전역 시드로 폴백 — 프로젝트 전용 정책 미삽입) */
        const val PROJECT_KEY = "ICMT"

        /** issueKey 고정 접두사. System.currentTimeMillis() 를 붙여 테스트 간 충돌을 방지한다. */
        const val ISSUE_KEY_PREFIX = "ICMT-"
    }

    @AfterEach
    fun cleanUp() {
        dsl.execute("DELETE FROM notifications")
        runCatching { dsl.execute("SELECT pgmq.purge_queue(?)", QUEUE_NAME) }
    }

    // ── IC-1: REPORTER/ASSIGNEE/WATCHER 알림 + actor 제외 + MENTIONED 0매치 ────

    /**
     * Given issue.commented × REPORTER/ASSIGNEE/WATCHER/MENTIONED × IN_APP 정책이 V401 전역 시드로 존재.
     * And IssueRecipientLookupPort 가 REPORTER_USER_ID/ASSIGNEE_USER_ID/워처 3명(그 중 하나는 actor,
     *   다른 하나는 visibility 제외 대상)을 반환.
     * When pgmq q_issue_events 에 issue.commented 이벤트 발행 (actorId=ACTOR_WATCHER_USER_ID).
     * Then notifications 에 REPORTER_USER_ID/ASSIGNEE_USER_ID/WATCHER_USER_ID 3건만 기록된다.
     * And ACTOR_WATCHER_USER_ID 는 actor 자기 제외로 미기록.
     * And VISIBILITY_EXCLUDED_USER_ID 는 visibility 필터로 미기록.
     * And MENTIONED 역할은 mentionedUserIds 가 없어 추가 알림을 만들지 않는다(총 3건으로 확인).
     */
    @Test
    fun `IC-1 issue-commented 이벤트가 REPORTER ASSIGNEE WATCHER 에게만 인앱 알림을 발송한다`() {
        val issueKey = "$ISSUE_KEY_PREFIX${System.currentTimeMillis()}"
        val occurredAt = Instant.now().toString()

        publishCommentedEvent(issueKey, occurredAt)

        // NotificationWorker 폴링 후 DB 기록 대기 (poll-interval=50ms, 최대 15초).
        // dispatch() 는 필터 통과 수신자(REPORTER/ASSIGNEE/WATCHER 3명)를 반복문에서 1명씩
        // insert+commit 하므로(단일 배치 트랜잭션 아님), ">0" 처럼 약한 조건으로 기다리면
        // 아직 일부만 커밋된 시점에 단언이 실행되는 레이스가 생긴다. 최종 안정 상태(3건)를
        // 명시적으로 기다려 이후 단언이 완전히 처리된 뒤 실행되게 한다.
        await()
            .atMost(15, TimeUnit.SECONDS)
            .untilAsserted {
                val count =
                    dsl.fetchOne(
                        "SELECT COUNT(*) FROM notifications WHERE event_type = ? AND issue_key = ?",
                        "issue.commented",
                        issueKey,
                    )!!.get(0, Long::class.java)
                assertThat(count).isEqualTo(3L)
            }

        // REPORTER 알림 기록 확인
        assertRecipientRecorded(
            issueKey,
            IssueCommentedRecipientTestPortsConfig.REPORTER_USER_ID,
            "REPORTER_USER_ID 는 REPORTER 역할 정책으로 notifications 에 기록되어야 한다",
        )

        // ASSIGNEE 알림 기록 확인
        assertRecipientRecorded(
            issueKey,
            IssueCommentedRecipientTestPortsConfig.ASSIGNEE_USER_ID,
            "ASSIGNEE_USER_ID 는 ASSIGNEE 역할 정책으로 notifications 에 기록되어야 한다",
        )

        // WATCHER 알림 기록 확인
        assertRecipientRecorded(
            issueKey,
            IssueCommentedRecipientTestPortsConfig.WATCHER_USER_ID,
            "WATCHER_USER_ID 는 WATCHER 역할 정책으로 notifications 에 기록되어야 한다",
        )

        // actor 자기 제외 — watcher 이면서 actorId 인 사용자는 미기록
        assertRecipientExcluded(
            issueKey,
            IssueCommentedRecipientTestPortsConfig.ACTOR_WATCHER_USER_ID,
            "ACTOR_WATCHER_USER_ID 는 watcher 목록에 포함되어 있어도 actor 자기 제외 로직으로" +
                " notifications 에 미기록이어야 한다",
        )

        // visibility 필터 — 제외 대상자는 미기록 (누출 차단 배선 확인)
        assertRecipientExcluded(
            issueKey,
            IssueCommentedRecipientTestPortsConfig.VISIBILITY_EXCLUDED_USER_ID,
            "VISIBILITY_EXCLUDED_USER_ID 는 IssueVisibilityPort 에 의해 제외되어 notifications 에" +
                " 미기록이어야 한다 (visibility 필터 미배선 시 이 단언이 실패해 누출을 탐지한다)",
        )

        // MENTIONED 정책 0매치 확인 — 총 기록 건수가 REPORTER/ASSIGNEE/WATCHER 3건과 정확히 일치해야
        // MENTIONED 역할이 추가 알림(중복)을 만들지 않았음을 보장한다.
        assertTotalNotificationCount(issueKey, expected = 3L)

        // 각 알림의 channel=IN_APP, status=SENT 확인 (V401 시드 채널/전송 성공 여부)
        assertAllSentViaInApp(issueKey)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /**
     * [recipientUserId] 가 [issueKey] 에 대해 notifications 에 정확히 1건 기록됐는지 단언한다.
     *
     * @param issueKey 대상 이슈 키
     * @param recipientUserId 확인할 수신자 userId
     * @param description 단언 실패 시 표시할 설명
     */
    private fun assertRecipientRecorded(
        issueKey: String,
        recipientUserId: java.util.UUID,
        description: String,
    ) {
        val count =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND issue_key = ?",
                recipientUserId,
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(count).describedAs(description).isEqualTo(1L)
    }

    /**
     * [recipientUserId] 가 [issueKey] 에 대해 notifications 에 전혀 기록되지 않았는지 단언한다.
     *
     * @param issueKey 대상 이슈 키
     * @param recipientUserId 확인할 수신자 userId (제외 대상)
     * @param description 단언 실패 시 표시할 설명
     */
    private fun assertRecipientExcluded(
        issueKey: String,
        recipientUserId: java.util.UUID,
        description: String,
    ) {
        val count =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND issue_key = ?",
                recipientUserId,
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(count).describedAs(description).isEqualTo(0L)
    }

    /**
     * [issueKey] 에 대한 notifications 총 기록 건수가 [expected] 와 일치하는지 단언한다.
     *
     * @param issueKey 대상 이슈 키
     * @param expected 기대하는 총 기록 건수
     */
    private fun assertTotalNotificationCount(
        issueKey: String,
        expected: Long,
    ) {
        val totalCount =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE issue_key = ?",
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(totalCount)
            .describedAs(
                "REPORTER/ASSIGNEE/WATCHER 3건만 존재해야 한다 — MENTIONED 정책은 issue.commented 이벤트에" +
                    " mentionedUserIds 가 없어 0명 매치되어야 한다(중복 없음)",
            )
            .isEqualTo(expected)
    }

    /**
     * [issueKey] 에 대한 모든 notifications 행이 channel=IN_APP, status=SENT 인지 단언한다.
     *
     * @param issueKey 대상 이슈 키
     */
    private fun assertAllSentViaInApp(issueKey: String) {
        val rows =
            dsl.fetch(
                "SELECT status, channel FROM notifications WHERE issue_key = ?",
                issueKey,
            )
        assertThat(rows.map { it.get(0, String::class.java) }).allMatch { it == "SENT" }
        assertThat(rows.map { it.get(1, String::class.java) }).allMatch { it == "IN_APP" }
    }

    /**
     * pgmq `q_issue_events` 큐에 issue.commented 이벤트를 발행한다.
     *
     * JSON 구조는 issue-tracking BC 의 `IssueCommented`(issueKey, projectKey, commentId, actorId,
     * occurredAt) 이벤트 직렬화 형식과 동일. BC 격리상 IssueDomainEvent 를 직접 import 하지 않고
     * 문자열로 구성한다.
     *
     * actorId 는 [IssueCommentedRecipientTestPortsConfig.ACTOR_WATCHER_USER_ID] 를 사용해
     * watcher 이면서 actor 인 사용자의 자기 제외 로직이 작동하는지 확인한다.
     *
     * @param issueKey 이슈 키
     * @param occurredAt 이벤트 발생 시각 ISO-8601 문자열
     */
    private fun publishCommentedEvent(
        issueKey: String,
        occurredAt: String,
    ) {
        val actorId = IssueCommentedRecipientTestPortsConfig.ACTOR_WATCHER_USER_ID
        val commentId = java.util.UUID.randomUUID()
        val payload =
            """
            {
              "type": "issue.commented",
              "issueKey": "$issueKey",
              "projectKey": "$PROJECT_KEY",
              "commentId": "$commentId",
              "actorId": { "value": "$actorId" },
              "occurredAt": "$occurredAt"
            }
            """.trimIndent()

        dsl.execute("SELECT pgmq.create(?)", QUEUE_NAME)
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
    }
}
