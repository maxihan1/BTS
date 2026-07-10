// BTS 배포 조립 진입점 — 8개 BC 를 하나의 ApplicationContext 로 통합 부팅하는 유일한 프로덕션 실행체

package com.bts.app

import com.atlas.bts.identity.IdentityAccessApplication
import com.bts.issue.IssueTrackingApplication
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator
import org.springframework.scheduling.annotation.EnableScheduling
import java.security.Security

/**
 * BTS 전체 조립 앱.
 *
 * ## 왜 이 클래스가 필요한가
 * 각 BC(identity-access·issue-tracking 등)는 자체 `@SpringBootApplication` 을 갖거나 라이브러리 모듈이라
 * 단독으로는 "제품 전체"가 부팅되지 않는다. 이 클래스가 `com.bts` + `com.atlas.bts` 전체를 컴포넌트 스캔해
 * 모든 BC 의 빈을 하나의 컨텍스트로 결선하는 유일한 배포 산출물이다.
 *
 * ## 기존 진입점 스캔 제외
 * `IdentityAccessApplication` · `IssueTrackingApplication` 은 그 자체가 `@SpringBootApplication`
 * (= `@SpringBootConfiguration`) 이다. 컴포넌트 스캔에 걸리면 "multiple @SpringBootConfiguration" 및
 * 중첩 auto-configuration 충돌을 일으키므로 [ComponentScan] excludeFilters 로 배제한다.
 *
 * ## 스케줄링 단일화
 * `@EnableScheduling` 은 이 진입점에서 한 번만 활성화한다. 각 BC 의 SchedulingConfiguration 은
 * `@EnableScheduling` 을 자체 보유하나, 스캔되어도 스케줄 post-processor 는 멱등이라 중복 무해.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(
    basePackages = ["com.bts", "com.atlas.bts"],
    // 여러 BC 가 같은 단순 클래스명(SchedulingConfiguration·JacksonNullableConfiguration 등)을 쓰면
    // 기본 이름 생성기는 빈 이름이 충돌한다. 패키지까지 포함한 FQN 을 빈 이름으로 써서 충돌을 원천 차단한다.
    nameGenerator = FullyQualifiedAnnotationBeanNameGenerator::class,
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [IdentityAccessApplication::class, IssueTrackingApplication::class],
        ),
        // 각 BC 의 SchedulingConfiguration(@EnableScheduling 만 담은 설정)은 단순 이름이 동일해 빈 이름 충돌.
        // 조립 진입점이 @EnableScheduling 을 전역 제공하므로 모두 제외한다. @Scheduled 메서드는 전역 스케줄러가 처리.
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["""com\.bts\.\w+\.SchedulingConfiguration"""],
        ),
    ],
)
class BtsApplication {
    companion object {
        init {
            // identity-access PemFileKeyProvider(@Profile("prod")) 가 JcaPEMKeyConverter.setProvider("BC") 로
            // BouncyCastle 를 참조하나, 운영 main 코드엔 등록이 없다(identity 테스트만 수동 등록 — identity 단독 배포
            // 부재로 잠복). 배포 산출물인 조립 앱이 진입점 클래스 로드 시점에 한 번 등록한다(부팅·@SpringBootTest 공통).
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<BtsApplication>(*args)
}
