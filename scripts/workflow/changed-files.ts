// 비교 기준 대비 바뀐 파일을 한 줄에 하나씩 찍는다 — 셸·Jenkinsfile 이 읽는 창구
//
// ## 왜 있나
//
// `Jenkinsfile` 이 E2E 도메인을 계산하려고 `git diff --name-only` 를 **직접** 돌리고 있었다.
// 그것이 2026-09-10 사고의 여섯 번째 사본이다 — 여섯이 전부 main 위에서 기준을 HEAD 로 잡아
// 차집합이 공집합이 됐고, CI 가 아무 테스트도 안 돌린 채 3분 만에 초록이었다.
//
// 파이프라인이 git 을 직접 부르면 그 줄은 판별식이 못 본다. 창구를 하나 두고 그 창구에만
// 계약을 건다.
//
// ## 종료 코드
//
//   0  기준을 정했다. 바뀐 파일을 한 줄에 하나씩 찍는다(0줄일 수 있다 — 진짜 변경 없음)
//   3  기준을 못 정했다. 아무것도 안 찍는다 — 호출자는 **전량으로 넓혀야 한다**
//
// ★`3` 을 `0` 으로 뭉개지 말 것. 「모른다」를 「없다」로 읽는 순간 조용한 통과가 된다.
import { changedFiles } from './diff-base.ts';

export function main(): number {
  const files = changedFiles();
  if (files === null) return 3;
  if (files.length > 0) process.stdout.write(`${files.join('\n')}\n`);
  return 0;
}

// ★`isMain` 가드 아래에서만 돈다. 판별식이 이 파일을 import 해서 계약을 검사하는데,
//   가드가 없으면 import 하는 순간 git 이 나간다(저장소 관용 — `classify-task.ts`).
const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop() ?? ' ');
if (isMain) process.exit(main());
