// 계약 스냅샷 정규화기가 보존해야 할 축(숫자 정수/실수, null vs 빈문자열)을 조립 부팅 없이 못박는 테스트

package com.bts.app.contract

import com.bts.app.contract.ContractSnapshotCanonicalizer.canonicalNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ContractSnapshotCanonicalizer] 의 **타입 보존 축**을 직접 검증한다.
 *
 * 조립 스냅샷 테스트([WorkflowSchemeContractSnapshotTest])는 실 응답에 나타나는 형태만 지나가므로,
 * 실 응답에 없는 분기(실수·빈 문자열)는 그쪽에서 영원히 검증되지 않는다. 여기서 합성 노드로 각
 * 분기를 못박아, 정규화가 계약 축을 뭉개는 회귀를 조립 부팅 없이 잡는다.
 */
class ContractSnapshotCanonicalizerTest {
    private val mapper = ObjectMapper()

    private fun canonicalJson(raw: String): String = canonicalNode(mapper.readTree(raw)).toString()

    // ── 숫자 축 — Long → Double 변경이 스냅샷 diff 를 만들어야 한다 ────────────────────

    @Test
    fun `정수와 실수는 서로 다른 표준값으로 정규화된다`() {
        // 백엔드 DTO 필드가 Long 에서 Double 로 바뀌면 JSON 이 `1` 에서 `1.0` 이 된다.
        // 둘이 같은 값으로 뭉개지면 스냅샷은 바이트 동일이라 그 타입 변경을 영영 못 잡는다.
        val integral = canonicalJson("""{"workflowSchemeId":1}""")
        val fractional = canonicalJson("""{"workflowSchemeId":1.0}""")

        assertThat(integral)
            .`as`("정수 필드가 실수로 바뀌었는데 정규화 결과가 같다 — 스냅샷이 Long→Double 을 못 잡는다")
            .isNotEqualTo(fractional)
    }

    @Test
    fun `정수는 크기와 무관하게 같은 표준값이 된다 (환경 의존성 차단)`() {
        // id 는 BIGSERIAL 이라 실행 환경마다 값이 다르고 Int 범위를 넘길 수 있다.
        // INT/LONG 을 갈랐다면 여기서 스냅샷이 흔들린다 — 정규화의 존재 이유가 무너진다.
        assertThat(canonicalJson("""{"id":1}"""))
            .isEqualTo(canonicalJson("""{"id":9999999999}"""))
    }

    @Test
    fun `실수는 값과 무관하게 같은 표준값이 된다`() {
        assertThat(canonicalJson("""{"ratio":0.25}"""))
            .isEqualTo(canonicalJson("""{"ratio":1234.75}"""))
    }

    @Test
    fun `숫자와 문자열 숫자는 구분된다`() {
        assertThat(canonicalJson("""{"id":1}"""))
            .`as`("숫자 1 과 문자열 \"1\" 이 같은 값이 되면 타입 변경을 못 잡는다")
            .isNotEqualTo(canonicalJson("""{"id":"1"}"""))
    }

    // ── null 축 — null 과 빈 문자열이 구분되어야 한다 ─────────────────────────────────

    @Test
    fun `null 과 빈 문자열은 서로 다른 값으로 정규화된다`() {
        // description 은 DB 가 NULL 과 '' 를 다른 값으로 취급한다(V201, NOT NULL 없음).
        // 백엔드가 null 을 '' 로 바꾸는 회귀를 스냅샷이 잡으려면 둘이 뭉개지면 안 된다.
        assertThat(canonicalJson("""{"description":null}"""))
            .`as`("null 과 빈 문자열이 같은 값으로 뭉개지면 null→'' 변질을 스냅샷이 못 잡는다")
            .isNotEqualTo(canonicalJson("""{"description":""}"""))
    }

    @Test
    fun `null 은 null 로 보존된다`() {
        assertThat(canonicalJson("""{"description":null}""")).isEqualTo("""{"description":null}""")
    }

    // ── 비-공허 가드 — 위 분기들이 실제 계약 파일에서도 살아 있는지 ───────────────────────

    /**
     * 실제 스냅샷 파일에 정수 leaf 와 null leaf 가 **실제로 존재**하는지 확인한다.
     *
     * 위 테스트들은 합성 노드를 쓰므로, 계약 파일이 텅 비거나 해당 축이 사라져도 초록이다.
     * 대상 집합이 비지 않았음을 여기서 못박아 가드가 공허해지는 것을 막는다.
     */
    @Test
    fun `계약 스냅샷에 정수 leaf 와 null leaf 가 실제로 존재한다`() {
        val text = Files.readString(snapshotPath())
        val root = mapper.readTree(text)

        val integrals = mutableListOf<String>()
        val nulls = mutableListOf<String>()
        collectLeaves(root, "", integrals, nulls)

        assertThat(integrals).`as`("계약 스냅샷에 정수 leaf 가 없다 — 숫자 축 가드가 공허하다").isNotEmpty()
        assertThat(nulls).`as`("계약 스냅샷에 null leaf 가 없다 — nullability 축 가드가 공허하다").isNotEmpty()
        // 정규화가 살아 있다면 파일의 모든 숫자는 표준값 둘 중 하나여야 한다.
        assertThat(text).doesNotContain("\"id\" : 0")
    }

    private fun collectLeaves(
        node: com.fasterxml.jackson.databind.JsonNode,
        path: String,
        integrals: MutableList<String>,
        nulls: MutableList<String>,
    ) {
        when {
            node.isObject -> node.fieldNames().forEach { collectLeaves(node.get(it), "$path.$it", integrals, nulls) }
            node.isArray -> node.forEachIndexed { i, e -> collectLeaves(e, "$path[$i]", integrals, nulls) }
            node.isIntegralNumber -> integrals.add(path)
            node.isNull -> nulls.add(path)
            else -> Unit
        }
    }

    /** 백엔드 스냅샷 생성기와 동일한 판별식(`docs/` + `CLAUDE.md`)으로 repo 루트를 찾는다. */
    private fun snapshotPath(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("docs")) && Files.isRegularFile(dir.resolve("CLAUDE.md"))) {
                return dir.resolve("docs/contracts/workflow-schemes.snapshot.json")
            }
            dir = dir.parent
        }
        error("repo 루트를 찾지 못했다(docs/ + CLAUDE.md 기준).")
    }
}
