// 소유를 늘 「전역」으로 답하는 테스트용 스코프 결정기 — 픽스처가 전부 전역 시드인 테스트 공용 (FR-WF-08)

package com.bts.workflow

import com.bts.shared.permission.WorkflowScope
import com.bts.workflow.scheme.application.WorkflowOwnershipScopeResolver
import io.mockk.every
import io.mockk.mockk

/**
 * DB 조회 없이 전역을 답하는 [WorkflowOwnershipScopeResolver] 스텁.
 *
 * 이 스텁을 쓰는 테스트들은 **워크플로우 편집·발행 동작**을 재지 소유 판정을 재지 않는다.
 * 그 테스트들이 쓰는 워크플로우는 전부 전역 시드(`project_id IS NULL`)라, 전역을 답하는 것이
 * 픽스처의 실제 상태와 같다.
 *
 * ★[WorkflowOwnershipScopeResolver.ofProjectKey] 만은 **진짜 동작을 그대로** 흉내 낸다.
 * 그쪽은 DB 를 보지 않는 순수 변환이라 거짓말할 이유가 없고, 여기서 전역으로 눌러 버리면
 * 「프로젝트 키를 지목했는데도 전역으로 판정된다」는 가짜 그린이 생긴다.
 *
 * 소유별 판정 자체는 `WorkflowOwnershipScopeResolverTest` 와
 * `IdentityAccessWorkflowDefinitionPermissionResolverTest` 가 전수로 잰다.
 */
fun globalOnlyOwnershipScope(): WorkflowOwnershipScopeResolver =
    mockk<WorkflowOwnershipScopeResolver>().also { stub ->
        every { stub.ofWorkflow(any()) } returns WorkflowScope.Global
        every { stub.ofScheme(any()) } returns WorkflowScope.Global
        every { stub.ofProjectId(any()) } returns WorkflowScope.Global
        every { stub.ofProjectKey(any()) } answers {
            firstArg<String?>()?.let { WorkflowScope.Project(it) } ?: WorkflowScope.Global
        }
    }
