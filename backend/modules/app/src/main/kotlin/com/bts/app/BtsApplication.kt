// BTS 배포 조립 진입점 — 9개 BC 를 하나의 ApplicationContext 로 통합 부팅하는 유일한 프로덕션 실행체

package com.bts.app

import com.atlas.bts.identity.IdentityAccessApplication
import com.bts.issue.IssueTrackingApplication
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.component.adapter.AlwaysAllowComponentPermissionResolver
import com.bts.issue.customfield.adapter.AlwaysAllowCustomFieldPermissionResolver
import com.bts.issue.project.adapter.NonProdAllowSystemAdminResolver
import com.bts.issue.template.adapter.AlwaysAllowTemplatePermissionResolver
import com.bts.issue.version.adapter.AlwaysAllowVersionPermissionResolver
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
 *
 * ## 비-prod 중복 스텁 배제 — 왜 필요하고, 왜 8개 중 6개인가
 * 각 BC 의 `@Profile("!prod")` 스텁은 *"identity-access 와 issue-tracking 은 각자 독립
 * `@SpringBootApplication` 이라 컨텍스트가 분리돼 충돌하지 않는다"* 는 전제로 설계됐다
 * (`DevAllowIssuePermissionResolver` KDoc). **이 조립 모듈이 생기면서 그 전제가 깨졌다** —
 * `com.bts` + `com.atlas.bts` 를 한 컨텍스트로 스캔하니 두 벌이 같이 등록돼
 * `NoUniqueBeanDefinitionException` 으로 **비-prod 에서만** 부팅이 죽는다(prod 는 `@Profile("prod")`
 * 실구현만 살아 정상). 그래서 조립 계층에서만 한쪽을 배제한다 — 각 BC 소스는 불변이다.
 *
 * **규칙 한 줄. 조립 컨텍스트에서 권한 리졸버의 출처는 언제나 identity-access 다.**
 * prod 실구현이 전부 identity-access 에 있고, `SystemPermissionResolver` 는 실구현이 identity-access ·
 * 스텁이 issue-tracking 이라 선택지가 없다. 6종 모두 같은 규칙 한 줄로 설명된다.
 *
 * ★ **`FilterType.REGEX` 를 쓰지 않는다.** 정규식은 클래스가 개명·이동하면 매칭이 조용히 풀려
 * 중복이 되살아난다. `ASSIGNABLE_TYPE` + 실제 import 라야 개명 시 **컴파일 에러**로 드러난다.
 *
 * ★ **배제 금지 2종.** issue-tracking 의 `AlwaysAllow*`/`NonProdAllow*` 는 **8개**지만 중복은 **6개**다.
 * `AlwaysAllowFieldPermissionResolver`(`FieldPermissionResolver`) 와
 * `AlwaysAllowIssueSecurityDirectory`(`IssueSecurityDirectory`) 는 identity-access 짝이
 * `@Profile("prod")` 뿐이라 **자기 타입의 유일한 비-prod 구현**이다. 패턴으로 싸잡아 배제하면
 * 곧바로 새 "빈 부재" 가 된다 — 봉합이 새 결함을 만드는 양식.
 * `NonProdAssemblyBootTest` 의 `AssemblyPortContract.MUST_NOT_EXCLUDE` 가 그 실수를 잡는다.
 *
 * ★ **`SystemPermissionResolver` 는 동작이 바뀐다.** 스텁(`isSystemAdmin` 항상 true)이 빠지므로
 * 비-prod 조립도 `system_role_assignments`/`global_permission_grants` 를 **실제로 조회**해 판정한다.
 * ADR `2026-06-04-system-admin-role` 의 *"모든 프로파일에서 실제 판정한다"* 를 조립에서 실현한 것이다
 * (2026-07-29 Maxi 결정 D5). 시드가 없으면 프로젝트 생성 등이 막히는데, 그것은 별건으로 추적한다.
 *
 * 반대 방향(비-prod 에서 빈이 **0개**였던 포트 3종)의 봉합은 `NonProdAssemblyPortConfig` 가 맡는다.
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
        // ── 비-prod 조립에서 중복되는 issue-tracking 스텁 6종 (아래 KDoc "왜 8개 중 6개인가" 참조) ──
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [
                AlwaysAllowIssuePermissionResolver::class,
                AlwaysAllowComponentPermissionResolver::class,
                AlwaysAllowCustomFieldPermissionResolver::class,
                AlwaysAllowTemplatePermissionResolver::class,
                AlwaysAllowVersionPermissionResolver::class,
                NonProdAllowSystemAdminResolver::class,
            ],
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
