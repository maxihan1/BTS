// 「프로젝트를 찾을 수 없습니다」 안내 카드의 한국어 라벨 — 워크플로우 스킴·가져오기 두 화면 공용

/**
 * 프로젝트 부재(404) 안내 카드 문구.
 *
 * ## 왜 화면별 라벨 파일이 아니라 여기 있나
 * 이 문구를 쓰는 화면이 둘이다 — `projects.$projectKey.settings.workflow-scheme.tsx` 의
 * `ProjectNotFoundCard`(정의처)와 그 카드를 재사용하는
 * `projects.$projectKey.settings.import.tsx`. 한쪽 화면의 네임스페이스에 두면 다른 화면의
 * 코드·테스트가 **남의 네임스페이스를 뒤지게** 된다(가져오기 테스트가
 * `workflowSchemeLabels.assignment.*` 를 참조하던 상태). 그렇다고 사본을 만들면 두 문구가
 * 서로를 검사하지 않고 갈라진다 — 이 저장소가 반복해서 사고를 낸 양식이다.
 *
 * ★`workflowSchemeLabels.assignment.projectNotFoundTitle`/`projectNotFoundMessage` 는
 *   **삭제하지 않고 이 상수를 가리키는 참조로 남겼다.** 기존 E2E·단위 테스트가 그 이름으로
 *   셀렉터를 잡고 있어서다(`e2e/workflow-scheme-assignment.spec.ts:72` 등). 값은 하나다.
 */
export const projectNotFoundLabels = {
  /** 부재 안내 카드 CardTitle */
  title: '프로젝트를 찾을 수 없습니다',
  /** 부재 안내 카드 본문 */
  message: '이 프로젝트가 존재하지 않거나 삭제됐습니다. 주소를 확인해 주세요.',
} as const
