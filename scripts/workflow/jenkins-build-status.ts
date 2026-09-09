// 젠킨스 최근 빌드 상태를 브랜치 기준으로 조회한다 — 머지 전 게이트가 읽는 자리
//
// ## 왜 이 스크립트가 있나
//
// 2026-09-09 에 `.husky/pre-push` 에서 백엔드·프론트 테스트를 걷어내고 젠킨스로 옮겼다(P5).
// 그 순간 「푸시 전에 깨진 걸 안다」가 「푸시 후 최대 5분 뒤에 안다」가 됐고, **깨진 커밋이
// 원격 브랜치에 올라갈 수 있다.** 그 사각을 받는 자리가 게이트 2(머지 전 사람 승인)인데,
// 사람이 젠킨스를 눈으로 확인하는 것에만 기대면 그 확인은 조용히 생략된다.
//
// 그래서 **명령 하나로 판정이 나오게** 한다. `bts-merge` Step 1 과 `bts-codereview` 의
// 게이트 2 요약이 이 출력을 그대로 싣는다.
//
// ## ★「빌드 없음」은 통과가 아니다
//
// `gh pr checks` 의 「체크 0건」과 **정확히 같은 함정**이다. 폴링이 아직 안 돌았거나,
// 잡이 다른 브랜치를 보고 있거나, 젠킨스가 죽었을 때 이 명령은 아무것도 못 찾는다.
// 그것을 「빨간불이 아니니 통과」로 읽으면 **검증 없는 머지**가 된다.
// 그래서 종료 코드를 셋으로 가른다 — 0(초록) · 1(빨강) · 2(**판정 불가**).
// 2 를 0 으로 뭉개지 않는 것이 이 스크립트의 존재 이유다.
//
// 사용. node --experimental-strip-types scripts/workflow/jenkins-build-status.ts [브랜치]
//       브랜치 생략 시 현재 브랜치.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
// ★git 을 spawn 할 때는 이 헬퍼를 거친다. 훅 컨텍스트에서 상속된 `GIT_DIR` 가 자식의 `cwd` 를
//   이기기 때문이다 — 걷어내지 않으면 여기서 부른 git 이 **실저장소**를 본다.
//   `git-spawn-sweep.ts` 가 저장소 전량에 대해 이 배선을 강제한다(내가 빠뜨려 red 를 봤다).
import { gitFixtureEnv } from './git-fixture-env.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/**
 * 접속 정보의 정본은 배포 설정 하나다 — 여기 사본을 두면 서버가 바뀔 때 따라오지 않는다.
 *
 * ★worktree 를 함께 본다. `config.sh` 는 gitignored 라 **worktree 에는 checkout 되지 않고**
 *   메인 체크아웃에만 있다. 이 저장소는 작업마다 worktree 를 파므로, 메인을 안 보면 이
 *   스크립트는 정상 작업 흐름에서 항상 「설정 없음」이 된다(2026-09-09 실측).
 */
function configPath(): string | null {
  const candidates = [path.join(REPO_ROOT, 'infra/deploy/config.sh')];
  try {
    const common = execFileSync('git', ['rev-parse', '--git-common-dir'], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
      env: gitFixtureEnv(),
    }).trim();
    candidates.push(path.join(path.dirname(path.resolve(REPO_ROOT, common)), 'infra/deploy/config.sh'));
  } catch {
    /* git 이 없으면 첫 후보만 본다 */
  }
  return candidates.find((c) => fs.existsSync(c)) ?? null;
}

function readConfig(): { server: string; key: string; user: string } {
  const CONFIG = configPath();
  if (CONFIG === null) {
    console.log('⚠️ 판정 불가 — infra/deploy/config.sh 가 없다(메인 체크아웃 포함).');
    console.log('   ★이것은 통과가 아니다. config.sh.example 을 복사해 채워라.');
    process.exit(2);
  }
  const raw = fs.readFileSync(CONFIG, 'utf8');
  const pick = (k: string): string =>
    raw.match(new RegExp(`^${k}="?([^"\\s#]+)"?`, 'm'))?.[1] ?? '';
  const server = pick('SERVER');
  const user = pick('SSH_USER') || 'root';
  const key = (pick('SSH_KEY') || '~/.ssh/ncp-bts.pem').replace(/^\$HOME|^~/, process.env.HOME ?? '');
  if (!server) {
    console.error('❌ config.sh 에서 SERVER 를 못 읽었다.');
    process.exit(2);
  }
  return { server, key, user };
}

