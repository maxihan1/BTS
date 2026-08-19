// jOOQ Record 의 NOT NULL 컬럼 접근 — !! 대신 원인을 말하는 실패

package com.bts.workflow.repository

import org.jooq.Field
import org.jooq.Record

/**
 * 스키마상 `NOT NULL` 인 컬럼을 읽는다.
 *
 * ### 왜 `!!` 를 쓰지 않는가
 * `DEVELOPMENT.md §1.3-12` 가 「`!!` null assertion 금지, 명시적 null 체크」를 절대 규칙으로 둔다.
 * jOOQ 는 모든 컬럼 접근을 nullable 로 돌려주므로 `!!` 를 쓰기 쉬운 자리인데, 그러면 실제로
 * null 이 왔을 때 `NullPointerException` 만 남고 **무엇이 잘못됐는지가 사라진다**.
 *
 * ### null 이 오면 그것은 스키마 가정 위반이다
 * 이 저장소의 jOOQ 코드 생성 입력은 손으로 유지하는 미러(`db/codegen/init_codegen.sql`)다.
 * 미러가 마이그레이션과 갈라지면 「코드는 NOT NULL 로 아는데 실제 컬럼은 nullable」인 상태가 된다.
 * 그때 나오는 메시지가 `NPE` 가 아니라 **어느 컬럼인지와 무엇을 확인해야 하는지**여야 한다.
 *
 * ### 반환 타입이 `T & Any` 인 이유
 * jOOQ 생성물의 `Field<T>` 는 `T` 자체가 이미 nullable 로 잡힌다(`Field<String?>`).
 * 그냥 `T` 로 돌려주면 호출부가 여전히 `String?` 를 받아 아무것도 좁혀지지 않는다.
 * `T & Any`(definitely non-nullable)로 두어야 null 제거가 타입에 반영된다.
 *
 * @throws IllegalStateException 컬럼이 null 일 때 — 컬럼명과 확인 지점을 담는다
 */
fun <T> Record.required(field: Field<T>): T & Any =
    this[field]
        ?: error(
            "스키마상 NOT NULL 인 '${field.name}' 이 null 이다. " +
                "코드젠 미러(db/codegen/init_codegen.sql)와 마이그레이션이 갈라졌는지 확인할 것",
        )
