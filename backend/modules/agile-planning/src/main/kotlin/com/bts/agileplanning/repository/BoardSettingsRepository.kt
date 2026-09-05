// 보드 설정 4탭의 저장 칸 접근 — 카드 레이아웃 · 시간 추적 · 근무일 · 상세 보기 필드 (V509 · 부채 177)

package com.bts.agileplanning.repository

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * 근무일 탭이 보드 하나에서 읽는 세 값 (R5 · R6).
 *
 * @property standardDays 표준 근무일 요일 집합. **null 은 「미설정」이고 빈 리스트와 다르다**(R6).
 * @property timezone IANA 타임존. null 은 미설정 = UTC(R10 · E7).
 * @property nonWorkingDates 비근무일. 날짜 오름차순.
 */
data class BoardWorkingDays(
    val standardDays: List<String>?,
    val timezone: String?,
    val nonWorkingDates: List<LocalDate>,
)

/**
 * 보드 설정 4탭의 영속 접근 — RED 단계 껍데기.
 *
 * 시그니처(주입 seam)만 두고 로직은 GREEN 단계에 넣는다. 신규 클래스를 아예 만들지 않으면
 * 테스트가 **컴파일 실패**로 죽어 「어서션이 무엇을 요구하는지」가 안 보인다.
 */
@Repository
class BoardSettingsRepository(
    private val dsl: DSLContext,
) {
    @Transactional(readOnly = true)
    fun findCardLayout(boardId: UUID): Map<String, List<String>> = emptyMap()

    @Transactional
    fun replaceCardLayout(
        boardId: UUID,
        viewScope: String,
        fieldKeys: List<String>,
    ) = Unit

    @Transactional(readOnly = true)
    fun findTimeTracking(boardId: UUID): String? = null

    @Transactional
    fun updateTimeTracking(
        boardId: UUID,
        timeTracking: String,
    ): Boolean = false

    @Transactional(readOnly = true)
    fun findWorkingDays(boardId: UUID): BoardWorkingDays? = null

    @Transactional
    fun updateWorkingDays(
        boardId: UUID,
        standardDays: List<String>?,
        timezone: String?,
    ): Boolean = false

    @Transactional
    fun replaceNonWorkingDates(
        boardId: UUID,
        dates: List<LocalDate>,
    ) = Unit

    @Transactional(readOnly = true)
    fun findDetailViewFields(boardId: UUID): Map<String, List<String>> = emptyMap()

    @Transactional
    fun replaceDetailViewFields(
        boardId: UUID,
        fieldGroup: String,
        fieldKeys: List<String>,
    ) = Unit
}
