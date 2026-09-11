// 배포가 지키는 「운영 상태」 목록이 한 곳에만 사는지 — 그리고 그 읽기가 실제로 도는지 본다
//
// ★무엇을 막나. `/opt/bts` 에는 저장소가 모르는 운영 상태가 산다(DB 덤프·관리자 비밀번호·
//   배포키). 그 목록을 두 곳이 쓴다.
//     infra/deploy/bts-deploy.sh     rsync `--delete` 제외 — 빠지면 **지워진다**
//     infra/jenkins/bootstrap.sh     chown 후 root 복구 — 빠지면 **파이프라인이 읽는다**
//   같은 목록인데 발현이 다르다. 두 벌이 되면 한쪽만 고쳐지고, 증상만 보고는 같은 원인이라고
//   생각하기 어렵다 — 이 저장소의 지배 결함 양식(two-lists-never-check-each-other)이다.
//
// ★★검사가 소스 텍스트를 읽지 않는다. `read-protected-paths.sh` 를 **실제로 돌려서**
//   무엇이 나오는지 본다. 2026-09-11 에 이 세션이 반복해 물린 것이 그것이다 —
//   소스에 그 문자열이 있다는 사실은 그 코드가 돈다는 뜻이 아니다.
import { describe, test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

const REPO_ROOT = path.resolve(import.meta.dirname, '../..')
const READER = 'infra/deploy/read-protected-paths.sh'
const LIST = 'infra/deploy/protected-paths.txt'

/** 읽기 스크립트를 실제로 실행해 보호 경로를 얻는다. */
export function runReader(cwd: string = REPO_ROOT): { paths: string[]; status: number; stderr: string } {
  const r = spawnSync('bash', [path.join(cwd, READER)], { encoding: 'utf-8' })
  return {
    paths: (r.stdout ?? '').split('\n').filter((l) => l.trim() !== ''),
    status: r.status ?? -1,
    stderr: r.stderr ?? '',
  }
}

/** 주석을 걷어낸 「코드 줄」만 남긴다 — 설명에 경로를 적는 것은 막지 않는다. */
export function codeLines(source: string): string[] {
  return source
    .split('\n')
    .map((l) => l.replace(/^\s*#.*$/, ''))
    .filter((l) => l.trim() !== '')
}

const read = (rel: string): string => fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')

/**
 * 패턴에 처음 걸리는 **코드** 줄의 번호 (0-기반). 없으면 -1.
 *
 * ★주석 줄을 건너뛴다. 안 그러면 「chown 을 앞에서 설명하는 주석」이 chown 자신보다
 *   앞 줄로 잡혀 순서 판정이 뒤집힌다 — 설명이 많을수록 잘 속는 검사가 된다.
 */
export function lineOf(lines: string[], pattern: RegExp): number {
  return lines.findIndex((l) => !/^\s*#/.test(l) && pattern.test(l))
}

describe('보호 경로 목록 — 정본 하나 · 읽기 한 벌', () => {
  test('★양성 대조군 — 읽기 스크립트가 실제로 돌고 경로를 낸다', () => {
    const { paths, status, stderr } = runReader()
    assert.equal(status, 0, `${READER} 가 exit ${status} 로 죽었다\n${stderr}`)
    assert.ok(
      paths.length >= 3,
      `보호 경로를 ${paths.length}건밖에 못 읽었다 — 파싱이 깨졌거나 목록이 비었다`,
    )
  })

  test('★읽어낸 경로에 주석 부스러기가 섞이지 않는다', () => {
    // 인라인 주석(`backups  # DB 덤프`)을 못 걷어내면 `--exclude=backups#DB덤프` 가 되고,
    // rsync 는 그 이름의 파일이 없으니 **아무것도 제외하지 않는다.** 조용히 지워진다.
    const { paths } = runReader()
    const dirty = paths.filter((p) => p.includes('#') || /\s/.test(p))
    assert.deepEqual(dirty, [], `주석·공백이 섞인 경로가 있다 — rsync 제외가 무효가 된다`)
  })

  test('★목록 파일이 실재하고, 읽기 스크립트가 그 파일을 읽는다', () => {
    assert.ok(fs.existsSync(path.join(REPO_ROOT, LIST)), `${LIST} 이 없다`)
    assert.match(
      read(READER),
      /protected-paths\.txt/,
      `${READER} 가 ${LIST} 을 읽지 않는다 — 정본이 둘로 갈렸다`,
    )
  })

  test('★★두 소비자가 모두 읽기 스크립트를 거친다 — 목록을 직접 읽지 않는다', () => {
    for (const consumer of ['infra/deploy/bts-deploy.sh', 'infra/jenkins/bootstrap.sh']) {
      const code = codeLines(read(consumer)).join('\n')
      assert.match(
        code,
        /read-protected-paths\.sh/,
        `${consumer} 가 읽기 스크립트를 부르지 않는다`,
      )
      // 목록 파일을 **직접** 읽으면 파싱이 두 벌이 된다. 읽기 스크립트 안에서만 허용된다.
      assert.doesNotMatch(
        code,
        /protected-paths\.txt/,
        `${consumer} 가 목록 파일을 직접 읽는다 — 파싱이 두 벌이 됐다`,
      )
    }
  })

  test('★★어느 소비자도 보호 경로를 하드코딩하지 않는다', () => {
    // ★비-공허의 핵심. 목록을 파일로 빼 놓고 스크립트에 그대로 복사해 두면 파일은 장식이 된다.
    //   주석은 허용한다 — 왜 지키는지 적어야 하고, 그것이 두 번째 목록이 되지는 않는다.
    const { paths } = runReader()
    const offenders: string[] = []
    for (const consumer of ['infra/deploy/bts-deploy.sh', 'infra/jenkins/bootstrap.sh']) {
      const lines = codeLines(read(consumer))
      for (const p of paths) {
        // 읽기 스크립트 호출 줄에는 경로가 없다. 코드 줄에 경로 리터럴이 있으면 하드코딩이다.
        const hit = lines.find((l) => l.includes(`'${p}'`) || l.includes(`"${p}"`))
        if (hit !== undefined) offenders.push(`${consumer}  →  ${p}`)
      }
    }
    assert.deepEqual(offenders, [], `보호 경로가 스크립트에 하드코딩돼 있다 — 두 벌이다`)
  })

  test('★목록이 빈 채로 통과하지 않는다 — 0건은 실패다', () => {
    // 빈 목록을 받으면 rsync 제외가 0건이 되고 `--delete` 가 백업을 지운다.
    // 그 상황에서 읽기 스크립트가 **침묵하지 않고 죽는지** 실제로 확인한다.
    const tmp = fs.mkdtempSync(path.join(REPO_ROOT, '.bts-cache/protected-'))
    try {
      fs.mkdirSync(path.join(tmp, 'infra/deploy'), { recursive: true })
      fs.copyFileSync(path.join(REPO_ROOT, READER), path.join(tmp, READER))
      fs.writeFileSync(path.join(tmp, LIST), '# 주석만 있고 경로가 없다\n\n')
      const r = spawnSync('bash', [path.join(tmp, READER)], { encoding: 'utf-8' })
      assert.notEqual(r.status, 0, '경로 0건인데 exit 0 이다 — 빈 제외 목록이 조용히 통과한다')
      assert.match(r.stderr ?? '', /0건|파싱/, `실패 메시지가 원인을 말하지 않는다: ${r.stderr}`)
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true })
    }
  })

  test('★목록 파일이 사라지면 침묵하지 않는다', () => {
    const tmp = fs.mkdtempSync(path.join(REPO_ROOT, '.bts-cache/protected-'))
    try {
      fs.mkdirSync(path.join(tmp, 'infra/deploy'), { recursive: true })
      fs.copyFileSync(path.join(REPO_ROOT, READER), path.join(tmp, READER))
      const r = spawnSync('bash', [path.join(tmp, READER)], { encoding: 'utf-8' })
      assert.notEqual(r.status, 0, '목록 파일이 없는데 exit 0 이다')
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true })
    }
  })
})

