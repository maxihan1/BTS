// 컬럼 WIP 제한의 부분 갱신 의도 — 「무변경」과 「해제(null)」를 타입으로 구분한다 (부채 177 R9)

package com.bts.agileplanning.application

/**
 * `PATCH /boards/{id}/columns/{columnId}` 의 `wipLimit` 부분 갱신 의도.
 *
 * ### 왜 `Int?` 로는 안 되는가
 *
 * `wipLimit` 은 **null 자체가 의미를 갖는다** — 지라의 *"To remove a constraint, clear the
 * existing value"*(J29)가 그것이다. 그래서 `Int?` 하나로는 세 경우를 두 개로만 표현할 수 있다.
 *
 * | 요청 | 뜻 | `Int?` 로 표현하면 |
 * |---|---|---|
 * | `{"name": "검수"}` | wipLimit **무변경** | `null` |
 * | `{"wipLimit": null}` | wipLimit **해제** | `null` ← **같은 값** |
 * | `{"wipLimit": 5}` | 5 로 설정 | `5` |
 *
 * 앞의 두 줄이 서버에서 같은 값이 되면 **이름만 바꿔도 WIP 제한이 조용히 해제된다.** 응답은 200 이고
 * 아무도 오류를 못 본다 — 사용자가 「보드가 이상해졌다」로 며칠 뒤 마주치는 종류의 결함이다.
 *
 * ### 왜 `JsonNullable` 을 그대로 안 쓰는가
 *
 * `JsonNullable` 은 HTTP 요청 바디의 **전송 여부**를 나타내는 웹 계층 타입이다. 서비스 시그니처에
 * 그것을 두면 애플리케이션 계층이 「JSON 에 필드가 있었나」를 알게 되고, 웹이 아닌 호출자(테스트 ·
 * 배치 · 이벤트 소비자)가 웹 타입을 만들어야 한다. 여기서는 **의도**만 넘긴다.
 *
 * `name` 쪽은 이 타입이 필요 없다 — 컬럼 이름은 해제할 수 없어(present-null 은 400) `String?` 의
 * null 이 「미전송」 하나만 뜻한다. `updateBoard` 가 같은 이유로 `String?` 를 쓴다.
 */
sealed interface WipLimitChange {
    /** 이 요청은 WIP 제한을 건드리지 않는다. 기존 값이 그대로 남는다. */
    data object Unchanged : WipLimitChange

    /**
     * WIP 제한을 [value] 로 설정한다.
     *
     * @property value 새 제한. **null 이면 해제**(무제한). 0 이하는 컨트롤러가 400 으로 거른다.
     */
    data class Set(val value: Int?) : WipLimitChange
}
