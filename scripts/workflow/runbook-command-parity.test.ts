// bootstrap.sh 가 가진 서브커맨드와 런북이 적은 명령이 서로를 검사하는지 대조
//
// ## 무엇을 막는가 — 2026-09-11 실측
//
// `bootstrap.sh` 의 `case` 는 `up|down|logs|lock|job` 다섯인데, 런북 §3 의 명령 블록에는
// **넷만** 있었다. 빠진 것이 `job` 이다.
//
// 하필 그것이 이번 세션에서 사고를 낸 명령이다 — 머지보다 먼저 돌려 잡을 폴링도
// 파라미터도 없는 껍데기로 만들었고, 증상이 「아무 빌드도 안 걸림」이라 아무도 몰랐다.
// **런북에 없는 명령은 절차가 없는 명령**이고, 절차 없이 돌린 결과가 그것이었다.
//
// ## 왜 차집합인가
//
// 문서가 낡는 것은 이 저장소에서 반복되는 양식이다(런북이 「E2E 미이전」이라고 적는 동안
// 전용 잡·전용 파이프라인·푸시 훅이 이미 있었다). 사람의 성실성에 기대지 않고
// **두 목록을 짝지어** 한쪽이 늘면 다른 쪽이 red 가 되게 한다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const BOOTSTRAP = join(ROOT, 'infra/jenkins/bootstrap.sh');
const RUNBOOK = join(ROOT, 'docs/runbooks/jenkins.md');

/** `bootstrap.sh` 의 `case` 가 실제로 받는 서브커맨드. */
function subcommands(): string[] {
  const sh = readFileSync(BOOTSTRAP, 'utf-8');
  const body = sh.slice(sh.indexOf('case '));
  return [...body.matchAll(/^ {2}([a-z][a-z|]*)\)/gm)]
    .flatMap((m) => (m[1] as string).split('|'))
    .filter((c) => c !== '*')
    .sort();
}

/**
 * 런북의 **명령 블록**(```로 감싼 곳)이 적은 서브커맨드.
 *
 * ★본문 산문의 언급은 세지 않는다. 「§3 을 보라」는 식으로 다른 절에서 이름만 나와도
 *   명령 블록에 없으면 **따라 할 수 없다** — 절차가 있는 것과 이름이 나오는 것은 다르다.
 *   실측(2026-09-11) — `job` 이 산문(§7)에만 있고 §3 블록에는 없었다. 그 상태에서
 *   머지보다 먼저 돌려 잡을 껍데기로 만들었다.
 */
function documented(): string[] {
  const md = readFileSync(RUNBOOK, 'utf-8');
  const blocks = [...md.matchAll(/```[\s\S]*?```/g)].map((m) => m[0]).join('\n');
  return [...new Set([...blocks.matchAll(/bootstrap\.sh\s+([a-z]+)/g)].map((m) => m[1] as string))]
    .sort();
}

describe('bootstrap.sh 서브커맨드 ⟺ 런북', () => {
  test('★비-공허 확인 — 양쪽을 실제로 읽었다', () => {
    assert.ok(
      subcommands().length >= 4,
      `bootstrap.sh 에서 서브커맨드를 ${subcommands().length}개밖에 못 찾았다 — 파싱이 깨졌다.`,
    );
    assert.ok(
      documented().length >= 3,
      `런북에서 명령을 ${documented().length}개밖에 못 찾았다 — 파싱이 깨졌다.`,
    );
  });

  test('★★런북에 없는 서브커맨드가 없다', () => {
    const doc = new Set(documented());
    const missing = subcommands().filter((c) => !doc.has(c));
    assert.deepEqual(
      missing,
      [],
      `bootstrap.sh 에 있는데 런북이 안 적은 명령: ${missing.join(', ')}\n` +
        '★런북에 없는 명령은 **절차가 없는 명령**이다.\n' +
        '  2026-09-11 에 `job` 이 그 상태였고, 머지보다 먼저 돌려 잡을 폴링도 파라미터도\n' +
        '  없는 껍데기로 만들었다. 증상이 「아무 빌드도 안 걸림」이라 아무도 몰랐다.',
    );
  });

  test('★★런북이 없는 서브커맨드를 적지 않는다 (반대 방향)', () => {
    const have = new Set(subcommands());
    const ghosts = documented().filter((c) => !have.has(c));
    assert.deepEqual(
      ghosts,
      [],
      `런북이 적었는데 bootstrap.sh 에 없는 명령: ${ghosts.join(', ')}\n` +
        '★없는 명령을 적어 두면 그대로 따라 하다 실패한다. 실제로 `--lock` 이\n' +
        '  `plugins.txt` 주석에 그렇게 적혀 있었다(정답은 `lock`).',
    );
  });
});
