// 칸반 보드 스윔레인 기준 필드 — 이슈 행 그룹화 방식을 결정하는 enum

package com.bts.agileplanning.domain

/**
 * 칸반 보드 스윔레인 기준 필드.
 *
 * 보드의 가로 행(스윔레인)을 어느 속성 기준으로 그룹화할지를 결정한다.
 * EPIC 기준 스윔레인은 FR-EP 이후로 이연되어 포함하지 않는다.
 *
 * ### ADR 2026-06-22
 * - [NONE] 기본값: 스윔레인 미사용. 단순 컬럼 보드로 동작한다.
 * - [ASSIGNEE] 담당자별 행 분리.
 * - [PRIORITY] 우선순위별 행 분리.
 *
 * @property NONE 스윔레인 비활성. 보드가 컬럼 단위로만 표시된다.
 * @property ASSIGNEE 이슈 담당자를 기준으로 스윔레인을 나눈다.
 * @property PRIORITY 이슈 우선순위를 기준으로 스윔레인을 나눈다.
 */
enum class SwimlaneField {
    /** 스윔레인 비활성. 보드가 컬럼 단위로만 표시된다. */
    NONE,

    /** 이슈 담당자를 기준으로 스윔레인을 나눈다. */
    ASSIGNEE,

    /** 이슈 우선순위를 기준으로 스윔레인을 나눈다. */
    PRIORITY,
}
