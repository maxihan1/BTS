// 생성 경로 단위 테스트가 최초(INITIAL) 전환 계획을 규칙 없이 통과시키는 stub (WF-05-3)

package com.bts.issue.application

import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionRequest
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowTransitionPort
import io.mockk.every

/**
 * 생성 요청([TransitionRequest.creation])으로 오는 [WorkflowTransitionPort.plan] 을 「규칙 없음 · 요청한 시작 상태 그대로」로 답한다.
 *
 * `createIssue` · `cloneIssue` 가 insert 전에 최초 전환 계획을 받으므로 strict mock 이면 그 호출에서 터진다.
 * 생성 요청만 잡으므로 같은 mock 의 일반 전환 stub 과 섞이지 않는다.
 */
internal fun WorkflowTransitionPort.passesCreation(): WorkflowTransitionPort =
    apply {
        every { plan(match { it.creation }) } answers {
            TransitionResult.Success(TransitionPlan(firstArg<TransitionRequest>().toStateKey, emptyList(), emptyList()))
        }
    }
