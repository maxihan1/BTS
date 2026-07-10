// 조립 부팅 갭 진단용 테스트 전용 스텁 — 운영 미배선 포트를 mock 으로 채워 '남은 갭' 을 열거한다 (운영 코드 아님, 절대 배포 안 됨)

package com.bts.app

import com.bts.workflow.port.outbound.PermissionResolver
import org.mockito.Mockito
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * ⚠️ 진단 전용. prod 조립 컨텍스트에서 아직 운영 구현이 없는 cross-BC 포트를 mock 으로 채운다.
 * 여기 등록되는 각 빈 = "배포 전에 운영 구현을 만들어야 하는 갭" 목록. 이 클래스는 test 소스라 배포 artifact 에 없다.
 */
@TestConfiguration
class AssemblyGapStubConfig {
    // 갭 1: com.bts.workflow.port.outbound.PermissionResolver — 운영 어댑터(IdentityAccessPermissionResolver) 미구현 (FR-WF-03)
    @Bean
    fun stubWorkflowPermissionResolver(): PermissionResolver = Mockito.mock(PermissionResolver::class.java)
}
