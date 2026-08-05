// backlog-labels.ts 단위 테스트 — i18n 라벨 값 정합성 및 콜론 종결 금지 가드
import { describe, it, expect } from 'vitest'
import { backlogLabels } from './backlog-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 정적 문자열 값 — 콜론 종결 금지 검증 (글로벌 §5)
// ─────────────────────────────────────────────────────────────────────────────

/** 객체를 재귀적으로 순회하여 모든 string 리프 값을 수집한다. 함수는 건너뜀. */
function collectStringLeaves(obj: unknown, path = ''): Array<{ path: string; value: string }> {
  if (typeof obj === 'string') return [{ path, value: obj }]
  if (typeof obj === 'function') return []
  if (obj !== null && typeof obj === 'object') {
    return Object.entries(obj as Record<string, unknown>).flatMap(([k, v]) =>
      collectStringLeaves(v, path ? `${path}.${k}` : k),
    )
  }
  return []
}

describe('backlogLabels — 콜론 종결 금지 (글로벌 §5)', () => {
  const leaves = collectStringLeaves(backlogLabels)

  it('정적 문자열 라벨이 하나 이상 존재한다', () => {
    expect(leaves.length).toBeGreaterThan(0)
  })

  leaves.forEach(({ path, value }) => {
    it(`"${path}" 값이 콜론으로 끝나지 않는다`, () => {
      expect(value.trimEnd()).not.toMatch(/:$/)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 함수 동작 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogLabels — columnAriaLabel 함수', () => {
  it('columnAriaLabel("백로그", 5) → "백로그 칸, 5개 이슈"', () => {
    expect(backlogLabels.columnAriaLabel('백로그', 5)).toBe('백로그 칸, 5개 이슈')
  })

  it('columnAriaLabel("스프린트 1", 0) → "스프린트 1 칸, 0개 이슈"', () => {
    expect(backlogLabels.columnAriaLabel('스프린트 1', 0)).toBe('스프린트 1 칸, 0개 이슈')
  })

  it('columnAriaLabel 반환값이 콜론으로 끝나지 않는다', () => {
    expect(backlogLabels.columnAriaLabel('백로그', 3)).not.toMatch(/:$/)
  })
})

describe('backlogLabels — cardAriaLabel 함수', () => {
  it('cardAriaLabel("ATLAS-1", "이슈 제목") → "ATLAS-1 — 이슈 제목"', () => {
    expect(backlogLabels.cardAriaLabel('ATLAS-1', '이슈 제목')).toBe('ATLAS-1 — 이슈 제목')
  })

  it('cardAriaLabel 반환값이 콜론으로 끝나지 않는다', () => {
    expect(backlogLabels.cardAriaLabel('ATLAS-1', '이슈')).not.toMatch(/:$/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F15 — 신규 문구군 전수 계약
//
// ★왜 이 describe 가 있나.
// `backlog-labels.ts` 는 F15 의 **유일한 공유 자원**이다. 세로 스택(T6) · 시작 다이얼로그(T7) ·
// 완료 다이얼로그(T8) · 드래그 공지(T4) 가 전부 여기서 문구를 읽는다. 문구 하나가 비면 그 task 가
// 이 파일을 다시 열어야 하고, 같은 wave 의 두 task 가 같은 파일을 열면 add/add 충돌이 난다
// (plan §분해 원칙 1). 그래서 「나중에 필요한 문구」를 **먼저** 계약으로 고정한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogLabels — FR-UX-13 F15 신규 문구군 (T4·T6·T7·T8 이 소비)', () => {
  it('FR-2: 접기 토글 이름은 섹션 이름을 포함하고 접힘/펼침 상태에 따라 달라지지 않는다', () => {
    // C-9 — 섹션 A 는 접힘, B 는 펼침이 **동시에** 가능하므로 「같은 버튼의 다른 상태」 면제가
    // 성립하지 않는다. 이름은 고정하고 상태는 aria-expanded 가 말한다.
    expect(backlogLabels.collapseSection('스프린트 1')).toContain('스프린트 1')
    expect(backlogLabels.collapseSection('백로그')).not.toBe(
      backlogLabels.collapseSection('스프린트 1'),
    )
    expect(backlogLabels.collapseSection('백로그')).not.toMatch(/:$/)
  })

  it('FR-3: 시작 다이얼로그의 설명·필드 라벨 3종이 전부 있다', () => {
    const d = backlogLabels.startDialog
    expect(d.description).not.toBe('')
    expect(d.startDateLabel).toBe('시작일')
    expect(d.endDateLabel).toBe('종료일')
    expect(d.goalLabel).toBe('목표')
  })

  it('FR-4: 시작 실패가 네 갈래로 분리돼 서로 다른 문구를 갖는다', () => {
    // 원안 표는 「세 갈래」였고 start 409 를 나머지와 뭉뚱그렸다 — 처방이 정반대(다이얼로그를
    // 닫고 재시도를 주지 않는다)라 뭉치면 거짓말이 된다 (스펙 §리뷰 반영 FR-4).
    const d = backlogLabels.startDialog
    const branches = [d.patchFailed, d.patchConflict, d.startFailed, d.startConflict]
    expect(new Set(branches).size).toBe(4)
    for (const message of branches) expect(message).not.toBe('')
    expect(d.startConflict).toBe('이미 시작된 스프린트입니다.')
  })

  it('FR-3·FR-6: 진행 중 라벨이 트리거 이름을 부분 문자열로 포함한다 (같은 버튼의 다른 상태)', () => {
    expect(backlogLabels.startDialog.pending).toContain(backlogLabels.startSprint)
    expect(backlogLabels.completeDialog.pending).toContain(backlogLabels.completeSprint)
  })

  it('FR-5: 완료 다이얼로그의 요약·빈 상태·이관 대상 문구가 있다', () => {
    const d = backlogLabels.completeDialog
    expect(d.summary(3, 2)).toBe('완료 3건 · 미완료 2건')
    expect(d.noIssuesToMove).toBe('옮길 이슈가 없습니다.')
    expect(d.moveTargetLabel).not.toBe('')
    // 이관 대상 「백로그」 옵션은 섹션 이름과 **같은 문자열**이어야 한다 — 두 벌로 갈리면
    // 한쪽만 고쳐져 화면에서 서로 다른 말이 된다.
    expect(d.backlogOption).toBe(backlogLabels.backlogTitle)
  })

  it('FR-6: 행 상태·요약 alert·403 문구가 있다', () => {
    const d = backlogLabels.completeDialog
    expect(d.rowMoved).toBe('이관됨')
    expect(d.rowFailed).toBe('이관 실패')
    expect(d.moveFailedAlert(3, 1)).toContain('3')
    expect(d.moveFailedAlert(3, 1)).toContain('1')
    // C-15 — 이관은 UPDATE 권한이라 CREATE 만 있는 사용자는 전건 403 을 받는다.
    expect(d.moveForbidden).not.toBe('')
    expect(d.moveProgress(1, 3)).toContain('1')
  })

  it('FR-7·E15: 워크플로우 조회 실패 안내와 truncated 차단 문구가 있다', () => {
    expect(backlogLabels.completeDialog.workflowLoadFailed).not.toBe('')
    expect(backlogLabels.completeDialog.truncatedBlocked).not.toBe('')
  })

  it('FR-9: 드래그 공지가 전수 있고 내부 droppable id 접두를 노출하지 않는다', () => {
    const a = backlogLabels.announce
    const messages = [
      a.dragStart('ATLAS-2'),
      a.overSprint('스프린트 1'),
      a.overBacklog,
      a.outOfDropZone,
      a.cannotMoveHere,
      a.movedToSprint('스프린트 1'),
      a.movedToBacklog,
      a.reordered,
      a.noChange,
      a.cancelled,
      a.forbidden,
      a.instructions,
    ]
    for (const message of messages) {
      expect(message).not.toBe('')
      // 공지는 사용자 언어다 — `backlog:ATLAS-2` 같은 내부 id 가 새어 나가면 안 된다.
      expect(message).not.toMatch(/backlog:|sprint:|card:/)
      expect(message.trimEnd()).not.toMatch(/:$/)
    }
    expect(a.dragStart('ATLAS-2')).toContain('ATLAS-2')
  })

  it('FR-9 C-4: onDragEnd 는 다섯 갈래이며 noop-move 가 침묵하지 않는다', () => {
    // `resolveBacklogDropAction` 의 반환 kind 는 5종(noop · noop-move · rerank · assign · unassign)이다.
    // 원안이 4종만 적어 noop-move 를 빠뜨렸고, 빠뜨리면 스크린리더가 **침묵**하는데 테스트는 초록이다.
    const a = backlogLabels.announce
    const endBranches = [
      a.movedToSprint('스프린트 1'),
      a.movedToBacklog,
      a.reordered,
      a.noChange,
      a.cannotMoveHere,
    ]
    expect(new Set(endBranches).size).toBe(5)
  })

  it('FR-17: 권한 없음 공지가 「옮겼습니다」류와 구분된다', () => {
    const a = backlogLabels.announce
    expect(a.forbidden).not.toContain('옮겼습니다')
    expect(a.forbidden).not.toBe(a.movedToBacklog)
  })
})
