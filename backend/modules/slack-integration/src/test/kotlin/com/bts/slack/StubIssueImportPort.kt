// slack-integration 통합 테스트용 settable IssueImportPort stub — 다음 결과 시드 + 넘어온 커맨드 캡처 (FR-SL-04 Task 9)

package com.bts.slack

import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueImportResult
import org.springframework.stereotype.Component

/**
 * 통합 테스트가 `/atlas create` 결과를 명시 시드하고 넘어온 커맨드를 캡처하는 [IssueImportPort] stub (FR-SL-04 Task 9).
 *
 * cross-BC 이슈 생성 쓰기 포트는 prod 에서 issue-tracking `IssueImportAdapter` 가 제공하나 slack test-boot
 * 컨텍스트에는 실 구현이 없다. Task 6 이 추가한 `SlashCommandHandlers(@Component)` 가 생성자에서 non-null
 * [IssueImportPort] 를 요구하므로, 실 어댑터가 없으면 slack 모듈의 모든 `@SpringBootTest` 컨텍스트 로드가
 * `NoSuchBeanDefinitionException` 으로 깨진다. 이 stub 이 그 공백을 메운다.
 *
 * ## `@Component` — 컴포넌트 스캔으로 전역 등록 (기존 [StubIssueUnfurlPort] 와 다른 등록 경로)
 * [StubIssueUnfurlPort]/[StubUserLookupPort] 등은 [SlackTestcontainersConfig] `@Bean` 으로 등록되지만,
 * 이 stub 은 `@Component` 로 [SlackIntegrationTestBootApplication] 컴포넌트 스캔(`scanBasePackages =
 * ["com.bts.slack"]`, 테스트 소스의 `@Component` 까지 포함)에 직접 잡힌다. 두 등록 경로 모두 유효하며
 * (slack 모듈의 모든 `@SpringBootTest` 가 [SlackTestcontainersConfig] 를 `@Import` 하고, 부트 클래스가
 * 테스트 패키지를 스캔하므로), `@Component` 는 별도 `@Bean` 선언 없이 [SlashCommandHandlers] 의 non-null
 * [IssueImportPort] 의존을 모든 test-boot 컨텍스트에서 충족한다. slack 모듈에 [IssueImportPort] 구현체가
 * 이 stub 하나뿐이라 `@Primary` 는 불필요하다.
 *
 * ## settable — 다음 결과 시드 + 커맨드 캡처
 * [nextResult] 를 테스트가 성공([IssueImportResult.Success]) 또는 실패([IssueImportResult.Failure])로
 * 미리 정하고, [importIssue] 는 넘어온 [IssueImportCommand] 를 [lastCommand] 에 캡처한 뒤 [nextResult] 를
 * 돌려준다. 테스트는 캡처된 커맨드의 projectKey/requesterUserId/summary 가 매핑 해석 결과와 일치하는지
 * 검증한다. 기본값은 어댑터 부재를 나타내는 fail-closed 실패라, 테스트가 명시 시드하지 않으면 생성이
 * 성공으로 위장하지 않는다(실 포트 default 와 동일 방향).
 */
@Component
class StubIssueImportPort : IssueImportPort {
    /** [importIssue] 가 돌려줄 결과. 테스트가 시나리오별로 시드한다(기본값 = fail-closed 어댑터 부재). */
    @Volatile
    var nextResult: IssueImportResult = defaultResult()

    /** [importIssue] 에 마지막으로 넘어온 커맨드. 미호출이면 null. 테스트가 인자 정합을 검증한다. */
    @Volatile
    var lastCommand: IssueImportCommand? = null

    /**
     * 넘어온 [cmd] 를 [lastCommand] 에 캡처하고 시드된 [nextResult] 를 반환한다.
     *
     * `SlashCommandHandlers.handleCreate` 는 1-arg 오버로드만 호출하므로 그 오버로드를 override 한다
     * (첨부 소스 2-arg 는 slash 명령 경로에서 쓰이지 않는다).
     *
     * @param cmd 생성 커맨드. projectKey/requesterUserId/summary 를 테스트가 검증한다.
     * @return [nextResult].
     */
    override fun importIssue(cmd: IssueImportCommand): IssueImportResult {
        lastCommand = cmd
        return nextResult
    }

    /** 테스트 간 상태 격리를 위해 시드/캡처를 기본값으로 되돌린다. */
    fun reset() {
        nextResult = defaultResult()
        lastCommand = null
    }

    private companion object {
        /** 미시드 기본 결과 — 어댑터 부재 fail-closed 실패(실 포트 default 와 동일 방향, init/reset 공용). */
        fun defaultResult(): IssueImportResult = IssueImportResult.failure(IssueImportResult.ADAPTER_UNAVAILABLE)
    }
}
