// git 픽스처가 GIT_DIR 를 상속해 진짜 저장소를 오염시키지 않는지 실측하는 판별식
//
// ## 왜 이 파일이 있나
//
// git 은 훅 프로세스에 `GIT_DIR` 를 **export 한다.** 그런데 판별식의 git 픽스처는 `cwd` 로만
// 격리하고 `GIT_DIR` 는 `process.env` 째로 상속했다. **`GIT_DIR` 는 `cwd` 를 이긴다** —
// 그래서 픽스처의 `init`/`add`/`commit` 이 작업 중이던 실저장소에 걸렸다.
// 실피해는 공유 `.git/config` 의 `core.bare = true` · 브랜치 ref 위의 픽스처 커밋 ·
// 인덱스 파괴였다. 같은 명령이 일반 셸에서는 초록이고 pre-push 훅에서만 터진다.
//
// ## 배선 문자열을 재지 않는다
//
// 「스크럽을 부르는가」만 보면 스크럽이 **문법적으로 있는데 아무것도 안 지우는** 경우가
// 그대로 통과한다(`invariant-satisfied-by-helptext-not-logic`). 그래서 이 파일은 실제로
// 임시 저장소(victim)를 세우고 `GIT_DIR` 를 건 채 픽스처를 돌려 **victim 이 변했는지**를 잰다.
//
// ## 이 판별식 자신이 함정에 빠지지 않게 하는 것
//
// 격리를 재려면 이 파일도 `git init` 을 부른다. 그 대상이 실저장소가 되는 순간
// **고치려던 결함을 고치는 코드가 저지르게 된다.** 그래서 victim 을 만드는 경로를
// 코드가 스스로 금지하고(`assertVictimPathSafe`), 만들어진 경로 전량을 마지막 판정이 훑는다.
// victim 을 세우는 git 호출도 **스크럽된 env** 로 부른다 — 이 파일은
// `process.env.GIT_DIR` 가 있든 없든 같은 결과여야 한다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { gitFixtureEnv } from './git-fixture-env.mjs'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/**
 * 이 파일이 실제로 만든 victim 경로 전량.
 *
 * 마지막 판정이 이 목록을 훑어 「언제나 mkdtemp 아래」를 사후 확인한다. 목록이 비면
 * 그 판정은 아무것도 안 지킨 것이므로 **비어 있음 자체를 실패로 다룬다.**
 */
const CREATED_VICTIMS: string[] = []

/**
 * victim 저장소로 삼아도 되는 경로인지 판정한다.
 *
 * 저장소 트리 안이거나 임시 디렉터리 밖이면 던진다. `git init` 이 불리기 **전에** 막는 것이
 * 요점이라 이 함수는 팩토리의 첫 문장에서 불린다.
 *
 * @param candidate 검사할 절대 경로 (존재하지 않아도 된다)
 * @throws {Error} 저장소 트리 안이거나 임시 디렉터리 밖일 때
 */
function assertVictimPathSafe(candidate: string): void {
  const real = resolveExisting(candidate)
  const repoReal = fs.realpathSync(REPO_ROOT)
  const tmpReal = fs.realpathSync(os.tmpdir())
  if (real === repoReal || real.startsWith(repoReal + path.sep)) {
    throw new Error(`victim 이 저장소 트리 안이다 — ${real}`)
  }
  if (real !== tmpReal && !real.startsWith(tmpReal + path.sep)) {
    throw new Error(`victim 이 임시 디렉터리 밖이다 — ${real}`)
  }
}

/**
 * 심볼릭 링크를 푼 절대 경로를 얻는다.
 *
 * macOS 의 `os.tmpdir()` 은 `/var/folders/...` 이고 실체는 `/private/var/folders/...` 다.
 * 한쪽만 풀면 「tmp 아래인가」 판정이 항상 거짓이 된다. 아직 없는 경로도 검사 대상이라
 * 존재하는 조상까지만 풀고 나머지는 이어 붙인다.
 *
 * @param target 절대 경로
 * @returns 실경로
 */
