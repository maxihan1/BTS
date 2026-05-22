// 이슈 권한 enum + 권한 적용 범위 sealed — ADR 2026-05-22-issue-permission-resolver-port

package com.bts.issue.port.outbound

/**
 * 이슈 도메인에서 검증하는 권한 목록.
 *
 * [IssuePermissionResolver] 가 이 enum 을 인자로 받아 권한 판정을 수행한다.
 *
 * | 권한 | 검증 엔드포인트 |
 * |------|----------------|
 * | [VIEW] | `GET /api/v1/issues/{key}`, `GET /api/v1/issues` |
 * | [CREATE] | `POST /api/v1/issues` |
 * | [UPDATE] | `PATCH /api/v1/issues/{key}` |
 * | [TRANSITION] | `POST /api/v1/issues/{key}/transition` |
 * | [SOFT_DELETE] | `DELETE /api/v1/issues/{key}` |
 * | [HARD_DELETE] | 본 PR scope 외 — DATA.md §3 하드 삭제 ADR 결정 후 별도 엔드포인트 도입 |
 */
enum class IssuePermission {
    /** 이슈 단건 조회 및 목록 조회 권한. */
    VIEW,

    /** 이슈 생성 권한. */
    CREATE,

    /** 이슈 필드(summary 등) 수정 권한. */
    UPDATE,

    /** 이슈 상태 전이 권한. WorkflowTransitionPort.plan() 호출 전 검증. */
    TRANSITION,

    /** 이슈 소프트 삭제 권한. deleted_at 설정, 키는 영구 보존(DATA.md §1.1). */
    SOFT_DELETE,

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
 * project-workflow BC 의 [com.bts.workflow.port.outbound.Scope] 와 동일한 패턴이나,
 * BC 격리 원칙에 따라 issue-tracking BC 가 독자적으로 정의한다.
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
