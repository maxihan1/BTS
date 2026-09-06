// 이슈 상세 보기 필드 구성(그룹 4종) 조회·그룹 단위 교체 유스케이스 — 부채 177 Task 13

package com.bts.agileplanning.application

import com.bts.agileplanning.repository.BoardSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 이슈 상세 보기 필드 그룹 4종 — **표시 순서 그대로**다 (J47).
 *
 * *"different groups of fields: General fields, Date fields, People, and Links."* 의 순서를 그대로 옮긴다.
 * 이 리스트는 두 가지를 동시에 정한다.
 * 1. **허용값** — 여기 없는 그룹은 400 이다. 최종 방어선은 `V509` 의 `CHECK (field_group IN (...))` 이고
 *    이 목록은 사용자에게 이유를 주기 위한 앞단이다(리포지터리 KDoc 과 같은 분담).
 * 2. **응답의 그룹 순서** — 화면 4구획의 위→아래 순서다.
 *
 * ★값을 늘리려면 `V509` 의 CHECK 를 **먼저** 넓혀야 한다. 여기만 늘리면 400 을 통과한 요청이
 * DB 에서 터져 500 이 된다.
 */

private val DETAIL_VIEW_FIELD_GROUPS = listOf("GENERAL", "DATE", "PEOPLE", "LINKS")

/**
 * `board_detail_view_fields.field_key` 의 컬럼 폭(V509 ④절 `VARCHAR(128)`).
 *
 * ★**DB 스키마의 미러다.** 넘겨 보내면 SQLSTATE 22001 로 죽어 500 이 되므로 여기서 400 으로 거둔다.
 * V509 를 고쳐 폭이 바뀌면 이 값도 함께 바뀌어야 한다 — 두 자리가 서로를 검사하지 않는다.
 */
private const val MAX_FIELD_KEY_LENGTH = 128

/**
 * 미지원 필드 그룹으로 상세 보기 구성을 저장하려 할 때 던지는 예외 — 400.
 *
 * message 에 요청 값을 되비추지 않는다 — 허용값만 알린다(선례 [QuickFilterEmptyQueryException] 과 같은 규율).
 * 어떤 값이 들어왔는지는 서버 로그에만 남긴다.
 *
 * [ResponseStatusException] 을 상속한다. 종전 주석은 그 이유를 「`BoardExceptionHandler` 의
 * `assignableTypes` 가 `BoardDetailViewController` 를 포함하지 않기 때문」이라 적었는데
 * **Task 29 가 포함시켰으므로 그 문장은 거짓이 됐다**(리뷰 P6 · 2026-09-06 정정).
 *
 * 상속을 유지하는 현재 이유는 다르다 — `handleResponseStatus` 가 이 예외를 받아
 * `AGILE_VALIDATION_FAILED` 봉투로 400 을 내고, **`reason` 을 `detail` 에 그대로 싣는다**
 * (리뷰 C7 로 그렇게 고쳤다). 즉 전용 핸들러를 새로 달지 않아도 사유가 사용자에게 도달한다.
 * 전용 예외 클래스를 더하는 것은 봉투가 아니라 `errorCode` 를 탭별로 가르고 싶을 때의 선택이고,
 * 지금은 네 탭이 **한 봉투**를 내는 것이 Task 29 가 세운 계약이다.
 */
class DetailViewFieldGroupInvalidException(
    reason: String = "상세 보기 필드 그룹은 GENERAL, DATE, PEOPLE, LINKS 중 하나여야 합니다.",
) : ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        reason,
    )

/**
 * 이슈 상세 보기 필드 구성 유스케이스 (R7·R7b·R7c · J46~J48).
 *
 * ## 개수 상한이 없다
 * 카드 레이아웃의 0..2(J17)는 **카드**의 제약이다. 상세 보기는 J48 이 「한 그룹에 여러 필드를
 * 순서대로」를 명시하므로 상한을 복사해 오지 않는다.
 *
 * ## 순서가 곧 데이터다 (J48)
 * *"drag and drop the field up or down in the list."* — 사용자가 드래그로 만든 순서가 저장 대상이다.
 * 그래서 이 서비스는 받은 목록을 **정렬하지도, 집합으로 만들지도 않는다.** `Set` 으로 한 번 거치거나
 * `sorted()` 를 끼우는 순간 「순서만 바꾼 저장」이 조용히 무시된다 — 사용자는 드래그가 저장되지 않는
 * 화면을 보게 된다.
 *
 * ## 권한 판정은 여기 없다
 * [com.bts.agileplanning.web.BoardDetailViewController] 가 actor 추출 → 보드 메타 조회(404) →
 * 권한 판정(403) 순서를 수행한 뒤 이 서비스를 부른다([BoardQuickFilterService] 와 같은 분담).
 *
 * @param repository 보드 설정 4탭의 영속 접근.
 */
