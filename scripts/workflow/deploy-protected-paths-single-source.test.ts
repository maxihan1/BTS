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
import os from 'node:os'
import { spawnSync } from 'node:child_process'

const REPO_ROOT = path.resolve(import.meta.dirname, '../..')
const READER = 'infra/deploy/read-protected-paths.sh'
const LIST = 'infra/deploy/protected-paths.txt'
const SECRETS = 'infra/deploy/secret-paths.txt'

/** 읽기 스크립트를 실제로 실행해 경로 목록을 얻는다. kind 는 protected | secret. */
export function runReader(
  kind: 'protected' | 'secret' = 'protected',
  cwd: string = REPO_ROOT,
): { paths: string[]; status: number; stderr: string } {
  const r = spawnSync('bash', [path.join(cwd, READER), kind], { encoding: 'utf-8' })
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
        /(protected|secret)-paths\.txt/,
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
    //
    // ★임시 디렉터리는 `os.tmpdir()` 다. 저장소 안(`.bts-cache/`)에 만들지 않는다 —
    //   그 디렉터리는 gitignore 라 CI 워크스페이스에 **없고**, 로컬에서 손으로 mkdir 한 것이
    //   초록의 이유였다(2026-09-11 빌드 #49 에서 ENOENT 로 적발). 로컬 초록이 CI 빨강이 되는
    //   전형이고, 원인은 「내가 초록을 만들려고 한 행동이 CI 에는 없다」는 것이다.
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-protected-'))
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
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-protected-'))
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
    const guardAt = lineOf(lines, /SECRET_LIST=/)
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

describe('③ 지우면 안 되는 것 ⊋ 읽히면 안 되는 것', () => {
  // ★★의미가 다른 둘을 한 목록에 누르면 한쪽이 다른 쪽을 망가뜨린다.
  //
  //   2026-09-11 실측. 처음에는 목록이 하나였고 `bootstrap.sh` 가 그것 전부를 root 로
  //   되돌렸다. 그래서 `backups/` 까지 root 로 잠겼는데 — **배포 5단계가 거기에 DB 덤프를
  //   쓴다.** 쓰기 불가로 죽을 상태였다. 「지워지면 안 된다」와 「읽히면 안 된다」가
  //   겹치지만 같지 않다는 것을 목록 하나가 감추고 있었다.
  //
  //   그래서 둘로 나눴고, 이 판별식이 **포함 관계**를 지킨다. 읽히면 안 되는 것은 당연히
  //   지워져도 안 되므로 secret ⊆ protected 다. 반대는 성립하지 않는다.

  test('★양성 대조군 — 두 목록이 모두 실제로 읽힌다', () => {
    for (const kind of ['protected', 'secret'] as const) {
      const { paths, status, stderr } = runReader(kind)
      assert.equal(status, 0, `${kind} 목록이 exit ${status} 로 죽었다\n${stderr}`)
      assert.ok(paths.length > 0, `${kind} 목록이 비었다`)
    }
  })

  test('★★secret 은 protected 의 부분집합이다', () => {
    const protectedPaths = runReader('protected').paths
    const secretPaths = runReader('secret').paths
    const orphans = secretPaths.filter((p) => !protectedPaths.includes(p))
    assert.deepEqual(
      orphans,
      [],
      `읽히면 안 되는데 지워져도 되는 경로가 있다: ${orphans.join(', ')}\n` +
        '  자격증명을 배포가 지워 버리면 젠킨스가 다음 빌드부터 안 뜬다.',
    )
  })

  test('★★backups 는 protected 이되 secret 이 아니다', () => {
    // 이 한 건이 두 목록을 나눈 이유 자체다. 되돌아가면 배포가 DB 덤프를 못 쓴다.
    const protectedPaths = runReader('protected').paths
    const secretPaths = runReader('secret').paths
    assert.ok(protectedPaths.includes('backups'), 'backups 가 protected 에 없다 — 배포가 지운다')
    assert.ok(
      !secretPaths.includes('backups'),
      'backups 가 secret 에 있다 — chown 복구가 root 로 잠그고,\n' +
        '  배포 5단계 `pg_dump > backups/...` 가 쓰기 불가로 죽는다.',
    )
  })

  test('★★bootstrap 은 secret 목록을 쓴다 (protected 를 쓰면 backups 가 잠긴다)', () => {
    const code = codeLines(read('infra/jenkins/bootstrap.sh')).join('\n')
    assert.match(
      code,
      // 호출이 변수를 거칠 수 있다(`bash "$READER" secret`). 둘 다 본다.
      /(read-protected-paths\.sh|\$READER)"?\s+secret/,
      'bootstrap 이 secret 목록을 지정하지 않는다 — 기본값 protected 로 backups 까지 잠근다',
    )
  })

  test('★★bts-deploy 는 protected 목록을 쓴다 (secret 만 쓰면 backups 가 지워진다)', () => {
    const code = codeLines(read('infra/deploy/bts-deploy.sh')).join('\n')
    assert.doesNotMatch(
      code,
      /(read-protected-paths\.sh|\$READER)"?\s+secret/,
      'bts-deploy 가 secret 목록을 쓴다 — backups 가 제외에서 빠져 `--delete` 가 지운다',
    )
    assert.match(code, /read-protected-paths\.sh/, 'bts-deploy 가 읽기 스크립트를 안 부른다')
  })

  test('★알 수 없는 종류를 조용히 통과시키지 않는다', () => {
    const r = spawnSync('bash', [path.join(REPO_ROOT, READER), 'nope'], { encoding: 'utf-8' })
    assert.notEqual(r.status, 0, '모르는 종류인데 exit 0 이다 — 빈 목록으로 흘러간다')
  })

  test('★★배포 스크립트가 secret 경로를 건드리지 않는다', () => {
    // ★★2026-09-14 실측(빌드 #55). 첫 배포가 `compose build` 직전 마지막 한 줄에서 죽었다.
    //
    //     open /opt/bts/infra/prod/.env: permission denied
    //
    //   `infra/prod/.env` 가 secret 목록에 있어 `bootstrap.sh` 가 root 600 으로 되돌리는데,
    //   `bts-deploy.sh` 의 `docker compose --env-file infra/prod/.env` 가 **바로 그 파일을
    //   읽는다.** 「읽으면 안 된다」와 「읽어야 한다」가 한 경로에 동시에 걸려 있었고
    //   그 모순을 보는 판별식이 없었다. rsync·DB 덤프까지 다 끝난 뒤에 죽는다.
    //
    //   ★위의 하드코딩 검사가 왜 못 잡았나. 그것은 `'경로'` · `"경로"` 처럼 **따옴표에 감싼**
    //     등장만 찾는다. `--env-file infra/prod/.env` 는 맨몸이라 빠져나갔다.
    //     이 검사는 따옴표 유무를 보지 않는다.
    //
    //   ★목록을 여기 적지 않는다. `read-protected-paths.sh secret` 이 내는 것을 그대로 쓴다 —
    //     적는 순간 그것이 세 번째 목록이 된다.
    const secretPaths = runReader('secret').paths
    assert.ok(secretPaths.length > 0, 'secret 목록이 비었다 — 이 검사가 공허하다')
    const lines = codeLines(read('infra/deploy/bts-deploy.sh'))
    // ★비-공허 짝. secret 이 **아닌** 경로는 실제로 찾아낸다는 것을 먼저 보인다.
    //   이게 없으면 탐색이 통째로 고장나도 「offender 0건」으로 조용히 초록이다.
    assert.ok(
      lines.some((l) => l.includes('infra/docker-compose.prod.yml')),
      '배포 스크립트에서 compose 파일 경로를 못 찾았다 — 이 검사의 탐색이 공허하다',
    )
    const offenders: string[] = []
    for (const line of lines) {
      for (const p of secretPaths) {
        if (line.includes(p)) offenders.push(`${p}  ←  ${line.trim()}`)
      }
    }
    assert.deepEqual(
      offenders,
      [],
      '배포 스크립트가 secret 목록의 경로를 건드린다:\n  ' +
        offenders.join('\n  ') +
        '\n  그 경로는 bootstrap.sh 가 root 600 으로 되돌린다 — 젠킨스(uid 1000)는 못 읽고\n' +
        '  배포가 `permission denied` 로 죽는다 (2026-09-14 빌드 #55 실측).\n' +
        '  읽어야 하는 파일이라면 secret 에서 빼라. protected 에는 남겨야 한다.',
    )
  })

  test('★secret 목록 파일이 실재한다', () => {
    assert.ok(fs.existsSync(path.join(REPO_ROOT, SECRETS)), `${SECRETS} 이 없다`)
  })

  test('★★배포가 읽는 설정 파일을 배포가 지우지 않는다', () => {
    // ★★2026-09-14 실측(빌드 #56). 배포가 시작 1초 만에 죽었다.
    //
    //     ❌ 배포 설정을 못 찾았다.
    //        ② 환경변수 BTS_DEPLOY_CONFIG 가 가리키는 파일
    //
    //   직전 빌드 #55 의 `rsync --delete` 가 `/opt/bts/infra/deploy/config.sh` 를 지웠다.
    //   그 파일은 gitignored 라 rsync **소스에 없고**, protected 목록에도 없었으므로
    //   대상에서 지워졌다. 즉 **배포가 자기 설정 파일을 지운다.**
    //
    //   ★고장이 한 박자 늦게 온다는 것이 이 결함의 핵심이다. 지우는 것은 3단계(rsync)이고
    //     읽는 것은 1단계다. 그래서 **그 배포는 죽지 않고 다음 배포가 죽는다.**
    //     원인과 증상이 다른 빌드에 있어 로그를 나란히 놓기 전에는 안 보인다.
    //
    //   ★protected-paths.txt 의 머리주석은 「2026-09-11 실측 — 대상에 실재하던 것들」을
    //     전수 조사했다고 적는다. 그 조사가 이 파일을 빠뜨렸다. 사람이 한 번 훑은 목록은
    //     그 시점의 스냅샷이고, 이 판별식이 그것을 계약으로 바꾼다.
    const jf = read('Jenkinsfile')
    const m = jf.match(/BTS_DEPLOY_CONFIG=(\S+)/)
    // ★비-공허 짝. Jenkinsfile 에서 그 변수를 실제로 찾아냈는지 먼저 보인다 —
    //   못 찾으면 아래 검사가 통째로 건너뛰어지고 조용히 초록이 된다.
    assert.ok(m, 'Jenkinsfile 이 BTS_DEPLOY_CONFIG 를 주지 않는다 — 이 검사가 공허하다')
    const abs = m[1]
    assert.match(abs, /^\//, `BTS_DEPLOY_CONFIG 가 절대경로가 아니다: ${abs}`)
    const rel = abs.replace(/^\/opt\/bts\//, '')
    assert.notEqual(rel, abs, `배포 대상 접두사를 못 떼었다 — 경로가 바뀌었나: ${abs}`)
    const protectedPaths = runReader('protected').paths
    assert.ok(
      protectedPaths.includes(rel),
      `배포가 읽는 설정 \`${rel}\` 이 protected 목록에 없다.\n` +
        '  그 파일은 gitignored 라 rsync 소스에 없다. 제외 목록에도 없으면 `--delete` 가\n' +
        '  대상에서 지우고, **다음** 배포가 「배포 설정을 못 찾았다」로 죽는다\n' +
        '  (2026-09-14 빌드 #55 가 지우고 #56 이 죽었다).',
    )
  })
})
