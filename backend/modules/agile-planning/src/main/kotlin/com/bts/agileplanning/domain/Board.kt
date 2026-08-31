// 칸반 보드 애그리게이트 루트 — 프로젝트별 칸반 보드 엔티티

package com.bts.agileplanning.domain

import java.time.Instant
import java.util.UUID

/**
 * 보드 이름 불변 계약 위반 예외.
 *
 * [Board] 의 init 블록이 공백 이름을 만났을 때 던진다. 검증 소유권은 도메인에 남기되 예외에 이름을
 * 붙여, HTTP 매핑이 [IllegalArgumentException] 계층 **전체**를 400 으로 삼키지 않게 한다. 맨
 * [IllegalArgumentException] 을 400 으로 매핑하면 호출 사슬 어디에서 터지든(예: [NumberFormatException])
 * 내부 버그가 400 으로 나가 5xx 경보에서 사라진다.
 *
 * [IllegalArgumentException] 을 상속해 `require` 계열과 같은 의미 범주를 유지한다.
 */
class BoardNameInvalidException : IllegalArgumentException("Board.name must not be blank.")

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
 * - [name] 은 비어 있거나 공백만 있으면 안 된다 — 위반 시 [BoardNameInvalidException].
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
        // 이름 불변식만 이름 있는 예외로 던진다 — HTTP 400 매핑을 이 한 조건으로 좁히기 위해서다.
        if (name.isBlank()) throw BoardNameInvalidException()
    }
}
