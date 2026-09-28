// E2E 샤드가 스펙 전체를 빠짐없이 덮는지 대조 — 반만 돌고 초록이 되는 자리를 막는다
//
// ## 왜 있나
//
// Playwright 의 `--shard=i/N` 은 **두 곳에 같은 N 을 적어야** 성립한다.
//
//     matrix: { shard: [1, 2, 3, 4, 5, 6, 7, 8] }   ← 뜨는 잡의 개수
//     playwright test --shard=${{ matrix.shard }}/8  ← 각 잡이 맡는 몫
//
// 둘이 갈리면 **조용히 일부만 돈다.** matrix 를 4개로 줄이고 `/8` 을 그대로 두면
// 스펙의 절반이 아무 잡에서도 안 돌고, 그 잡들은 전부 초록이다. 빨간불이 아니라 부재다.
// 반대로 matrix 가 12 인데 `/8` 이면 9~12 번 잡이 「shard index out of range」로 죽는다 —
// 이쪽은 시끄러워서 그나마 낫다.
//
// 이 저장소가 이름 붙인 지배 결함 양식 그대로다 — 두 목록이 서로를 안 본다.
// 처방도 같다. 차집합을 기계가 보고, 비-공허 짝으로 그 기계가 살아 있음을 증명한다.
//
// ## 무엇을 강제하나
//
// ① `e2e` 잡의 matrix shard 배열이 **정확히 1..N** 이다 (빠진 번호도 중복도 없다).
// ② 그 N 이 `--shard=<i>/<N>` 의 분모와 같다.
// ③ 전량을 돈다 — 스펙을 골라내는 인자(파일 경로 목록)가 붙어 있지 않다.
//    Maxi 확정으로 E2E 는 전량 CI 편입이다. 「바뀐 것만」으로 되돌아가면 여기서 red 다.
// ④ **비-공허 짝** — 위 판정을 가짜 YAML 로 흔들어 실제로 red 를 내는지 확인한다.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const WORKFLOW = path.join(REPO_ROOT, '.github/workflows/verify.yml')

export interface ShardWiring {
  /** matrix 에 적힌 샤드 번호. */
  matrix: number[]
  /** `--shard=i/N` 의 분모 N. 못 찾으면 `null`. */
  denominator: number | null
  /** `playwright test` 줄 전문. 못 찾으면 `null`. */
  command: string | null
}

/**
 * 워크플로우에서 E2E 샤드 배선을 읽는다.
 *
 * ★YAML 파서를 쓰지 않고 글자로 읽는다. 의존성을 늘리지 않으려는 것도 있지만,
 *   더 큰 이유는 이 판별식이 **사람이 YAML 에서 보는 것과 같은 것**을 봐야 하기 때문이다.
 */
export function readShardWiring(source: string): ShardWiring {
  const matrixLine = /shard:\s*\[([^\]]*)\]/.exec(source)
  const matrix =
    matrixLine?.[1] === undefined
      ? []
      : matrixLine[1]
          .split(',')
          .map((s) => Number(s.trim()))
          .filter((n) => Number.isInteger(n))

  // ★`--shard` 가 **있는** 줄을 찾는다. 단순히 `playwright test` 로 찾으면 시각 회귀 잡의
  //   `--project=visual` 줄이 먼저 걸릴 수 있고, 그러면 분모를 못 읽어 ②가 거짓 red 가 된다.
  const cmdLine = source.split('\n').find((l) => /playwright test/.test(l) && /--shard=/.test(l) && !l.trim().startsWith('#'))
  // ★`${{ matrix.shard }}` 안에 **공백이 있다.** `[^/\s]+` 로 읽으면 공백에서 끊겨
  //   분모를 영영 못 찾는다 — 실제로 그렇게 적었다가 이 판별식이 잡았다(2026-09-11).
  const denom = cmdLine === undefined ? null : Number(/--shard=.*?\/(\d+)/.exec(cmdLine)?.[1] ?? NaN)

  return {
    matrix,
    denominator: Number.isInteger(denom) ? (denom as number) : null,
    command: cmdLine ?? null,
  }
}

