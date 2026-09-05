// 추정 탭 — 보드 시간 추적(time_tracking) 저장 유스케이스 (부채 177 · J36·J37)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.BoardSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 추정 탭이 다루는 보드를 찾지 못했을 때 — 404.
 *
 * 소프트 삭제된 보드도 여기로 온다. 내부 식별자(boardId)는 message 에 담지 않는다.
 */
class EstimationBoardNotFoundException :
    ResponseStatusException(HttpStatus.NOT_FOUND, "보드를 찾을 수 없습니다.")

/**
 * 스크럼이 아닌 보드의 시간 추적을 바꾸려 할 때 — **409**(J37 · 스펙 E5).
 *
 * ### 왜 404 가 아닌가
 * **보드는 존재한다.** 404 는 거짓말이고, 형제 [SprintBoardNotScrumException] 이 같은 자리에 같은
 * 이유로 이미 서 있다(memory: permission-assert-before-existence-makes-403-lie 와 같은 양식의 오도).
 * 「없는 보드」와 「있는데 조작이 막힌 보드」는 클라이언트가 서로 다르게 안내해야 한다 —
 * 앞은 목록을 새로고침해야 하고, 뒤는 이 보드에서 영영 불가능하다.
 *
 * ### 값은 지우지 않는다 (스펙 E6)
 * 이 예외를 던지는 경로는 저장 칸을 **읽지도 쓰지도 않는다.** 칸반으로 바뀐 보드의
 * `time_tracking` 을 여기서 `NONE` 으로 되돌리면 스크럼으로 되돌렸을 때 값이 사라진다.
 * 칸반에서 무시하는 것은 **읽는 쪽**의 몫이다([BoardSettingsRepository.findTimeTracking] KDoc).
 *
 * ★전용 [com.bts.agileplanning.web.BoardExceptionHandler] 핸들러가 있어야 errorCode 가 붙는다.
 * 지금은 없다 — 그 파일이 Task 8·10·13 과 공유하는 자원이라 이 task 가 건드리지 않았다.
 * [ResponseStatusException] 을 상속하므로 **상태 코드 409 는 그대로 전파**되고, 바디만 비어 있다.
 */
class TimeTrackingBoardNotScrumException :
    ResponseStatusException(HttpStatus.CONFLICT, "시간 추적은 스크럼 보드에서만 바꿀 수 있습니다.")

/**
 * 시간 추적 값이 허용값 밖일 때 — 400.
 *
 * 문자열을 그대로 저장 계층에 넘기면 V509 의 `boards_time_tracking_allowed` CHECK 가 걸려
 * **500** 이 된다. 사용자 입력 오류가 5xx 경보로 새지 않게 서비스가 먼저 가른다.
 * 거부된 값은 message 에 담지 않고 로그로만 남긴다.
 */
class TimeTrackingInvalidException :
    ResponseStatusException(HttpStatus.BAD_REQUEST, "시간 추적 값이 올바르지 않습니다.")

/**
 * 시간 추적 방식 (R4 · J36).
 *
 * `Remaining estimate and time spent` 는 *"tracks progress by subtracting the value from the
 * **Time spent** field from the original estimate"* 다(J36). [NONE] 이 기본값이며
 * `boards.time_tracking` 의 `DEFAULT 'NONE'`(V509) 과 같은 값이다 — 배포 시점 백필이 무변경이다.
 *
 * ★**자리 주의.** 값 자체는 도메인 개념이라 [BoardType] 옆(`domain/`)이 제자리다. 이 task 의
 * 허용 파일이 서비스·컨트롤러·테스트 셋뿐이라 여기 둔다 — 도메인으로 옮기는 것은 별도 정리 대상이다.
 */
enum class TimeTracking {
    /** 시간 추적을 쓰지 않는다. 기존 보드 전량의 값이다. */
    NONE,

    /** 잔여 추정 + 소요 시간으로 진행을 잰다(J36). */
    REMAINING_AND_SPENT,
    ;

    companion object {
        /**
         * 문자열을 [TimeTracking] 으로 읽는다.
         *
         * [BoardType.from] 과 같은 모양이다 — 파싱을 한 곳에 모아 허용값이 늘 때 고칠 자리를 하나로 둔다.
         * 다만 여기서는 `null`/생략을 기본값으로 봐주지 않는다. 추정 탭 PATCH 의 유일한 필드라
         * 「안 보냈다」는 요청 자체가 성립하지 않는다(DTO 의 `@field:NotBlank` 가 1차 방어).
         *
         * @param raw 시간 추적 문자열. 대소문자는 가리지 않는다.
         * @throws TimeTrackingInvalidException 허용값 밖일 때 — 400.
         */
        fun from(raw: String): TimeTracking =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw TimeTrackingInvalidException()
    }
}

