// 칸반 보드 애그리게이트 루트 — 프로젝트별 칸반 보드 엔티티

package com.bts.agileplanning.domain

import java.time.Instant
import java.util.UUID

/**
 * 칸반 보드 애그리게이트 루트.
 *
 * 프로젝트 이슈를 컬럼별로 시각화하는 작업 현황판.
 * 한 프로젝트에 여러 보드를 가질 수 있다.
 *
 * ### BC 격리
 * [projectKey] 는 문자열로 보관한다. issue-tracking · project-workflow BC 를 직접 import 하지 않는다
 * (notification BC 의 project_key 문자열 BC 격리 선례와 동일 패턴).
 *
 * ### 불변 계약
 * - [id] 는 생성 후 변경 불가.
 * - [projectKey] 는 비어 있으면 안 된다.
 * - [name] 은 비어 있거나 공백만 있으면 안 된다.
 * - [swimlaneField] 기본값은 [SwimlaneField.NONE]. 미지정 시 스윔레인을 사용하지 않는다.
 *
 * @property id 보드 UUID (PK).
 * @property projectKey 소속 프로젝트 키. BC 격리 목적으로 FK 없이 문자열로 보관. 예: `"ATLAS"`.
 * @property name 보드 표시 이름. 예: `"BTS 개발 보드"`.
 * @property columns 보드에 속한 컬럼 목록. 빈 리스트는 워크플로우 스킴 미할당 보드.
 * @property createdAt 보드 생성 시각.
 * @property updatedAt 보드 최종 수정 시각.
 * @property deletedAt soft-delete 시각. null 이면 활성 보드.
 * @property swimlaneField 스윔레인 기준 필드. 기본값 [SwimlaneField.NONE] 은 스윔레인 비활성.
 */
data class Board(
    val id: UUID,
    val projectKey: String,
    val name: String,
    val columns: List<BoardColumn>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val swimlaneField: SwimlaneField = SwimlaneField.NONE,
) {
    init {
        require(projectKey.isNotBlank()) { "Board.projectKey must not be blank." }
        require(name.isNotBlank()) { "Board.name must not be blank." }
    }
}
