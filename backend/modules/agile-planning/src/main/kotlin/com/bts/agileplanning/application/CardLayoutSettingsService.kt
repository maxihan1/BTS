// 카드 레이아웃 설정 저장 유스케이스 — 뷰별 교체와 400 판정 (부채 177 Task 8 · J17·J18)

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

/** 한 뷰에 실을 수 있는 추가 필드 수의 상한 (J17 — *"up to three additional fields"*). */
private const val MAX_FIELDS_PER_VIEW = 3

/** 커스텀 필드 키 접두사. 표준 필드 카탈로그와 커스텀 필드를 한 배열에서 구분하는 표식이다. */
private const val CUSTOM_FIELD_PREFIX = "cf_"

/**
 * 카드 레이아웃 요청이 규칙을 어겼을 때 던지는 예외 — 400.
 *
 * [ResponseStatusException] 을 상속하는 것은 이 BC 의 관용구다([QuickFilterEmptyQueryException] 등).
 * 상속 덕에 `BoardExceptionHandler` 가 이 컨트롤러를 맡게 되는 날 별도 매핑 없이
 * `handleResponseStatus` 가 RFC 7807 봉투를 씌운다 —
 * [com.bts.agileplanning.web.BoardCardLayoutController] KDoc 의 「예외 매핑」 절을 함께 읽어라.
 */
class CardLayoutInvalidException(reason: String) : ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

/**
 * 카드 레이아웃 구성을 가지는 뷰 (R3 · J18).
 *
 * 스크럼 보드는 **백로그**와 **활성 스프린트(보드)** 가 서로 다른 필드 집합을 가질 수 있다.
 * 칸반은 보드 뷰 하나뿐이라 [BACKLOG] 를 갖지 않는다 — 그 판정은 [CardLayoutSettingsService] 가 한다.
 *
 * 허용값의 정본은 `V509` 의 `CHECK (view_scope IN ('BOARD','BACKLOG'))` 다. 이 열거형은 그 CHECK 를
 * 대신하는 것이 아니라 **사용자에게 이유를 주기 위해** 앞에 선다 — 사전 검사는 동시 저장 경합을
 * 못 막는다(`#444` X1). 그래서 [BoardSettingsRepository] 의 인자는 여전히 [String] 이다.
 */
enum class CardLayoutViewScope {
    /** 보드(스크럼이면 활성 스프린트) 뷰. 모든 보드 종류가 갖는다. */
    BOARD,

    /** 백로그 뷰. **스크럼 보드에만 있다.** */
    BACKLOG,
    ;

    companion object {
        /**
         * 문자열을 [CardLayoutViewScope] 로 읽는다.
         *
         * @param raw 요청 바디의 뷰 키.
         * @throws CardLayoutInvalidException 400 — 허용값 밖일 때. 그대로 DB 로 넘기면 CHECK 위반이
         *   500 으로 나가 사용자가 무엇을 잘못했는지 알 수 없다.
         */
        fun from(raw: String): CardLayoutViewScope =
            entries.firstOrNull { it.name == raw }
                ?: throw CardLayoutInvalidException("지원하지 않는 카드 레이아웃 뷰입니다: $raw")
    }
}

/**
 * 카드에 얹을 수 있는 **표준** 필드 카탈로그 (R2).
 *
 * `BoardCardResponse` 가 이미 나르는 값만 담는다 — 여기 없는 키를 허용하면 저장은 되는데 카드가
 * 그릴 것이 없는 「도달할 UI 가 없는 설정」이 된다.
 *
 * ★**요약(`summary`)은 없다.** J19 의 1층이라 **항상 최상단이고 토글 대상이 아니다**(R2).
 * 표준 필드처럼 생겼지만 이 카탈로그에 넣으면 사용자가 요약을 끌 수 있게 된다.
 *
 * ★**커스텀 필드는 이 열거형에 없다.** 프로젝트마다 다르므로 [CUSTOM_FIELD_PREFIX] 접두사로 받는다
 * (스펙 §API 예시의 `cf_story_points`). 실제 커스텀 필드 키의 존재 여부는 issue-tracking 이 소유한
 * 지식이라 여기서 검증하지 않는다 — 확인하려면 권한 모델까지 복사해야 하고, 그 순간
 * 「두 목록이 서로를 검사하지 않는다」가 된다(스펙 C-6 과 같은 이유).
 */
enum class CardLayoutFieldKey {
    /** 소속 에픽. `BoardCardResponse.epicKey`. */
    EPIC,

    /** 우선순위. `BoardCardResponse.priority`. */
    PRIORITY,

    /** 담당자. `BoardCardResponse.assigneeId`. */
    ASSIGNEE,

    /** 라벨. `BoardCardResponse.labels`. */
    LABELS,

    /** 최초 추정치. `BoardCardResponse.originalEstimateSeconds`. */
    ESTIMATE,

    /** 이슈 종류. `BoardCardResponse.typeKey`. */
    ISSUE_TYPE,
}