/**
 * 추정 탭의 시간 추적 설정 유스케이스 — agile-planning BC (부채 177 · J36·J37).
 *
 * ## 판정 순서와 그 이유
 * ```
 * 값 파싱 ─ 허용값 밖 ──────────── 400
 *   └ 보드 조회 ─ 없음/소프트삭제 ─ 404   ← 「보드가 없다」
 *       └ 종류 판정 ─ 칸반 ────────── 409   ← 「보드는 있고 조작이 막혔다」(J37 · E5)
 *           └ 저장 ─ 그 사이 사라짐 ── 404
 * ```
 * ★**404 와 409 를 가르는 것이 이 서비스의 핵심**이다. 「칸반이면 409」만 두면 보드를 조회조차
 * 하지 않고 409 를 내는 구현이 통과한다 — 그러면 없는 보드에도 「칸반이라 막혔다」고 답한다.
 *
 * ★**보드 종류 판정은 여기(서비스)에 있어야 한다.** 컨트롤러가 판정하고 서비스는 시키는 대로
 * 쓰게 만들면 화면을 거치지 않는 PATCH 가 규칙을 우회한다
 * (memory: patch-merge-domain-bypass — DTO/컨트롤러 검증은 1차 방어일 뿐이다).
 *
 * ## 트랜잭션
 * 조회와 쓰기가 **한 트랜잭션**이다. 종류를 읽은 뒤 쓰기 전에 보드가 소프트 삭제되면
 * [BoardSettingsRepository.updateTimeTracking] 이 `false`(404 신호)를 돌려주고 그 판정으로 합류한다 —
 * 「검사와 사용 사이」를 한 트랜잭션 안에서 다시 확인하는 자리다.
 *
 * @param boardRepository 보드 메타(종류·존재) 조회.
 * @param settingsRepository 설정 4탭 저장 칸 접근(V509).
 */
@Service
class EstimationSettingsService(
    private val boardRepository: BoardRepository,
    private val settingsRepository: BoardSettingsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보드의 시간 추적 설정을 갱신한다 (J36 · J37).
     *
     * @param boardId 대상 보드 UUID.
     * @param timeTracking 요청된 시간 추적 값 문자열(`NONE` / `REMAINING_AND_SPENT`).
     * @return 저장된 값의 이름.
     * @throws TimeTrackingInvalidException 400 — 허용값 밖.
     * @throws EstimationBoardNotFoundException 404 — 보드 미존재 또는 소프트 삭제.
     * @throws TimeTrackingBoardNotScrumException 409 — 칸반 보드(J37 · E5).
     */
    @Transactional
    fun updateTimeTracking(
        boardId: UUID,
        timeTracking: String,
    ): String {
        val value = TimeTracking.from(timeTracking)
        requireScrumBoard(boardId)

        if (!settingsRepository.updateTimeTracking(boardId, value.name)) {
            // 조회와 쓰기 사이에 보드가 사라졌다. 「저장된 줄 알았는데 아니다」를 만들지 않는다.
            throw EstimationBoardNotFoundException()
        }

        log.info("시간 추적 갱신 — boardId={}, timeTracking={}", boardId, value.name)
        return value.name
    }

    /**
     * 보드가 존재하고(404) **스크럼**인지(409) 확인한다 — 이 task 의 404↔409 갈림이 사는 자리다.
     *
     * 한 함수에 던지는 자리가 셋이면 detekt `ThrowsCount`(상한 2) 에 걸린다. 숫자를 맞추려고 자른 것이
     * 아니라 **판정의 결이 다르다** — 여기는 「이 보드를 만질 수 있는가」이고, 부르는 쪽은 「저장이
     * 실제로 됐는가」다.
     *
     * @param boardId 대상 보드 UUID.
     * @throws EstimationBoardNotFoundException 404 — 보드 미존재 또는 소프트 삭제.
     * @throws TimeTrackingBoardNotScrumException 409 — 칸반 보드(J37 · E5).
     */
    private fun requireScrumBoard(boardId: UUID) {
        val board = boardRepository.findById(boardId) ?: throw EstimationBoardNotFoundException()
        if (board.boardType != BoardType.SCRUM) {
            log.info("시간 추적 변경 거부 — 스크럼이 아니다. boardId={}, boardType={}", boardId, board.boardType)
            throw TimeTrackingBoardNotScrumException()
        }
    }
}
