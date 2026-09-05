// 보드 설정 「작업일」 탭 저장 유스케이스 — 표준 근무일 · 비근무일 · 타임존 (부채 177 Task 10)

package com.bts.agileplanning.application

import com.bts.agileplanning.repository.BoardSettingsRepository
import com.bts.agileplanning.repository.BoardWorkingDays
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
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
 * ## ★ 미설정(NULL) = 달력일 전부 — 이 서비스가 지키는 첫 번째 규칙
 * `standardDays` 의 **null 과 빈 리스트는 다른 값**이다.
 *
 * | 입력 | 뜻 | 결과 |
 * |---|---|---|
 * | `null`(또는 키 생략) | **미설정** | 200. `working_days` 에 SQL NULL 을 쓴다 |
 * | `[]` | 근무일 0개 | **400** (스펙 E1) |
 * | `["MON", ...]` | 근무일 지정 | 200 |
 *
 * **미설정은 「달력일 전부」를 뜻한다.** `BurndownCalculator`(Task 11)가 이 문장에 기댄다 —
 * `working_days` 가 NULL 인 보드의 번다운은 x축도 ideal 선의 분모도 **달력일 수** 그대로다.
 * 미설정을 「근무일 0개」로 뭉개면 ideal 선이 0 으로 나뉘고, 반대로 「월~금」으로 채우면
 * **아무도 설정을 바꾸지 않았는데 기존 모든 스프린트의 차트가 배포 순간 바뀐다**(스펙 R6 · V509 ①).
 *
 * 그래서 「0개는 400」과 「NULL 은 200」은 **한 쌍으로만 옳다.** 둘 중 하나만 두면 —
 * NULL 까지 400 으로 막으면 미설정 보드를 아무도 저장할 수 없고, 0개를 통과시키면 0 나눗셈이다.
 *
 * ## ★ 정규화 책임이 여기 있다
 * [BoardSettingsRepository.replaceNonWorkingDates] 는 중복 날짜를 **조용히 삼키지 않는다** —
 * `PRIMARY KEY (board_id, date)` 가 거부해 500 이 된다. 리포지터리가 `distinct()` 를 하지 않는 것은
 * 의도된 결정이고(사용자가 자기가 무엇을 저장했는지 모르게 되므로), 정규화는 이 서비스의 몫이다.
 * 정규화 결과를 **응답으로 되돌려주므로** 사용자는 실제 저장된 집합을 그대로 본다.
 *
 * @param settingsRepository 설정 4탭 저장 칸 접근.
 */
@Service
class WorkingDaysSettingsService(
    private val settingsRepository: BoardSettingsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 작업일 설정 세 값을 통째로 저장한다 (J38·J39·J40).
     *
     * 검증 → 정규화 → 저장 순서다. 검증에 걸리면 **저장 계층에 아무것도 가지 않는다** —
     * 요일만 반영되고 비근무일이 빠진 중간 상태를 만들지 않기 위해서다.
     *
     * @param boardId 대상 보드 UUID.
     * @param standardDays 표준 근무일 요일 키(`MON`..`SUN`). **null 이면 미설정**(≠ 빈 리스트).
     * @param nonWorkingDates 비근무일. 중복은 여기서 제거하고 오름차순으로 정렬한다.
     *   **스프린트 기간 밖 날짜도 그대로 저장한다**(스펙 E8) — 보드는 스프린트 기간을 모르고,
     *   여기서 거르면 스프린트가 바뀔 때마다 설정이 소실된다.
     * @param timezone IANA 타임존. null 이면 미설정(UTC · 스펙 E7).
     * @return 정규화 후 실제로 저장된 값.
     * @throws WorkingDaysInvalidException 400 — 근무일 0개(E1) · 미지원 요일 키 · 비-IANA 타임존.
     * @throws WorkingDaysBoardNotFoundException 404 — 저장 직전 보드가 사라졌다.
     */
    @Transactional
    fun save(
        boardId: UUID,
        standardDays: List<String>?,
        nonWorkingDates: List<LocalDate>,
        timezone: String?,
    ): BoardWorkingDays {
        val days = normalizeStandardDays(standardDays)
        val zone = validateTimezone(timezone)
        val dates = nonWorkingDates.distinct().sorted()

        log.info(
            "작업일 설정 저장 — boardId={}, days={}, timezone={}, nonWorkingDates={}",
            boardId,
            days?.size ?: "미설정",
            zone ?: "미설정",
            dates.size,
        )

        if (!settingsRepository.updateWorkingDays(boardId, days, zone)) {
            throw WorkingDaysBoardNotFoundException()
        }
        settingsRepository.replaceNonWorkingDates(boardId, dates)

        return BoardWorkingDays(standardDays = days, timezone = zone, nonWorkingDates = dates)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 요일 키를 검증하고 중복 제거 + 주 순서 정렬한다 (J38 · E1).
     *
     * **null 은 그대로 통과시킨다** — 미설정이 유효한 상태이기 때문이다(위 클래스 KDoc 참조).
     * 키는 대소문자를 접지 않는다. 허용 집합이 7개뿐이라 관용을 넣을 이유가 없고,
     * 관용은 「무엇이 저장됐는가」를 흐린다.
     *
     * @param raw 요청이 준 요일 키 목록.
     * @return 정규화된 목록. 입력이 null 이면 null.
     * @throws WorkingDaysInvalidException 0개이거나 지원하지 않는 키가 섞였을 때.
     */
    private fun normalizeStandardDays(raw: List<String>?): List<String>? {
        if (raw == null) return null

        if (raw.isEmpty()) {
            throw WorkingDaysInvalidException(
                "근무일은 최소 1일 이상이어야 합니다. 근무일을 쓰지 않으려면 값을 비우지 말고 미설정으로 두세요.",
            )
        }
        val unsupported = raw.filterNot { it in WEEK_ORDER }
        if (unsupported.isNotEmpty()) {
            throw WorkingDaysInvalidException("지원하지 않는 요일 키입니다: ${unsupported.joinToString(", ")}")
        }
        return raw.distinct().sortedBy(WEEK_ORDER::indexOf)
    }

    /**
     * IANA 타임존인지 확인한다 (J40 · E7).
     *
     * `ZoneId.of` 가 아니라 [ZoneId.getAvailableZoneIds] 멤버십으로 판정한다 — `ZoneId.of` 는
     * `"UTC+09:00"` 같은 오프셋 표기도 통과시키는데, 그것은 IANA 지역이 아니라서 서머타임을
     * 따라가지 못한다. 근무일 귀속(R10)은 지역 규칙이 필요하다.
     *
     * @param raw 요청이 준 타임존 문자열.
     * @return 검증된 타임존. 입력이 null 이면 null(미설정 = UTC).
     * @throws WorkingDaysInvalidException IANA 타임존이 아닐 때.
     */
    private fun validateTimezone(raw: String?): String? {
        if (raw == null) return null
        if (raw !in ZoneId.getAvailableZoneIds()) {
            throw WorkingDaysInvalidException("IANA 타임존이 아닙니다: $raw")
        }
        return raw
    }

    private companion object {
        /**
         * `working_days` 에 허용하는 요일 키 — `VARCHAR(3)[]` 3글자에 맞춘 표기다.
         *
         * V509 는 이 칸에 CHECK 를 걸지 않았다(배열이라 걸 자리가 없다). 그래서 이 목록이
         * **유일한 방어선**이다 — 여기를 통과한 값이 그대로 DB 에 들어간다.
         * 순서가 곧 주의 순서이고, 정렬 키로도 쓴다.
         */
        val WEEK_ORDER = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    }
}
