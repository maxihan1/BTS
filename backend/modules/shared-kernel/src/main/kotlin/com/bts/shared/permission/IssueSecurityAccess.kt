// 이슈 보안 등급 접근 결과 — actor 가 접근 가능한 등급 집합을 담는 공용 VO.

package com.bts.shared.permission

import java.util.UUID

/**
 * actor 가 접근 가능한 이슈 보안 등급 집합.
 *
 * issue-tracking BC 의 목록 쿼리에서 WHERE 조건 푸시다운에 사용된다 (T10 소비).
 * [IssueSecurityDirectory.accessibleLevels] 의 반환 타입.
 *
 * ## unrestricted 빠른 경로
 * [unrestricted] 가 `true` 이면 프로젝트에 적용된 보안 스킴이 없거나
 * 개발/스테이징 환경 stub 이 활성화된 것이므로 WHERE 절 필터를 적용하지 않는다.
 * `true` 이면 나머지 필드는 무의미하며 빈 집합이 반환된다.
 *
 * ## 멤버 타입별 등급 집합
 * - [staticLevelIds]: USER / GROUP / PROJECT_ROLE 멤버 조건으로 actor 가 포함된 등급 ID 집합.
 *   항상 적용된다.
 * - [reporterLevelIds]: REPORTER 멤버 조건을 가진 등급 ID 집합.
 *   actor 가 이슈 reporter 일 때만 해당 이슈에 접근 가능하다.
 * - [assigneeLevelIds]: ASSIGNEE 멤버 조건을 가진 등급 ID 집합.
 *   actor 가 이슈 assignee 일 때만 해당 이슈에 접근 가능하다.
 *
 * ## BC 경계 규칙
 * 이 클래스는 shared-kernel 에 위치하므로 UUID / String / Boolean / Set<UUID> 같은
 * 원시·표준 타입만 사용한다. identity-access / issue-tracking 타입을 import 하면
 * [com.bts.shared.architecture.SharedKernelBoundaryArchTest] 가 빌드를 차단한다.
 *
 * @property unrestricted true 이면 필터 미적용 빠른 경로. 나머지 필드는 빈 집합.
 * @property staticLevelIds USER/GROUP/PROJECT_ROLE 기준 접근 가능 등급 ID 집합.
 * @property reporterLevelIds REPORTER 조건 등급 ID 집합 (reporter 일 때만 적용).
 * @property assigneeLevelIds ASSIGNEE 조건 등급 ID 집합 (assignee 일 때만 적용).
 */
data class IssueSecurityAccess(
    val unrestricted: Boolean,
    val staticLevelIds: Set<UUID>,
    val reporterLevelIds: Set<UUID>,
    val assigneeLevelIds: Set<UUID>,
)
