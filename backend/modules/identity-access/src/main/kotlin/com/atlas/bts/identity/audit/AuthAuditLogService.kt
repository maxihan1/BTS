// 인증 감사 로그 서비스 인터페이스 (FR-09-31)

package com.atlas.bts.identity.audit

import java.util.UUID

/**
 * 인증 감사 로그 기록 및 조회 서비스 계약.
 *
 * 현재 구현체. [InMemoryAuthAuditLogService] (in-memory, 본 PR 범위).
 * 후속 구현체. DB persistence + 월 단위 파티션 (SDD 19.9 후속 PR).
 */
interface AuthAuditLogService {

    /**
     * 인증 이벤트를 감사 로그에 기록한다.
     *
     * @param event 기록할 감사 이벤트
     */
    fun record(event: AuthAuditLog)

    /**
     * 특정 사용자의 최근 감사 로그를 최신순으로 반환한다.
     *
     * @param userId 조회 대상 사용자 ID
     * @param limit 최대 반환 건수
     * @return 최신순 감사 로그 목록 (최대 [limit]개)
     */
    fun findRecent(userId: UUID, limit: Int): List<AuthAuditLog>
}
