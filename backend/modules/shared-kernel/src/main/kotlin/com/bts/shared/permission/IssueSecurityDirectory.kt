// 이슈 보안 등급 조회 outbound port(공용). actorId/projectKey 는 원시 타입만 사용해 BC 결합 제거.

package com.bts.shared.permission

import java.util.UUID

/**
 * 이슈 보안 등급(Issue Security Level) 조회 outbound port — 전 BC 공용.
 *
 * issue-tracking BC 가 이 interface 를 통해 보안 등급 정보를 요청하고,
 * identity-access BC 가 prod 구현체(`@Profile("prod")`)를 제공한다 (FR-PM-06 T9).
 * 개발/스테이징 환경에서는 `AlwaysAllowIssueSecurityDirectory` stub 이 활성화된다.
 *
 * ## 배선 (IssuePermissionResolver 동형 패턴)
 * 계약 타입(이 interface + [IssueSecurityAccess])을 shared-kernel 에 배치하여
 * issue-tracking 과 identity-access 모두 이 모듈만 의존한다.
 * identity-access 가 issue-tracking 내부를 역방향 의존하는 god-dependency 를 차단한다.
 *
 * ## 파라미터 타입 — UUID / String (BC 공통 분모)
 * 각 BC 는 자체 별칭을 사용할 수 있으나, 공용 포트 시그니처는 java.util.UUID 와
 * String 을 사용해 BC 간 타입 결합을 제거한다.
 *
 * ## prod 구현
 * T9 에서 identity-access BC 가 `IdentityAccessIssueSecurityDirectory`
 * (`@Component @Profile("prod")`)를 구현한다.
 * - `AlwaysAllowIssueSecurityDirectory` — `@Profile("!prod")` (개발/테스트/스테이징)
 * - `IdentityAccessIssueSecurityDirectory` — `@Profile("prod")` (운영, T9 구현)
 *
 * ## BC 경계 규칙
 * identity-access / issue-tracking 타입을 이 interface 에 사용하면
 * [com.bts.shared.architecture.SharedKernelBoundaryArchTest] 가 빌드를 차단한다.
 *
 * @see IssueSecurityAccess
 * @see IssuePermissionResolver
 */
interface IssueSecurityDirectory {
    /**
     * 주어진 보안 등급([levelId])이 특정 프로젝트([projectKey])의 적용 스킴에 속하는지 판정한다.
     *
     * 이슈 생성/수정 시 지정한 `security_level_id` 가 해당 프로젝트 스킴 소속인지 검증하는 데
     * 사용된다 (T6 소비 — 422 Unprocessable Entity 판단).
     *
     * @param levelId 검증할 보안 등급 UUID.
     * @param projectKey 등급이 속해야 하는 프로젝트 키. 예) "ATLAS".
     * @return 등급이 프로젝트 스킴 소속이면 `true`, 아니면 `false`.
     */
    fun levelBelongsToProjectScheme(
        levelId: UUID,
        projectKey: String,
    ): Boolean

    /**
     * actor 가 접근 가능한 보안 등급 집합을 반환한다.
     *
     * 이슈 목록 쿼리의 WHERE 조건 푸시다운에 사용된다 (T10 소비).
     * [IssueSecurityAccess.unrestricted] 가 `true` 이면 필터를 적용하지 않는다.
     *
     * @param actorId 접근 가능 등급을 조회할 행위자 UUID.
     * @param projectKey 대상 프로젝트 키. 예) "ATLAS".
     * @return [IssueSecurityAccess] — 접근 가능 등급 집합 및 unrestricted 플래그.
     */
    fun accessibleLevels(
        actorId: UUID,
        projectKey: String,
    ): IssueSecurityAccess

    /**
     * 주어진 보안 등급 UUID 집합을 등급 이름으로 역방향 일괄 조회한다.
     *
     * 이슈 변경 이력(audit trail) 기록 시점에 securityLevel 표시명을 박제(스냅샷)하기 위해 사용된다.
     * 미존재 id 는 결과 맵에서 제외된다.
     *
     * 기본 구현은 빈 맵을 반환한다(fail-safe).
     * 빈 맵 반환은 표시명 미박제를 의미하며, 보안 접근 판단에는 영향을 주지 않는다.
     * production 환경에서는 반드시 override 해야 한다.
     *
     * @param levelIds 이름을 조회할 보안 등급 UUID 집합
     * @return 실재하는 levelId 만 포함한 [UUID] to 등급명 맵 (순서 미보장)
     */
    fun findLevelNames(levelIds: Set<UUID>): Map<UUID, String> = emptyMap()
}