describe('② 위험한 일보다 안전장치가 먼저다 — 순서', () => {
  // ★★가드의 문구가 맞아도 **위치**가 틀리면 사고를 설명할 뿐 막지는 못한다.
  //
  //   2026-09-11 실측. `bootstrap.sh` 가 `chown -R 1000:1000 /opt/bts` 를 먼저 하고
  //   그다음에 「무엇을 root 로 되돌릴지」 읽는 스크립트를 찾았다. 그 파일이 서버에 아직
  //   없어서 「자격증명이 노출된 상태다」를 정확히 찍고 죽었는데 — **그 진단이 맞았다.**
  //   젠킨스 관리자 비밀번호와 GitHub 배포 개인키가 2분간 uid 1000 소유로 있었다.
  //
  //   메시지는 완벽했고 순서가 틀렸다. 그래서 이 판별식은 문구가 아니라 **줄 순서**를 본다.

  const bootstrap = (): string[] => read('infra/jenkins/bootstrap.sh').split('\n')

  test('★양성 대조군 — 두 지점이 모두 실재한다', () => {
    const lines = bootstrap()
    assert.ok(lineOf(lines, /chown -R "\$\{JUID\}/) >= 0, 'chown 교정이 없다 — 이 검사가 공허하다')
    assert.ok(lineOf(lines, /read-protected-paths\.sh/) >= 0, '읽기 스크립트 호출이 없다')
  })

  test('★★보호 목록을 읽는 것이 chown 보다 먼저다', () => {
    const lines = bootstrap()
    const readAt = lineOf(lines, /READER=.*read-protected-paths\.sh/)
    const guardAt = lineOf(lines, /PROTECT_LIST=/)
    const chownAt = lineOf(lines, /chown -R "\$\{JUID\}:\$\{JGID\}" \/opt\/bts/)
    assert.ok(readAt >= 0 && guardAt >= 0 && chownAt >= 0, `세 지점을 다 못 찾았다: read=${readAt} guard=${guardAt} chown=${chownAt}`)
    assert.ok(
      readAt < chownAt && guardAt < chownAt,
      `안전장치가 chown 뒤에 있다 (읽기 ${readAt + 1}행 · 판정 ${guardAt + 1}행 · chown ${chownAt + 1}행).\n` +
        '  위험한 일을 먼저 하고 그 안전장치를 나중에 찾으면, 그사이 자격증명이 노출된다.\n' +
        '  2026-09-11 에 실제로 2분간 노출됐다.',
    )
  })

  test('★★목록을 못 읽으면 chown 을 하지 않는다고 말한다', () => {
    // 실패 메시지가 「노출됐다」가 아니라 「하지 않았다」여야 한다 —
    // 전자는 사후 보고, 후자는 사전 차단이다. 문구가 사후 보고로 돌아갔다면 순서도
    // 같이 뒤집혔을 가능성이 높다.
    //
    // ★창을 **줄 번호**로 연다. 첫 판본은 `indexOf('chown -R "${JUID}')` 로 잘랐는데,
    //   그 문자열이 주석에도 나타나서 주석 한 줄만 심으면 창이 빈 문자열이 됐다 —
    //   검사가 통째로 무효가 되는데 red 는 엉뚱한 이유로 난다(2026-09-11 프로브에서 적발).
    const lines = bootstrap()
    const readAt = lineOf(lines, /READER=.*read-protected-paths\.sh/)
    const chownAt = lineOf(lines, /chown -R "\$\{JUID\}:\$\{JGID\}" \/opt\/bts/)
    assert.ok(readAt >= 0 && chownAt > readAt, `창을 못 열었다: read=${readAt} chown=${chownAt}`)
    const guardBlock = lines.slice(readAt, chownAt).join('\n')
    assert.match(
      guardBlock,
      /chown 을 하지 않았다/,
      'READER 를 못 찾았을 때의 메시지가 「하지 않았다」를 말하지 않는다 — 순서가 다시 뒤집혔을 수 있다',
    )
  })
})
