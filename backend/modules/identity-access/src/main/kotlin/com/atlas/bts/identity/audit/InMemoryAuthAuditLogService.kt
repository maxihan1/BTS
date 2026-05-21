// 인증 감사 로그 인메모리 임시 구현체 — DB persistence 는 SDD 19.9 후속 PR (FR-09-31)

package com.atlas.bts.identity.audit

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * [AuthAuditLogService] 인메모리 구현체 (FR-09-31).
 *
 * thread-safe [ConcurrentLinkedDeque]를 사용하여 동시 record 호출에 안전하다.
 * 용량 상한([MAX_CAPACITY])을 초과하면 가장 오래된 항목을 제거한다.
 *
 * ## 한계 및 후속 PR 계획
 *
 * **현재 (이 PR):** in-memory + logback `audit.auth` 파일 기록만 지원.
 * 프로세스 재시작 시 인메모리 로그가 소실된다.
 *
 * **후속 PR (SDD 19.9):** `audit_logs` 테이블 + 월 단위 파티션 구현.
 * - Flyway 마이그레이션 `V00x__create_audit_logs.sql`
 * - `JdbcAuthAuditLogService` 구현체로 교체 (`@Primary`)
 * - 이 클래스는 테스트용 `@Bean`으로 유지하거나 제거
 *
 * ## 보안 주의사항
 *
 * 감사 이벤트는 logback `audit.auth` 로거를 통해 별도 파일에도 기록된다.
 * PII 필드(ipAddress, userAgent, deviceFingerprint)는 logger에 전달하지 않는다.
 * (DEVELOPMENT.md §보안 규칙 8번 — 로그에 PII/비밀값 마스킹)
 */
@Service
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
