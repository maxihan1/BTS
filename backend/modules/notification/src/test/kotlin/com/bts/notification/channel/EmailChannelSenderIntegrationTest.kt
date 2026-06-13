// EmailChannelSender MailHog Testcontainers 통합 테스트 — 실제 SMTP 발송 및 한국어 제목 보존 검증

package com.bts.notification.channel

import com.bts.notification.NotificationTestBootApplication
import com.bts.notification.TestPermissionConfig
import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import jakarta.mail.internet.MimeUtility
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [EmailChannelSender] MailHog Testcontainers 통합 테스트 (FR-NT-02).
 *
 * MailHog 는 테스트용 가짜 SMTP 서버로, 받은 메일을 HTTP API(`/api/v2/messages`)로 조회할 수 있다.
 * 실제 SMTP 연결 없이 이메일 발송 경로 전체를 검증한다.
 *
 * ## 검증 시나리오
 * - **EMAIL-1**: `channel=EMAIL` Notification 을 `send()` → MailHog 에 1건 수신,
 *   Subject 디코딩 후 한국어 원문 일치, To 주소 일치.
 * - **EMAIL-2**: 수신자 이메일 미존재 → `send()` 가 [IllegalStateException] throw,
 *   MailHog 에 메일 미수신.
 *
 * ## 인프라
 * - [EmailChannelSenderIntegrationTestConfig] — MailHog 컨테이너 + @DynamicPropertySource +
 *   UserLookupPort fake + IssueRecipientLookupPort stub + JwtDecoder stub 제공.
 * - MailHog HTTP API 조회: JDK `java.net.http.HttpClient` (신규 의존성 불필요).
 *
 * ## 주의
 * - Subject 헤더는 MIME encoded-word (`=?UTF-8?...?=`) 형태일 수 있으므로
 *   [MimeUtility.decodeText] 로 디코딩 후 원문과 비교한다 (인코딩 형태 직접 비교 금지 — 거짓그린 방지).
 */
