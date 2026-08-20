// 전역 상태 카탈로그 도메인 예외 — 원인이 다르면 사용자가 할 수 있는 행동도 다르다

package com.bts.workflow.status.domain.exception

import java.util.UUID

/** 살아 있는 상태를 못 찾았을 때. → 404 */
class StatusNotFoundException(
    val statusId: UUID,
) : RuntimeException("Status not found: '$statusId'")

/**
 * 살아 있는 상태가 이미 그 `key` 를 쓰고 있을 때. → 409
 *
 * 소프트 삭제된 상태의 key 는 대상이 아니다 — `V206` 이 부분 유니크로 바꿔 재사용을 열었다.
 */
class StatusKeyConflictException(
    val statusKey: String,
) : RuntimeException("Status key already in use: '$statusKey'")

/**
 * 이름이 대소문자 무시 기준으로 겹칠 때. → 409
 *
 * `uq_statuses_lower_name` 이 강제한다. 같은 이름의 상태가 둘이면 사용자가 전환을 걸 때
 * 어느 쪽인지 구분할 수 없다(ADR D1 · Jira Cloud 동일).
 */
class StatusNameConflictException(
    val statusName: String,
) : RuntimeException("Status name already in use (case-insensitive): '$statusName'")

/**
 * 어느 워크플로우가 쓰고 있는 상태를 지우려 할 때. → 409
 *
 * 사용자가 할 수 있는 행동은 「그 워크플로우에서 먼저 뺀다」이다.
 */
class StatusInUseException(
    val statusKey: String,
    val workflowCount: Int,
) : RuntimeException("Status '$statusKey' is used by $workflowCount workflow(s)")

/**
 * 시스템 예약 상태를 지우려 할 때. → 409
 *
 * [StatusInUseException] 과 나눈 이유 — 그쪽은 「떼면 지울 수 있다」이고 이쪽은 **할 수 있는 일이 없다**.
 * 문구를 같게 두면 사용자가 워크플로우를 뒤지며 시간을 버린다.
 */
class StatusProtectedException(
    val statusKey: String,
) : RuntimeException("Status '$statusKey' is system-reserved and cannot be deleted")