/**
 * 실행 본문. **`isMain` 가드 아래에서만 돈다.**
 *
 * ★가드가 필요한 이유가 판별식 쪽에 있다. `git-fixture-isolation.test.ts` 의 sweep 은
 *   「git 을 부르는 파일은 테스트가 **import** 하거나 테스트 자신이어야 한다」를 요구한다.
 *   스크립트는 자식 후보가 아니라 import 만이 길인데, 가드가 없으면 import 하는 순간
 *   SSH 가 나간다. 저장소 관용과 같은 형태다(`classify-task.ts`).
 */
export function main(): void {
const branch =
  process.argv[2] ??
  execFileSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: gitFixtureEnv(),
  }).trim();

const { server, key, user } = readConfig();

// 젠킨스는 루프백 전용이라 SSH 안에서 curl 한다. 자격증명은 서버 `.env` 에만 있고
// 이 스크립트도 이 대화도 값을 보지 않는다.
// ★`tree=` 로 좁히지 않는다. 중첩 대괄호(`actions[lastBuiltRevision[branch[name]]]`)를 쓰면
//   curl 이 URL 을 거부한다(2026-09-09 실측 — 응답 0바이트). 전체 JSON 은 수 KB 라 좁힐 값이 없다.
const remote = `
cd /opt/bts/infra/jenkins 2>/dev/null || exit 90
set -a; . ./.env; set +a
curl -sf -u "$JENKINS_ADMIN_ID:$JENKINS_ADMIN_PASSWORD" \
  "http://127.0.0.1:18081/job/bts-ci/lastBuild/api/json" || exit 91
`;

let json = '';
try {
  json = execFileSync(
    'ssh',
    ['-i', key, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', `${user}@${server}`, 'bash -s'],
    { input: remote, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
  );
} catch (e) {
  const code = (e as { status?: number }).status;
  console.log('⚠️ 판정 불가 — 젠킨스에 닿지 못했다.');
  console.log(
    code === 90
      ? '   원인. 서버에 /opt/bts/infra/jenkins 가 없다.'
      : code === 91
        ? '   원인. 젠킨스가 응답하지 않는다(컨테이너 정지 또는 인증 실패).'
        : `   원인. SSH 실패 (exit ${code ?? '?'}).`,
  );
  console.log('   ★이것은 통과가 아니다. 로컬 전량 실행으로 대신하거나 머지하지 않는다.');
  process.exit(2);
}

let d: { number?: number; result?: string | null; building?: boolean; url?: string; actions?: unknown[] };
try {
  d = JSON.parse(json);
} catch {
  console.log('⚠️ 판정 불가 — 젠킨스 응답을 못 읽었다.');
  process.exit(2);
}

// 마지막 빌드가 **이 브랜치의 것인지** 본다. 다른 브랜치 결과를 이 브랜치의 초록으로 읽는 것이
// 가장 나쁜 오독이다 — 그것이 정확히 「가짜 초록」이다.
//
// git 플러그인은 `buildsByBranchName` 에 `refs/remotes/origin/<브랜치>` 를 키로 남긴다.
const built =
  JSON.stringify(d).match(/"refs\/remotes\/origin\/([^"]+)"/)?.[1] ??
  JSON.stringify(d).match(/"name":"(?:origin\/)?([^"]+)"/)?.[1] ??
  '';
const sameBranch = built === branch;

console.log(`젠킨스 빌드 #${d.number ?? '?'} · 브랜치 ${built || '알 수 없음'} · ${d.url ?? ''}`);

if (d.building) {
  console.log('⚠️ 판정 불가 — 아직 도는 중이다. 끝난 뒤 다시 본다.');
  process.exit(2);
}
if (!sameBranch) {
  console.log(`⚠️ 판정 불가 — 마지막 빌드가 다른 브랜치(${built})다. 현재 ${branch}.`);
  console.log('   ★다른 브랜치의 초록을 이 브랜치의 초록으로 읽지 않는다.');
  process.exit(2);
}
if (d.result === 'SUCCESS') {
  console.log('✅ 초록 — 이 브랜치의 최근 빌드가 통과했다.');
  process.exit(0);
}
if (d.result === null || d.result === undefined) {
  console.log('⚠️ 판정 불가 — 결과가 비어 있다.');
  process.exit(2);
}
console.log(`❌ 빨강 — ${d.result}. 머지하지 않는다.`);
process.exit(1);
}

const isMain = import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  main();
}
