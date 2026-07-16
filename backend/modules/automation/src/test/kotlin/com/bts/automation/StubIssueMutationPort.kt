// automation 통합 테스트용 fail-safe IssueMutationPort stub — 호출 기록 + 실패 주입 토글 (FR-AT-02 Task 9)

package com.bts.automation

import com.bts.shared.issue.AddCommentCommand
import com.bts.shared.issue.AssignCommand
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.MutationResult
import com.bts.shared.issue.SetFieldCommand
import com.bts.shared.issue.SetFixVersionsCommand
import java.util.concurrent.CopyOnWriteArrayList

/**
 * automation 통합 테스트가 실제 issue-tracking prod 어댑터
 * ([com.bts.shared.issue.IssueMutationPort] 의 `@Profile("prod")` 구현) 없이도 [ActionExecutor] 를
 * 구동할 수 있게 하는 fail-safe [IssueMutationPort] stub (FR-AT-02 Task 9).
 *
 * automation BC 격리상 prod 구현(issue-tracking `AutomationIssueMutationAdapter`)은 automation 모듈의
 * 컴파일/테스트 클래스패스에 존재하지 않는다 — [ActionExecutor] 는 이 포트를 **non-null 생성자 주입**으로
 * 요구하므로([[crossbc-resolver-nullable-fail-open]] 회귀 방지), automation test-boot 컨텍스트에는 이
 * stub 이 대신 등록되어야 한다([StubAutomationPermissionResolver] 와 정확히 동형인 consumer-owns-stub
 * 패턴 — plan-eng-review E4).
 *
 * ## fail-safe 기본값
 * 기본 동작은 항상 성공(commands 를 기록만 하고 [MutationResult] 를 반환)한다 — 웹 계층/워커 슬라이스
 * 테스트가 이 stub 을 명시 설정하지 않아도 자동화 실행 경로가 예외 없이 통과한다(consumer 가 실패
 * 시나리오를 검증하려면 [failNextCallsWith] 로 명시 토글해야 한다).
 *
 * ## CGLIB/`@Transactional` 함정 회피 ([[test-fake-transactional-interface-cglib-npe]])
 * 순수 Kotlin 클래스이며 `@Transactional` 을 붙이지 않는다 — 인터페이스 구현체에 `@Transactional` 을
 * 붙이면 CGLIB 프록시가 Kotlin final getter 를 인터셉트하지 못해 NPE 로 이어질 수 있다.
 */
class StubIssueMutationPort : IssueMutationPort {
    /** [setField] 호출 기록(테스트 검증용, 호출 순서 보존). */
    val setFieldCalls: MutableList<SetFieldCommand> = CopyOnWriteArrayList()

    /** [assign] 호출 기록(테스트 검증용, 호출 순서 보존). */
    val assignCalls: MutableList<AssignCommand> = CopyOnWriteArrayList()

    /** [addComment] 호출 기록(테스트 검증용, 호출 순서 보존). */
    val addCommentCalls: MutableList<AddCommentCommand> = CopyOnWriteArrayList()

    /** [setFixVersions] 호출 기록(테스트 검증용, 호출 순서 보존). */
    val setFixVersionsCalls: MutableList<SetFixVersionsCommand> = CopyOnWriteArrayList()

    private var failure: RuntimeException? = null

    override fun setField(cmd: SetFieldCommand): MutationResult {
        setFieldCalls += cmd
        failure?.let { throw it }
        return successResult(cmd.issueKey, cmd.dryRun)
    }

    override fun assign(cmd: AssignCommand): MutationResult {
        assignCalls += cmd
        failure?.let { throw it }
        return successResult(cmd.issueKey, cmd.dryRun)
    }

    override fun addComment(cmd: AddCommentCommand): MutationResult {
        addCommentCalls += cmd
        failure?.let { throw it }
        return MutationResult(issueKey = cmd.issueKey, applied = !cmd.dryRun, version = null)
    }

    override fun setFixVersions(cmd: SetFixVersionsCommand): MutationResult {
        setFixVersionsCalls += cmd
        failure?.let { throw it }
        return successResult(cmd.issueKey, cmd.dryRun)
    }

    /** 이후 모든 호출이 [exception] 을 던지도록 설정한다(호출 기록은 예외 발생 전에 여전히 남는다). */
    fun failNextCallsWith(exception: RuntimeException) {
        failure = exception
    }

    /** 실패 주입과 호출 기록을 모두 초기화한다(테스트 격리용). */
    fun reset() {
        failure = null
        setFieldCalls.clear()
        assignCalls.clear()
        addCommentCalls.clear()
        setFixVersionsCalls.clear()
    }

    private fun successResult(
        issueKey: String,
        dryRun: Boolean,
    ): MutationResult {
        val version = if (dryRun) null else DEFAULT_VERSION
        return MutationResult(issueKey = issueKey, applied = !dryRun, version = version)
    }

    private companion object {
        const val DEFAULT_VERSION = 1L
    }
}
