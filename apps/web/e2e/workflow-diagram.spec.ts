// FR-WF-07 D8 E2E — 다이어그램 탭 (노드 배치 영속 · 끌어서 전환 생성)
//
// 관련 함정 메모리.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — MSW 브라우저 워커 사용
//   - msw-mutation-stateful-refetch: workflow-draft-handlers 는 인메모리 stateful —
//     저장 후 **클라이언트 사이드 재진입**으로 화면이 실제로 갱신되는지까지 본다
//   - ★ `page.goto()` 로 재진입하면 안 된다. full navigation 이 MSW 핸들러 모듈을 재평가해
//     목 저장소를 리셋한다. 첫 진입에만 쓴다.
//
// ★★ 셀렉터를 추측으로 정하지 않는다 (learnings.md:223).
//   mermaid 에서 `.node` 가 시작/종료 `[*]` 까지 세어 노드 수가 +2 어긋난 실측 사고가 있다.
//   여기 쓰는 `.react-flow__node[data-id=...]` 는 xyflow 가 노드마다 붙이는 것이고,
//   아래 첫 판정이 **그 전제 자체를 먼저 잰다** — 전제가 틀리면 시나리오보다 먼저 죽는다.
import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { loginAsSystemAdmin } from './fixtures/workflow-scheme-fixtures'
import { gotoAdminPage } from './fixtures/admin-hub'

/** 목 픽스처 기준 — software-default 는 5 상태 + 7 전환(INITIAL 1 + NORMAL 6) */
const TARGET_KEY = 'software-default'
const TARGET_NAME = '소프트웨어 개발 기본 워크플로우'

/** 목록에서 편집기로 들어간다 (클라이언트 사이드). */
async function openEditor(page: Page): Promise<void> {
  await page.getByRole('button', { name: `편집 ${TARGET_NAME}` }).click()
  await expect(page.getByRole('textbox', { name: '워크플로우 이름' })).toBeVisible()
}

/** 다이어그램 탭을 연다. 캔버스가 지연 로드라 렌더를 기다린다. */
async function openDiagramTab(page: Page): Promise<void> {
  await page.getByRole('tab', { name: '다이어그램' }).click()
  await expect(page.getByLabel('워크플로우 다이어그램 캔버스')).toBeVisible()
}

/** 노드 한 개. xyflow 는 노드마다 `data-id` 에 우리가 준 id(=상태 키)를 싣는다. */
function node(page: Page, stateKey: string) {
  return page.locator(`.react-flow__node[data-id="${stateKey}"]`)
}

test('D8-0 캔버스가 초안의 상태를 노드로 그린다 (셀렉터 전제 확인)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await openDiagramTab(page)

  // 이 판정이 아래 두 시나리오의 **전제**다. 셀렉터가 틀리면 여기서 먼저 죽어,
  // 「드래그가 안 먹었다」와 「노드를 못 찾았다」를 혼동하지 않는다.
  await expect(node(page, 'open')).toBeVisible()
  await expect(node(page, 'in_progress')).toBeVisible()

  // 상태 5개 + 시작 노드 1개. 시작 노드는 `[*]` 대응이라 상태가 아니다 —
  // 그것을 상태로 세면 mermaid 에서 겪은 +2 어긋남이 여기서 재발한다.
  await expect(page.locator('.react-flow__node')).toHaveCount(6)

  // ★ 간선 축도 함께 고정한다. 이것이 없으면 `buildGraph` 의 간선 매핑이 통째로 깨져
  //   간선이 0개로 렌더돼도 red 가 안 난다 — 게이트 2 리뷰가 그 공백을 지적했다.
  //   픽스처 전환 7건이고 GLOBAL 은 0건이라(GLOBAL 은 간선을 안 만든다) 7이 맞다.
  await expect(page.locator('.react-flow__edge')).toHaveCount(7)
})

test('D8-0b 간선을 고르면 그 전환의 편집이 열린다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await openDiagramTab(page)

  // 간선 클릭 → onEditTransition 배선. 이 판정이 없으면 handleEdgeClick 이 죽어도 조용하다.
  await page.locator('.react-flow__edge').first().click()

  await expect(page.getByRole('dialog', { name: '전환 수정' })).toBeVisible()
})

