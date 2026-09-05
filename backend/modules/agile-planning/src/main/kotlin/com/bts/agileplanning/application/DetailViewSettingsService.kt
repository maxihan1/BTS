// 이슈 상세 보기 필드 구성(그룹 4종) 조회·교체 유스케이스 — 부채 177 Task 13 (RED 껍데기)

package com.bts.agileplanning.application

import com.bts.agileplanning.repository.BoardSettingsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 이슈 상세 보기 필드 구성 유스케이스 (R7 · J46~J48).
 *
 * RED 단계 껍데기 — 시그니처만 있고 로직은 GREEN 에서 채운다.
 *
 * @param repository 보드 설정 4탭의 영속 접근.
 */
@Service
class DetailViewSettingsService(
    @Suppress("UnusedPrivateProperty") private val repository: BoardSettingsRepository,
) {
    /**
     * 보드의 상세 보기 구성을 그룹별로 읽는다.
     *
     * @param boardId 대상 보드 UUID.
     * @return `fieldGroup → 필드 키 목록`.
     */
    @Transactional(readOnly = true)
    fun findFields(
        @Suppress("UnusedParameter") boardId: UUID,
    ): Map<String, List<String>> = emptyMap()

    /**
     * 요청에 실린 그룹만 통째로 교체한다.
     *
     * @param boardId 대상 보드 UUID.
     * @param groups `fieldGroup → 새 필드 키 목록`.
     * @return 교체 후의 전체 구성.
     */
    @Transactional
    fun replaceGroups(
        @Suppress("UnusedParameter") boardId: UUID,
        @Suppress("UnusedParameter") groups: Map<String, List<String>>,
    ): Map<String, List<String>> = emptyMap()
}
