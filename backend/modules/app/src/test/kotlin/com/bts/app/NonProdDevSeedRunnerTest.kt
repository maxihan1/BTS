// 러너의 프로파일 단언(L2)과 실패 비대칭(D9)을 컨텍스트 없이 직접 검증

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment

/**
 * [NonProdDevSeedRunner] 의 두 결정을 **부팅 없이** 못 박는다.
 *
 * - **L2 런타임 단언** — 허용목록 밖 프로파일이면 예외를 던져 기동을 실패시킨다.
 *   조용히 `return` 하면 음성 봉인이 공허해진다(learning `negative-guard-needs-body-discriminator`).
 * - **D9 실패 비대칭** — 시드 자체가 터지면 **부팅은 계속**한다. dev 편의 기능이 부팅을 막으면
 *   #321 이 연 "비-prod 에서 켜진다" 를 되돌리는 회귀가 된다.
 *
 * ## 왜 부팅 테스트로는 부족한가
 * [NonProdDevSeedBootTest] / [ProdDevSeedAbsenceBootTest] 는 `@Profile` 이 실제로 걸리는지를 본다.
 * 그러나 **"프로파일이 뚫렸을 때 무슨 일이 일어나는가"** 는 빈이 없으면 코드가 아예 안 돌아 관측이 불가능하다.
 * 이 테스트가 그 사각을 덮는다 — 대역([DevSeeder])을 직접 넣어 러너만 떼어 돌린다.
 */
class NonProdDevSeedRunnerTest {
    /** 호출 횟수만 세는 대역. */
    private class RecordingSeeder : DevSeeder {
        var calls = 0

        override fun seed() {
            calls++
        }
    }

    private fun envOf(vararg profiles: String) =
        MockEnvironment().apply { if (profiles.isNotEmpty()) setActiveProfiles(*profiles) }

    @Test
    fun `무프로파일(default) 이면 시드를 1회 호출한다`() {
        val seeder = RecordingSeeder()

        NonProdDevSeedRunner(seeder, envOf()).run(DefaultApplicationArguments())

        assertThat(seeder.calls)
            .describedAs("조립 앱의 실제 기본 부팅은 무프로파일이다 — 여기서 안 돌면 아무 의미가 없다")
            .isEqualTo(1)
    }

    @Test
    fun `dev 프로파일이면 시드를 1회 호출한다`() {
        val seeder = RecordingSeeder()

        NonProdDevSeedRunner(seeder, envOf("dev")).run(DefaultApplicationArguments())

        assertThat(seeder.calls).isEqualTo(1)
    }

    @Test
    fun `prod 가 활성이면 예외로 기동을 실패시킨다`() {
        val seeder = RecordingSeeder()
        val runner = NonProdDevSeedRunner(seeder, envOf("prod"))

        assertThatThrownBy { runner.run(DefaultApplicationArguments()) }
            .describedAs("조용한 skip 이면 음성 봉인이 공허해진다")
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(seeder.calls).describedAs("단언 실패 시 시드는 호출되면 안 된다").isZero()
    }

    @Test
    fun `모르는 프로파일(staging) 이면 예외로 기동을 실패시킨다`() {
        // ★이슈 2 가드 — 부정(!prod) 이었다면 staging 은 그냥 통과했다.
        val seeder = RecordingSeeder()
        val runner = NonProdDevSeedRunner(seeder, envOf("staging"))

        assertThatThrownBy { runner.run(DefaultApplicationArguments()) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(seeder.calls).isZero()
    }

    @Test
    fun `시드가 터져도 부팅은 계속된다`() {
        // D9 비대칭 — 격리 위반은 기동 실패, 시드 실패는 로그만.
        val throwing = DevSeeder { throw IllegalStateException("시드 실패 시뮬레이션") }
        val runner = NonProdDevSeedRunner(throwing, envOf())

        assertThatCode { runner.run(DefaultApplicationArguments()) }
            .describedAs("dev 편의 기능이 부팅을 막으면 #321 이 연 비-prod 부팅을 되돌리는 회귀다")
            .doesNotThrowAnyException()
    }

    @Test
    fun `L1 표현식과 L2 허용목록이 같은 집합이다`() {
        // 두 층이 갈리면 "빈은 등록되는데 단언이 막는다" 같은 모순이 조용히 생긴다.
        val fromExpression = NonProdDevSeedRunner.PROFILE_EXPRESSION.split("|").map { it.trim() }.toSet()

        assertThat(fromExpression)
            .describedAs("L1(@Profile 표현식)과 L2(런타임 허용목록)는 항상 같은 집합이어야 한다")
            .isEqualTo(NonProdDevSeedRunner.ALLOWED_PROFILES)
    }
}