test('D8-1 노드를 끌어 옮기고 저장하면 재진입해도 그 자리에 있다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await openDiagramTab(page)

  const target = node(page, 'open')
  const before = await target.boundingBox()
  expect(before).not.toBeNull()

  // 캔버스 좌표를 직접 밀어 준다. dragTo 는 대상 중심으로 가버려 「얼마나 옮겼는가」를 못 잰다.
  await page.mouse.move(before!.x + before!.width / 2, before!.y + before!.height / 2)
  await page.mouse.down()
  await page.mouse.move(before!.x + before!.width / 2 + 160, before!.y + before!.height / 2 + 80, { steps: 10 })
  await page.mouse.up()

  const afterDrag = await target.boundingBox()
  expect(afterDrag!.x).toBeGreaterThan(before!.x + 100)

  await page.getByRole('button', { name: '변경 사항 저장' }).click()
  await expect(page.getByText('저장됨')).toBeVisible()

  // ── 목록으로 나갔다 **클라이언트 사이드로** 다시 들어온다 ──
  await page.getByRole('button', { name: '목록으로' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '워크플로우 관리' })).toBeVisible()
  await openEditor(page)
  await openDiagramTab(page)

  // 좌표가 초안에 저장돼 되읽혔다면 자동 배치 자리로 돌아가지 않는다.
  const reopened = await node(page, 'open').boundingBox()
  expect(reopened!.x).toBeGreaterThan(before!.x + 100)
})

test('D8-2 핸들을 끌어 전환을 만들면 전환 탭에도 나타난다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await openDiagramTab(page)

  const source = node(page, 'done')
  const target = node(page, 'open')
  await source.hover()

  // hover 하면 핸들이 드러난다(Jira J4). 출발 핸들에서 **도착 핸들로** 끈다.
  //
  // ★ 노드 중앙에 떨어뜨리면 안 된다. xyflow 는 드롭 지점이 대상 핸들의 `connectionRadius`
  //   (기본 20px) 안에 있어야 연결을 성사시키는데, 노드 폭이 140px 를 넘어 중앙은 왼쪽 target
  //   핸들에서 70px 넘게 떨어진다. 처음에 중앙으로 썼다가 red 였다 — 연결이 안 된 것이지
  //   구현이 틀린 것이 아니었다.
  const sourceHandle = source.locator('.react-flow__handle.source')
  const targetHandle = target.locator('.react-flow__handle.target')
  const handleBox = await sourceHandle.boundingBox()
  const dropBox = await targetHandle.boundingBox()
  expect(handleBox).not.toBeNull()
  expect(dropBox).not.toBeNull()

  await page.mouse.move(handleBox!.x + handleBox!.width / 2, handleBox!.y + handleBox!.height / 2)
  await page.mouse.down()
  await page.mouse.move(dropBox!.x + dropBox!.width / 2, dropBox!.y + dropBox!.height / 2, { steps: 12 })
  await page.mouse.up()

  // ★ 이름으로 좁힌다. 이름 없이 `getByRole('dialog')` 로 잡으면 **어느 다이얼로그가 열렸는지**를
  //   판정하지 않게 되고, 그 공백에서 문서가 「전환 수정이 열린다」로 잘못 적혔다(게이트 2 리뷰 #6).
  //   캔버스에서 끌어 만드는 것은 생성이므로 「전환 만들기」다 — 새 이름을 만들지 않았다는 것이
  //   즉사 계약이고, 그것은 이 이름이 main 에 이미 있다는 뜻이지 「전환 수정」이라는 뜻이 아니다.
  const dialog = page.getByRole('dialog', { name: '전환 만들기' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('textbox', { name: '전환 이름' }).fill('다시 열기')
  await dialog.getByRole('button', { name: '저장' }).click()

  // 캔버스와 목록이 같은 초안을 본다는 증거 — 한쪽에서 만든 것이 다른 쪽에 나타난다.
  await page.getByRole('tab', { name: '전환' }).click()
  await expect(page.getByRole('list', { name: '전환 목록' })).toContainText('다시 열기')
})

test('D8-3 관리 진입점에서 들어와도 다이어그램 탭이 있다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await gotoAdminPage(page, '워크플로우 관리')
  await openEditor(page)

  // 탭이 셋이고 기본은 여전히 '상태' 다 — 기본 탭을 바꾸면 기존 E2E 가 깨진다.
  await expect(page.getByRole('tab')).toHaveCount(3)
  await expect(page.getByRole('list', { name: '편성된 상태 목록' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '다이어그램' })).toBeVisible()
})

