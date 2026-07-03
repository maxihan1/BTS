// 퀵필터 도메인 — 보드별로 저장된 이름 붙은 필터 조합

package com.bts.agileplanning.domain

import java.util.UUID

/** 퀵필터 name 필드의 최대 글자 수. */
private const val MAX_NAME_LENGTH = 50

/**
 * 퀵필터 도메인.
 *
 * 보드 상단 칩으로 노출되는, 이름 붙은 필터 조합. 보드에 1:N 종속하며 그 보드를 보는
 * 모든 사용자가 공유한다(사용자별 소유 없음).
 *
 * ### 불변 계약
 * - [name] 은 비어 있거나 공백만 있으면 안 되며, [MAX_NAME_LENGTH]자를 초과할 수 없다.
 * - [query] 형식(파싱 유효성) 검증은 이 도메인의 책임이 아니다. 서비스 계층이
 *   `BoardFilterQueryParser` 로 검증한다.
 *
 * @property id 퀵필터 UUID (PK).
 * @property boardId 소속 보드 UUID.
 * @property name 칩에 표시되는 이름. 예: `"내 버그"`.
 * @property query `BoardCardFilter` 쿼리 파라미터 형식의 필터 조건 문자열.
 */
data class QuickFilter(
    val id: UUID,
    val boardId: UUID,
    val name: String,
    val query: String,
) {
    init {
        require(name.isNotBlank()) { "QuickFilter.name must not be blank." }
        require(name.length <= MAX_NAME_LENGTH) {
            "QuickFilter.name must be $MAX_NAME_LENGTH characters or fewer, but was ${name.length}."
        }
    }
}
