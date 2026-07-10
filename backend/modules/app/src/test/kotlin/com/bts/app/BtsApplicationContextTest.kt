// 조립 앱 컨텍스트 로드 검증 — 8개 BC 전체 빈이 하나의 컨텍스트로 결선·부팅되는지 확인 (dev postgres 5433 대상)

package com.bts.app

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * 전체 조립 컨텍스트가 로드되는지만 확인한다. dev 인프라(`docker compose -f infra/docker-compose.dev.yml up -d postgres`)가
 * 떠 있어야 하며, application.yml 의 기본값(localhost:5433)으로 접속한다.
 *
 * 이 테스트가 통과하면 빈 충돌·설정 누락·cross-BC 미배선·보안 체인 순서 문제가 없다는 뜻이다.
 */
@SpringBootTest
class BtsApplicationContextTest {
    @Test
    fun `조립 컨텍스트가 로드된다`() {
        // contextLoads — 컨텍스트 초기화 자체가 검증. 실패 시 예외로 표면화.
    }
}
