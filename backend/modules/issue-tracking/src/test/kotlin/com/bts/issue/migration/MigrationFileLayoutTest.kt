// DB 마이그레이션 파일이 BC 폴더 하위에 있어야 함을 검증하는 회귀 가드

package com.bts.issue.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * db/migration/ 루트에 V*.sql 파일이 잔존하지 않는지 검증하는 회귀 가드 테스트.
 *
 * 배경.
 * ADR 2026-05-26-bc-migration-prefix-policy 에 따라 모든 Flyway 마이그레이션 파일은
 * BC 하위 폴더 (예: db/migration/issue-tracking/) 에 위치해야 한다.
 * db/migration/ 루트에 V*.sql 가 남아 있으면 BC 폴더 정책 위반이다.
 *
 * 검증 방식.
 * Spring 의 PathMatchingResourcePatternResolver 를 사용해 classpath 상에서
 * db/migration/V*.sql 패턴을 직접 검색한다.
 * build output 의 실제 resources 디렉토리를 스캔하므로 런타임 classpath 기준으로 검증된다.
 *
 * 참조. ADR 2026-05-26-bc-migration-prefix-policy.md / DATA.md §4.1.
 */
class MigrationFileLayoutTest {

    @Test
    fun `db_migration 루트에 V SQL 파일이 잔존하지 않아야 한다 (BC prefix 정책)`() {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val resources = resolver.getResources("classpath:db/migration/V*.sql")
        assertEquals(
            0,
            resources.size,
            "db/migration/ 루트에 V*.sql 잔존. ADR 2026-05-26-bc-migration-prefix-policy.md 위반. " +
                "BC 폴더 (db/migration/issue-tracking/, db/migration/project-workflow/) 하위로 이동 필요. " +
                "잔존 파일: ${resources.map { it.filename }}",
        )
    }
}
