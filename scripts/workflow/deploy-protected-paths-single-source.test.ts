// 배포가 지키는 「운영 상태」 목록이 한 곳에만 사는지 — 그리고 그 읽기가 실제로 도는지 본다
//
// ★무엇을 막나. `/opt/bts` 에는 저장소가 모르는 운영 상태가 산다(DB 덤프·운영 비밀).
//   그 목록을 이 소비자가 쓴다.
//     infra/deploy/bts-deploy.sh     rsync `--delete` 제외 — 빠지면 **지워진다**
//   목록을 스크립트에 다시 적으면 두 벌이 되고 한쪽만 고쳐진다 — 이 저장소의 지배 결함
//   양식(two-lists-never-check-each-other)이다.
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

  test('★★소비자가 읽기 스크립트를 거친다 — 목록을 직접 읽지 않는다', () => {
    for (const consumer of ['infra/deploy/bts-deploy.sh']) {
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

  test('★★소비자가 보호 경로를 하드코딩하지 않는다', () => {
    // ★비-공허의 핵심. 목록을 파일로 빼 놓고 스크립트에 그대로 복사해 두면 파일은 장식이 된다.
    //   주석은 허용한다 — 왜 지키는지 적어야 하고, 그것이 두 번째 목록이 되지는 않는다.
    const { paths } = runReader()
    const offenders: string[] = []
    for (const consumer of ['infra/deploy/bts-deploy.sh']) {
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
    //   그 디렉터리는 gitignore 라 새 체크아웃에 **없고**, 로컬에서 손으로 mkdir 한 것이
    //   초록의 이유였다(2026-09-11 ENOENT 로 적발). 「내가 초록을 만들려고 한 행동이 다른
    //   체크아웃에는 없다」는 전형이다.
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

describe('③ 지우면 안 되는 것 ⊋ 읽히면 안 되는 것', () => {
  // ★★의미가 다른 둘을 한 목록에 누르면 한쪽이 다른 쪽을 망가뜨린다.
  //
  //   2026-09-11 실측. 처음에는 목록이 하나였고 chown 복구가 그것 전부를 root 로
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
        '  운영 비밀을 배포가 지워 버리면 다음 기동부터 서비스가 안 뜬다.',
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
    //   `infra/prod/.env` 가 secret 목록에 있어 root 600 으로 잠겼는데,
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
        '\n  그 경로는 정해진 소유자로 잠긴다 — 배포가 읽으면\n' +
        '  `permission denied` 로 죽는다 (2026-09-14 빌드 #55 실측).\n' +
        '  읽어야 하는 파일이라면 secret 에서 빼라. protected 에는 남겨야 한다.',
    )
  })

  test('★secret 목록 파일이 실재한다', () => {
    assert.ok(fs.existsSync(path.join(REPO_ROOT, SECRETS)), `${SECRETS} 이 없다`)
  })
})

describe('④ 「읽으면 안 된다」는 누구에게인가 — secret 복구는 소유자를 지정한다', () => {
  /*
   * ★★2026-09-14 실측. 배포는 성공했는데 **운영 백엔드가 크래시루프**에 빠졌다.
   *
   *     java.io.FileNotFoundException: /secrets/bts-jwt.pem (Permission denied)
   *     PEM 파일 파싱 실패 (EC-19) → Spring 기동 실패
   *
   *   secret 목록을 **`chown -R root:root` 로 일괄** 되돌렸다.
   *   `infra/secrets` 가 그 목록에 있고, 그 안의 JWT 서명키를 **백엔드 컨테이너
   *   (uid 999)가 읽어야 한다.** root 400 이 되면 못 읽는다.
   *
   *   ★잠복이 이 결함의 본체다. 잠긴 시각은 9/11 16:27(ctime 실측)인데 증상은 9/14
   *     컨테이너가 재생성될 때 나왔다. 이미 떠 있던 백엔드는 기동 때 읽은 키를 메모리에
   *     들고 있었다. 사흘 동안 「운영 정상」이면서 **재시작하면 죽는** 상태였다.
   *
   *   ★원인은 의도와 구현의 **범위 차이**다. 목록의 뜻은 「아무나 읽으면 안 된다」인데
   *     구현은 「root 말고 아무도 못 읽는다」였다.
   *
   *   ★처방은 권한을 푸는 것이 아니라 **소유자를 정확히 지정하는 것**이다.
   *     JWT 키를 uid 999 소유로 두면 백엔드만 읽는다 —
   *     목록의 원래 의도가 그제서야 정확히 구현된다.
   */

  test('★양성 대조군 — secret 목록을 실제로 읽어냈다', () => {
    const { paths, status, stderr } = runReader('secret')
    assert.equal(status, 0, `secret 목록이 exit ${status} 로 죽었다\n${stderr}`)
    assert.ok(paths.length > 0, 'secret 목록이 비었다 — 이 검사가 공허하다')
  })

  test('★★secret 의 모든 경로에 소유자 uid 가 붙어 있다', () => {
    // 소유자를 안 적으면 「누가 읽어야 하는가」를 아무도 모르고, 구현은 root 로
    // 되돌아간다 — 그것이 이 결함의 시작이었다.
    const r = spawnSync('bash', [path.join(REPO_ROOT, READER), 'secret-owners'], {
      encoding: 'utf-8',
    })
    assert.equal(
      r.status,
      0,
      `읽기 스크립트가 secret-owners 종류를 모른다 — 소유자를 지정할 방법이 없다\n${r.stderr}`,
    )
    const rows = (r.stdout ?? '').split('\n').filter((l) => l.trim() !== '')
    assert.ok(rows.length > 0, 'secret-owners 가 0건이다')
    const orphans = rows.filter((l) => !/^\S+\t\d+$/.test(l))
    assert.deepEqual(
      orphans,
      [],
      `소유자 uid 가 없거나 숫자가 아닌 줄이 있다: ${orphans.join(' / ')}`,
    )
    // ★비-공허 짝. 경로만 내는 기존 종류와 **건수가 같아야** 한다 —
    //   한쪽이 조용히 줄면 되돌리지 않는 경로가 생긴다.
    assert.equal(
      rows.length,
      runReader('secret').paths.length,
      'secret 과 secret-owners 의 건수가 다르다 — 한쪽이 경로를 빠뜨린다',
    )
  })

  test('★알 수 없는 종류는 여전히 조용히 통과하지 않는다', () => {
    // 종류를 하나 늘렸다고 오타가 빈 목록으로 흘러가서는 안 된다.
    const r = spawnSync('bash', [path.join(REPO_ROOT, READER), 'secret-owner'], {
      encoding: 'utf-8',
    })
    assert.notEqual(r.status, 0, '오타(secret-owner)인데 exit 0 이다')
  })

  test('★★JWT 키 소유자가 백엔드 이미지의 uid 와 같다', () => {
    /*
     * ★★목록에 숫자를 적는 순간 그것은 **두 번째 목록**이다. 정본은 이미지의 uid 이고,
     *   목록은 그 사본이다 — 둘이 갈리면 파일은 아무도 못 읽는 소유자에게 잠긴다.
     *
     * ★그래서 이미지 쪽에 uid 를 **고정**해야 이 대조가 성립한다. 종전 Dockerfile 은
     *   `useradd -r` 로 uid 를 **자동 할당**받았다 — 999 는 그때 시스템에 남아 있던
     *   번호였을 뿐이고, 베이스 이미지가 바뀌면 조용히 달라진다. 그 상태에서 목록에
     *   999 를 적으면 「오늘은 맞는」 숫자가 된다.
     */
    const dockerfile = read('infra/prod/Dockerfile.backend')
    const m = dockerfile.match(/useradd[^\n]*?-u\s+(\d+)/)
    assert.ok(
      m,
      'Dockerfile.backend 가 uid 를 고정하지 않는다 (`useradd -r -u <uid>`).\n' +
        '  `useradd -r` 만 쓰면 uid 를 자동 할당받아 이미지마다 달라질 수 있고,\n' +
        '  그러면 secret 목록의 소유자 숫자와 조용히 갈린다.',
    )
    const imageUid = m[1]
    const r = spawnSync('bash', [path.join(REPO_ROOT, READER), 'secret-owners'], {
      encoding: 'utf-8',
    })
    assert.equal(r.status, 0, `secret-owners 를 못 읽었다\n${r.stderr}`)
    const row = (r.stdout ?? '')
      .split('\n')
      .find((l) => l.startsWith('infra/secrets\t'))
    assert.ok(row, 'secret 목록에 infra/secrets 가 없다 — JWT 서명키가 노출된다')
    const listedUid = row.split('\t')[1]
    assert.equal(
      listedUid,
      imageUid,
      `JWT 키 소유자로 적힌 uid(${listedUid})가 백엔드 이미지의 uid(${imageUid})와 다르다.\n` +
        '  그 소유자로 chown 하면 백엔드가 키를 못 읽고 기동에서 죽는다\n' +
        '  — `PEM 파일 파싱 실패 ... (Permission denied) (EC-19)` (2026-09-14 실측).',
    )
  })
})
