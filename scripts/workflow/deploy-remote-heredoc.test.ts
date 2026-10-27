// bts-deploy.sh 의 원격 heredoc 안에 맥에서 먼저 실행될 명령 치환이 없는지 지키는 테스트
//
// 왜. `run_on_target <<REMOTE` 는 delimiter 에 따옴표가 없어 맥 셸이 본문을 먼저 푼다.
// 주석 안이라도 백틱 · `$(` 는 맥에서 실행된다 — 2026-10-27 배포에서 `sleep 10` · `date ...` ·
// `run_on_target`(빈 입력 SSH)이 맥에서 돌았다. 원격에서 돌릴 치환은 `\$(` 로 이스케이프한다.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

test('원격 heredoc 본문에 이스케이프 안 된 명령 치환(백틱 · $()이 없다', () => {
  const lines = readFileSync(new URL('../../infra/deploy/bts-deploy.sh', import.meta.url), 'utf8').split('\n')
  const start = lines.findIndex((l) => /<<REMOTE$/.test(l))
  const end = lines.findIndex((l, i) => i > start && l === 'REMOTE')
  assert.ok(start >= 0 && end > start, 'run_on_target <<REMOTE 블록을 못 찾았다')

  const hits = lines
    .slice(start + 1, end)
    .map((l, i) => ({ line: start + 2 + i, text: l }))
    .filter(({ text }) => text.includes('`') || /(^|[^\\])\$\(/.test(text))
  assert.deepEqual(hits, [])
})
