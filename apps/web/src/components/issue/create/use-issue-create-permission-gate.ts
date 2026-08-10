// 이슈 생성 폼의 CREATE 권한 게이트 — 선택된 프로젝트 기준, 「명시 거부」만 차단
import { useProjectPermissions } from '@/hooks/use-project-permissions'

/**
 * 선택된 프로젝트에 이슈 생성 권한이 **명시적으로 없는가**.
 *
 * ## 왜 폼에 두는가
 * 이슈 생성 진입 경로가 4개(상단바 버튼 · `/issues/new` 딥링크 · `c` 단축키 · 명령 팔레트)인데
 * 전부 `IssueCreateForm` 하나를 지난다. 게이트를 버튼에 두면 그중 3개가 그대로 열리고,
 * 게이트된 버튼으로 열어도 **폼 안에서 무권한 프로젝트로 갈아타기**가 남는다.
 *
 * ## ★판정식을 `!isLoading && CREATE === true` 로 쓰면 안 되는 이유
 * 그 형태는 `issues.index.tsx` 처럼 **버튼을 회색으로 만드는** 용도다. 여기서는 제출 차단 +
 * 「권한이 없습니다」 문구 노출이라 **사실 주장**이 되고, 미지를 거부로 읽으면 둘이 깨진다.
 * - 권한 조회가 아직 안 끝난 구간에 제출하면 **거짓 문구**를 본다. 프로젝트를 바꿀 때마다
 *   queryKey 가 바뀌어 그 구간이 **반복 재발**한다.
 * - `use-project-permissions.ts` 에 `retry:false` 도 에러 폴백도 없다. 500·네트워크 단절이면
 *   `data === undefined` 로 안착해 **CREATE 를 실제로 가진 사용자를 영구 차단**한다.
 *
 * 그래서 **명시적 거부만** 막고 나머지는 서버가 최종 판정하게 둔다(403 은 폼의
 * `resolveCreateErrorMessage` 가 권한 문구로 받는다). `use-projects.ts` KDoc 이 적어 둔
 * 「API 에러는 조용한 fail-safe」 관례와도 같은 방향이다.
 *
 * @param projectKey 폼에서 선택된 프로젝트 키. 빈 문자열이면 훅이 조회하지 않는다.
 * @returns 명시적으로 CREATE 가 거부된 경우에만 `true`. 미지(로딩·조회실패)는 `false`.
 */
export function useIssueCreatePermissionGate(projectKey: string): boolean {
  const { data } = useProjectPermissions(projectKey)
  return data?.permissions.CREATE === false
}
