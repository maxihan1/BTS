// 추정 탭 — 보드 시간 추적(time_tracking) 저장 유스케이스 (부채 177 · J36·J37)

package com.bts.agileplanning.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 추정 탭의 시간 추적 설정 유스케이스 — **RED 단계 껍데기**.
 *
 * 아직 로직이 없다. 보드 종류(스크럼/칸반) 판정도, 저장도 하지 않고 받은 값을 그대로 돌려준다.
 * 이 껍데기는 테스트가 **컴파일 실패가 아니라 어서션 실패**로 red 를 내게 하려고만 존재한다
 * (신규 클래스라 시그니처가 없으면 red 를 눈으로 볼 수 없다).
 */
@Service
class EstimationSettingsService {
    /**
     * 시간 추적 값을 저장한다 — GREEN 단계에서 채운다.
     *
     * @param boardId 대상 보드 UUID.
     * @param timeTracking 요청된 시간 추적 값 문자열.
     * @return 저장된 값. 지금은 받은 값을 그대로 돌려준다.
     */
    @Transactional
    fun updateTimeTracking(
        boardId: UUID,
        timeTracking: String,
    ): String = timeTracking
}
