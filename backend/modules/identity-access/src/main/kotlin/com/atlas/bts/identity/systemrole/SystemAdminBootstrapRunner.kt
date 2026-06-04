// 부트스트랩 시점 지정 사용자에게 SYSTEM_ADMIN을 멱등 승격하는 ApplicationRunner (FR-PM-08 Task 5)

package com.atlas.bts.identity.systemrole

import com.atlas.bts.identity.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 애플리케이션 기동 시 설정으로 지정한 사용자를 전역 [SystemRole.SYSTEM_ADMIN]으로 승격한다
 * (FR-PM-08 Task 5 — 전역 관리자 부트스트랩).
 *
 * ## 동작
 * `bts.bootstrap.admin-username` 설정값(`BTS_BOOTSTRAP_ADMIN_USERNAME` 환경변수)을 기준으로 한다.
 * 1. 설정값이 비어 있으면(기본값) 아무 동작도 하지 않는다 — 미설정 환경(테스트 컨텍스트 포함) 부팅 안전.
 * 2. 이미 [SystemRole.SYSTEM_ADMIN] 보유자가 한 명이라도 있으면 멱등 skip 한다.
 *    부트스트랩은 "최초 관리자가 없을 때만" 동작하므로 재기동마다 권한을 재부여하지 않는다.
 * 3. username 으로 사용자를 조회한다. 없으면 경고 로그만 남기고 종료한다(부팅 실패 아님).
 * 4. 사용자가 존재하면 [SystemRoleAssignmentRepository.assign] 으로 역할을 부여한다
 *    (assign 자체도 `ON CONFLICT DO NOTHING` 으로 멱등).
 *
 * ## 프로파일
 * 모든 프로파일에서 등록되는 일반 Spring 빈이다 (@Profile 한정 금지).
 * 기본값이 빈 문자열이라 설정하지 않은 환경에서는 (1)에서 즉시 종료하므로 부팅에 영향이 없다.
 *
 * ## 로깅
 * username 은 식별자라 로그에 남긴다. 토큰/비밀번호 등 민감정보는 본 러너가 다루지 않는다.
 *
 * @see docs/decisions/2026-06-04-system-admin-role.md 전역 관리자 역할 확정 ADR
 */
@Component
class SystemAdminBootstrapRunner(
    private val userRepository: UserRepository,
    private val systemRoleAssignmentRepository: SystemRoleAssignmentRepository,
    @Value("\${bts.bootstrap.admin-username:}") private val adminUsername: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(SystemAdminBootstrapRunner::class.java)

    override fun run(args: ApplicationArguments) {
        if (adminUsername.isBlank()) {
            return
        }
        if (systemRoleAssignmentRepository.existsByRole(SystemRole.SYSTEM_ADMIN)) {
            log.info("SYSTEM_ADMIN 보유자가 이미 존재하여 부트스트랩 승격을 건너뜁니다.")
            return
        }
        val user = userRepository.findByUsername(adminUsername)
        if (user == null) {
            log.warn(
                "부트스트랩 SYSTEM_ADMIN 대상 사용자를 찾을 수 없습니다 (username={}). 승격을 건너뜁니다.",
                adminUsername,
            )
            return
        }
        systemRoleAssignmentRepository.assign(user.id, SystemRole.SYSTEM_ADMIN)
        log.info("부트스트랩으로 사용자에게 SYSTEM_ADMIN을 부여했습니다 (username={}).", adminUsername)
    }
}