describe('E2E 샤드가 전체를 덮는다', () => {
  const src = (() => {
    // ★파일이 없으면 통과가 아니라 실패다. 「검사 대상이 없어서 초록」은 침묵 실패다.
    assert.ok(fs.existsSync(WORKFLOW), `verify.yml 이 없다: ${WORKFLOW}`)
    return fs.readFileSync(WORKFLOW, 'utf-8')
  })()

  test('★★① matrix 샤드 번호가 정확히 1..N 이다', () => {
    const { matrix } = readShardWiring(src)
    assert.ok(matrix.length > 0, 'E2E matrix 에서 샤드 번호를 하나도 못 읽었다 — 배선이 사라졌거나 형식이 바뀌었다')
    const expected = Array.from({ length: matrix.length }, (_, i) => i + 1)
    assert.deepEqual(
      [...matrix].sort((a, b) => a - b),
      expected,
      `샤드 번호가 1..${matrix.length} 가 아니다 — ${JSON.stringify(matrix)}\n` +
        '★빠진 번호가 있으면 그 몫의 스펙은 **아무 잡에서도 안 돈다.** 빨간불이 아니라 부재다.',
    )
  })

  test('★★② matrix 개수와 --shard 의 분모가 같다', () => {
    const { matrix, denominator, command } = readShardWiring(src)
    assert.notEqual(denominator, null, `\`--shard=i/N\` 을 못 찾았다. 읽은 명령 줄: ${command}`)
    assert.equal(
      denominator,
      matrix.length,
      `matrix 는 ${matrix.length}개인데 --shard 분모는 ${denominator} 다.\n` +
        `  명령 ${command}\n` +
        '★분모가 더 크면 그 차이만큼의 스펙이 조용히 안 돈다. 전부 초록인 채로.',
    )
  })

  test('★③ 전량을 돈다 — 스펙을 골라내는 인자가 없다', () => {
    const { command } = readShardWiring(src)
    assert.notEqual(command, null, 'playwright 명령을 못 찾았다')
    assert.ok(
      !/\be2e\/[\w./-]*\.spec\.ts/.test(command!),
      `명령에 스펙 경로가 붙어 있다 — 전량이 아니다.\n  ${command}\n` +
        '★E2E 전량 CI 편입은 Maxi 확정(2026-09-11)이다. 「바뀐 것만」으로 되돌리려면\n' +
        '  이 판별식과 계획 문서를 같은 커밋에서 고쳐라.',
    )
  })

  test('★★④ 비-공허 짝 — 어긋난 배선을 실제로 잡는다', () => {
    // 분모 불일치
    const mismatched = readShardWiring('shard: [1, 2, 3, 4]\n  run: playwright test --shard=${{ matrix.shard }}/8')
    assert.notEqual(
      mismatched.denominator,
      mismatched.matrix.length,
      '★비-공허 확인 실패 — matrix 4 · 분모 8 을 같다고 읽었다. ②는 아무것도 지키지 않는다.',
    )

    // 번호 빠짐
    const gapped = readShardWiring('shard: [1, 2, 4]\n  run: playwright test --shard=${{ matrix.shard }}/3')
    assert.notDeepEqual(
      [...gapped.matrix].sort((a, b) => a - b),
      [1, 2, 3],
      '★비-공허 확인 실패 — [1,2,4] 를 1..3 으로 읽었다. ①은 아무것도 지키지 않는다.',
    )

    // 스펙 경로가 붙은 경우
    const narrowed = readShardWiring('shard: [1]\n  run: playwright test e2e/board.spec.ts --shard=${{ matrix.shard }}/1')
    assert.ok(
      /\be2e\/[\w./-]*\.spec\.ts/.test(narrowed.command!),
      '★비-공허 확인 실패 — 스펙 경로를 못 알아본다. ③은 아무것도 지키지 않는다.',
    )
  })
})
