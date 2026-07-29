// prod 조립 컨텍스트에 dev 시드 장치가 하나도 등록되지 않음을 실부팅으로 확인 (음성 봉인)

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

/**
 * prod 조립 부팅의 **음성 대조군** — dev 시드 장치가 prod 컨텍스트에 **존재하지 않음**을 확인한다.
 *
 * 양성 대조군은 [NonProdDevSeedBootTest] 다. 둘은 **서로의 대조군**이며 한쪽만 있으면 봉인이 절반만 닫힌다
 * (learning `seal-closes-only-half-by-default`). 양성만 있으면 "시드가 prod 에도 도는가" 를 아무도 안 묻고,
 * 음성만 있으면 "시드가 실제로 일하는가" 를 아무도 안 묻는다.
 *
 * ## 무엇을 단언하고, 무엇을 일부러 단언하지 않는가
 * **단언한다** — [NonProdDevSeedRunner] 빈이 **0개**. 이것이 prod 격리의 실제 기전(L1 `@Profile` 허용목록)이며,
 * M1 뮤테이션(허용목록을 넓히거나 부정으로 되돌림)이 곧바로 이 단언을 red 로 만든다.
 *
 * **단언하지 않는다** — `users` 행 수 0. 이 테스트는 [ProdAssemblyHttpTestBase] 를 통해 **공유 dev postgres**
 * 에 붙는데, 그 DB 에는 다른 경로로 들어간 선재 행이 있다(2026-07-29 실측 — `alice` 가 2026-07-10 부터 존재).
 * 공유 DB 에서 "0행" 을 단언하면 환경에 따라 깨지거나, 반대로 우연히 통과해 **공허**해진다.
 * 「시드가 실제로 행을 만든다」는 증명은 빈 DB 를 쓰는 양성 테스트의 몫이다.
 *
 * ## 왜 태그가 없나
 * [NonProdAssemblyBootTest] 계열과 달리 이 테스트는 prod 프로파일이라 기본 `test` 태스크에서 돈다
 * (`build.gradle.kts:114-118` 이 `nonprod-assembly` 태그만 분리한다). 즉 `backend-ci.yml:133` 의
 * 기존 스텝이 이미 이 테스트를 수집한다 — CI 신규 배선 불필요.
 */
class ProdDevSeedAbsenceBootTest : ProdAssemblyHttpTestBase() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `prod 조립 컨텍스트에 dev 시드 러너가 없다`() {
        val names = context.getBeanNamesForType(NonProdDevSeedRunner::class.java)

        assertThat(names.toList())
            .describedAs("prod 에 dev 시드 러너가 등록되면 알려진 비밀번호 계정이 운영에 생긴다")
            .isEmpty()
    }

    @Test
    fun `prod 조립 컨텍스트에 dev 시더가 없다`() {
        // 러너만 막고 시더 빈을 남겨두면, 다른 누군가 시더를 주입해 호출하는 경로가 열린다.
        // 두 빈 모두 prod 에 없어야 격리가 닫힌다.
        val names = context.getBeanNamesForType(NonProdDevSeeder::class.java)

        assertThat(names.toList())
            .describedAs("prod 에 시더 빈이 남으면 다른 컴포넌트가 주입해 호출할 수 있다")
            .isEmpty()
    }
}