function resolveExisting(target: string): string {
  const absolute = path.resolve(target)
  if (fs.existsSync(absolute)) return fs.realpathSync(absolute)
  const parent = path.dirname(absolute)
  if (parent === absolute) return absolute
  return path.join(resolveExisting(parent), path.basename(absolute))
}

/** victim 저장소의 관측 축 — 오염되면 이 중 하나 이상이 반드시 변한다. */
interface RepoSnapshot {
  commitCount: string
  coreBare: string
  head: string
}

/**
 * victim 의 상태를 읽는다. **스크럽된 env** 로 읽으므로 주변 `GIT_DIR` 에 안 흔들린다.
 *
 * @param root victim 작업 디렉터리
 * @param gitDir victim 의 `.git` 경로
 * @returns 커밋 수 · `core.bare` · HEAD
 */
function snapshotRepo(root: string, gitDir: string): RepoSnapshot {
  const read = (...args: string[]): string => {
    const r = spawnSync('git', ['--git-dir', gitDir, ...args], {
      cwd: root,
      encoding: 'utf-8',
      env: gitFixtureEnv(),
    })
    return r.status === 0 ? r.stdout.trim() : `<exit ${r.status}>`
  }
  return {
    commitCount: read('rev-list', '--count', '--all'),
    coreBare: read('config', '--get', 'core.bare'),
    head: read('rev-parse', 'HEAD'),
  }
}

/**
 * 두 스냅숏에서 **달라진 축을 전수 열거**한다. 개수를 세지 않고 이름을 적는다.
 *
 * @param before 이전 상태
 * @param after 이후 상태
 * @returns `축 before → after` 문자열 목록
 */
function changedAxes(before: RepoSnapshot, after: RepoSnapshot): string[] {
  const keys = Object.keys(before) as (keyof RepoSnapshot)[]
  return keys.filter((k) => before[k] !== after[k]).map((k) => `${k} ${before[k]} → ${after[k]}`)
}

/**
 * victim 저장소를 세운다. 경로 금지를 통과한 자리에만 만든다.
 *
 * @param parentDir mkdtemp 가 내준 임시 부모 디렉터리
 * @returns victim 작업 디렉터리와 `.git` 경로
 */
function createVictimRepo(parentDir: string): { root: string; gitDir: string } {
  const root = path.join(parentDir, 'victim')
  assertVictimPathSafe(root)
  fs.mkdirSync(root, { recursive: true })
  CREATED_VICTIMS.push(root)
  const git = (...args: string[]) => spawnSync('git', args, { cwd: root, encoding: 'utf-8', env: gitFixtureEnv() })
  git('init', '-q', '-b', 'main')
  git('config', 'user.email', 'victim@example.com')
  git('config', 'user.name', 'victim')
  fs.writeFileSync(path.join(root, 'KEEP.md'), 'victim\n')
  git('add', '-A')
  git('commit', '-qm', 'victim base')
  return { root, gitDir: path.join(root, '.git') }
}

/**
 * 기존 픽스처 테스트가 하는 절차(`init` → `add` → `commit`)를 그대로 재현한다.
 *
 * `cwd` 는 언제나 넘긴다 — 스크럽 뒤 git 이 저장소를 찾는 근거가 그것이다.
 *
 * @param workDir 픽스처가 제 저장소를 만들려는 디렉터리
 * @param env git 에 넘길 환경변수
 */
function runFixtureProcedure(workDir: string, env: NodeJS.ProcessEnv): void {
  const git = (...args: string[]) => spawnSync('git', args, { cwd: workDir, encoding: 'utf-8', env })
  git('init', '-q')
  git('config', 'user.email', 'fixture@example.com')
  git('config', 'user.name', 'fixture')
  fs.writeFileSync(path.join(workDir, 'F.kt'), 'fixture\n')
  git('add', '-A')
  git('commit', '-qm', 'base')
}

