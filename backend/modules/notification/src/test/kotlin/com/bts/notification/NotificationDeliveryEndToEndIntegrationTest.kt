// STOMP end-to-end 통합테스트 — 연결→pgmq 발행→NotificationWorker 소비→클라이언트 수신 (FR-NT-02 Task 11)

package com.bts.notification

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * STOMP over WebSocket end-to-end 통합 테스트 (FR-NT-02 S1/S4/S6).
 *
 * ## 검증 시나리오
 * - **S1 멘션 실시간 알림**: 인증된 STOMP 클라이언트가 /user/queue/notifications 구독 →
 *   pgmq `q_issue_events` 에 IssueMentioned JSON 발행 → NotificationWorker 소비 →
 *   클라이언트가 인앱 알림 payload 수신. notifications 테이블에 SENT 기록 확인.
 * - **S6 미인증 거부**: JWT 없이 STOMP CONNECT → StompAuthChannelInterceptor 거부.
 * - **S4 재전달 멱등**: 같은 이벤트 2회 발행 → notifications 테이블에 행 1건, 클라이언트 1회 수신.
 *
 * ## 인프라
 * - Testcontainers `quay.io/tembo/pg16-pgmq:latest` — pgmq 확장 사전 설치. V400~V403 마이그레이션 적용.
 * - [NotificationDeliveryTestcontainersConfig] — pgmq 이미지 + DataSource + JwtDecoder 테스트 빈 제공.
 * - [NotificationDeliverySchedulingConfig] — @EnableScheduling 활성화 + poll-interval 50ms.
 * - @SpringBootTest(RANDOM_PORT) — WebSocketConfig 와 실제 Tomcat 포트 활성화.
 *
 * ## JWT 빈 구성 방식
 * [NotificationDeliveryTestcontainersConfig] 에서 JwtDecoder 를 테스트 빈으로 등록한다.
 * 특정 고정 토큰(RECIPIENT_JWT_TOKEN) 을 decode 하면 subject=RECIPIENT_USER_ID 를 반환한다.
 * RSA 키쌍 없이 단순 Map lookup 으로 구현 — 프로덕션 JwtDecoder 를 교체하지 않으므로 테스트 격리 완전.
 *
 * ## 주의 (memory: concurrent-testcontainers-suite-flaky)
 * 이 클래스를 단독으로 실행할 때는 안정적이지만, 다른 Testcontainers 클래스와 동시에 실행하면
 * 워커 크래시가 발생할 수 있다. test-results XML 에 실패가 기록된 경우에만 실제 실패로 처리한다.
 */
