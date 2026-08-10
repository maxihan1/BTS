// 프로젝트 Import(가져오기) 화면의 한국어 라벨 정본 — 라우트 파일 한글 리터럴 금지
/**
 * 가져오기 화면 라벨.
 *
 * ## 왜 본문(message)은 여기 없나
 * CREATE 거부 카드의 **본문**은 서버가 403 으로 돌려줄 문장을 그대로 쓴다
 * (`api/imports.ts` 의 `importFailureMessage('IMPORT_ACCESS_DENIED')`).
 * 사전 신호와 사후 에러가 같은 문장이어야 사용자가 두 화면을 같은 사건으로 읽는다 —
 * 여기에 사본을 두면 서버 메시지가 바뀔 때 조용히 갈라진다.
 *
 * 그래서 이 파일이 소유하는 것은 **서버가 주지 않는 문자열**뿐이다.
 * 선례는 `workflow-scheme-labels.ts` 의 `assignment.forbiddenTitle`/`forbiddenMessage` 쌍이다.
 */
export const importLabels = {
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
