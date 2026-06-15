// ClamAV Testcontainers singleton 기반 클래스 — 실 clamd 연동 통합 테스트 공통 기반

package com.bts.issue.attachment

import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

/**
 * ClamAV Testcontainers singleton 기반 클래스.
 *
 * **JVM 단위 singleton 패턴** — [AttachmentMinioTestcontainersBase] 와 동일한 방식으로,
 * `@Container` 어노테이션 대신 companion object 에서 `.apply { start() }` 로 JVM 라이프사이클에
 * 컨테이너를 바인딩한다. JVM 종료 시 Ryuk 이 자동 정리한다.
 *
 * ## 컨테이너 설정
 * - 이미지: `clamav/clamav:latest`
 * - 노출 포트: 3310 (clamd INSTREAM 기본 포트)
 * - 환경변수 `CLAMAV_NO_FRESHCLAMD=true`: freshclamd 데몬을 비활성화해 번들 DB 로 기동한다.
 *   네트워크 접근 없이 EICAR 테스트 파일 탐지에 충분하며 기동 지연을 최소화한다.
 * - Wait 전략: 3310 포트 listen 대기 + startupTimeout 120 초 (clamd 초기 DB 로드가 느림).
 *
 * ## 사용 가이드
 * - 자식 클래스에 `@Testcontainers` 붙이지 않는다 (JVM singleton lifecycle 사용).
 * - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 선언됨 — 자식도 동일 적용.
 * - [clamavContainer] 의 동적 host/port 를 자식 테스트에서 사용한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// 상속 base — 자식이 companion 의 clamavContainer 를 사용한다. object 로 만들면 상속 불가(detekt 오탐).
@Suppress("UtilityClassWithPublicConstructor")
abstract class AttachmentClamavTestcontainersBase {
    companion object {
        private const val CLAMD_PORT = 3310
        private const val STARTUP_TIMEOUT_SECONDS = 120L

        /**
         * JVM 단위 singleton ClamAV container.
         *
         * `CLAMAV_NO_FRESHCLAMD=true` 로 freshclamd 를 비활성화해 번들 바이러스 DB 만으로 기동한다.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리.
         *
         * clamd 는 시작 시 DB 로드로 인해 포트 listen 까지 수십 초가 걸릴 수 있다.
         * startupTimeout 을 [STARTUP_TIMEOUT_SECONDS] 초로 넉넉히 설정한다.
         */
        @JvmStatic
        val clamavContainer: GenericContainer<*> =
            GenericContainer<Nothing>("clamav/clamav:latest")
                .apply {
                    withExposedPorts(CLAMD_PORT)
                    waitingFor(
                        Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofSeconds(STARTUP_TIMEOUT_SECONDS)),
                    )
                    addEnv("CLAMAV_NO_FRESHCLAMD", "true")
                    start()
                }
    }
}
