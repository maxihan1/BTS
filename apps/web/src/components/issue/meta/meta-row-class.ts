// 이슈 상세 우측 메타 패널의 한 줄 · 라벨 클래스 — 라벨 왼쪽 · 값 오른쪽(Jira 「세부 사항」 실측)

/**
 * 한 줄 = 라벨 칸 7rem + 값 칸. 2026-10-28 Jira Cloud REA 실측(`docs/design/2026-10-28-jira-cloud-rea-gap.md`
 * JV-17) — Jira 오른쪽 「세부 사항」은 라벨 · 값이 한 줄로 서는 「읽는 표」이고 필드 하나가 약 70px 이다.
 * 종전 BTS 는 라벨 위 · 값 아래로 쌓아 약 89px, 900px 화면에 필드가 6개뿐이었다.
 */
export const META_ROW_CLASS =
  'grid grid-cols-[7rem_minmax(0,1fr)] items-center gap-x-3 px-3.5 py-2 border-b border-border last:border-b-0'

/** 필드 라벨 — Jira 14px 500 진한 색(JV-20). 종전 12px 옅은 색은 값보다 약해 훑기 어려웠다 */
export const META_LABEL_CLASS = 'text-sm font-medium text-foreground'

/**
 * 값 select — 평소에는 글자처럼(테두리 · 바탕 없음) 보이고 마우스를 올리면 바탕이 깔린다(JV-17
 * 「값은 테두리 없는 글자, 클릭하면 편집」). 넓은 화면은 Jira 밀도 32px, 좁은 화면은 터치 44px.
 */
export const META_SELECT_CLASS =
  'w-full cursor-pointer rounded-md border border-transparent bg-transparent px-2 text-sm lg:h-8 max-lg:min-h-[44px] ' +
  'hover:bg-(--bg-neutral-hover) focus:outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed'
