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
}
