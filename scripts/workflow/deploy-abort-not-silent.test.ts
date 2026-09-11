// 배포 도중 중단이 「침묵」으로 끝나지 않는지 — abortPrevious 가 배포를 죽이는 자리를 지킨다
//
// ★무엇을 막나. `bts-ci` 는 `disableConcurrentBuilds(abortPrevious: true)` 다. 배포가
//   도는 중 main 으로 푸시 하나가 들어오면 폴링이 새 빌드를 걸고 이 빌드는 **그 자리에서
//   죽는다.** 배포 스크립트는 rsync → DB 덤프 → compose build → up → health 순이라
//   `compose up` 중에 끊기면 운영이 반쯤 갈린 채 남는다.
//
// ★★그런데 그 결과가 `Finished: NOT_BUILT` 다 — **빨간불이 아니라 침묵이다.**
//   2026-09-11 빌드 #48 실측. `DEPLOY=true` 로 건 빌드가 폴링 빌드에 abort 됐고,
//   확인해 보기 전까지 「배포를 걸었는데 안 됐다」를 알 방법이 없었다.
//
//   abort 자체는 막지 못한다(interrupt 는 강제다). 이 판별식이 지키는 것은 그다음이다 —
//   **어디서 죽었는지 말하는 장치가 살아 있는가.**
import { describe, test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const REPO_ROOT = path.resolve(import.meta.dirname, '../..')
const CI = path.join(REPO_ROOT, 'Jenkinsfile')

const source = (): string => fs.readFileSync(CI, 'utf-8')

/** 주석이 아닌 코드 줄만 남긴다 — 「설명에 그 단어가 있다」로 통과하지 않게. */
export function codeOf(src: string): string {
  return src
    .split('\n')
    .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l))
    .join('\n')
}

/** 패턴에 처음 걸리는 코드 줄 번호 (0-기반). 없으면 -1. */
export function lineOf(src: string, pattern: RegExp): number {
  return src
    .split('\n')
    .findIndex((l) => !/^\s*(\/\/|\*|\/\*)/.test(l) && pattern.test(l))
}

describe('배포 중단은 침묵하지 않는다', () => {
  test('★양성 대조군 — 지키려는 전제가 아직 성립한다', () => {
    // 이 판별식이 공허해지는 경로는 「abortPrevious 를 뺐다」다. 그러면 지킬 대상이 없다.
    // 뺐다면 이 테스트가 red 로 알려 주고, 그때 이 파일을 지우면 된다.
    assert.match(
      codeOf(source()),
      /disableConcurrentBuilds\(abortPrevious:\s*true\)/,
      'abortPrevious 가 없다 — 배포가 죽을 일이 없으니 이 판별식도 필요 없다. 확인 후 지워라.',
    )
    assert.match(codeOf(source()), /stage\('배포'\)/, '배포 stage 가 없다 — 훑기가 깨졌다')
  })

  test('★★배포 stage 가 진입·완료를 표시한다', () => {
    const code = codeOf(source())
    assert.match(
      code,
      /env\.DEPLOY_ENTERED\s*=\s*'true'/,
      '배포 진입 표시가 없다 — abort 됐을 때 배포 전인지 중인지 구별할 수 없다',
    )
    assert.match(
      code,
      /env\.DEPLOY_COMPLETED\s*=\s*'true'/,
      '배포 완료 표시가 없다 — 완주한 빌드도 「중단됐다」로 오인한다',
    )
  })

  test('★★진입 표시가 배포 실행보다 앞이다', () => {
    // ★순서가 핵심이다. `bts-deploy.sh` 호출 **뒤**에 표시를 세우면, 그 호출 도중
    //   죽었을 때 표시가 안 서고 post 는 「배포 전에 죽었다」로 읽는다 —
    //   정확히 위험한 구간만 감지에서 빠진다.
    const src = source()
    const enter = lineOf(src, /env\.DEPLOY_ENTERED\s*=/)
    const run = lineOf(src, /bash infra\/deploy\/bts-deploy\.sh/)
    assert.ok(enter >= 0 && run >= 0, `두 지점을 못 찾았다: enter=${enter} run=${run}`)
    assert.ok(
      enter < run,
      `진입 표시(${enter + 1}행)가 배포 실행(${run + 1}행)보다 뒤다 — 위험 구간이 감지에서 빠진다`,
    )
  })

  test('★★완료 표시가 배포 실행보다 뒤다', () => {
    const src = source()
    const done = lineOf(src, /env\.DEPLOY_COMPLETED\s*=/)
    const run = lineOf(src, /bash infra\/deploy\/bts-deploy\.sh/)
    assert.ok(done >= 0 && run >= 0, `두 지점을 못 찾았다: done=${done} run=${run}`)
    assert.ok(
      done > run,
      `완료 표시(${done + 1}행)가 배포 실행(${run + 1}행)보다 앞이다 — 죽어도 완주로 읽힌다`,
    )
  })

  test('★★post 에 aborted 처리가 있고 그 표시를 실제로 읽는다', () => {
    const code = codeOf(source())
    assert.match(code, /^\s*aborted\s*\{/m, 'post 에 aborted 블록이 없다 — 중단이 침묵한다')
    const at = code.indexOf('aborted {')
    const block = code.slice(at, at + 2000)
    assert.match(
      block,
      /DEPLOY_ENTERED/,
      'aborted 블록이 배포 진입 표시를 읽지 않는다 — 모든 중단을 똑같이 다룬다',
    )
    assert.match(
      block,
      /DEPLOY_COMPLETED/,
      'aborted 블록이 완료 표시를 읽지 않는다 — 완주한 빌드도 경고한다',
    )
  })

  test('★★aborted 가 말만 하지 않고 운영 상태를 실제로 찍는다', () => {
    // ★「경고 문구만」이면 공허하다. 중단된 시점에 필요한 것은 **지금 무엇이 서비스 중인가**다.
    const code = codeOf(source())
    const at = code.indexOf('aborted {')
    const block = code.slice(at, at + 2000)
    assert.match(block, /curl[^\n]*bts\.maxihan\.com/, 'aborted 가 운영 헬스를 찍지 않는다')
    assert.match(block, /docker ps/, 'aborted 가 운영 컨테이너 상태를 찍지 않는다')
    assert.match(
      block,
      /backups|rollback-/,
      'aborted 가 되돌릴 수단(백업·롤백 이미지)을 보여주지 않는다',
    )
  })
})
