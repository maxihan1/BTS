// BoardFilterQueryParser 순수 단위 테스트 — 쿼리 파라미터 파싱 + 400 에러 경로

package com.bts.agileplanning.web

import com.bts.shared.board.BoardCardFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * [BoardFilterQueryParser] 순수 단위 테스트 — Spring 컨텍스트 없음.
 *
 * 검증 시나리오.
 * - PARSE-1. assignee UUID → assigneeIds 에 포함
 * - PARSE-2. assignee "unassigned" → includeUnassigned=true
 * - PARSE-3. assignee UUID + "unassigned" 혼합 → 둘 다 반영
 * - PARSE-4. component UUID → componentIds 에 포함
 * - PARSE-5. label 문자열 → labels 에 포함
 * - PARSE-6. 모두 빈/blank → BoardCardFilter.EMPTY (EC2)
 * - PARSE-7. blank 값은 무시되고 유효한 값만 포함
 * - ERR-1. 비-UUID assignee → ResponseStatusException 400
 * - ERR-2. 비-UUID component → ResponseStatusException 400
 * - ERR-3. "UNASSIGNED" 대문자 → 비-UUID 로 간주하여 400
 *
 * [FR-UX-01] serialize/deserialize 인코딩 왕복 (BLOCKER-A 반영).
 * - SER-1. assignee/label/component 모두 채운 필터 → 정렬된 정규 쿼리스트링
 * - SER-2. 필드 정렬(assignee→unassigned→label→component) 확인
 * - SER-3. 빈 필터(EMPTY) → 빈 문자열
 * - SER-4. statusKeys 는 직렬화에서 제외된다 (board GET 미지원 범위)
 * - SER-5. 값에 공백 포함 시 URLEncoder 로 `+` 인코딩
 * - DESER-1. 정규 쿼리스트링 → parse 위임과 동일한 BoardCardFilter
 * - DESER-2. `+` 는 공백으로 디코딩된다 (application/x-www-form-urlencoded)
 * - DESER-3. 빈 문자열 → BoardCardFilter.EMPTY
 * - ROUNDTRIP-1. 공백 포함 라벨("my bug") serialize→deserialize 왕복 시 값 보존 (리뷰 B1 함정)
 * - ROUNDTRIP-2. unassigned 센티널 왕복 보존
 * - ROUNDTRIP-3. UUID/특수문자(라벨에 `&`, `=` 포함) 왕복 보존
 */
class BoardFilterQueryParserTest {
    // ── PARSE-1. assignee UUID ────────────────────────────────────────────────

    @Test
    fun `PARSE-1 assignee UUID 값은 assigneeIds 에 포함된다`() {
        val uuid = UUID.randomUUID()

        val filter =
            BoardFilterQueryParser.parse(
                assignee = listOf(uuid.toString()),
                label = emptyList(),
                component = emptyList(),
            )

        assertThat(filter.assigneeIds).containsExactly(uuid)
        assertThat(filter.includeUnassigned).isFalse()
        assertThat(filter.labels).isEmpty()
        assertThat(filter.componentIds).isEmpty()
    }

    // ── PARSE-2. assignee "unassigned" 센티널 ────────────────────────────────

    @Test
    fun `PARSE-2 assignee 에 "unassigned" 가 있으면 includeUnassigned=true`() {
        val filter =
            BoardFilterQueryParser.parse(
                assignee = listOf("unassigned"),
                label = emptyList(),
                component = emptyList(),
            )

        assertThat(filter.includeUnassigned).isTrue()
        assertThat(filter.assigneeIds).isEmpty()
    }

    // ── PARSE-3. assignee UUID + "unassigned" 혼합 ───────────────────────────

    @Test
    fun `PARSE-3 assignee 에 UUID 와 "unassigned" 혼합이면 둘 다 반영된다`() {
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()

        val filter =
            BoardFilterQueryParser.parse(
                assignee = listOf(uuid1.toString(), "unassigned", uuid2.toString()),
                label = emptyList(),
                component = emptyList(),
            )

        assertThat(filter.assigneeIds).containsExactlyInAnyOrder(uuid1, uuid2)
        assertThat(filter.includeUnassigned).isTrue()
    }

    // ── PARSE-4. component UUID ───────────────────────────────────────────────

    @Test
    fun `PARSE-4 component UUID 값은 componentIds 에 포함된다`() {
        val uuid = UUID.randomUUID()

        val filter =
            BoardFilterQueryParser.parse(
                assignee = emptyList(),
                label = emptyList(),
                component = listOf(uuid.toString()),
            )

        assertThat(filter.componentIds).containsExactly(uuid)
    }

    // ── PARSE-5. label 문자열 ─────────────────────────────────────────────────

