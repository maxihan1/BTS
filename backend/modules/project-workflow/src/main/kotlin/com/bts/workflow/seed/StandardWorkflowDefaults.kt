// 표준 워크플로우의 YAML 기본값을 읽는 좁은 창구 — 「기본값으로 복원」이 시딩 전체에 묶이지 않게

package com.bts.workflow.seed

/**
 * 표준 워크플로우의 YAML 기본값을 읽는다. DB 에는 쓰지 않는다.
 *
 * ### 왜 [YamlSeedService] 를 직접 주입하지 않는가
 * 그 클래스는 시딩 전체(부팅 이벤트 · 카탈로그 보정 · 스킴 매핑)를 지고 있어 협력자가 5개다.
 * 기존 테스트들이 「`YamlSeedService` 는 Spring 없이 미동작」이라고 적고 회피할 만큼 조립이 무겁다
 * (`WorkflowResolverImplIntegrationTest` 헤더).
 *
 * 「기본값으로 복원」에 필요한 것은 그중 **읽기 한 가지**뿐이다. 창구를 좁히면 복원 경로가 시딩
 * 전체에 묶이지 않고, 그 경로를 검증하는 테스트도 스텁 하나로 선다.
 */
interface StandardWorkflowDefaults {
    /**
     * 표준 워크플로우의 YAML 원본을 검증된 DTO 로 돌려준다.
     *
     * @param key 워크플로우 키. 표준 4종이 아니면 null — 복원할 기본값이 없다는 뜻이다.
     * @throws IllegalStateException YAML 이 있는데 파싱·검증에 실패했을 때. 조용히 null 로 접지 않는다
     */
    fun loadStandardYaml(key: String): WorkflowYamlDto?

    /**
     * 이 키에 되돌릴 기본값이 있는가. 화면이 「기본값으로 복원」을 보여줄지 정할 때 쓴다.
     *
     * ### 왜 default 구현인가 — 판정과 실행이 같은 근거를 봐야 한다
     * [loadStandardYaml] 이 null 인지로 답한다. 구현이 표준 키 **목록 상수**와 대조하는 별도
     * 경로를 두면 그 목록과 실제 리소스가 서로를 검사하지 않는 두 번째 목록이 되고, 파일이 빠진
     * 키를 「복원 가능」이라고 답한 뒤 실행에서만 터진다. 여기서 한 번 정의해 두면 그 갈림이
     * 구조적으로 생기지 않는다.
     *
     * 표준 4종은 클래스패스 리소스라 이 호출은 싸다.
     */
    fun hasStandardDefault(key: String): Boolean = loadStandardYaml(key) != null
}
