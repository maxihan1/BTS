// 조립 앱 컨텍스트 로드 검증 — 9개 BC 전체 빈이 prod 프로파일로 하나의 컨텍스트에 결선·부팅되는지 확인

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

/**
 * 전체 조립 컨텍스트가 **prod 프로파일**로 로드되는지 확인한다.
 *
 * 조립 앱은 prod 산출물이다 — 권한 resolver 가 `@Profile("prod")`(실제) ↔ `@Profile("!prod")`(스텁)로
 * 배타 설계라, prod 프로파일이라야 실제 구현 하나만 활성화돼 충돌이 없다.
 *
 * [ProdAssemblyHttpTestBase]를 상속해 prod + RANDOM_PORT 셋업(issuer URI·RSA 키·slack signing secret
 * 런타임 주입)을 공유한다 — 상속하지 않으면 `webEnvironment` 차이로 컨텍스트 캐시가 갈라져 같은 JVM 에
 * 9-BC prod 컨텍스트가 두 벌 뜬다(베이스 KDoc "webEnvironment는 컨텍스트 캐시 키의 일부다" 참조).
 *
 * 사전 조건: dev postgres(`docker compose -f infra/docker-compose.dev.yml up -d postgres`, 5433) 기동.
 *
 * 통과 시 = 빈 충돌·설정 누락·cross-BC 미배선·보안 체인 순서 문제 없음.
 */
