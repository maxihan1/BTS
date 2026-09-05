// 카드 레이아웃 설정 저장 유스케이스 — 뷰별 교체와 400 판정 (부채 177 Task 8 · J17·J18)

package com.bts.agileplanning.application

import com.bts.agileplanning.repository.BoardSettingsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 카드 레이아웃 탭의 저장 유스케이스 (R2·R3 · J17·J18).
 *
 * ★ RED 단계의 껍데기다 — 시그니처만 있고 로직은 GREEN 에서 넣는다.
 */
@Service
class CardLayoutSettingsService(
    private val settingsRepository: BoardSettingsRepository,
) {
    /**
     * 요청에 담긴 뷰들의 카드 레이아웃을 교체하고, 저장 후의 **전체 구성**을 돌려준다.
     *
     * @param boardId 대상 보드 UUID.
     * @param requested `viewScope → 필드 키 목록`. 요청에 없는 뷰는 건드리지 않는다.
     * @return 저장 후 `viewScope → 필드 키 목록`.
     */
    @Transactional
    fun replaceCardLayout(
        boardId: UUID,
        requested: Map<String, List<String>>,
    ): Map<String, List<String>> = emptyMap()
}
