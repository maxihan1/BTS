// 상태 노드의 Tailwind 크기 클래스와 lib 기하 상수가 같은 값인지 대조하는 판별식
//
// ★ **두 목록이 서로를 안 보면 조용히 갈라진다** — 이 저장소가 이름 붙인 지배 결함 양식이다.
//   `StatusNode.tsx` 는 노드 크기를 Tailwind 클래스(`min-h-11` …)로 적고,
//   `workflow-layout.ts` 는 같은 크기를 숫자 상수로 적는다. 그 숫자로 라벨이 겹치는지와
//   self-loop 고리가 이웃을 뚫는지를 판정하므로, 갈라지면 **판정이 없는 좌표계**로 셈하게 된다.
//   클래스를 `min-h-12` 로 바꿔도 모든 테스트가 초록인 채 고리만 노드보다 작아지는 식이다.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const NODE_SOURCE = 'apps/web/src/components/workflow/editor/StatusNode.tsx'
const LAYOUT_SOURCE = 'apps/web/src/lib/workflow-layout.ts'

/**
 * Tailwind 크기 단위 → px. 기본 스케일은 `0.25rem`(4px) 배수다.
 *
 * 이름에 든 숫자를 그대로 쓰지 않고 4를 곱한다 — `min-h-11` 은 11px 이 아니라 44px 이다.
 */
const TAILWIND_UNIT_PX = 4

/** 소스에서 `<접두>-<숫자>` 클래스를 찾아 px 로 돌려준다. */
function tailwindSizePx(source: string, prefix: string): number {
  const found = new RegExp(`\\b${prefix}-(\\d+)\\b`).exec(source)
  assert.ok(found !== null, `${NODE_SOURCE} 에 '${prefix}-<숫자>' 클래스가 없다`)
  return Number(found[1]) * TAILWIND_UNIT_PX
}

/** lib 에서 `export const <이름> = <숫자>` 를 찾아 값을 돌려준다. */
function layoutConstant(source: string, name: string): number {
  const found = new RegExp(`export const ${name} = (\\d+)`).exec(source)
  assert.ok(found !== null, `${LAYOUT_SOURCE} 에 '${name}' 상수가 없다`)
  return Number(found[1])
}

describe('상태 노드 기하 — Tailwind 클래스와 배치 상수 대조', () => {
  const node = readFileSync(NODE_SOURCE, 'utf8')
  const layout = readFileSync(LAYOUT_SOURCE, 'utf8')

  test('높이 — min-h-* 와 NODE_HEIGHT_PX 가 같다', () => {
    assert.equal(
      tailwindSizePx(node, 'min-h'),
      layoutConstant(layout, 'NODE_HEIGHT_PX'),
      '노드 높이가 갈렸다. self-loop 고리 반지름 상한이 이 값에서 나오므로 고리가 이웃 행을 삼킨다',
    )
  })

  test('최소 폭 — min-w-* 와 NODE_MIN_WIDTH_PX 가 같다', () => {
    assert.equal(
      tailwindSizePx(node, 'min-w'),
      layoutConstant(layout, 'NODE_MIN_WIDTH_PX'),
      '노드 최소 폭이 갈렸다. 간선 끝점이 폭에 달려 있어 라벨 겹침 판정이 어긋난다',
    )
  })

  test('최대 폭 — max-w-* 와 NODE_MAX_WIDTH_PX 가 같다', () => {
    assert.equal(
      tailwindSizePx(node, 'max-w'),
      layoutConstant(layout, 'NODE_MAX_WIDTH_PX'),
      '노드 최대 폭이 갈렸다. 이 값이 self-loop 고리의 가로 여유를 정한다',
    )
  })

  test('★고리 상한이 노드 높이 상수를 통해 온다 — 리터럴을 다시 적지 않았다', () => {
    // `TransitionEdge.tsx` 가 44 를 직접 적으면 세 번째 목록이 생겨 이 판별식이 못 본다.
    const edge = readFileSync('apps/web/src/components/workflow/editor/TransitionEdge.tsx', 'utf8')
    assert.match(
      edge,
      /const SELF_LOOP_MAX_RADIUS_PX = NODE_HEIGHT_PX/,
      'TransitionEdge 가 노드 높이를 상수로 받지 않고 숫자를 다시 적었다',
    )
  })
})
