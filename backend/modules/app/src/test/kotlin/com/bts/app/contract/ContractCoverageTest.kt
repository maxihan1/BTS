// 계약 스냅샷이 덮는 엔드포인트 수를 측정하고 후퇴를 차단하는 커버리지 판별식

package com.bts.app.contract

import com.bts.app.ProdAssemblyHttpTestBase
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **백엔드↔프론트 계약 커버리지 판별식.**
 *
 * ## 위험 모델
 * PR #317 이 `docs/contracts/workflow-schemes.snapshot.json` 으로 계약 스냅샷 기전을 세웠지만
 * 덮는 것은 **8 endpoint** 뿐이다. 나머지는 「MSW 가 MSW 와 맞는」 상태다 —
 * 프론트 테스트가 전량 초록이어도 백엔드와의 정합을 **보장하지 않는다.**
 *
 * 이 저장소는 그 기전으로 이미 두 번 데였다.
 * - #317 — 스킴 8 endpoint 중 정합은 1건뿐이었다(응답 7종 + 요청 1종 파손)
 * - 댓글 MSW — 에러 형태와 **판정 순서**가 백엔드와 갈려 있었다
 *
 * ## 이 테스트가 하는 일 — 전수 봉인이 아니라 **후퇴 차단**
 * 295 endpoint 를 한 번에 덮는 것은 endpoint 마다 시드·인증이 필요한 **프로그램**이지 한 번의 수정이
 * 아니다. 그래서 여기서는 **커버리지를 측정해 동결**한다.
 * - 스냅샷이 덮는 endpoint 수가 **줄면 실패**한다 (기전이 조용히 썩는 것을 막는다)
 * - 늘면 "동결값을 올려라" 라고 알려준다 (느슨해진 채 방치되는 것을 막는다)
 *
 * BC 키워드 커버리지·CI 매트릭스 커버리지와 **같은 형태**다 — 이 저장소의 지배적 결함 양식인
 * 「두 목록이 서로를 안 본다」에 대한 같은 처방이다.
 *
 * ## 왜 조립(:modules:app)인가
 * 실제 등록된 endpoint 전수는 9 BC 를 한 클래스패스에 올린 조립 컨텍스트에서만 셀 수 있다
 * (형제 [CorsAllowedMethodsCoverageTest] · [GlobalControllerAdviceSealTest] 와 같은 근거).
 */
class ContractCoverageTest : ProdAssemblyHttpTestBase() {
    @Autowired
    private lateinit var handlerMappings: List<RequestMappingInfoHandlerMapping>

