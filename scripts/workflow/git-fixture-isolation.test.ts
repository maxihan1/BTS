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
import { deriveGitSpawnCallSites, deriveWiringSets, wiringMismatch } from './git-spawn-sweep.ts'

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
  /** 인덱스에 올라 있는 경로. `GIT_INDEX_FILE` 오염은 커밋·ref 를 안 건드리고 여기만 바꾼다. */
  indexEntries: string
}

/**
 * victim 의 상태를 읽는다. **스크럽된 env** 로 읽으므로 주변 `GIT_DIR` 에 안 흔들린다.
 *
 * @param root victim 작업 디렉터리
 * @param gitDir victim 의 `.git` 경로
 * @returns 커밋 수 · `core.bare` · HEAD · 인덱스 경로
 */
function snapshotRepo(root: string, gitDir: string): RepoSnapshot {
  const read = (...args: string[]): string => {
    // ★이 호출은 **일부러 여러 줄로** 쓴다 — 호출 이름과 프로그램 이름이 다른 줄에 있고
    //   옵션 객체도 줄을 넘긴다. 아래 호출부 비-공허 짝이 이 형태를 근거로 삼는다.
    //   스캐너가 줄 단위로 썩으면 여기가 집합에서 빠져 그 짝이 red 가 된다. 한 줄로 접지 마라.
    const r = spawnSync(
      'git',
      ['--git-dir', gitDir, ...args],
      {
        cwd: root,
        encoding: 'utf-8',
        env: gitFixtureEnv(),
      },
    )
    return r.status === 0 ? r.stdout.trim() : `<exit ${r.status}>`
  }
  return {
    commitCount: read('rev-list', '--count', '--all'),
    coreBare: read('config', '--get', 'core.bare'),
    head: read('rev-parse', 'HEAD'),
    indexEntries: read('ls-files'),
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
 * @param name 부모 아래 만들 디렉터리 이름. 한 판정이 저장소를 둘 이상 세울 때 갈라 쓴다
 * @returns victim 작업 디렉터리와 `.git` 경로
 */
function createVictimRepo(parentDir: string, name = 'victim'): { root: string; gitDir: string } {
  const root = path.join(parentDir, name)
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

/**
 * 비-공허 짝이 픽스처에 넘기는 env — **일부러** `GIT_DIR` 를 걸어 오염을 재현시킨다.
 *
 * 짝과 그 짝을 검사하는 판정이 각자 env 를 조립하면 두 벌이 되고 서로를 안 본다. 한 자리만 둔다.
 *
 * @param gitDir victim 의 `.git` 경로
 * @returns 픽스처에 넘길 환경변수
 */
function leakEnv(gitDir: string): NodeJS.ProcessEnv {
  return { ...process.env, GIT_DIR: gitDir }
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
      // 「아무 일도 안 일어났다」가 통과하지 않게 — 픽스처는 제 저장소에 **커밋을 만들었어야** 한다.
      // ★`.git` 존재만 보면 이 판정이 자립하지 못한다. victim 생성이나 픽스처 절차가 통째로
      //   실패하면 `snapshotRepo` 가 양쪽 다 `<exit …>` 를 돌려줘 before === after 가 되고,
      //   격리가 없어도 초록이 된다. 그래서 전제를 **값으로** 확인한다 — 커밋 수를 실제로 읽는다.
      const fixtureOwn = snapshotRepo(workDir, path.join(workDir, '.git'))
      assert.match(
        fixtureOwn.commitCount,
        /^[1-9][0-9]*$/,
        '픽스처가 제 저장소에 커밋을 하나도 못 만들었다 — 이 판정의 전제가 깨졌다.\n' +
          `victim 이 안 변한 것은 격리가 아니라 픽스처 생성 실패다. 관측. ${JSON.stringify(fixtureOwn)}`,
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
      runFixtureProcedure(workDir, leakEnv(victim.gitDir))

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

  test('★★비-공허 짝이 주변 GIT_* 를 함께 상속하지 않는다 — 제3 저장소 무손상', () => {
    // ★`GIT_DIR` 하나만 덮어쓰고 나머지를 상속하면 `GIT_INDEX_FILE` 이 그 `GIT_DIR` 를 **이긴다** —
    //   짝이 오염시키는 곳이 제 victim 이 아니라 그 변수가 가리키는 **제3의 저장소**가 된다.
    //   훅 아래에서 그 자리는 작업 중이던 진짜 저장소였고, 인덱스가 통째로 비었다.
    //   경로 금지(`assertVictimPathSafe`)는 victim **경로**만 지키므로 이 방향을 원리적으로 못 본다.
    //   그래서 짝이 실제로 넘기는 env 를 제3의 저장소(bystander)로 실측한다.
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-git-iso-bystander-'))
    const savedIndexFile = process.env.GIT_INDEX_FILE
    try {
      const victim = createVictimRepo(tmp, 'victim')
      const bystander = createVictimRepo(tmp, 'bystander')
      const victimBefore = snapshotRepo(victim.root, victim.gitDir)
      const before = snapshotRepo(bystander.root, bystander.gitDir)

      const workDir = path.join(tmp, 'work')
      fs.mkdirSync(workDir)
      // git 이 pre-commit 훅에 실제로 심는 그대로 — `GIT_DIR` 와 `GIT_INDEX_FILE` 가 함께 온다.
      process.env.GIT_INDEX_FILE = path.join(bystander.gitDir, 'index')
      runFixtureProcedure(workDir, leakEnv(victim.gitDir))

      const after = snapshotRepo(bystander.root, bystander.gitDir)
      assert.deepEqual(
        after,
        before,
        '비-공허 짝이 GIT_DIR 밖의 GIT_* 를 상속해 제3의 저장소를 건드렸다.\n' +
          '짝의 바탕 env 를 스크럽하고 GIT_DIR 하나만 얹어라 — 짝이 오염시켜도 되는 곳은\n' +
          '그 짝이 제 손으로 만든 victim 뿐이다.\n' +
          `달라진 축. ${JSON.stringify(changedAxes(before, after))}`,
      )

      // 짝이 여전히 무는지 — 바탕을 스크럽해도 GIT_DIR 하나로 victim 은 계속 오염돼야 한다.
      // 이 단언이 없으면 위 deepEqual 은 「짝이 아무것도 안 한다」로도 통과한다.
      const victimAfter = snapshotRepo(victim.root, victim.gitDir)
      assert.notDeepEqual(
        victimAfter,
        victimBefore,
        '짝이 제 victim 조차 안 바꿨다 — 오염 재현이 죽었으니 이 파일의 비-공허성이 통째로 공허하다.\n' +
          `관측한 상태. ${JSON.stringify(victimAfter)}`,
      )
    } finally {
      if (savedIndexFile === undefined) delete process.env.GIT_INDEX_FILE
      else process.env.GIT_INDEX_FILE = savedIndexFile
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

// ─────────────────────────────────────────────────────────
// git 을 spawn 하는 전량이 헬퍼를 거치는지 — 소스에서 재계산한다
// ─────────────────────────────────────────────────────────
//
// ## 층위가 둘이다 — 파일 단위는 호출 하나를 빠뜨린 파일을 못 본다
//
// 이미 배선된 파일에 스크럽 없는 git 호출을 더 붙이면 파일 단위 대조는 초록이다.
// 실제로 이 배선 작업 중에 `select-backend-modules.test.ts` 의 여러 줄로 쓴 호출이 그렇게
// 빠졌고, 잡아낸 것은 `GIT_DIR` 를 건 채 판별식 전량을 돌린 실측이었다.
// 요구는 「호출 단위」인데 판정이 「파일 단위」였다 — 두 층위가 어긋난 그 틈으로 샜다.
//
// 그래서 아래 두 describe 가 층위를 나눠 맡는다. 파일 단위는 「헬퍼를 아예 안 쓰는 파일」을,
// 호출 단위는 「쓰는데 일부 호출을 빠뜨린 파일」을 잡는다. 둘 다 소스에서 재계산되므로
// 사람이 유지하는 목록은 어느 쪽에도 없다.
//
// 호출 단위 규칙을 「스크럽 헬퍼를 부른다」로 쓰지 않는 이유는 이 파일의 비-공허 짝이
// **일부러** 스크럽 없이 부르기 때문이다. 그렇게 쓰면 그 한 자리를 살리려고 사람이 적는
// 예외 목록이 되살아나고, 그 목록이 red 를 끄는 가장 싼 방법이 된다.
// 「env 를 명시했는가」로 쓰면 그 자리도 자연히 통과하므로 **예외가 0개**다.

/**
 * 파생 집합이 반드시 물어야 하는 픽스처 생성자 — 이 스윕의 **비-공허 짝**이다.
 *
 * 이름을 더하면 판정이 엄해지기만 한다. 얹어서 red 를 끌 수 없다는 점이 예외 목록과
 * 다른 자리다. 호출 형태를 놓쳐 파생 집합이 비면 양방향 대조가 `빈집합 == 빈집합` 으로
 * 조용히 통과하는데, 그 자리를 이 상수가 막는다.
 */
const KNOWN_FIXTURE_CREATORS = [
  'scripts/workflow/select-backend-modules.test.ts',
  'scripts/workflow/todos-reorder-integrity.test.ts',
]

describe('git 을 spawn 하는 전량이 스크럽 헬퍼를 거친다 (파생집합 양방향)', () => {
  test('★★파생 집합이 비어 있지 않고 알려진 픽스처 생성자를 실제로 문다 (비-공허 짝)', () => {
    const { spawners } = deriveWiringSets()
    assert.notDeepEqual(
      spawners,
      [],
      'git 을 spawn 하는 파일을 하나도 못 찾았다 — 호출 형태를 놓친 것이다.\n' +
        '이대로면 아래 양방향 대조가 `빈집합 == 빈집합` 으로 조용히 통과한다.',
    )
    const missed = KNOWN_FIXTURE_CREATORS.filter((f) => !spawners.includes(f))
    assert.deepEqual(
      missed,
      [],
      '임시 저장소를 세우는 것으로 알려진 파일이 파생 집합에서 빠졌다 — 스윕이 썩었다.\n' +
        `빠진 것 전수. ${JSON.stringify(missed)}\n` +
        `실제 파생 집합 전수. ${JSON.stringify(spawners)}`,
    )
  })

  test('★★git 을 spawn 하는 파일 집합과 헬퍼 임포트 집합이 양방향으로 같다', () => {
    const { unscrubbed, stray } = wiringMismatch(deriveWiringSets())
    assert.deepEqual(
      { 'git 을 부르는데 헬퍼를 안 거친다': unscrubbed, '헬퍼를 임포트하는데 git 을 안 부른다': stray },
      { 'git 을 부르는데 헬퍼를 안 거친다': [], '헬퍼를 임포트하는데 git 을 안 부른다': [] },
      'git 을 spawn 하는 파일과 스크럽 헬퍼를 거치는 파일이 어긋난다.\n' +
        '앞쪽은 훅 안에서 GIT_DIR 를 상속해 실저장소를 건드릴 수 있는 자리다 — 헬퍼로 배선하라.\n' +
        '뒤쪽은 배선만 남은 자리이거나, 호출 형태를 파생 집합이 놓친 자리다.\n' +
        '예외 선언은 두지 않는다 — 목록에 한 줄 얹는 것이 red 를 끄는 가장 싼 방법이 되기 때문이다.',
    )
  })
})

/**
 * 이 판별식 파일 자신의 저장소 상대 경로.
 *
 * 형태 앵커를 **제 파일 안**에 둔다. 다른 파일의 서식에 기대면 그쪽을 한 줄로 접는 순간
 * 여기가 red 인데 고칠 자리는 저기라 다음 사람이 판정을 지우는 쪽으로 기운다.
 */
const SELF = path.relative(REPO_ROOT, fileURLToPath(import.meta.url))

describe('git 을 spawn 하는 호출부 전량이 env 를 명시한다 (호출 단위)', () => {
  test('★★옵션 객체에 env 키가 없는 호출부가 하나도 없다', () => {
    const bare = deriveGitSpawnCallSites()
      .filter((site) => !site.hasEnv)
      .map((site) => `${site.file}:${site.line} ${site.callee}`)
    assert.deepEqual(
      bare,
      [],
      'git 을 부르면서 env 를 스스로 정하지 않은 호출부가 있다 — 훅 안에서 GIT_DIR 를 상속해\n' +
        '실저장소의 인덱스·ref·config 를 건드릴 수 있는 자리다. 스크럽 헬퍼로 배선하라.\n' +
        '파일 단위 대조는 이 자리를 못 본다 — 이미 배선된 파일에 호출을 하나 더 붙인 형태라\n' +
        '그쪽 집합은 그대로 초록이다. 그래서 이 판정이 따로 있다.\n' +
        `자리 전수. ${JSON.stringify(bare, null, 2)}`,
    )
  })

  test('★★호출부 파생 집합이 비어 있지 않고 여러 줄로 쓴 호출까지 문다 (비-공허 짝)', () => {
    // 정규식이 썩어 집합이 비면 위 판정이 `빈집합 == 빈집합` 으로 조용히 통과한다.
    const sites = deriveGitSpawnCallSites()
    assert.notDeepEqual(
      sites,
      [],
      'git 을 spawn 하는 호출부를 하나도 못 찾았다 — 호출 형태를 놓친 것이다.\n' +
        '이대로면 위 판정이 아무것도 안 지킨다.',
    )

    const files = [...new Set(sites.map((site) => site.file))]
    const missed = KNOWN_FIXTURE_CREATORS.filter((f) => !files.includes(f))
    assert.deepEqual(
      missed,
      [],
      '임시 저장소를 세우는 것으로 알려진 파일에서 호출부를 하나도 못 찾았다 — 스캐너가 썩었다.\n' +
        `빠진 것 전수. ${JSON.stringify(missed)}\n` +
        `호출부를 찾은 파일 전수. ${JSON.stringify(files)}`,
    )

    // ★형태 앵커. 이 파일의 `snapshotRepo` 호출은 일부러 줄을 넘겨 쓴다 — 줄 단위로 보는
    //   스캐너였다면 놓쳤을 형태이고, 실제로 그 형태가 이번에 빠져나갔다.
    const acrossLines = sites
      .filter((site) => site.file === SELF && site.calleeSpansLines && site.optionsSpanLines)
      .map((site) => `${site.file}:${site.line}`)
    assert.notDeepEqual(
      acrossLines,
      [],
      '여러 줄에 걸쳐 쓴 호출을 이 스윕이 더는 못 문다 — 줄 단위로 좁혀졌거나 앵커가 접혔다.\n' +
        '앵커는 이 파일의 snapshotRepo 안에 있다. 그 호출을 한 줄로 접었다면 되돌려라.\n' +
        `이 파일에서 찾은 호출부 전수. ${JSON.stringify(sites.filter((s) => s.file === SELF))}`,
    )
  })
})