class BtsApplicationContextTest : ProdAssemblyHttpTestBase() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `조립 컨텍스트가 prod 프로파일로 로드된다`() {
        // contextLoads — 컨텍스트 초기화 자체가 검증. 실패 시 예외로 표면화.
    }

    @Test
    fun `automation 워커 빈이 조립 컨텍스트에 결선된다`() {
        // FullyQualifiedAnnotationBeanNameGenerator → 빈 이름 = FQN 클래스명.
        // automation 이 build 의존 + 스캔에 포함돼야만 이 빈이 존재한다.
        assertThat(context.containsBean("com.bts.automation.worker.AutomationExecutionWorker")).isTrue()
    }

    @Test
    fun `IssueSnapshotPort prod 어댑터가 조립 컨텍스트에 결선된다(fail-closed 회귀 가드, FR-AT-03)`() {
        // automation ActionExecutor 는 IssueSnapshotPort 를 non-null 로 요구한다(조건 게이트,
        // [[crossbc-resolver-nullable-fail-open]] 회귀 방지 — shared-kernel IssueSnapshotPort KDoc 참조).
        // issue-tracking 의 AutomationIssueSnapshotAdapter(@Profile("prod"))가 빠지면 이 빈이 사라져
        // ActionExecutor 주입이 NoSuchBeanDefinitionException 으로 컨텍스트 부팅 자체를 막아야 한다
        // (silent no-op 금지). 컨텍스트가 이미 로드에 성공했다는 사실 자체가 주입 충족을 증명하지만,
        // 이 단언은 그 충족이 "우연한 다른 빈"이 아니라 의도한 prod 어댑터임을 이름으로 고정한다.
        assertThat(
            context.containsBean("com.bts.issue.adapter.outbound.automation.AutomationIssueSnapshotAdapter"),
        ).isTrue()
    }

    @Test
    fun `IssueSecurityClassificationPort prod 어댑터가 조립 컨텍스트에 결선된다(fail-closed 회귀 가드, FR-SL-06)`() {
        // slack SlackChannelBroadcastWorker 는 IssueSecurityClassificationPort 를 non-null 로 요구한다.
        // issue-tracking 의 IssueSecurityClassificationAdapter(@Profile("prod"))가 빠지면 이 빈이 사라져
        // 워커 주입이 NoSuchBeanDefinitionException 으로 컨텍스트 부팅 자체를 막아야 한다(silent no-op 금지,
        // slack-integration 의 non-prod 스텁 AlwaysUnrestrictedIssueSecurityClassification 은
        // @Profile("!prod") 라 이 prod 조립 컨텍스트에는 등록되지 않는다).
        assertThat(
            context.containsBean("com.bts.issue.adapter.IssueSecurityClassificationAdapter"),
        ).isTrue()
    }

    @Test
    fun `SlackChannelBroadcastWorker 가 조립 컨텍스트에 결선된다(FR-SL-06 PR-B)`() {
        // 채널 브로드캐스트 큐(q_slack_channel_broadcasts) 폴링 워커. slack-integration 이 build 의존 +
        // 스캔에 포함돼야만 이 빈이 존재한다.
        assertThat(context.containsBean("com.bts.slack.worker.SlackChannelBroadcastWorker")).isTrue()
    }

    @Test
    fun `SlackChannelBroadcaster 가 조립 컨텍스트에 결선된다(FR-SL-06 PR-B)`() {
        // notification 이 프로젝트 활동 이벤트를 q_slack_channel_broadcasts 로 발행하는 컴포넌트.
        // notification 이 build 의존 + 스캔에 포함돼야만 이 빈이 존재한다.
        assertThat(context.containsBean("com.bts.notification.channel.SlackChannelBroadcaster")).isTrue()
    }

    @Test
    fun `ProjectMembershipWritePort prod 어댑터가 조립 컨텍스트에 결선된다(fail-closed 회귀 가드, FR-PM-10)`() {
        // ★ 위 두 포트 가드와 성격이 다르다 — 이 단언은 "이름 고정"이 아니라 유일한 탐지기다.
        // IssueSnapshotPort/IssueSecurityClassificationPort 는 소비자(ActionExecutor·브로드캐스트 워커)가
        // 이미 non-null 로 주입받아, 어댑터가 사라지면 컨텍스트 부팅이 먼저 깨진다 — 그 단언들은 충족 주체가
        // "우연한 다른 빈"이 아님을 덧붙여 고정할 뿐이다. 반면 ProjectMembershipWritePort 의 소비자
        // (issue-tracking 프로젝트 생성)는 PR-2 라 아직 없다(실측 — 참조는 어댑터 자신과 그 테스트뿐).
        // 즉 지금 이 어댑터가 스캔에서 빠져도 부팅은 멀쩡히 성공하고, 이 단언만이 그것을 잡는다.
        // PR-2 가 포트를 주입받는 순간 부팅 실패가 1차 탐지기로 합류하지만, 그때까지의 공백을 이 가드가 메운다.
        assertThat(
            context.containsBean("com.atlas.bts.identity.project.ProjectMembershipWriteAdapter"),
        ).isTrue()
    }

    @Test
    fun `SystemPermissionResolver prod 구현이 조립 컨텍스트에 결선된다(FR-PM-10)`() {
        // FR-PM-10 판정식(grant OR isSystemAdmin — ADR D-2)을 담은 prod 구현을 이름으로 고정한다.
        // 이 판정기 자체는 @Profile 이 없어(클래스 KDoc "@Profile 분리 없음") 전 프로파일에서 활성이다.
        //
        // 왜 "부팅이 곧 증명"으로 충분하지 않은가 — SystemPermissionResolver 는 **인터페이스**이고 구현이
        // 6개다(prod 2 + 테스트 스텁 4). 특히 issue-tracking 의 NonProdAllowSystemAdminResolver 는
        // @Component @Profile("!prod") 인 **AlwaysAllow 스텁**이다(isSystemAdmin 이 항상 true — 그 클래스
        // KDoc 이 "운영 환경 사용 시 권한 우회가 발생한다. @Profile(\"!prod\") 로 운영 차단이 보장된다" 라고
        // 명시). 그 프로파일이 뒤집히고 이 판정기가 스캔에서 빠지면, 주입은 스텁으로 **조용히 충족돼 부팅이
        // 성공**하고 hasGlobalPermission 이 전원 true 로 열린다 — CREATE_PROJECT 가 전 사용자에게 개방되는
        // fail-open 이다. 부팅 성공은 그 상태를 구분하지 못하고, 이 이름 고정만이 구분한다.
        //
        // ※ 이 판정기가 override 를 "갖고 있는가"는 T5(IdentityAccessSystemPermissionResolverGlobalPermissionTest)가
        //   행위로 증명한다. 여기서 리플렉션으로 declaringClass 를 보면 CGLIB 프록시에 헛fail 한다.
        //
        // ※ 협력자 GlobalPermissionGrantRepository 는 **일부러 따로 고정하지 않는다** — 실측으로 결정했다.
        //   @Repository 를 제거하면 이 판정기의 생성자 주입이
        //   "NoSuchBeanDefinitionException: No qualifying bean of type 'GlobalPermissionGrantRepository'" 로
        //   터져 **조립 컨텍스트 부팅 자체가 실패**한다(이 클래스 8건 전량 fail 로 관측). 부팅 실패가 이미
        //   더 큰 탐지기이므로 이름 고정을 하나 더 두면 중복 가드가 되어 유지비만 늘어난다.
        //   위 ProjectMembershipWriteAdapter 와 갈리는 지점이 바로 이것이다 — 그쪽은 소비자가 아직 없어
        //   빠져도 부팅이 성공하므로 이름 고정이 유일한 탐지기다.
        assertThat(
            context.containsBean("com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver"),
        ).isTrue()
    }
}