    @Test
    fun `PARSE-5 label 문자열은 그대로 labels 에 포함된다`() {
        val filter =
            BoardFilterQueryParser.parse(
                assignee = emptyList(),
                label = listOf("bug", "enhancement"),
                component = emptyList(),
            )

        assertThat(filter.labels).containsExactly("bug", "enhancement")
    }

    // ── PARSE-6. 모두 빈/blank → EMPTY (EC2) ─────────────────────────────────

    @Test
    fun `PARSE-6 모든 파라미터가 비어 있으면 BoardCardFilter_EMPTY 를 반환한다 (EC2)`() {
        val filter =
            BoardFilterQueryParser.parse(
                assignee = emptyList(),
                label = emptyList(),
                component = emptyList(),
            )

        assertThat(filter).isEqualTo(BoardCardFilter.EMPTY)
        assertThat(filter.isEmpty()).isTrue()
    }

    // ── PARSE-7. blank 값 무시 ────────────────────────────────────────────────

    @Test
    fun `PARSE-7 blank 값은 무시되고 유효한 값만 포함된다`() {
        val uuid = UUID.randomUUID()

        val filter =
            BoardFilterQueryParser.parse(
                assignee = listOf("  ", uuid.toString(), ""),
                label = listOf("  ", "bug"),
                component = emptyList(),
            )

        assertThat(filter.assigneeIds).containsExactly(uuid)
        assertThat(filter.labels).containsExactly("bug")
    }

    // ── ERR-1. 비-UUID assignee → 400 ────────────────────────────────────────

    @Test
    fun `ERR-1 assignee 에 비-UUID 값이 있으면 ResponseStatusException 400 을 던진다`() {
        assertThatThrownBy {
            BoardFilterQueryParser.parse(
                assignee = listOf("not-a-uuid"),
                label = emptyList(),
                component = emptyList(),
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
            })
    }

    // ── ERR-2. 비-UUID component → 400 ───────────────────────────────────────

    @Test
    fun `ERR-2 component 에 비-UUID 값이 있으면 ResponseStatusException 400 을 던진다`() {
        assertThatThrownBy {
            BoardFilterQueryParser.parse(
                assignee = emptyList(),
                label = emptyList(),
                component = listOf("foo-bar"),
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
            })
    }

    // ── ERR-3. "UNASSIGNED" 대문자 → 400 (센티널은 소문자 "unassigned" 만) ─────

    @Test
    fun `ERR-3 대문자 "UNASSIGNED" 는 센티널이 아니라 비-UUID 로 간주하여 400 을 던진다`() {
        assertThatThrownBy {
            BoardFilterQueryParser.parse(
                assignee = listOf("UNASSIGNED"),
                label = emptyList(),
                component = emptyList(),
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
            })
    }

    // ── SER-1. 전체 필드 → 정렬된 정규 쿼리스트링 ───────────────────────────────

    @Test
    fun `SER-1 assignee unassigned label component 를 모두 채운 필터는 정렬된 정규 쿼리스트링으로 직렬화된다`() {
        val uuidA1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val uuidA2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val uuidC1 = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val filter =
            BoardCardFilter(
                assigneeIds = listOf(uuidA2, uuidA1),
                includeUnassigned = true,
                labels = listOf("urgent", "bug"),
                componentIds = listOf(uuidC1),
            )

        val serialized = BoardFilterQueryParser.serialize(filter)

        assertThat(serialized).isEqualTo(
            "assignee=00000000-0000-0000-0000-000000000001" +
                "&assignee=00000000-0000-0000-0000-000000000002" +
                "&assignee=unassigned" +
                "&label=bug" +
                "&label=urgent" +
                "&component=00000000-0000-0000-0000-000000000003",
        )
    }

    // ── SER-2. 필드 정렬(assignee→unassigned→label→component) ───────────────

    @Test
    fun `SER-2 assigneeIds 가 비어도 unassigned 는 assignee 그룹 안에서 label component 보다 앞선다`() {
        val uuidC1 = UUID.fromString("00000000-0000-0000-0000-000000000009")
        val filter =
            BoardCardFilter(
                includeUnassigned = true,
                labels = listOf("bug"),
                componentIds = listOf(uuidC1),
            )

        val serialized = BoardFilterQueryParser.serialize(filter)

        assertThat(serialized).isEqualTo(
            "assignee=unassigned&label=bug&component=00000000-0000-0000-0000-000000000009",
        )
    }

    // ── SER-3. 빈 필터 → 빈 문자열 ────────────────────────────────────────────

    @Test
    fun `SER-3 빈 필터는 빈 문자열로 직렬화된다`() {
        val serialized = BoardFilterQueryParser.serialize(BoardCardFilter.EMPTY)

        assertThat(serialized).isEmpty()
    }

