// changed-e2e-specs 가 바뀐 시나리오만 내는지 + pre-push 가 그것을 부르는지 대조하는 판별식
//
// ## 두 가지를 함께 본다
//
// ① **스크립트가 옳은 것을 내는가** — 출력 계약(상대경로 · 없으면 빈 출력).
// ② **훅이 그것을 부르는가** — 안 부르면 스크립트는 장식이다.
//
// ②만 있으면 문구 계약이라 스크립트가 텅 비어도 초록이다(룰 L 전례). ①만 있으면
// 「잘 만든 장치가 아무 데도 연결되지 않은」 상태가 된다 — `select-test-scope` 의
// E2E 판정이 정확히 그 상태였다. 판정은 있었는데 내는 명령이 전량(약 3시간)이라
// 훅 어디에도 배선되지 않았고, E2E 를 고쳐도 푸시 전에 아무것도 확인되지 않았다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';
// ★`main` 을 임포트한다. `git-fixture-isolation.test.ts` 는 「git 을 spawn 하는 파일」과
//   「어느 테스트의 자식으로 도는 파일」의 차집합을 보는데, 자식 후보가 테스트 파일뿐이라
//   임포트로 이어야 이 스크립트가 감시망에 실린다.
import { main } from './changed-e2e-specs.ts';
import { e2eSpecs } from './select-test-scope.ts';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const HOOK = join(ROOT, '.husky/pre-push');
const SCRIPT = 'scripts/workflow/changed-e2e-specs.ts';

test('e2eSpecs — 바뀐 시나리오만 apps/web 상대경로로 낸다', () => {
  assert.deepEqual(
    e2eSpecs([
      'apps/web/e2e/board-manage.spec.ts',
      'apps/web/src/components/board/Board.tsx',
      'backend/modules/automation/src/main/kotlin/X.kt',
    ]),
    ['e2e/board-manage.spec.ts'],
  );
});

test('e2eSpecs — spec 이 아닌 e2e 파일(픽스처·헬퍼)은 대상이 아니다', () => {
  assert.deepEqual(e2eSpecs(['apps/web/e2e/fixtures/seed.ts']), []);
});

test('★e2eSpecs — 전량(null)과 없음([])을 구분한다', () => {
  // 둘을 같은 값으로 만들면 「변경 목록을 못 구했다」가 조용히 「생략」이 된다.
  assert.equal(e2eSpecs(null), null);
  assert.deepEqual(e2eSpecs([]), []);
});

test('main 은 종료 코드 0 이다 (훅을 막지 않는다)', () => {
  // 이 스크립트는 **무엇을 돌릴지 알려주는** 역할이다. 판정은 playwright 가 한다.
  assert.equal(main(), 0);
});

test('★pre-push 가 이 스크립트를 부른다 (배선 대조)', () => {
  const hook = readFileSync(HOOK, 'utf-8');
  assert.match(
    hook,
    new RegExp(SCRIPT.replace(/[/.]/g, '\\$&')),
    'pre-push 가 changed-e2e-specs 를 부르지 않는다.\n' +
      '그러면 E2E 를 고쳐도 푸시 전에 도는지 확인되지 않고, 검증 안 된 시나리오가\n' +
      'CI 와 배포 후 검증에 그대로 쓰인다 — 「썼는데 한 번도 실행 안 된 테스트」가 된다.',
  );
});

test('★pre-push 가 인자 없는 playwright test 를 부르지 않는다 (전량 금지)', () => {
  const hook = readFileSync(HOOK, 'utf-8');
  const bare = hook
    .split('\n')
    .filter((l) => !l.trim().startsWith('#'))
    // ★spec 인자(경로)가 하나도 없으면 전량이다. `--project=chromium` 같은 플래그는
    //   인자가 아니다 — 그것만 붙은 줄도 전량이므로 함께 잡아야 한다.
    .filter((l) => {
      const m = l.match(/playwright test(.*)$/);
      if (!m) return false;
      const args = m[1].replace(/--[\w-]+(=\S+)?/g, '').replace(/[)\s]/g, '');
      return args.length === 0;
    });
  assert.deepEqual(
    bare,
    [],
    '인자 없는 `playwright test` 가 훅에 있다 — 167파일 전량이고 2코어 실측 약 3시간이다.\n' +
      '3시간짜리 명령은 아무도 안 돌린다. 그래서 종전 배선이 존재하지 않았다.',
  );
});


