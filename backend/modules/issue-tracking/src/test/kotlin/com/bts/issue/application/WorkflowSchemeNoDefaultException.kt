// project-workflow BC WorkflowSchemeNoDefaultException 을 issue-tracking 테스트에서 공유하는 스텁 예외

package com.bts.issue.application

/**
 * project-workflow BC 의 WorkflowSchemeNoDefaultException 을 issue-tracking 테스트에서
 * BC 격리 위반 없이 시뮬레이션하기 위한 공유 스텁 예외.
 *
 * GREEN 구현체는 javaClass.simpleName == "WorkflowSchemeNoDefaultException" 으로 감지한다.
 * 이 클래스의 simpleName 이 동일하므로 단위 테스트에서 동일한 감지 경로가 활성화된다.
 */
internal class WorkflowSchemeNoDefaultException(schemeKey: String) :
    RuntimeException("No default mapping for: $schemeKey")
