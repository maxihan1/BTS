// MinIO Testcontainers singleton 기반 클래스 — attachment 저장소 통합 테스트 공통 기반

package com.bts.issue.attachment

import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer

/**
 * MinIO Testcontainers singleton 기반 클래스.
 *
 * **JVM 단위 singleton 패턴** — [IssueTestcontainersBase] 와 동일한 방식으로,
 * `@Container` 어노테이션 대신 companion object 에서 `.apply { start() }` 로 JVM 라이프사이클에
 * 컨테이너를 바인딩한다. JVM 종료 시 Ryuk 이 자동 정리한다.
 *
 * ## 사용 가이드
 * - 자식 클래스에 `@Testcontainers` 붙이지 않는다 (JVM singleton lifecycle 사용).
 * - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 선언됨 — 자식도 동일 적용.
 * - [minioContainer] 의 동적 endpoint/credentail 를 자식 테스트에서 주입해 사용한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AttachmentMinioTestcontainersBase {
    companion object {
        /**
         * JVM 단위 singleton MinIO container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리.
         */
        @JvmStatic
        val minioContainer: MinIOContainer =
            MinIOContainer("minio/minio:latest")
                .apply { start() }
    }
}