@Service
class DetailViewSettingsService(
    private val repository: BoardSettingsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보드의 상세 보기 구성을 그룹별로 읽는다 (R7c).
     *
     * 리포지터리는 **구성이 없는 그룹의 키를 아예 주지 않는다.** 그 구멍을 응답까지 흘리면 모달과
     * 사이드패널이 각자 「키 부재」와 「빈 배열」을 나눠 다뤄야 하고, 한쪽만 처리하면 같은 이슈가
     * 여는 방식에 따라 다르게 보인다(R7c 가 막으려는 바로 그 증상). 그래서 여기서 4종을 채워
     * **항상 같은 모양**으로 내보낸다.
     *
     * @param boardId 대상 보드 UUID.
     * @return `fieldGroup → 필드 키 목록`. 키는 항상 [DETAIL_VIEW_FIELD_GROUPS] 4종이고 순서도 그대로다.
     */
    @Transactional(readOnly = true)
    fun findFields(boardId: UUID): Map<String, List<String>> = readNormalized(boardId)

    /**
     * 요청에 실린 그룹만 통째로 교체한다 (J47 · J48).
     *
     * **요청에 없는 그룹은 건드리지 않는다** — PATCH 의 부분 갱신 의미 그대로다. 드래그 한 번이
     * 한 그룹만 바꾸므로 나머지 3종을 함께 실어 보내라고 요구하면 클라이언트가 옛 값을 덮어쓴다.
     *
     * ★**검증을 전부 마친 뒤에 쓴다.** 돌면서 쓰다가 중간에 400 을 던지면 앞선 그룹은 이미 저장된
     * 채로 사용자는 실패 응답을 받는다 — 화면과 DB 가 갈린다. 한 트랜잭션이라 롤백되기는 하지만,
     * 롤백에 기대는 대신 쓰기 자체를 시작하지 않는 편이 의도가 드러난다.
     *
     * @param boardId 대상 보드 UUID.
     * @param groups `fieldGroup → 새 필드 키 목록`. 빈 목록은 그 그룹을 비운다(J48 의 Delete).
     * @return 교체 후 전체 구성([findFields] 와 같은 모양).
     * @throws DetailViewFieldGroupInvalidException 400 — 미지원 그룹이 하나라도 섞여 있을 때.
     */
    @Transactional
    fun replaceGroups(
        boardId: UUID,
        groups: Map<String, List<String>>,
    ): Map<String, List<String>> {
        val unsupported = groups.keys.filterNot { it in DETAIL_VIEW_FIELD_GROUPS }
        if (unsupported.isNotEmpty()) {
            log.info("상세 보기 필드 그룹 미지원 — boardId={}, groups={}", boardId, unsupported)
            throw DetailViewFieldGroupInvalidException()
        }

        groups.values.forEach { fieldKeys -> fieldKeys.forEach { requireStorableFieldKey(it) } }

        groups.forEach { (fieldGroup, fieldKeys) ->
            repository.replaceDetailViewFields(boardId, fieldGroup, fieldKeys)
        }
        return readNormalized(boardId)
    }

    /**
     * 저장된 구성을 읽어 그룹 4종이 항상 존재하는 모양으로 좁힌다.
     *
     * `associateWith` 라 결과는 [DETAIL_VIEW_FIELD_GROUPS] 의 **선언 순서**를 그대로 갖는다(J47).
     * 그룹 **안**의 순서는 손대지 않는다 — 리포지터리가 `position` 순으로 준 목록 그대로다.
     *
     * ## ★왜 [replaceGroups] 가 [findFields] 를 부르지 않고 이 private 함수를 부르나
     * 둘이 하는 일은 같지만 [findFields] 는 `@Transactional(readOnly = true)` 다. 같은 클래스 안에서
     * 부르면 Spring 프록시를 타지 않아 그 속성이 **무시된다**(self-invocation). 지금은 쓰기
     * 트랜잭션에 합류하는 것이 의도한 동작이라 결과가 우연히 맞지만, 「어노테이션이 붙어 있는데
     * 적용되지 않는」 호출을 남겨 두면 다음 사람이 그것을 근거로 삼는다. private 함수로 빼면
     * 프록시가 개입할 자리 자체가 없어져 그 착시가 생기지 않는다.
     */
    private fun readNormalized(boardId: UUID): Map<String, List<String>> {
        val stored = repository.findDetailViewFields(boardId)
        return DETAIL_VIEW_FIELD_GROUPS.associateWith { stored[it].orEmpty() }
    }

    /**
     * 필드 키가 **저장 가능한 모양**인지 확인한다.
     *
     * ★**카탈로그 검증은 일부러 하지 않는다.** 모르는 키를 그대로 살려 두는 것이
     * [readNormalized] 와 화면(`DetailViewPanel`·`IssueMetaPanel`)의 계약이다 —
     * 카탈로그에 없는 키는 화면이 **원문 그대로** 그린다. 여기서 거르면 그 계약이 깨진다.
     * 이 점이 형제 [CardLayoutSettingsService.requireSupportedFieldKey] 와 다르고,
     * 그쪽은 카드 3칸이라는 좁은 자리라 카탈로그를 강제할 수 있다.
     *
     * 그래서 **DB 가 받아 줄 수 없는 모양만** 막는다.
     *
     * @param fieldKey 요청 바디의 필드 키. **nullable 로 받는다** — Jackson 은 `["summary", null]`
     *   같은 배열 원소의 null 을 막지 못한다(선언 타입은 `String` 인데 런타임에 null 이 앉는다).
     *   여기서 400 으로 거두지 않으면 `field_key NOT NULL` 위반이 **500** 으로 나간다.
     *   형제 서비스가 KDoc 에 적어 둔 그 함정이고, 이 서비스에는 적용되지 않고 있었다(리뷰 C3).
     * @throws DetailViewFieldGroupInvalidException 400 — null · 공백뿐 · [MAX_FIELD_KEY_LENGTH] 초과.
     */
    private fun requireStorableFieldKey(fieldKey: String?) {
        if (fieldKey == null || fieldKey.isBlank()) {
            throw DetailViewFieldGroupInvalidException("필드 키는 비어 있을 수 없습니다.")
        }
        if (fieldKey.length > MAX_FIELD_KEY_LENGTH) {
            throw DetailViewFieldGroupInvalidException(
                "필드 키는 ${MAX_FIELD_KEY_LENGTH}자를 넘을 수 없습니다.",
            )
        }
    }
}