    // ── SER-4. statusKeys 미직렬화(board GET 미지원 범위) ─────────────────────

    @Test
    fun `SER-4 statusKeys 는 직렬화에서 제외된다`() {
        val filter = BoardCardFilter(statusKeys = listOf("TODO", "IN_PROGRESS"))

        val serialized = BoardFilterQueryParser.serialize(filter)

        assertThat(serialized).isEmpty()
    }

    // ── SER-5. 공백 포함 값 → URLEncoder `+` 인코딩 ───────────────────────────

    @Test
    fun `SER-5 공백 포함 라벨은 URLEncoder 로 플러스 기호로 인코딩된다`() {
        val filter = BoardCardFilter(labels = listOf("my bug"))

        val serialized = BoardFilterQueryParser.serialize(filter)

        assertThat(serialized).isEqualTo("label=my+bug")
    }

    // ── DESER-1. 정규 쿼리스트링 → parse 위임과 동일한 결과 ────────────────────

    @Test
    fun `DESER-1 쿼리스트링을 디코딩해 parse 위임과 동일한 BoardCardFilter 를 반환한다`() {
        val uuid = UUID.randomUUID()
        val componentUuid = UUID.randomUUID()

        val filter =
            BoardFilterQueryParser.deserialize(
                "assignee=$uuid&label=bug&component=$componentUuid",
            )

        assertThat(filter.assigneeIds).containsExactlyInAnyOrder(uuid)
        assertThat(filter.labels).containsExactlyInAnyOrder("bug")
        assertThat(filter.componentIds).containsExactlyInAnyOrder(componentUuid)
        assertThat(filter.includeUnassigned).isFalse()
    }

    // ── DESER-2. `+` → 공백 디코딩 ─────────────────────────────────────────────

    @Test
    fun `DESER-2 플러스 기호는 공백으로 디코딩된다`() {
        val filter = BoardFilterQueryParser.deserialize("label=my+bug")

        assertThat(filter.labels).containsExactly("my bug")
    }

    // ── DESER-3. 빈 문자열 → EMPTY ─────────────────────────────────────────────

    @Test
    fun `DESER-3 빈 문자열은 BoardCardFilter_EMPTY 로 디코딩된다`() {
        val filter = BoardFilterQueryParser.deserialize("")

        assertThat(filter).isEqualTo(BoardCardFilter.EMPTY)
    }

    // ── ROUNDTRIP-1. 공백 포함 라벨 왕복 보존 (리뷰 B1 함정) ───────────────────

    @Test
    fun `ROUNDTRIP-1 공백 포함 라벨은 serialize 후 deserialize 해도 값이 보존된다`() {
        val filter = BoardCardFilter(labels = listOf("my bug"))

        val roundTripped = BoardFilterQueryParser.deserialize(BoardFilterQueryParser.serialize(filter))

        assertThat(roundTripped.labels).containsExactlyInAnyOrder("my bug")
    }

    // ── ROUNDTRIP-2. unassigned 센티널 왕복 보존 ────────────────────────────────

    @Test
    fun `ROUNDTRIP-2 unassigned 센티널은 serialize 후 deserialize 해도 보존된다`() {
        val uuid = UUID.randomUUID()
        val filter = BoardCardFilter(assigneeIds = listOf(uuid), includeUnassigned = true)

        val roundTripped = BoardFilterQueryParser.deserialize(BoardFilterQueryParser.serialize(filter))

        assertThat(roundTripped.assigneeIds).containsExactlyInAnyOrder(uuid)
        assertThat(roundTripped.includeUnassigned).isTrue()
    }

    // ── ROUNDTRIP-3. UUID/특수문자 라벨 왕복 보존 ───────────────────────────────

    @Test
    fun `ROUNDTRIP-3 특수문자(앤퍼샌드 등호)를 포함한 라벨과 UUID 는 왕복해도 값이 보존된다`() {
        val assigneeUuid = UUID.randomUUID()
        val componentUuid = UUID.randomUUID()
        val filter =
            BoardCardFilter(
                assigneeIds = listOf(assigneeUuid),
                labels = listOf("a&b=c"),
                componentIds = listOf(componentUuid),
            )

        val roundTripped = BoardFilterQueryParser.deserialize(BoardFilterQueryParser.serialize(filter))

        assertThat(roundTripped.assigneeIds).containsExactlyInAnyOrder(assigneeUuid)
        assertThat(roundTripped.labels).containsExactlyInAnyOrder("a&b=c")
        assertThat(roundTripped.componentIds).containsExactlyInAnyOrder(componentUuid)
    }
}
