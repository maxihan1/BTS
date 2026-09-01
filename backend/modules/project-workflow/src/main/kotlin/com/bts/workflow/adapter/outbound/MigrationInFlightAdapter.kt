// MigrationInFlightPort 구현 — bulk_operations 단일 테이블 EXISTS 판정 (읽기 전용)

package com.bts.workflow.adapter.outbound

import com.bts.workflow.application.port.MigrationInFlightPort
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `bulk_operations` 에서 끝나지 않은 STATUS_MIGRATION 을 찾는다.
 *
 * issue-tracking 의 테이블이지만 **읽기 전용 EXISTS 판정**만 한다 — `IssueStatusUsageAdapter` 와
 * 같은 동적 참조 패턴이라 BC 격리를 깨지 않는다.
 *
 * ### 왜 `DSL.table()` 조립이 아니라 raw SQL 인가
 * 범위가 `payload` JSONB 배열 안에 있어 `jsonb_array_elements_text` 를 거쳐야 한다. 형제 어댑터가
 * 쓰는 동적 필드 참조로는 그 함수 호출과 그 결과에 건 조건을 표현할 수 없다. 테이블·컬럼 이름은
 * 여전히 문자열이므로 격리 성격은 같고, 바인딩은 파라미터로만 넘겨 SQL 주입 표면을 만들지 않는다.
 *
 * ### 왜 배열 파라미터 하나인가
 * 키 개수만큼 `IN` 항목을 붙이면 프로젝트 수마다 다른 SQL 이 되어 실행 계획 캐시가 흩어진다.
 * `= ANY(?)` 는 개수와 무관하게 같은 문장이다.
 */
@Component
class MigrationInFlightAdapter(
    private val dsl: DSLContext,
) : MigrationInFlightPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 빈 범위는 조기에 false 다.
     *
     * 빈 배열을 `ANY` 에 흘리면 항상 거짓이라 결과는 같지만, 「스코프가 비었다」를 DB 왕복으로
     * 확인할 이유가 없다. 형제 어댑터(`IssueStatusUsageAdapter`)가 같은 자리에서 같은 처방을 쓴다.
     */
    @Transactional(readOnly = true)
    override fun hasInFlightMigration(projectKeys: Set<String>): Boolean {
        if (projectKeys.isEmpty()) {
            log.debug("hasInFlightMigration 스코프가 비어 false 를 돌려준다")
            return false
        }
        val found =
            dsl.fetchOne(
                "SELECT EXISTS (" +
                    " SELECT 1 FROM bulk_operations bo" +
                    " WHERE bo.operation_type = 'STATUS_MIGRATION'" +
                    "   AND bo.status IN ('PENDING', 'RUNNING')" +
                    "   AND EXISTS (" +
                    "     SELECT 1 FROM jsonb_array_elements_text(bo.payload -> 'projectKeys') AS k(v)" +
                    "     WHERE k.v = ANY(?)" +
                    "   )" +
                    ") AS in_flight",
                projectKeys.toTypedArray(),
            )?.get("in_flight", Boolean::class.java) ?: false
        log.debug("hasInFlightMigration projectCount={} inFlight={}", projectKeys.size, found)
        return found
    }
}
