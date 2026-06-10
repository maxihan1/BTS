// 버전의 생명주기 상태를 나타내는 enum — UNRELEASED / RELEASED / ARCHIVED
package com.bts.issue.version.domain

/**
 * 버전의 생명주기 상태.
 *
 * 허용 전이 그래프(나머지는 모두 [VersionTransitionNotAllowedException]).
 * ```
 * UNRELEASED ──release──▶ RELEASED      (releasedAt = now)
 * UNRELEASED ◀─unrelease─ RELEASED      (releasedAt = null)
 * UNRELEASED ──archive──▶ ARCHIVED      (releasedAt 유지 — null)
 * RELEASED   ──archive──▶ ARCHIVED      (releasedAt 유지 — 시각 보존)
 * ARCHIVED   ─unarchive─▶ UNRELEASED    (releasedAt = null)
 * ```
 *
 * self-transition(예: RELEASED → release) 및 그래프 외 전이(예: ARCHIVED → release)는 거부한다.
 */
enum class VersionStatus {
    /** 아직 릴리스되지 않은 상태. 기본값. */
    UNRELEASED,

    /** 릴리스된 상태. releasedAt 이 설정된다. */
    RELEASED,

    /** 보관된 상태. 읽기 전용 — unarchive 외 모든 mutation 거부. */
    ARCHIVED,
}