// ─────────────────────────────────────────────────────────────────────────
// ★★거짓 빨강 차단 (2026-09-11 감사 적발)
//
// 훅이 고른 spec 중 `--project=chromium` 이 못 도는 것이 섞이면 playwright 가
// `No tests found` 로 **exit 1** 이고, 푸시가 막힌다. 두 경우가 있었다.
//
//   ① `e2e/visual/` — chromium 프로젝트가 `testIgnore: '**​/e2e/visual/**'` 다
//   ② **삭제된 spec** — `diff-base.ts` 가 `--no-renames` 라 개명하면 옛 경로가
//      반드시 삭제로 목록에 들어온다
//
// 실측 — 둘 다 EXIT=1. 에러 문구는 「정규식 인자를 확인하라」라 원인이 안 보인다.
//
// ★여기서 사람이 배우는 처방은 `git push --no-verify` 이고, 그 순간 **판별식 전량**
//   (유일한 기계 강제 지점)까지 함께 꺼진다. 거짓 빨강은 가드를 무력화하는
//   가장 흔한 경로다 — 빨간불이 잦으면 사람이 빨간불을 끄는 법을 배운다.
// ─────────────────────────────────────────────────────────────────────────

test('★★visual spec 은 훅 목록에서 빠진다 (chromium 이 못 돈다)', () => {
  assert.deepEqual(
    e2eSpecs(['apps/web/e2e/visual/visual-regression.spec.ts']),
    [],
    'visual spec 이 훅 목록에 들어간다 — `--project=chromium` 이 그것을 무시하므로\n' +
      '`No tests found` 로 푸시가 막힌다. 그 프로젝트는 `--project=visual` 전용이다.',
  );
});

test('★★디스크에 없는 spec 은 빠진다 (개명·삭제)', () => {
  assert.deepEqual(
    e2eSpecs(['apps/web/e2e/this-file-does-not-exist-xyz.spec.ts']),
    [],
    '삭제된 spec 경로가 훅 목록에 들어간다 — playwright 가 exit 1 로 푸시를 막는다.\n' +
      '★spec 을 **개명하기만 해도** 이 상태가 된다(`--no-renames` 라 옛 경로가 삭제로 뜬다).',
  );
});

test('★실재하는 spec 은 그대로 남는다 (비-공허 짝)', () => {
  // ★이 짝이 없으면 위 두 단언은 `e2eSpecs` 를 「항상 []」로 만들어도 통과한다.
  //   그러면 바뀐 E2E 가 푸시 전에 한 번도 안 돈다 — 훨씬 나쁘다.
  const real = e2eSpecs(['apps/web/e2e/smoke.spec.ts']);
  assert.deepEqual(real, ['e2e/smoke.spec.ts'], '실재하는 spec 까지 걸러낸다 — 검증이 사라진다.');
});

test('★★기준을 모르면 침묵하지 않는다 (null ≠ 변경 없음)', () => {
  const src = readFileSync(
    resolve(dirname(fileURLToPath(import.meta.url)), 'changed-e2e-specs.ts'),
    'utf-8',
  );
  assert.match(
    src,
    /specs === null[\s\S]{0,400}stderr\.write/,
    '기준을 못 정했을 때(`null`) 아무 말도 안 한다.\n' +
      '★그러면 「E2E 변경 없음」과 화면이 같다. 사람은 「안 고쳤나 보다」로 읽는데\n' +
      '  실제로는 「기준을 몰라서 아무것도 못 골랐다」다 — 구별할 방법이 없다.\n' +
      '  형제 스크립트 `push-backend-tests.ts` 는 같은 상황에서 크게 말한다.',
  );
});
