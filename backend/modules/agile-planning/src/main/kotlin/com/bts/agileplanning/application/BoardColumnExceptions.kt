// 컬럼:상태 1:N 이 만든 카드 이동 예외 3종 — 요청 해석 실패와 미매핑 상태를 코드로 가른다

package com.bts.agileplanning.application

import java.util.UUID

/**
 * 카드 이동 요청이 대상을 **정확히 하나** 지정하지 않았다 — 400 `AGILE_VALIDATION_FAILED`.
 *
 * `toColumnId` 와 `toStateKey` 는 둘 다 nullable 이고 **둘 중 하나만** 와야 한다.
 * `@NotNull` 로는 「둘 중 정확히 하나」를 표현할 수 없어 서비스가 진다.
 *
 * 이 판정을 컨트롤러에도 두지 않는 이유는 규칙이 두 곳이 되면 언젠가 갈리기 때문이다.
 */
class MoveTargetAmbiguousException(
    override val message: String,
) : RuntimeException(message)

/**
 * `toStateKey` 가 그 보드의 **어느 컬럼에도** 매핑되지 않았다 — 404 `AGILE_BOARD_STATE_NOT_MAPPED` (E4).
 *
 * 워크플로우에는 있으나 이 보드가 담지 않은 상태다. 보드 조회 응답의 `unmappedStates`(R8)가
 * 목록으로 알려 주는 바로 그 상태이고, UI 는 이 코드를 보고 「그 상태를 컬럼에 추가하세요」로
 * 안내할 수 있다.
 *
 * 보드 미존재(`AGILE_BOARD_NOT_FOUND`)와 **코드를 나눈다** — 둘 다 404 라 상태 코드만으로는
 * 구분이 안 되고, 사용자가 할 수 있는 일이 서로 다르다.
 *
 * @property stateKey 매핑되지 않은 상태 키.
 */
class BoardStateNotMappedException(
    val stateKey: String,
) : RuntimeException("보드에 매핑되지 않은 상태입니다: stateKey=$stateKey")

/**
 * 하위 호환 경로(`toColumnId`)가 상태 **2개 이상**인 컬럼을 가리켰다 — 400 `AGILE_COLUMN_STATE_AMBIGUOUS` (R7).
 *
 * 1:1 시절에는 컬럼이 상태를 유일하게 결정했다. 1:N 이 되면 그 도출이 성립하지 않고,
 * 서버가 둘 중 하나를 고르면 **사용자가 의도하지 않은 전환이 조용히 일어난다.**
 * 「어느 상태로 가라」는 클라이언트만 안다(J3·J4).
 *
 * 클라이언트에게는 「`toStateKey` 로 옮겨 가라」는 신호다.
 *
 * @property columnId 모호한 컬럼 UUID.
 * @property stateCount 그 컬럼이 담은 상태 수. 응답 메시지가 이유를 구체적으로 말한다.
 */
class ColumnStateAmbiguousException(
    val columnId: UUID,
    val stateCount: Int,
) : RuntimeException("컬럼이 상태 ${stateCount}개를 담아 이동 대상을 정할 수 없습니다: columnId=$columnId")
