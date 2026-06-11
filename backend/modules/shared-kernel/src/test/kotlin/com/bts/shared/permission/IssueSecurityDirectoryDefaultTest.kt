// IssueSecurityDirectory.findLevelNames default 메서드 fail-safe 단위 테스트

package com.bts.shared.permission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [IssueSecurityDirectory.findLevelNames] default 구현 단위 테스트.
 *
 * 기존 levelBelongsToProjectScheme / accessibleLevels 만 구현한 anonymous fake 로
 * 새 default 메서드를 호출해 빈 맵(fail-safe) 반환을 검증한다.
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class IssueSecurityDirectoryDefaultTest {
    @Test
    fun `findLevelNames default 는 빈 맵을 반환한다`() {
        val directory = object : IssueSecurityDirectory {
            override fun levelBelongsToProjectScheme(levelId: UUID, projectKey: String) = false
            override fun accessibleLevels(actorId: UUID, projectKey: String) =
                IssueSecurityAccess(
                    unrestricted = true,
                    staticLevelIds = emptySet(),
                    reporterLevelIds = emptySet(),
                    assigneeLevelIds = emptySet(),
                )
        }
        assertThat(directory.findLevelNames(setOf(UUID.randomUUID()))).isEmpty()
    }
}
