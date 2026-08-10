// 프로젝트 Import(가져오기) 화면의 한국어 라벨 정본 — 라우트 파일 한글 리터럴 금지

/**
 * 가져오기 화면(`routes/projects.$projectKey.settings.import.tsx`)이 렌더하는 한국어 문자열.
 *
 * ## 이 파일이 소유하는 범위
 * 그 라우트 파일이 **직접 그리는 모든 사용자 노출 문자열**이다 — 페이지 제목 · 모드 토글
 * 라벨/도움말/aria-label · 로딩 문구 · 거부 카드 제목. 라우트에 한글 리터럴을 남기지 않는다.
 * (처음에는 거부 카드 제목 하나만 옮겼는데, 같은 파일에 리터럴 4곳이 그대로 남아 규칙이
 * 자기 파일에서 거짓이 됐다 — 2026-08-10 게이트2 리뷰 C4.)
 *
 * ## 여기 **없는** 두 종류
 * 1. **서버가 주는 문장.** CREATE 거부 카드의 **본문**은 서버가 403 으로 돌려줄 문장을 그대로
 *    쓴다(`api/imports.ts` 의 `importFailureMessage('IMPORT_ACCESS_DENIED')`). 사전 신호와
 *    사후 에러가 같은 문장이어야 사용자가 두 화면을 같은 사건으로 읽는다 — 여기에 사본을 두면
 *    서버 메시지가 바뀔 때 조용히 갈라진다.
 * 2. **다른 화면과 공유하는 문장.** 프로젝트 부재 카드(`ProjectNotFoundCard`)는 워크플로우 스킴
 *    화면과 공용이라 `project-not-found-labels.ts` 가 소유한다. 이 파일에 복사하지 말 것.
 *
 * 선례는 `workflow-scheme-labels.ts` 의 `assignment.*` 그룹이다.
 */
export const importLabels = {
  /** 페이지 h1 — E2E 셀렉터(`e2e/import.spec.ts:77` · `e2e/import-mapping.spec.ts:86`)가 이 문자열로 잡는다 */
  pageHeading: '가져오기(Import)',
  /** 모드 세그먼트 토글 컨테이너의 aria-label(`role="group"`) */
  modeToggleAriaLabel: 'Import 방식',
  /**
   * 모드별 토글 버튼 텍스트.
   *
   * 키는 라우트의 `ImportPageMode` 유니언과 1:1 이다. 모드를 늘리면 라우트의
   * `Record<ImportPageMode, string>` 대입에서 컴파일 에러가 나므로 두 목록이 조용히 갈라지지 않는다.
   */
  modeLabels: {
    simple: '바로 가져오기',
    mapping: '매핑하며 가져오기',
  },
  /** 토글 하단 한 줄 도움말 — 어느 모드가 어떤 입력에 맞는지 안내 (FR-IM-02 DR-3) */
  modeHelpText:
    '바로 가져오기 = canonical 컬럼·JSON 첨부 zip / 매핑하며 가져오기 = 임의 CSV 컬럼·작성자·값 매핑',
  /** CREATE 권한이 명시적으로 거부됐을 때 폼을 대체하는 안내 카드의 제목 */
  createDeniedTitle: '가져오기를 사용할 수 없습니다',
  /**
   * 존재·권한 두 쿼리가 정착하기 전에 보여 주는 로딩 문구.
   *
   * 빈 화면 금지 규칙(`DESIGN.md` §상태 3종)의 로딩 자리다. 형제 페이지
   * (`settings.workflow-scheme.tsx:92-98`)와 같은 문구를 쓴다.
   */
  gateLoading: '로딩 중...',
} as const
