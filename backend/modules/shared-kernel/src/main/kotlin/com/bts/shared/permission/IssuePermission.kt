// 이슈 권한 enum + 권한 적용 범위 sealed — 전 BC 공용. issue-tracking이 묻고 identity-access가 판정.

package com.bts.shared.permission

/**
 * 이슈 도메인에서 검증하는 권한 목록.
 *
 * [IssuePermissionResolver] 가 이 enum 을 인자로 받아 권한 판정을 수행한다.
 *
 * | 권한 | 검증 엔드포인트 |
 * |------|----------------|
 * | [BROWSE] | `GET /api/v1/issues` (프로젝트 이슈 목록 조회) |
 * | [VIEW] | `GET /api/v1/issues/{key}` (이슈 단건 조회) |
 * | [CREATE] | `POST /api/v1/issues` |
 * | [UPDATE] | `PATCH /api/v1/issues/{key}` |
 * | [TRANSITION] | `POST /api/v1/issues/{key}/transition` |
 * | [SOFT_DELETE] | `DELETE /api/v1/issues/{key}` |
 * | [SOFT_DELETE] | `DELETE /api/v1/issues/{key}/comments/{commentId}` 의 모더레이터 판정 (FR-CO-02) |
 * | [SET_SECURITY] | `PATCH /api/v1/issues/{key}` 의 securityLevelId 변경 (FR-PM-06) |
 * | [HARD_DELETE] | 본 PR scope 외 — DATA.md §3 하드 삭제 ADR 결정 후 별도 엔드포인트 도입 |
 *
 * ## ★ [SOFT_DELETE] 는 이름보다 넓은 의미를 갖는다 (FR-CO-02 ADR §D2)
 * 이 값의 이름과 권한 매트릭스 코드(`DELETE_ISSUE`)는 **이슈** 삭제만 가리키지만, 실제 판정 범위에는
 * **이슈에 딸린 댓글의 모더레이션 삭제**가 포함된다. 표의 [SOFT_DELETE] 행이 둘인 이유다.
 *
 * 왜 새 enum 값을 만들지 않았나. 권한 값을 하나 늘리면 매트릭스 코드·시드 마이그레이션·resolver 분기·
 * 타 모듈의 값 개수 가드까지 함께 번진다(폭발 반경). 반면 "이슈를 지울 수 있는 사람"과 "그 이슈의 댓글을
 * 지울 수 있는 사람"을 실무에서 다르게 두어야 할 근거가 없어서, 값을 늘리는 대신 **의미 확장을 여기에
 * 명시**하는 쪽을 택했다. 조용한 확장은 다음 사람이 이 enum 을 이슈 전용으로 오해하게 만든다.
 */
enum class IssuePermission {
    /** 프로젝트 이슈 목록 조회 권한. `GET /api/v1/issues` (BROWSE_PROJECT 매트릭스). */
    BROWSE,

    /** 이슈 단건 조회 권한. `GET /api/v1/issues/{key}` (VIEW_ISSUE 매트릭스). */
    VIEW,

    /** 이슈 생성 권한. */
    CREATE,

    /** 이슈 필드(summary 등) 수정 권한. */
    UPDATE,

    /** 이슈 상태 전환 권한. WorkflowTransitionPort.plan() 호출 전 검증. */
    TRANSITION,

    /**
     * 이슈 소프트 삭제 권한. deleted_at 설정, 키는 영구 보존(DATA.md §1.1).
     *
     * **댓글 삭제의 모더레이터 판정에도 쓰인다 (FR-CO-02).** 작성자 본인이 아닌 사용자가 댓글을
     * 지울 수 있는지는 이 권한 보유 여부로 갈린다 — 클래스 KDoc "이름보다 넓은 의미" 참조.
     * 단 **수정**은 작성자 한정이라 이 권한으로 열리지 않는다. 두 술어를 섞지 말 것.
     */
    SOFT_DELETE,

    /**
     * 이슈 보안 수준(security level) 지정/변경 권한. `PATCH /api/v1/issues/{key}` 의
     * securityLevelId 변경 시 검증한다. SET_ISSUE_SECURITY 매트릭스 위임 (FR-PM-06).
     */
    SET_SECURITY,

    /**
     * 이슈 하드 삭제 권한.
     *
     * 본 PR scope 외. DATA.md §3 하드 삭제 ADR 결정 후 별도 관리자 엔드포인트로 도입한다.
     * 현재는 enum 값만 예약하여 향후 [IssuePermissionResolver] 구현체 변경을 최소화한다.
     */
    HARD_DELETE,
}

/**
 * 이슈 권한 평가의 적용 범위.
 *
 * project-workflow BC 의 Scope 와 동일한 패턴이나,
 * BC 격리 원칙에 따라 shared-kernel 이 독자적으로 정의한다.
 * 다른 BC 의 내부 타입을 직접 import 하지 않는다.
 *
 * when 식에서 else 분기 없이 컴파일러가 완전성(exhaustiveness)을 보장한다.
 *
 * ## 구현체 설명
 * - [Global] — 시스템 수준 권한. 예. 전체 이슈 archive 관리자 기능.
 * - [Project] — 프로젝트 단위 권한. 예. `ATLAS` 프로젝트 내 이슈 생성.
 * - [Issue] — 단일 이슈 권한. 예. `ATLAS-1` 이슈 수정/삭제.
 */
sealed interface IssueScope {
    /**
     * 시스템 전역 권한 범위.
     *
     * 프로젝트/이슈에 국한되지 않는 시스템 수준 작업(예. 전체 이슈 archive)에 사용한다.
     * data object 이므로 참조 동일성이 보장된다.
     */
    data object Global : IssueScope

    /**
     * 특정 프로젝트 권한 범위.
     *
     * @param key 프로젝트 식별 키. 예. "ATLAS".
     */
    data class Project(val key: String) : IssueScope

    /**
     * 특정 이슈 권한 범위.
     *
     * @param key 이슈 식별 키. 예. "ATLAS-1".
     */
    data class Issue(val key: String) : IssueScope
}
