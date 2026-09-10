// 배포가 main 에서만 일어나는지, 승인과 배포가 같은 조건을 쓰는지 대조하는 판별식
//
// ## 무엇을 막는가
//
// 2026-09-10 에 배포 stage 의 조건이 `DEPLOY && RUN_FULL` 뿐이었다 — **브랜치를 안 봤다.**
// 이 잡은 검증 편의로 작업 브랜치(`chore/jenkins-p1-bootstrap`)를 보도록 걸려 있었고,
// 그 임시 설정이 배포 경로까지 열어 **미머지 코드를 운영에 배포할 수 있는 상태**였다.
//
// 「어차피 사람이 승인한다」로 넘길 수 없다. 그때의 승인 화면은
// 「운영에 배포한다. 계속할까?」만 보여줬다 — **어느 브랜치인지 안 보인다.**
// 사람이 막을 수 없는 것을 사람에게 맡긴 셈이다.
//
// ## CI 는 머지하지 않는다
//
// 머지는 GitHub 에서 사람이 한다(게이트 2). 무료 플랜이라 브랜치 보호가 403 으로 거부되므로
// 젠킨스는 머지를 막지도 하지도 못한다(`docs/rules/behavior-rules.md` §4).
// 그래서 **배포 대상은 이미 머지된 main** 이고, 그 전제가 파이프라인에 박혀 있어야 한다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const JENKINSFILE = join(ROOT, 'Jenkinsfile');

/** `stage('<이름>')` 의 `when { ... }` 블록 본문을 뽑는다. */
function whenBlock(src: string, stageName: string): string {
  const at = src.indexOf(`stage('${stageName}')`);
  assert.ok(at >= 0, `stage('${stageName}') 를 못 찾았다 — 이름을 바꿨다면 이 판별식도 함께 고쳐라.`);
  const whenAt = src.indexOf('when {', at);
  assert.ok(whenAt >= 0 && whenAt - at < 400, `stage('${stageName}') 에 when 블록이 없다.`);
  // `steps` 가 나오기 전까지가 조건부다.
  const stepsAt = src.indexOf('steps', whenAt);
  return src.slice(whenAt, stepsAt);
}

const BRANCH_COND = /GIT_BRANCH_NAME\s*==\s*'main'/;

test("★배포 stage 는 main 에서만 돈다", () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  assert.match(
    whenBlock(jf, '배포'),
    BRANCH_COND,
    "배포 stage 의 when 에 브랜치 조건이 없다.\n" +
      '그러면 작업 브랜치를 운영에 배포할 수 있다 — 잡이 어느 브랜치를 보게 걸려 있느냐에\n' +
      '운영 배포가 좌우된다. 검증용 설정이 운영 경로를 여는 자리다.',
  );
});

test("★배포 승인 stage 도 같은 조건이다", () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  assert.match(
    whenBlock(jf, '배포 승인'),
    BRANCH_COND,
    '승인 stage 에 브랜치 조건이 없다.\n' +
      '★승인과 배포의 조건이 다르면 **승인은 건너뛰고 배포만 도는 경로**가 생긴다 —\n' +
      '  승인 없는 배포다. 두 stage 는 같은 조건이어야 한다.',
  );
});

test('★승인 화면이 무엇을 배포하는지 보여준다', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  const at = jf.indexOf("stage('배포 승인')");
  const body = jf.slice(at, jf.indexOf("stage('배포')", at));
  for (const [what, re] of [
    ['브랜치', /브랜치\s+\$\{env\.GIT_BRANCH_NAME\}/],
    ['커밋', /커밋\s+\$\{sha\}/],
  ] as const) {
    assert.match(
      body,
      re,
      `승인 문구에 ${what} 가 없다.\n` +
        '판단할 정보가 없는 승인은 반사적으로 눌리게 되고, 그때 승인 게이트는 이름만 남는다.',
    );
  }
});

test('브랜치 이름은 한 곳에서 계산한다 (세 곳이 서로 다른 답을 내지 않게)', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  const assigns = jf.split('\n').filter((l) => /env\.GIT_BRANCH_NAME\s*=[^=]/.test(l));
  assert.equal(
    assigns.length,
    1,
    `env.GIT_BRANCH_NAME 대입이 ${assigns.length}곳이다 — 한 곳이어야 한다.\n` +
      '여러 곳에서 계산하면 detached HEAD·BRANCH_NAME 부재 같은 상황에서 서로 다른 답이 나온다.',
  );
});