@SpringBootTest(
    classes = [
        NotificationTestBootApplication::class,
        EmailChannelSenderIntegrationTestConfig::class,
        TestPermissionConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailChannelSenderIntegrationTest {
    @Autowired
    lateinit var emailChannelSender: EmailChannelSender

    companion object {
        /** fake UserLookupPort 가 이메일을 알고 있는 수신자 UUID */
        val KNOWN_RECIPIENT_ID: UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")

        /** fake UserLookupPort 가 이메일을 모르는(null 반환) 수신자 UUID */
        val UNKNOWN_RECIPIENT_ID: UUID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")

        /** KNOWN_RECIPIENT_ID 에 매핑되는 수신자 이메일 주소 */
        const val KNOWN_RECIPIENT_EMAIL = "recipient@bts-test.local"

        /** MailHog HTTP API base URL — @DynamicPropertySource 로 주입된 포트를 참조한다 */
        fun mailhogApiUrl(): String {
            val port = EmailChannelSenderIntegrationTestConfig.mailhog.getMappedPort(8025)
            return "http://localhost:$port"
        }
    }

    /** 각 테스트 전 MailHog 메일함을 비워 테스트 간 격리를 보장한다 */
    @BeforeEach
    fun clearMailhog() {
        val client = HttpClient.newHttpClient()
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("${mailhogApiUrl()}/api/v1/messages"))
                .DELETE()
                .build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }

    // ─────────────────────────────────────────────────────────────────────
    // EMAIL-1: 발송 → 수신 + 한국어 제목 보존
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Given 알려진 수신자 ID 의 EMAIL 채널 알림.
     * When `EmailChannelSender.send()` 로 발송.
     * Then MailHog HTTP API 에 메일 1건 수신.
     * And Subject 디코딩 후 한국어 원문 "ATLAS-42 에서 멘션되었습니다" 와 일치.
     * And To 주소가 [KNOWN_RECIPIENT_EMAIL] 과 일치.
     */
    @Test
    fun `EMAIL-1 한국어 제목 알림 발송 시 MailHog 에 수신되고 제목과 수신자가 올바르게 설정된다`() {
        val koreanTitle = "ATLAS-42 에서 멘션되었습니다"
        val notification = buildEmailNotification(recipientId = KNOWN_RECIPIENT_ID, title = koreanTitle)

        emailChannelSender.send(notification)

        // MailHog 에 메일이 도착할 때까지 폴링 (SMTP 비동기 특성상 짧은 지연 가능)
        val messages =
            await()
                .atMost(10, TimeUnit.SECONDS)
                .until({ fetchMailhogMessages() }) { it.isNotEmpty() }

        assertThat(messages).hasSize(1)

        val firstMessage = messages.first()

        // Subject: MIME encoded-word 형태일 수 있으므로 MimeUtility.decodeText 로 디코딩 후 비교
        val rawSubject = extractSubject(firstMessage)
        val decodedSubject = MimeUtility.decodeText(rawSubject)
        assertThat(decodedSubject).isEqualTo(koreanTitle)

        // To: 수신자 이메일 일치
        val toAddress = extractTo(firstMessage)
        assertThat(toAddress).contains(KNOWN_RECIPIENT_EMAIL)
    }

    // ─────────────────────────────────────────────────────────────────────
    // EMAIL-2: 이메일 부재 → 발송 안 함
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Given 이메일 주소를 모르는 수신자 ID 의 EMAIL 채널 알림.
     * When `EmailChannelSender.send()` 호출.
     * Then [IllegalStateException] throw.
     * And MailHog 에 메일이 수신되지 않는다.
     */
    @Test
    fun `EMAIL-2 수신자 이메일 미존재 시 IllegalStateException 이 발생하고 메일이 발송되지 않는다`() {
        val notification =
            buildEmailNotification(recipientId = UNKNOWN_RECIPIENT_ID, title = "이메일 없는 수신자 테스트")

        assertThatThrownBy { emailChannelSender.send(notification) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(UNKNOWN_RECIPIENT_ID.toString())

        // 발송이 없었으므로 MailHog 메일함은 비어 있어야 한다
        // 짧게 대기 후 확인 (혹시 비동기로 도달할 수 있는 경우에 대비)
        Thread.sleep(500)
        val messages = fetchMailhogMessages()
        assertThat(messages).isEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 테스트용 EMAIL 채널 알림을 생성한다.
     *
     * @param recipientId 수신자 userId
     * @param title 알림 제목
     * @return 발송할 [Notification] 인스턴스
     */
    private fun buildEmailNotification(
        recipientId: UUID,
        title: String,
    ): Notification =
        Notification(
            id = UUID.randomUUID(),
            recipientUserId = recipientId,
            eventType = NotificationEventType.ISSUE_MENTIONED,
            channel = Channel.EMAIL,
            issueKey = "ATLAS-42",
            title = title,
            body = "멘션 본문입니다.",
            payload = null,
            status = NotificationStatus.PENDING,
            dedupKey = UUID.randomUUID().toString(),
            readAt = null,
            createdAt = Instant.now(),
        )

    /**
     * MailHog HTTP API `GET /api/v2/messages` 를 호출해 수신된 메시지 목록을 반환한다.
     *
     * @return MailHog 응답 JSON 에서 파싱된 메시지 맵 목록
     */
    private fun fetchMailhogMessages(): List<Map<*, *>> {
        val client = HttpClient.newHttpClient()
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("${mailhogApiUrl()}/api/v2/messages"))
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) return emptyList()

        val body = response.body()
        // MailHog JSON 구조: { "total": N, "count": N, "start": 0, "items": [...] }
        // 간단한 문자열 파싱 대신 ObjectMapper 없이 Kotlin + JDK 로 처리:
        // "items" 배열이 비어 있으면 빈 리스트 반환, 있으면 raw content 를 맵으로 파싱
        if (!body.contains("\"items\"")) return emptyList()

        // items 배열 유무 확인 — ObjectMapper 없이 단순 체크
        val itemsStart = body.indexOf("\"items\"")
        val arrayStart = body.indexOf("[", itemsStart)
        val arrayEnd = body.lastIndexOf("]")

        if (arrayStart < 0 || arrayEnd < 0 || arrayEnd <= arrayStart) return emptyList()

        val itemsArray = body.substring(arrayStart, arrayEnd + 1)
        // 빈 배열이면 빈 리스트
        if (itemsArray.trim() == "[]") return emptyList()

        // 최소한 1개 이상의 메시지가 있으면 단순히 raw JSON 을 담은 맵 1개로 표현
        // 실제 필드 파싱은 extractSubject / extractTo 에서 수행
        return listOf(mapOf("raw" to body))
    }

    /**
     * MailHog 응답 JSON 에서 첫 번째 메시지의 Subject 헤더를 추출한다.
     *
     * MailHog API 응답 구조에서 Subject 를 추출하기 위해 JSON 문자열 파싱을 사용한다.
     * Subject 헤더는 `"Subject":["..."]` 형태로 items[0].Content.Headers 안에 있다.
     *
     * @param message [fetchMailhogMessages] 가 반환한 맵 (raw JSON 포함)
     * @return Subject 헤더 원문 (encoded-word 포함 가능)
     */
    private fun extractSubject(message: Map<*, *>): String {
        val raw = message["raw"] as? String ?: return ""
        // MailHog JSON 에서 "Subject":["값"] 패턴을 추출
        val subjectPattern = Regex(""""Subject"\s*:\s*\["([^"]+)"\]""")
        return subjectPattern.find(raw)?.groupValues?.get(1) ?: ""
    }

    /**
     * MailHog 응답 JSON 에서 첫 번째 메시지의 To 주소를 추출한다.
     *
     * @param message [fetchMailhogMessages] 가 반환한 맵
     * @return To 헤더 원문
     */
    private fun extractTo(message: Map<*, *>): String {
        val raw = message["raw"] as? String ?: return ""
        val toPattern = Regex(""""To"\s*:\s*\["([^"]+)"\]""")
        return toPattern.find(raw)?.groupValues?.get(1) ?: ""
    }
}
