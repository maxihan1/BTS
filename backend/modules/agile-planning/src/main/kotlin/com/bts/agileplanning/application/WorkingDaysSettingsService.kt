// 보드 설정 「작업일」 탭 저장 유스케이스 — 표준 근무일 · 비근무일 · 타임존 (부채 177 Task 10)

package com.bts.agileplanning.application

import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.agileplanning.repository.BoardWorkingDays
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * 작업일 설정 값이 규칙에 어긋난다 — 400 (스펙 E1 · J38·J40).
 *
 * @property reason 로깅·응답 상세에 쓰는 사유. 내부 식별자를 담지 않는다.
 */
class WorkingDaysInvalidException(val reason: String) : RuntimeException(reason)

/**
 * 저장 시점에 보드가 사라졌다(또는 soft-deleted) — 404.
 *
 * 컨트롤러의 게이트가 이미 존재를 확인하지만, 게이트와 저장 사이에 삭제가 끼어들 수 있다.
 * 그 경우 [BoardSettingsRepository.updateWorkingDays] 가 `false` 를 주며, 조용히 200 을 돌려주면
 * 사용자는 저장되지 않은 값을 저장됐다고 믿는다.
 */
class WorkingDaysBoardNotFoundException : RuntimeException("보드를 찾을 수 없습니다.")

/**
 * 보드 설정 「작업일」 탭의 저장 유스케이스 (R5 · J38·J39·J40).
 *
 * @param settingsRepository 설정 4탭 저장 칸 접근.
 */
@Service
class WorkingDaysSettingsService(
    private val settingsRepository: BoardSettingsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 작업일 설정 세 값을 통째로 저장한다.
     *
     * @param boardId 대상 보드 UUID.
     * @param standardDays 표준 근무일 요일 키 목록.
     * @param nonWorkingDates 비근무일 목록.
     * @param timezone IANA 타임존.
     * @return 저장된 값.
     */
    @Transactional
    fun save(
        boardId: UUID,
        standardDays: List<String>?,
        nonWorkingDates: List<LocalDate>,
        timezone: String?,
    ): BoardWorkingDays {
        log.info("작업일 설정 저장 — boardId={}", boardId)

        settingsRepository.updateWorkingDays(boardId, standardDays, timezone)
        settingsRepository.replaceNonWorkingDates(boardId, nonWorkingDates)
        return BoardWorkingDays(standardDays, timezone, nonWorkingDates)
    }
}
