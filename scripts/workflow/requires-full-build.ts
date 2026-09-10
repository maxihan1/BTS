// 이 변경이 전량 빌드를 요구하는지 판정해 출력하는 스크립트 — 젠킨스 `전량 판정` stage 가 부른다
//
// ## 무엇을 위한 것인가
//
// CI 설정(`Jenkinsfile*` · 선별기 자신 · `backend/`)이 바뀌면 영향 범위를 알 수 없다.
// 그때는 **전량 빌드**로 다뤄야 한다 — 조립 부팅과 인프라 봉인까지 포함해서다.
//
// ## 왜 젠킨스가 직접 판정하지 않나
//
// 판정 입력은 `select-backend-modules.ts` 의 `WIDEN_PREFIXES` **하나**다. 파이프라인이
// 같은 목록을 Groovy 로 다시 적으면 그 순간 두 목록이 되고, 새 CI 파일이 생길 때 한쪽만
// 고쳐진다 — 이 저장소가 이름 붙인 지배 결함 양식이다.
//
// ## 출력 계약
//
// `true` 또는 `false` 한 줄. 판정 불가(변경 목록을 못 구함)도 `true` 다 — 모르면 넓게 간다.
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { requiresFullBuild } from './select-backend-modules.ts';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

// 비교 기준 대비 변경 파일 — 본체는 `diff-base.ts` 하나다(2026-09-10).
// 종전에는 여기에 사본이 있었고, main 위에서 `origin/main...HEAD` 가 항상 공집합이라
// **CI 설정을 바꿔도 전량으로 안 넓어졌다.** 계약. scripts/workflow/diff-base.test.ts
import { changedFiles } from './diff-base.ts';

export function main(): number {
  process.stdout.write(requiresFullBuild(changedFiles()) ? 'true' : 'false');
  return 0;
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop() ?? ' ');
if (isMain) process.exit(main());
