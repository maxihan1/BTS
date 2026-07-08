// user_keymap 테이블 접근 인터페이스 (FR-PF-03)

package com.atlas.bts.identity.keymap

import java.util.UUID

/**
 * user_keymap 테이블 접근 인터페이스 (FR-PF-03).
 *
 * 구현체: [JdbcUserKeymapRepository].
 *
 * user_keymap 은 사용자가 기본 단축키를 재지정한 override 만 저장한다 — [KeymapAction.DEFAULT_BINDINGS]
 * 는 이 테이블에 없다. 기본값과의 병합(effective 키맵 계산)은 이 리포지토리가 아니라 호출자
 * (`UserKeymapService`, Task 4)의 책임이다.
 */
interface UserKeymapRepository {
    /**
     * [userId] 의 override 목록을 조회한다.
     *
     * 저장된 override 가 없으면(전체 기본값 사용 중) 빈 목록을 반환한다.
     *
     * @return action 오름차순으로 정렬된 override 목록
     */
    fun findByUserId(userId: UUID): List<KeymapBinding>

    /**
     * [userId] 의 override 집합을 [overrides] 로 원자적으로 교체한다(replace-all).
     *
     * 기존 override 를 전부 삭제한 뒤 [overrides] 를 새로 저장한다 — 특정 action 만 갱신하는
     * 부분 UPSERT 는 지원하지 않는다. [overrides] 가 빈 목록이면 전체 기본값 복원(해당 user_id
     * 행 전부 삭제)을 의미한다.
     */
    fun replaceOverrides(
        userId: UUID,
        overrides: List<KeymapBinding>,
    )
}
