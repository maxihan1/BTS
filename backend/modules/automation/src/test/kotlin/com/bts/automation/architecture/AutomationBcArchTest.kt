// automation BC 아키텍처 격리 규칙 검증 — 다른 BC 직접 import 금지(cross-BC는 이벤트/shared-kernel 포트만) (FR-AT-01 Task 11)

package com.bts.automation.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * automation BC 아키텍처 규칙 검증 (FR-AT-01 Task 11, ADR 2026-07-10-fr-at-01-automation-triggers).
 *
 * automation BC는 다른 BC의 내부 패키지를 **직접 import 하지 않는다**. cross-BC 통신은 오직
 * (1) pgmq 이벤트(`q_automation_events` 구독·`q_automation_execution` 발행)와
 * (2) shared-kernel 포트(`com.bts.shared..` — 예: [com.bts.shared.permission.AutomationPermissionResolver])
 * 를 통해서만 이루어진다. 이 규칙이 ADR D2("import 금지 — 이벤트만")를 빌드 시점에 강제한다.
 *
 * ### 패키지 prefix
 * - issue-tracking: `com.bts.issue..`
 * - project-workflow: `com.bts.workflow..`
 * - identity-access: `com.atlas.bts.identity..` (다른 BC와 prefix 다름)
 * - notification: `com.bts.notification..` / slack: `com.bts.slack..` / search: `com.bts.search..`
 *   / agile-planning: `com.bts.agileplanning..`
 *
 * `com.bts.shared..`(shared-kernel 포트)는 허용 채널이므로 금지 대상이 아니다.
 */
class AutomationBcArchTest {
    /** automation BC 프로덕션 클래스 전체 (테스트 클래스 제외). */
    private val importedClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.bts.automation")
    }

    /** 룰 1 — issue-tracking 내부 패키지 직접 import 금지(이벤트 fan-out은 issue-tracking 쪽 코드). */
    @Test
    fun mustNotImportIssueTracking() {
        bcIsolationRule("com.bts.issue..", "issue-tracking").check(importedClasses)
    }

    /** 룰 2 — project-workflow 내부 패키지 직접 import 금지. */
    @Test
    fun mustNotImportProjectWorkflow() {
        bcIsolationRule("com.bts.workflow..", "project-workflow").check(importedClasses)
    }

    /** 룰 3 — identity-access 내부 패키지 직접 import 금지(권한은 shared-kernel resolver 포트로만). */
    @Test
    fun mustNotImportIdentityAccess() {
        bcIsolationRule("com.atlas.bts.identity..", "identity-access").check(importedClasses)
    }

    /** 룰 4 — 그 밖의 BC(notification/slack/search/agile-planning) 내부 패키지 직접 import 금지. */
    @Test
    fun mustNotImportOtherBoundedContexts() {
        listOf(
            "com.bts.notification.." to "notification",
            "com.bts.slack.." to "slack-integration",
            "com.bts.search.." to "search-export-import",
            "com.bts.agileplanning.." to "agile-planning",
        ).forEach { (pkg, bc) -> bcIsolationRule(pkg, bc).check(importedClasses) }
    }

    /**
     * automation(`com.bts.automation..`)이 [targetPackage]를 직접 import 하면 실패시키는 규칙.
     *
     * @param targetPackage 금지 대상 BC 내부 패키지(예: `com.bts.issue..`).
     * @param bcName fail 메시지용 BC 이름.
     */
    private fun bcIsolationRule(
        targetPackage: String,
        bcName: String,
    ) = noClasses()
        .that().resideInAPackage("com.bts.automation..")
        .should().dependOnClassesThat().resideInAnyPackage(targetPackage)
        .because("automation BC는 $bcName 을 직접 import 할 수 없다 — cross-BC는 이벤트/shared-kernel 포트만(ADR D2).")
        .allowEmptyShould(true)
}
