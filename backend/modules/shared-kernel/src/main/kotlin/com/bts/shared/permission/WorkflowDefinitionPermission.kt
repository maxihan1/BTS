// 워크플로우 정의 권한 enum(공용) — project-workflow 가 묻고, prod 판정은 identity-access.

package com.bts.shared.permission

/**
 * 워크플로우 **정의**(상태 편성·전환·초안) 편집에서 검증하는 권한 목록.
 *
 * [WorkflowDefinitionPermissionResolver] 가 이 enum 을 인자로 받아 권한 판정을 수행한다.
 *
 * READ 는 게이트하지 않는다. 워크플로우 정의는 이슈 화면이 상태 목록을 그리는 데 쓰이므로
 * 로그인 사용자면 읽을 수 있어야 한다(스킴 읽기 게이트 ADR 2026-07-26 과 같은 판단).
 *
 * | 권한 | 검증 엔드포인트 |
 * |------|----------------|
 * | [CREATE] | `POST /api/v1/workflows` · `POST /api/v1/workflows/{key}/duplicate` · `POST /api/v1/statuses` |
 * | [UPDATE] | `PUT /api/v1/workflows/{key}` · `PUT /api/v1/statuses/{id}` · 상태 편성 3종 · 캐시 무효화 |
 * | [DELETE] | `DELETE /api/v1/workflows/{key}` · `DELETE /api/v1/statuses/{id}` |
 * | [PUBLISH] | 초안 발행 (로드맵 PR 6 이 소비한다 — 지금은 선언만) |
 *
 * ### `WorkflowSchemePermission` 과 왜 다른 enum 인가
 * 그쪽은 **스킴**(이슈 타입별 워크플로우 매핑 묶음)의 관리·배정 권한이다. 이름이 스킴을 뜻하는데
 * 워크플로우 정의 편집을 담게 되면 다음 사람이 두 개념을 같은 것으로 읽는다.
 * 도메인당 enum 1개 + resolver 1개가 이 저장소의 관례다.
 *
 * @see WorkflowDefinitionPermissionResolver
 */
enum class WorkflowDefinitionPermission {
    /** 워크플로우·전역 상태 생성 권한. */
    CREATE,

    /** 워크플로우·전역 상태 수정 권한(이름·설명·카테고리·상태 편성·표시 순서). */
    UPDATE,

    /** 워크플로우·전역 상태 삭제 권한. 삭제는 소프트 삭제다. */
    DELETE,

    /** 초안 발행 권한. 로드맵 PR 6 이 소비한다. */
    PUBLISH,
}
