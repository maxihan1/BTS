// 인증 감사 로그 인메모리 구현체 — 단위테스트 헬퍼(프로덕션 빈은 JdbcAuthAuditLogService) (FR-09-31)

package com.atlas.bts.identity.audit

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * [AuthAuditLogService] 인메모리 구현체 (FR-09-31).
 *
 * thread-safe [ConcurrentLinkedDeque]를 사용하여 동시 record 호출에 안전하다.
 * 용량 상한([MAX_CAPACITY])을 초과하면 가장 오래된 항목을 제거한다.
 *
 * ## 역할 (FR-AU-10 이후)
 *
 * 프로덕션 빈은 DB 영속 [JdbcAuthAuditLogService] 로 전환되었다(`@Service`).
 * 이 클래스는 더 이상 `@Service` 가 아니며 **단위테스트 헬퍼**로 강등되었다 —
 * 테스트가 직접 `InMemoryAuthAuditLogService()` 로 생성해 mock 없이 record/findRecent 를 검증한다.
 * 동일 인터페이스 2 빈 충돌 방지(EC-6).
 *
 * **한계.** in-memory 라 프로세스 재시작 시 소실된다(테스트 전용이므로 무방).
 *
 * ## 보안 주의사항
 *
 * 감사 이벤트는 logback `audit.auth` 로거를 통해 기록된다.
 * PII 필드(ipAddress, userAgent, deviceFingerprint)는 logger에 전달하지 않는다.
 * (DEVELOPMENT.md §보안 규칙 8번 — 로그에 PII/비밀값 마스킹)
 */
class InMemoryAuthAuditLogService : AuthAuditLogService {

    private val log = LoggerFactory.getLogger("audit.auth")

    private val store = ConcurrentLinkedDeque<AuthAuditLog>()

    override fun record(event: AuthAuditLog) {
        store.addFirst(event)
        trimIfOverCapacity()
        // PII 미포함 필드만 로깅 — ipAddress/userAgent/deviceFingerprint 제외
        log.info(
            "eventType={} userId={} providerId={}",
            event.eventType,
            event.userId,
            event.providerId,
        )
    }

    override fun findRecent(userId: UUID, limit: Int): List<AuthAuditLog> {
        return store.asSequence()
            .filter { it.userId == userId }
            .take(limit)
            .toList()
    }

    private fun trimIfOverCapacity() {
        while (store.size > MAX_CAPACITY) {
            store.pollLast()
        }
    }

    companion object {
        /** 인메모리 최대 보관 건수. 초과 시 오래된 항목 제거. */
        const val MAX_CAPACITY = 10_000
    }
}
