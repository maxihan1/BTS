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