/** 두 사각형이 실제로 포개지는가. 맞닿기만 한 것은 겹침이 아니다. */
function overlaps(a: NonNullable<Awaited<ReturnType<Locator['boundingBox']>>>, b: typeof a): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
}

test('D8-4 한 자리에 모이는 전환 이름 셋이 서로 겹치지 않는다 (부채 168)', async ({ page }) => {
  /*
   * ★ 이 판정은 단위 테스트가 **낼 수 없다.** `lib/workflow-layout.ts` 는 노드 좌표만 알고
   *   실제 라벨은 xyflow 가 주는 핸들 좌표에 그려지므로, lib 과 컴포넌트가 각각 초록이어도
   *   실제 캔버스에서 겹칠 수 있다. 그 사이를 재는 것은 여기뿐이다.
   *
   * 픽스처의 세 전환은 중점이 대수적으로 같은 한 점이다 — 카테고리 열 배치가
   * `open(0,0)` · `in_progress(260,0)` · `in_review(260,120)` · `closed(520,120)` 을 만들고
   * `520 = 2 × 260` 이라 그렇다. 눈확인(2026-09-03)에서 `Requ|Cancel|nges` 로 뭉갰다.
   */
  await loginAsSystemAdmin(page)
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await openDiagramTab(page)

  const names = ['Submit for Review', 'Request Changes', 'Cancel']
  const boxes = []
  for (const name of names) {
    const label = page.getByText(name, { exact: true })
    await expect(label).toBeVisible()
    const box = await label.boundingBox()
    expect(box, `'${name}' 라벨의 좌표를 못 읽었다`).not.toBeNull()
    boxes.push({ name, box: box! })
  }

  for (let i = 0; i < boxes.length; i += 1) {
    for (let j = i + 1; j < boxes.length; j += 1) {
      const left = boxes[i]!
      const right = boxes[j]!
      expect(
        overlaps(left.box, right.box),
        `'${left.name}' 과 '${right.name}' 라벨이 포개져 둘 다 못 읽는다`,
      ).toBe(false)
    }
  }
})

test('D8-5 노드를 고르면 화면이 그것을 알려 준다 (부채 169)', async ({ page }) => {
  /*
   * ★ 배선까지 재는 판정이다. `StatusNode` 단위 테스트는 `selected` 를 **직접 넘겨서** 재므로,
   *   캔버스가 그 프롭을 실제로 흘려보내는지는 못 본다. 눈확인 ⑧ 이 실패한 자리가 바로 거기다 —
   *   상태는 DOM 에 있었고 그리는 쪽이 없었다.
   */
  await loginAsSystemAdmin(page)
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await openDiagramTab(page)

  const target = node(page, 'open').locator('> div')
  await expect(target).not.toHaveClass(/ring-2/)

  await node(page, 'open').click()

  // 카테고리 색(bg-muted)은 그대로 남고 링만 더해진다 — 선택이 카테고리를 덮으면 상태를 못 읽는다
  await expect(target).toHaveClass(/ring-2/)
  await expect(target).toHaveClass(/bg-muted/)
})

test('D8-6 고른 노드를 끌어 놓아도 선택 표시가 남는다 (부채 169)', async ({ page }) => {
  /*
   * ★ 실측으로 잡은 회귀다. 초안이 바뀌면 캔버스가 노드 배열을 새로 만들어 `setLiveNodes` 로
   *   갈아끼우는데, 그 배열에는 `selected` 가 없다 — 드래그는 `onNodeDragStop → onMoveState`
   *   로 초안을 바꾸므로 **놓는 순간 링이 사라졌다.** 부채 169 가 적은 목적("여러 상태를
   *   오가며 편집할 때 지금 무엇을 고른 상태인지 알려 준다")이 편집을 시작하는 순간 무너진다.
   *
   * 단위 테스트는 이걸 못 잡는다 — `StatusNode` 에 `selected` 를 직접 넘겨서 재기 때문이다.
   */
  await loginAsSystemAdmin(page)
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await openDiagramTab(page)

  const wrapper = node(page, 'open')
  const inner = wrapper.locator('> div')

  await wrapper.click()
  await expect(inner).toHaveClass(/ring-2/)

  const box = await wrapper.boundingBox()
  expect(box).not.toBeNull()
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2)
  await page.mouse.down()
  await page.mouse.move(box!.x + box!.width / 2 + 120, box!.y + box!.height / 2 + 60, { steps: 10 })
  await page.mouse.up()

  await expect(inner).toHaveClass(/ring-2/)
})
