// cross-BC 벨로시티 조회 포트 계약 검증 — SprintVelocityLookupPort·VelocityContribution

package com.bts.shared.velocity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * cross-BC 벨로시티 원천 데이터 조회 포트 계약 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [SprintVelocityLookupPort.fetchVelocitySource] default 구현이 빈 map 을 반환함(fail-safe).
 * - adapter 가 미등록된 환경에서도 예외 없이 안전하게 계산이 진행된다.
 * - [VelocityContribution] VO 필드 보존 및 equality.
 */
class SprintVelocityLookupPortDefaultTest {
    @Test
    fun `default fetchVelocitySource returns empty map fail-safe`() {
        val port = object : SprintVelocityLookupPort {}
        val issueKeysBySprint = mapOf(UUID.randomUUID() to setOf("PROJ-1", "PROJ-2"))

        val result = port.fetchVelocitySource(issueKeysBySprint, "PROJ", UUID.randomUUID())

        assertThat(result).isEmpty()
    }

    @Test
    fun `VelocityContribution 은 계획-완료 초 단위 값을 보존하고 동등성을 갖는다`() {
        val a = VelocityContribution(commitmentSeconds = 3600L, completedSeconds = 1800L)
        val b = VelocityContribution(commitmentSeconds = 3600L, completedSeconds = 1800L)

        assertThat(a).isEqualTo(b)
        assertThat(a.commitmentSeconds).isEqualTo(3600L)
        assertThat(a.completedSeconds).isEqualTo(1800L)
    }
}
