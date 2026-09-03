// 편집기 라벨이 Playwright strict mode 를 깨지 않는지 대조하는 회귀 가드 (FR-WF-07 D8)
import { describe, it, expect } from 'vitest'
import { workflowEditorLabels } from '../workflow-editor-labels'

/**
 * 탭 이름은 `getByRole('tab', { name })` 로 잡히는데 **기본이 non-exact 부분 일치**다.
 * `e2e/workflow-editor.spec.ts:89` 가 `getByRole('tab', { name: '전환' })` 로 전환 탭을 고르므로,
 * 새 탭 이름이 `'전환'` 을 substring 으로 품는 순간 그 단언이 두 개를 잡아 strict mode 로 즉사한다.
 * 상태 탭도 같다.
 */
const EXISTING_TAB_NAMES = ['상태', '전환'] as const

describe('워크플로우 편집기 탭 라벨', () => {
  it('다이어그램 탭 이름이 정의돼 있다', () => {
    expect(workflowEditorLabels.editor.diagramTab).toBe('다이어그램')
  })

  it('다이어그램 탭 이름이 기존 탭 이름을 substring 으로 품지 않는다', () => {
    const diagramTab = workflowEditorLabels.editor.diagramTab
    for (const existing of EXISTING_TAB_NAMES) {
      expect(diagramTab).not.toContain(existing)
    }
  })

  it('기존 탭 이름도 다이어그램 탭 이름을 품지 않는다', () => {
    // 반대 방향도 막는다 — 기존 이름이 새 이름을 품으면 새 탭을 고르는 단언이 깨진다.
    const diagramTab = workflowEditorLabels.editor.diagramTab
    for (const existing of EXISTING_TAB_NAMES) {
      expect(existing).not.toContain(diagramTab)
    }
  })
})

describe('다이어그램 캔버스 라벨', () => {
  it('캔버스 aria-label 과 조작 라벨이 정의돼 있다', () => {
    const { diagram } = workflowEditorLabels
    expect(diagram.canvasLabel).toBeTruthy()
    expect(diagram.fitView).toBeTruthy()
    expect(diagram.empty).toBeTruthy()
    expect(diagram.globalPanel).toBeTruthy()
    expect(diagram.initialNode).toBeTruthy()
    expect(diagram.lockedHint).toBeTruthy()
  })

  it('빈 상태 문구가 상태 패널의 것을 돌려 쓰지 않는다', () => {
    // 「상태가 하나도 없다」와 「캔버스에 그릴 것이 없다」는 다른 사실이다.
    // 같은 문자열을 쓰면 사용자가 어느 탭에 있는지 모른 채 같은 말을 두 번 본다.
    expect(workflowEditorLabels.diagram.empty).not.toBe(workflowEditorLabels.statusPanel.empty)
  })
})
