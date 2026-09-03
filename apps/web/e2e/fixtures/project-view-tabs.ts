// 프로젝트 뷰 탭바 e2e 헬퍼 — 접힌 탭도 열어 준다 (Jira 패리티 J5 · 캠페인 PR ⑤)
import { expect, type Locator, type Page } from '@playwright/test'

/** 🔒 e2e 계약 문자열 — `navLabels.projectViewNav` 와 같은 값이어야 한다 */
const VIEW_NAV_NAME = '프로젝트 뷰 전환'

/** 🔒 오버플로 트리거 — `projectViewLabels.overflowTrigger` 와 같은 값이어야 한다 */
const MORE_TRIGGER_NAME = '더 보기'

/**
 * 프로젝트 뷰 전환 nav.
 *
 * 🛑 사이드바에 **같은 이름의 링크**가 있다(보드·백로그·타임라인·컴포넌트·버전 5종). 문서 전역
 *    조회는 strict mode 로 죽으므로 조회는 반드시 이 nav 로 좁힌다.
 */
export function projectViewNav(page: Page): Locator {
  return page.getByRole('navigation', { name: VIEW_NAV_NAME })
}

/**
 * 탭 링크를 돌려준다. 폭이 모자라 접혔으면 「더 보기」를 열어 그 안에서 찾는다.
 *
 * ### 왜 헬퍼가 필요한가 (위험 R1)
 * 탭이 9개라 창 폭·사이드바 폭·라벨 길이에 따라 뒤쪽이 접힌다. 스펙마다 「보이겠지」로 쓰면
 * 폭이 조금만 달라져도 무더기로 깨지고, 반대로 「항상 접혔겠지」로 써도 같은 일이 난다.
 * 이 헬퍼가 **폭 불변식에서 스펙을 떼어 놓는다.**
 *
 * ### 실측 (2026-09-04 · Playwright 기본 뷰포트 1280×720 · 사이드바 펼침)
 * `e2e/project-tabs-overflow.spec.ts` 가 매 실행마다 실측하고 결과를 단언한다 —
 * 여기 숫자를 적어 두면 화면이 바뀐 뒤에도 주석만 옛말로 남는다. 그래서 **숫자를 적지 않고
 * 판별식에 맡긴다.** 이 헬퍼는 「접혔든 아니든 눌린다」만 보장한다.
 *
 * 🛑 `exact: true` 필수. Playwright 의 `getByRole` 은 기본이 부분 일치라
 *    `보드` 가 같은 nav 의 `대시보드` 를, `백로그` 가 보드 화면 빈 상태의 `백로그로 이동` 을
 *    함께 잡는다(`i18n/project-view-labels.ts` 조회 규약 ②·③).
 *
 * @param page Playwright 페이지
 * @param label 탭 라벨 — `projectViewLabels` 의 값과 정확히 같아야 한다
 * @returns 클릭 가능한 탭 링크 locator
 */
export async function openTabIfOverflowed(page: Page, label: string): Promise<Locator> {
  const nav = projectViewNav(page)
  await expect(nav).toBeVisible()

  const direct = nav.getByRole('link', { name: label, exact: true })
  if ((await direct.count()) > 0) return direct

  // 접혀 있다 — 트리거는 접힌 탭이 있을 때만 렌더된다(`use-tab-overflow.shouldRenderMore`).
  const more = nav.getByRole('button', { name: MORE_TRIGGER_NAME, exact: true })
  await expect(more).toBeVisible()
  await more.click()

  // 팝오버는 nav **안**으로 포털된다 — 그래서 같은 스코프에서 다시 찾을 수 있다.
  const collapsed = nav.getByRole('link', { name: label, exact: true })
  await expect(collapsed).toBeVisible()
  return collapsed
}

/** 탭을 눌러 이동한다 — 접혀 있으면 열고 누른다 */
export async function clickProjectViewTab(page: Page, label: string): Promise<void> {
  const tab = await openTabIfOverflowed(page, label)
  await tab.click()
}

/**
 * 사이드바 프로젝트 트리의 **리포트** 링크로 이동한다.
 *
 * ### 왜 이 헬퍼가 탭 헬퍼 옆에 있는가
 * 리포트 3종(벨로시티·누적 흐름도·사이클/리드 타임)은 **탭이 아니다.** 예전에는 백로그 화면의
 * 인라인 nav 에 섞여 있었는데, 탭바가 정본 9탭으로 통합되면서 그 자리가 없어졌다.
 * 남은 UI 경로는 사이드바 트리의 `리포트` 그룹 하나뿐이라, 「리포트로 어떻게 가는가」의 답이
 * 여기 있어야 탭 헬퍼를 찾은 사람이 같이 본다.
 *
 * 🛑 `page.goto` 로 대체하지 않는다 — SPA 내부 이동이라야 MSW store 가 리셋되지 않는다.
 *
 * ⚠️ 캠페인 PR ⑨ 가 이 하위 목록을 보드 목록으로 갈아치운다. 그때 이 헬퍼도 함께 바뀐다.
 *
 * @param page Playwright 페이지
 * @param projectName 사이드바에 보이는 프로젝트 **이름**(키가 아니다)
 * @param label 리포트 링크 라벨
 */
export async function openProjectReportFromSidebar(
  page: Page,
  projectName: string,
  label: string,
): Promise<Locator> {
  const tree = page.getByRole('navigation', { name: '프로젝트', exact: true })
  await expect(tree).toBeVisible()

  const projectToggle = tree.getByRole('button', { name: `${projectName} 하위 메뉴`, exact: true })
  if ((await projectToggle.getAttribute('aria-expanded')) !== 'true') await projectToggle.click()
  await expect(projectToggle).toHaveAttribute('aria-expanded', 'true')

  const reportsToggle = tree.getByRole('button', { name: '리포트', exact: true })
  if ((await reportsToggle.getAttribute('aria-expanded')) !== 'true') await reportsToggle.click()
  await expect(reportsToggle).toHaveAttribute('aria-expanded', 'true')

  const link = tree.getByRole('link', { name: label, exact: true })
  await expect(link).toBeVisible()
  return link
}