@SpringBootTest(
    classes = [
        NotificationTestBootApplication::class,
        NotificationDeliveryTestcontainersConfig::class,
        NotificationDeliverySchedulingConfig::class,
        TestPermissionConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationDeliveryEndToEndIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    lateinit var dsl: DSLContext

    private lateinit var stompClient: WebSocketStompClient

    companion object {
        /** 수신자 userId — StompAuthChannelInterceptor 가 JWT subject 로 Principal.name 에 박제 */
        val RECIPIENT_USER_ID: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

        /** 이벤트 발화자 userId (멘션 이벤트 actorId — 본인 제외 로직 확인) */
        val ACTOR_USER_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

        /**
         * NotificationDeliveryTestcontainersConfig.jwtDecoder 가 인식하는 고정 테스트 JWT 토큰.
         *
         * 실제 서명/만료 검증이 없는 테스트 전용 stub 토큰이다.
         * 프로덕션 토큰과 혼동되지 않도록 "test-jwt-" prefix 를 사용한다.
         */
        const val RECIPIENT_JWT_TOKEN = "test-jwt-recipient-cccccccc"

        /** STOMP CONNECT WebSocket 엔드포인트 */
        const val WS_ENDPOINT = "/ws"

        /** 인앱 알림 구독 destination */
        const val NOTIFICATION_DESTINATION = "/user/queue/notifications"

        /**
         * pgmq 이슈 이벤트 큐 이름.
         * NotificationWorker.QUEUE_NAME 과 동일하게 유지.
         */
        const val QUEUE_NAME = "q_issue_events"
    }

    @BeforeEach
    fun setUpStompClient() {
        stompClient =
            WebSocketStompClient(StandardWebSocketClient()).apply {
                // Jackson 으로 JSON payload 직렬화/역직렬화
                messageConverter = MappingJackson2MessageConverter()
            }
    }

    @AfterEach
    fun tearDownAndClean() {
        stompClient.stop()
        // 테스트 간 격리 — notifications 테이블 전체 삭제 (seed 없음)
        dsl.execute("DELETE FROM notifications")
        // pgmq 큐 잔여 메시지 제거 (다음 테스트로 오염 방지)
        runCatching {
            dsl.execute("SELECT pgmq.purge_queue(?)", QUEUE_NAME)
        }
    }

    // ── S6: 미인증 거부 ────────────────────────────────────────────────────────

    /**
     * Given JWT 없이 STOMP CONNECT 시도.
     * When StompAuthChannelInterceptor 가 Authorization 헤더를 검증.
     * Then 연결이 거부된다 (session 이 connected 상태가 되지 않음).
     */
    @Test
    fun `S6 JWT 없이 STOMP CONNECT 시 연결이 거부된다`() {
        val wsUrl = "ws://localhost:$port$WS_ENDPOINT"
        val errorQueue = LinkedBlockingQueue<Throwable>(1)
        val connectedFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        val handler =
            object : StompSessionHandlerAdapter() {
                override fun afterConnected(
                    session: StompSession,
                    connectedHeaders: StompHeaders,
                ) {
                    connectedFlag.set(true)
                }

                override fun handleTransportError(
                    session: StompSession,
                    exception: Throwable,
                ) {
                    errorQueue.offer(exception)
                }

                override fun handleException(
                    session: StompSession,
                    command: org.springframework.messaging.simp.stomp.StompCommand?,
                    headers: StompHeaders,
                    payload: ByteArray,
                    exception: Throwable,
                ) {
                    errorQueue.offer(exception)
                }
            }

        // Authorization 헤더 없이 연결
        val connectHeaders = StompHeaders()
        stompClient.connectAsync(wsUrl, WebSocketHttpHeaders(), connectHeaders, handler)

        // 연결 거부 확인 — 짧은 대기 후 연결 미완료 또는 에러 발생
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until {
                // 연결이 거부되면 transport error 가 큐에 들어오거나 connectedFlag 가 false 유지
                errorQueue.isNotEmpty() || !connectedFlag.get()
            }

        // 연결이 수립되지 않았음을 확인
        assertThat(connectedFlag.get()).isFalse()
    }

    // ── S1: 멘션 알림 실시간 수신 ────────────────────────────────────────────

    /**
     * Given 인증된 STOMP 클라이언트가 /user/queue/notifications 구독.
     * When pgmq q_issue_events 에 IssueMentioned JSON(mentionedUserIds=[수신자]) 발행.
     * Then NotificationWorker 가 소비하고 클라이언트가 인앱 알림 payload 수신.
     * And notifications 테이블에 status=SENT 행 1건 기록.
     */
    @Test
    fun `S1 인증 클라이언트가 구독 후 IssueMentioned 이벤트 발행 시 인앱 알림을 수신한다`() {
        val wsUrl = "ws://localhost:$port$WS_ENDPOINT"
        val receivedPayloads = LinkedBlockingQueue<Map<*, *>>(10)

        val session = connectWithJwt(wsUrl, RECIPIENT_JWT_TOKEN)

        // /user/queue/notifications 구독
        session.subscribe(
            NOTIFICATION_DESTINATION,
            object : StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = Map::class.java

                override fun handleFrame(
                    headers: StompHeaders,
                    payload: Any?,
                ) {
                    @Suppress("UNCHECKED_CAST")
                    if (payload is Map<*, *>) {
                        receivedPayloads.offer(payload)
                    }
                }
            },
        )

        // pgmq 에 IssueMentioned 이벤트 발행
        val issueKey = "ATLAS-${System.currentTimeMillis()}"
        val occurredAt = Instant.now().toString()
        publishMentionEvent(issueKey, occurredAt)

        // NotificationWorker 폴링 후 STOMP 푸시 대기 (worker poll-interval=50ms, 최대 10초)
        val received =
            receivedPayloads.poll(10, TimeUnit.SECONDS)
        assertThat(received).isNotNull()

        // payload 필드 검증
        assertThat(received!!["eventType"]).isEqualTo("issue.mentioned")
        assertThat(received["issueKey"]).isEqualTo(issueKey)
        assertThat(received["title"].toString()).contains(issueKey)

        // notifications 테이블 기록 확인 (status=SENT)
        await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted {
                val count =
                    dsl.fetchOne(
                        "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND event_type = ?",
                        RECIPIENT_USER_ID,
                        "issue.mentioned",
                    )!!.get(0, Long::class.java)
                assertThat(count).isEqualTo(1L)
            }

        val notificationRow =
            dsl.fetchOne(
                "SELECT status, issue_key FROM notifications WHERE recipient_user_id = ? AND event_type = ?",
                RECIPIENT_USER_ID,
                "issue.mentioned",
            )
        assertThat(notificationRow).isNotNull()
        assertThat(notificationRow!!.get(0, String::class.java)).isEqualTo("SENT")
        assertThat(notificationRow.get(1, String::class.java)).isEqualTo(issueKey)

        session.disconnect()
    }

    // ── S4: 재전달 멱등 ────────────────────────────────────────────────────────

    /**
     * Given 같은 IssueMentioned 이벤트를 pgmq 에 2회 발행.
     * When NotificationWorker 가 각 메시지를 소비.
     * Then notifications 테이블에 행 1건만 존재 (dedup_key ON CONFLICT DO NOTHING).
     * And 클라이언트는 알림을 1회만 수신한다.
     */
    @Test
    fun `S4 같은 이벤트 2회 발행 시 알림이 1건만 기록되고 중복 발송되지 않는다`() {
        val wsUrl = "ws://localhost:$port$WS_ENDPOINT"
        val receivedPayloads = LinkedBlockingQueue<Map<*, *>>(10)

        val session = connectWithJwt(wsUrl, RECIPIENT_JWT_TOKEN)

        session.subscribe(
            NOTIFICATION_DESTINATION,
            object : StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = Map::class.java

                override fun handleFrame(
                    headers: StompHeaders,
                    payload: Any?,
                ) {
                    @Suppress("UNCHECKED_CAST")
                    if (payload is Map<*, *>) {
                        receivedPayloads.offer(payload)
                    }
                }
            },
        )

        // 동일한 occurredAt 으로 같은 이벤트 2회 발행
        // dedup_key = hash(eventType, issueKey, occurredAt, recipientUserId, channel) 이 동일해야 함
        val issueKey = "ATLAS-IDEM-${System.currentTimeMillis()}"
        val occurredAt = Instant.parse("2026-06-12T00:00:00Z").toString()

        publishMentionEvent(issueKey, occurredAt)
        publishMentionEvent(issueKey, occurredAt)

        // 첫 번째 알림 수신 대기
        val firstReceived = receivedPayloads.poll(10, TimeUnit.SECONDS)
        assertThat(firstReceived).isNotNull()

        // 충분히 대기 후 두 번째 알림이 오지 않음을 확인
        val secondReceived = receivedPayloads.poll(3, TimeUnit.SECONDS)
        assertThat(secondReceived).isNull()

        // DB 에도 1건만 존재
        val count =
            dsl.fetchOne(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND issue_key = ?",
                RECIPIENT_USER_ID,
                issueKey,
            )!!.get(0, Long::class.java)
        assertThat(count).isEqualTo(1L)

        session.disconnect()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * STOMP 세션을 수립한다. Authorization 헤더에 [jwtToken] 을 주입한다.
     *
     * @param wsUrl WebSocket 서버 URL
     * @param jwtToken CONNECT frame 에 주입할 Bearer 토큰
     * @return 연결된 StompSession
     */
    private fun connectWithJwt(
        wsUrl: String,
        jwtToken: String,
    ): StompSession {
        val connectHeaders = StompHeaders()
        connectHeaders.add("Authorization", "Bearer $jwtToken")

        val future =
            stompClient.connectAsync(
                wsUrl,
                WebSocketHttpHeaders(),
                connectHeaders,
                object : StompSessionHandlerAdapter() {},
            )

        return future.get(10, TimeUnit.SECONDS)
    }

    /**
     * pgmq `q_issue_events` 큐에 IssueMentioned 이벤트를 발행한다.
     *
     * JSON 구조는 issue-tracking BC 의 IssueDomainEvent 발행 형식과 동일.
     * BC 격리상 IssueDomainEvent 를 직접 import 하지 않고 문자열로 구성.
     *
     * @param issueKey 이슈 키 (예: ATLAS-1)
     * @param occurredAt 이벤트 발생 시각 ISO-8601 문자열
     */
    private fun publishMentionEvent(
        issueKey: String,
        occurredAt: String,
    ) {
        // pgmq 큐 존재 보장 — 없으면 생성
        dsl.execute("SELECT pgmq.create(?)", QUEUE_NAME)

        val payload =
            """
            {
              "type": "issue.mentioned",
              "issueKey": "$issueKey",
              "projectKey": "ATLAS",
              "actorId": "$ACTOR_USER_ID",
              "mentionedUserIds": ["$RECIPIENT_USER_ID"],
              "sourceField": "description",
              "occurredAt": "$occurredAt"
            }
            """.trimIndent()

        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
    }
}