    /**
     * 계약 스냅샷이 덮는 endpoint 집합.
     *
     * 스냅샷 키는 `"METHOD /path"` 형식이다(`$comment` 메타 키는 제외).
     * 디렉터리 전체를 훑으므로 **새 스냅샷 파일을 추가하면 자동으로 커버리지에 반영**된다 —
     * 파일 목록을 여기 하드코딩하면 그것이 또 하나의 어긋날 목록이 된다.
     */
    private fun coveredEndpoints(): Set<String> {
        val dir = CONTRACTS_DIR
        if (!Files.isDirectory(dir)) return emptySet()
        val mapper = ObjectMapper()
        return Files.list(dir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".snapshot.json") }
                .toList()
                .flatMap { file ->
                    mapper.readTree(Files.readString(file)).fieldNames().asSequence()
                        .filterNot { it.startsWith("$") }
                        .toList()
                }
                .toSet()
        }
    }

    /** 조립 전역에 실제로 등록된 `/api/v1` 하위 endpoint 수 (METHOD × 패턴 조합). */
    private fun registeredEndpointCount(): Int =
        handlerMappings
            .flatMap { it.handlerMethods.keys }
            .flatMap { info ->
                val ant = info.patternsCondition?.patterns.orEmpty()
                val pathPatterns = info.pathPatternsCondition?.patterns.orEmpty().map { it.patternString }
                val paths = (ant + pathPatterns).filter { it.startsWith("/api/v1/") }
                val methods = info.methodsCondition.methods.map { it.name }
                paths.flatMap { p -> methods.map { m -> "$m $p" } }
            }
            .toSet()
            .size

    @Test
    fun `판별식이 비어 있지 않다 - 스냅샷과 등록 매핑을 실제로 수집한다`() {
        // 하한이 없으면 수집이 0건이어도 아래 단언이 공허하게 통과한다.
        // 0 은 "없다" 가 아니라 "내 판별식이 틀렸다" 를 먼저 의심해야 한다.
        assertThat(coveredEndpoints())
            .describedAs("계약 스냅샷에서 endpoint 를 하나도 수집하지 못했다 — 경로나 파일 서식이 바뀌었다")
            .isNotEmpty()
        assertThat(registeredEndpointCount())
            .describedAs("조립 컨텍스트에서 /api/v1 매핑을 수집하지 못했다 — 수집 로직이 고장났다")
            .isGreaterThan(MIN_REGISTERED)
    }

    @Test
    fun `계약 스냅샷 커버리지가 후퇴하지 않는다`() {
        val covered = coveredEndpoints()

        assertThat(covered.size)
            .describedAs(
                "계약 스냅샷이 덮는 endpoint 가 %d 개로 동결값 %d 보다 줄었다.\n" +
                    "스냅샷 항목을 지웠다면 그 endpoint 는 다시 「MSW 가 MSW 와 맞는」 상태로 돌아간다.\n" +
                    "덮는 범위를 늘렸다면 COVERED_FLOOR 를 %d 로 올려라 — 안 올리면 이 판별식이 느슨해진 채 방치된다.\n" +
                    "현재 커버: %s",
                covered.size,
                COVERED_FLOOR,
                covered.size,
                covered.sorted(),
            )
            .isGreaterThanOrEqualTo(COVERED_FLOOR)
    }

    /**
     * 커버리지 갭을 **눈에 보이게** 남긴다.
     *
     * 실패시키지 않는 이유 — 295 endpoint 를 덮는 것은 프로그램이고, 실패로 두면 그 프로그램이
     * 끝날 때까지 CI 가 항상 빨갛다. 대신 **갭을 출력**해 규모가 잊히지 않게 한다.
     * 침묵하는 상한(silent cap)을 두지 않는다는 이 저장소의 원칙이다.
     */
    @Test
    fun `커버리지 갭을 보고한다`() {
        val covered = coveredEndpoints().size
        val registered = registeredEndpointCount()

        println(
            "계약 커버리지 — 스냅샷 $covered / 등록 $registered endpoint " +
                "(갭 ${registered - covered}). " +
                "갭에 해당하는 endpoint 는 프론트 모크와 백엔드가 어긋나도 아무 테스트가 반응하지 않는다.",
        )

        // 등록보다 많이 덮을 수는 없다 — 넘으면 스냅샷에 죽은 endpoint 가 남은 것이다.
        assertThat(covered)
            .describedAs("스냅샷이 등록 endpoint 보다 많다 — 삭제된 endpoint 의 스냅샷 항목이 남아 있다")
            .isLessThanOrEqualTo(registered)
    }

    private companion object {
        /**
         * 현재 계약 스냅샷이 덮는 endpoint 수.
         *
         * **줄이는 방향으로 갱신하지 마라.** 늘리는 방향으로만 갱신한다.
         *
         * 이력 — 2026-07-27 착수 시 **8**(워크플로우 스킴 전용) →
         * 같은 날 `core-read.snapshot.json` 추가로 **11**
         * (`whoami` · `issue-types` · `projects` — 소비 폭이 넓고 시드가 싼 축 우선).
         */
        const val COVERED_FLOOR = 11

        /** 등록 endpoint 수집이 고장나지 않았음을 확인하는 하한 (실측 300+). */
        const val MIN_REGISTERED = 100

        val CONTRACTS_DIR: Path = repoRoot().resolve("docs/contracts")

        /** 테스트 작업 디렉터리(backend/)에서 저장소 루트를 찾는다. */
        fun repoRoot(): Path {
            var dir = Paths.get("").toAbsolutePath()
            while (!Files.isDirectory(dir.resolve("docs/contracts")) && dir.parent != null) {
                dir = dir.parent
            }
            return dir
        }
    }
}
