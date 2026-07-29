// 러너가 의존하는 시드 동작의 이음새 — 구현은 NonProdDevSeeder 하나뿐

package com.bts.app

/**
 * dev 시드 동작의 이음새(seam).
 *
 * ## 왜 구현이 하나뿐인데 인터페이스인가
 * [NonProdDevSeedRunner] 의 두 결정 — L2 프로파일 단언(허용목록 밖이면 **기동 실패**)과
 * D9 실패 비대칭(시드가 터져도 **부팅 계속**) — 은 **시드가 실패하는 상황**을 만들어야 검증된다.
 *
 * 조립 모듈 테스트 클래스패스에는 모의(mock) 프레임워크가 없고(`build.gradle.kts` —
 * spring-boot-starter-test · archunit · testcontainers 뿐), [NonProdDevSeeder] 의 협력자 3종 중 2종은
 * final 클래스라 대역을 만들 수 없다. MockK 를 넣으면 "신규 의존성 0" 제약이 깨진다.
 *
 * 즉 이 인터페이스는 **추상화를 위한 추상화가 아니라** 그 제약 아래에서 D9 를 검증하기 위한 이음새다.
 * 대안으로 [NonProdDevSeeder] 를 `open` 으로 두는 방법도 있으나, 하위 클래스가 생성자 인자를 실제로
 * 만들어야 해서 final 협력자 2종에 다시 막힌다. "유일" 하지는 않지만 가장 값싼 길이다.
 *
 * 부팅 테스트는 `@Profile` 이 걸리는지만 볼 수 있고, **"프로파일이 뚫렸을 때 무슨 일이 일어나는가"** 는
 * 빈이 없으면 코드가 아예 돌지 않아 관측 자체가 불가능하다.
 */
fun interface DevSeeder {
    /** 로그인 가능한 최소 상태를 보장한다. 멱등이어야 한다. */
    fun seed()
}