/**
 * 카드 레이아웃 탭의 저장 유스케이스 (R2·R3 · J17·J18).
 *
 * ## 요청 단위로 **전부 검증한 뒤에** 쓴다
 * 한 요청이 두 뷰를 담을 수 있다. 뷰를 하나씩 「검증 → 저장」하면 뒤엣것이 400 일 때 앞엣것만
 * 저장된 반쪽 상태가 남는다. `@Transactional` 이 롤백해 주지만, 그것은 **예외가 났을 때**의 얘기이고
 * 검증 순서 자체를 앞으로 모아 두면 롤백에 기대지 않아도 반쪽 저장이 성립하지 않는다.
 *
 * ## 상한 3개를 여기서도 재는 이유 (완료 기준 2)
 * `V509` 의 `CHECK (position BETWEEN 0 AND 2)` 가 **진짜 방어**다. 여기 검증은 사용자에게 이유를
 * 주려고 있는 것이지 DB 제약을 대신하지 않는다 — 사전 검사는 동시 저장 경합을 못 막는다(`#444` X1).
 *
 * ## 응답은 저장 후 **다시 읽은** 값이다
 * 요청을 되돌려주면(echo) 저장이 안 돼도 화면은 성공으로 보인다. 그래서 [BoardSettingsRepository]
 * 로 다시 읽어 돌려준다 — 같은 트랜잭션 안이라 방금 쓴 값이 보인다.
 *
 * @param boardRepository 보드 메타(종류·존재) 조회용.
 * @param settingsRepository `board_card_layout_fields` 접근(부채 177 Task 7).
 */
@Service
class CardLayoutSettingsService(
    private val boardRepository: BoardRepository,
    private val settingsRepository: BoardSettingsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 요청에 담긴 뷰들의 카드 레이아웃을 교체하고, 저장 후의 **전체 구성**을 돌려준다.
     *
     * ★**요청에 없는 뷰는 건드리지 않는다.** 보드 뷰를 저장했다고 백로그 구성이 사라지면 J18 이
     * 무너진다. 「없는 뷰 = 빈 목록」으로 읽는 순간 그 사고가 난다.
     *
     * @param boardId 대상 보드 UUID.
     * @param requested `viewScope → 필드 키 목록`. 순서가 곧 카드에서의 자리(`position`)다.
     *   빈 목록은 「그 뷰의 추가 필드 없음」이고 유효한 저장이다.
     * @return 저장 후 `viewScope → 필드 키 목록`. 구성이 없는 뷰는 키가 아예 없다.
     * @throws ResponseStatusException 404 — 보드 미존재 또는 soft-deleted.
     * @throws CardLayoutInvalidException 400 — 뷰 4개 초과 · 미지원 필드 키 · 미지원 뷰 ·
     *   칸반 보드에 백로그 뷰.
     */
    @Transactional
    fun replaceCardLayout(
        boardId: UUID,
        requested: Map<String, List<String>>,
    ): Map<String, List<String>> {
        val board =
            boardRepository.findById(boardId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "보드를 찾을 수 없습니다.")

        // 쓰기 전에 요청 전체를 검증한다 — 반쪽 저장이 성립할 여지를 남기지 않는다.
        val validated = requested.map { (rawScope, fieldKeys) -> validateView(board.boardType, rawScope, fieldKeys) }

        validated.forEach { (scope, fieldKeys) ->
            log.info("카드 레이아웃 저장 boardId={} viewScope={} fields={}", boardId, scope, fieldKeys)
            settingsRepository.replaceCardLayout(boardId, scope.name, fieldKeys)
        }

        return settingsRepository.findCardLayout(boardId)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 한 뷰의 요청을 검증하고 저장 가능한 형태로 좁힌다.
     *
     * @param boardType 대상 보드의 종류. 백로그 뷰의 존재 여부가 여기에 달렸다(R3).
     * @param rawScope 요청 바디의 뷰 키.
     * @param fieldKeys 그 뷰의 필드 키 목록.
     * @return 검증된 뷰와 필드 키 목록의 쌍.
     * @throws CardLayoutInvalidException 400 — 미지원 뷰 · 칸반의 백로그 뷰 · 상한 초과 · 미지원 필드 키.
     */
    private fun validateView(
        boardType: BoardType,
        rawScope: String,
        fieldKeys: List<String>,
    ): Pair<CardLayoutViewScope, List<String>> {
        val scope = CardLayoutViewScope.from(rawScope)
        if (scope == CardLayoutViewScope.BACKLOG && boardType != BoardType.SCRUM) {
            // 칸반은 보드 뷰 하나뿐이다(R3). 보드가 **없는** 것이 아니라 그 뷰가 없는 것이라 400 이다.
            throw CardLayoutInvalidException("칸반 보드에는 백로그 뷰가 없습니다.")
        }
        if (fieldKeys.size > MAX_FIELDS_PER_VIEW) {
            throw CardLayoutInvalidException("카드에 추가할 수 있는 필드는 뷰당 최대 ${MAX_FIELDS_PER_VIEW}개입니다.")
        }
        fieldKeys.forEach { requireSupportedFieldKey(it) }
        return scope to fieldKeys
    }

    /**
     * 필드 키가 표준 카탈로그([CardLayoutFieldKey]) 또는 커스텀 필드([CUSTOM_FIELD_PREFIX])인지 확인한다.
     *
     * @param fieldKey 요청 바디의 필드 키. **nullable 로 받는다** — Jackson 은 `["EPIC", null]` 같은
     *   배열 원소의 null 을 막지 못한다. 여기서 400 으로 거두지 않으면 NOT NULL 위반이 500 으로 나간다.
     * @throws CardLayoutInvalidException 400 — 카탈로그에 없고 커스텀 필드 접두사도 아닐 때.
     */
    private fun requireSupportedFieldKey(fieldKey: String?) {
        val isCustomField =
            fieldKey != null &&
                fieldKey.length > CUSTOM_FIELD_PREFIX.length &&
                fieldKey.startsWith(CUSTOM_FIELD_PREFIX)
        val isStandardField = CardLayoutFieldKey.entries.any { it.name == fieldKey }
        if (!isCustomField && !isStandardField) {
            throw CardLayoutInvalidException("카드에 표시할 수 없는 필드입니다: $fieldKey")
        }
    }
}