describe('git 픽스처 격리 — GIT_DIR 상속 차단', () => {
  test('GIT_DIR 가 걸려 있어도 헬퍼로 만든 픽스처가 victim 저장소를 안 바꾼다', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-git-iso-'))
    try {
      const victim = createVictimRepo(tmp)
      const before = snapshotRepo(victim.root, victim.gitDir)

      // 훅이 만드는 상황 그대로 — GIT_DIR 가 victim 을 가리킨 채 픽스처가 돈다.
      const workDir = path.join(tmp, 'work')
      fs.mkdirSync(workDir)
      runFixtureProcedure(workDir, gitFixtureEnv({ ...process.env, GIT_DIR: victim.gitDir }))

      const after = snapshotRepo(victim.root, victim.gitDir)
      assert.deepEqual(
        after,
        before,
        `GIT_DIR 를 건 픽스처가 victim 을 바꿨다 — 스크럽이 실효가 없다.\n` +
          `달라진 축. ${JSON.stringify(changedAxes(before, after))}`,
      )
      // 「아무 일도 안 일어났다」가 통과하지 않게 — 픽스처는 제 자리에 저장소를 만들었어야 한다.
      assert.ok(
        fs.existsSync(path.join(workDir, '.git')),
        '픽스처가 제 작업 디렉터리에 저장소를 안 만들었다 — 이 판정의 전제가 깨졌다',
      )
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true })
    }
  })

  test('★★스크럽을 끄면 같은 절차가 victim 을 실제로 바꾼다 (비-공허 짝)', () => {
    // ★이 짝이 없으면 위 판정은 「원래 아무 일도 안 일어나는 조합」을 지키는 가짜 그린이다.
    //   같은 절차·같은 GIT_DIR 로, 스크럽만 빼고 돌려 victim 이 실제로 오염되는지 본다.
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-git-iso-leak-'))
    try {
      const victim = createVictimRepo(tmp)
      const before = snapshotRepo(victim.root, victim.gitDir)

      const workDir = path.join(tmp, 'work')
      fs.mkdirSync(workDir)
      runFixtureProcedure(workDir, { ...process.env, GIT_DIR: victim.gitDir })

      const after = snapshotRepo(victim.root, victim.gitDir)
      assert.notDeepEqual(
        after,
        before,
        'GIT_DIR 를 상속시켜도 victim 이 그대로다 — 이 결함이 재현되지 않으니 위 판정이 공허하다.\n' +
          `관측한 상태. ${JSON.stringify(after)}`,
      )
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true })
    }
  })

  test('★victim 은 언제나 mkdtemp 아래이고 REPO_ROOT 가 아니다 (자기 함정)', () => {
    // 금지가 실제로 무는지 — 저장소 자신 · 저장소 안 · 임시 디렉터리 밖을 전부 거부해야 한다.
    assert.throws(() => assertVictimPathSafe(REPO_ROOT), /저장소 트리 안/, 'REPO_ROOT 를 victim 으로 허용한다')
    assert.throws(
      () => assertVictimPathSafe(path.join(REPO_ROOT, 'scripts', 'victim')),
      /저장소 트리 안/,
      '저장소 안의 경로를 victim 으로 허용한다',
    )
    assert.throws(
      () => assertVictimPathSafe(path.join(os.homedir(), 'bts-victim')),
      /임시 디렉터리 밖/,
      '임시 디렉터리 밖을 victim 으로 허용한다',
    )

    // 그리고 이 파일이 **실제로 만든** 경로 전량이 그 금지를 지켰어야 한다.
    assert.ok(
      CREATED_VICTIMS.length > 0,
      'victim 을 하나도 안 만들었다 — 이 판정이 훑을 대상이 없어 공허하다. 앞 판정이 먼저 돌아야 한다.',
    )
    const tmpReal = fs.realpathSync(os.tmpdir())
    const repoReal = fs.realpathSync(REPO_ROOT)
    const strayed = CREATED_VICTIMS.filter(
      (v) => !resolveExisting(v).startsWith(tmpReal + path.sep) || resolveExisting(v).startsWith(repoReal + path.sep),
    )
    assert.deepEqual(strayed, [], `mkdtemp 밖에 victim 을 만들었다 — 전수. ${JSON.stringify(strayed)}`)
  })
})
