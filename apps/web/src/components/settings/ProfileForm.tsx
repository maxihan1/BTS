// 사용자 프로필 편집 폼 — 조회 프리필 + 3-state PATCH + 아바타 업로드/삭제 (FR-PR-01 D6 Task 7)
import type { JSX, ChangeEvent, FormEvent } from 'react'
import { useMemo, useState } from 'react'
import { useProfile, usePatchProfile, useUploadAvatar, useDeleteAvatar } from '@/api/useProfile'
import { buildPatchBody } from '@/api/profile'
import type { ProfileResponse } from '@/api/profile'
import { ApiError } from '@/api/client'
import { Avatar } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { profileLabels, mapProfileError, mapAvatarError } from '@/i18n/profile-labels'

/**
 * `Intl.supportedValuesOf('timeZone')` 전체 목록에 현재 값이 없으면 추가한다(EC3 방어).
 * 서버 저장값이 IANA 타임존이라면 목록에 있어야 하지만, 방어적으로 처리한다.
 */
function buildTimezoneOptions(currentTimezone: string): string[] {
  const zones = Intl.supportedValuesOf('timeZone')
  return zones.includes(currentTimezone) ? zones : [currentTimezone, ...zones]
}

/**
 * 프로필 편집 폼 진입점 — 조회 로딩/에러를 게이트하고 성공 시 {@link ProfileFormContent}를 렌더한다.
 *
 * @returns 프로필 편집 폼 JSX
 */
export function ProfileForm(): JSX.Element {
  const { data: profile, isLoading, isError } = useProfile()

  if (isLoading) {
    return <p className="text-sm text-muted-foreground">{profileLabels.status.loading}</p>
  }

  if (isError || profile === undefined) {
    return (
      <p role="alert" aria-live="polite" className="text-sm text-destructive">
        {profileLabels.status.loadError}
      </p>
    )
  }

  return <ProfileFormContent profile={profile} />
}

interface ProfileFormContentProps {
  readonly profile: ProfileResponse
}

/**
 * 실제 편집 폼 — 프로필 조회 성공 후에만 렌더된다.
 * displayName/timezone/department 로컬 편집 상태를 소유하고, 저장/아바타 업로드·삭제를 처리한다.
 */
function ProfileFormContent({ profile }: ProfileFormContentProps): JSX.Element {
  const patchProfile = usePatchProfile()
  const uploadAvatar = useUploadAvatar()
  const deleteAvatar = useDeleteAvatar()

  const [displayName, setDisplayName] = useState(profile.displayName)
  const [timezone, setTimezone] = useState(profile.timezone)
  const [department, setDepartment] = useState(profile.department ?? '')
  const [saveSuccess, setSaveSuccess] = useState(false)

  // Intl.supportedValuesOf('timeZone')는 417개 고정 목록 순회라 렌더마다 재계산할 필요가 없다 —
  // profile.timezone(초기 서버 값)이 바뀌지 않는 한 재사용한다.
  const timezoneOptions = useMemo(() => buildTimezoneOptions(profile.timezone), [profile.timezone])
  const isSaveDisabled = displayName.trim().length === 0 || patchProfile.isPending

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    setSaveSuccess(false)
    patchProfile.reset()

    if (displayName.trim().length === 0) {
      return
    }

    const body = buildPatchBody(
      { displayName: profile.displayName, timezone: profile.timezone, department: profile.department },
      { displayName, timezone, department },
    )

    patchProfile.mutate(body, {
      onSuccess: (updated) => {
        setDisplayName(updated.displayName)
        setTimezone(updated.timezone)
        setDepartment(updated.department ?? '')
        setSaveSuccess(true)
      },
    })
  }

  function handleAvatarChange(e: ChangeEvent<HTMLInputElement>): void {
    const file = e.target.files?.[0]
    // 같은 파일 재선택도 change를 재발화하도록 즉시 초기화(EC9) — 업로드 성공/실패와 무관
    e.target.value = ''
    if (file === undefined) return

    uploadAvatar.reset()
    uploadAvatar.mutate(file)
  }

  function handleAvatarDelete(): void {
    deleteAvatar.reset()
    deleteAvatar.mutate()
  }

  const avatarBusy = uploadAvatar.isPending || deleteAvatar.isPending

  const patchErrorMessage =
    patchProfile.error instanceof ApiError ? mapProfileError(patchProfile.error) : null
  const avatarError = uploadAvatar.error ?? deleteAvatar.error
  const avatarErrorMessage = avatarError instanceof ApiError ? mapAvatarError(avatarError) : null
  const errorMessage = patchErrorMessage ?? avatarErrorMessage

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Avatar
          avatarUrl={profile.avatarUrl}
          displayName={profile.displayName}
          username={profile.username}
          size="lg"
        />
        <div className="space-y-2">
          <Label htmlFor="profile-avatar-file">{profileLabels.avatar.fileInputLabel}</Label>
          <input
            id="profile-avatar-file"
            type="file"
            accept="image/*"
            disabled={avatarBusy}
            onChange={handleAvatarChange}
          />
          <div>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={avatarBusy}
              onClick={handleAvatarDelete}
            >
              {deleteAvatar.isPending ? profileLabels.avatar.deletingButton : profileLabels.avatar.deleteButton}
            </Button>
          </div>
        </div>
      </div>

      {saveSuccess && (
        <div role="status" className="rounded-lg bg-primary/10 p-3 text-sm text-primary">
          {profileLabels.status.saveSuccess}
        </div>
      )}

      {errorMessage !== null && (
        <div
          role="alert"
          aria-live="polite"
          className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
        >
          {errorMessage}
        </div>
      )}

      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        <div className="space-y-1.5">
          <Label htmlFor="profile-username">{profileLabels.form.usernameLabel}</Label>
          <Input id="profile-username" value={profile.username} readOnly />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="profile-email">{profileLabels.form.emailLabel}</Label>
          <Input id="profile-email" value={profile.email ?? ''} readOnly />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="profile-display-name">{profileLabels.form.displayNameLabel}</Label>
          <Input
            id="profile-display-name"
            value={displayName}
            onChange={(e) => { setDisplayName(e.target.value) }}
            disabled={patchProfile.isPending}
          />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="profile-timezone">{profileLabels.form.timezoneLabel}</Label>
          <select
            id="profile-timezone"
            value={timezone}
            onChange={(e) => { setTimezone(e.target.value) }}
            disabled={patchProfile.isPending}
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-sm"
          >
            {timezoneOptions.map((zone) => (
              <option key={zone} value={zone}>
                {zone}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="profile-department">{profileLabels.form.departmentLabel}</Label>
          <Input
            id="profile-department"
            value={department}
            onChange={(e) => { setDepartment(e.target.value) }}
            disabled={patchProfile.isPending}
          />
        </div>

        <div>
          <Button type="submit" disabled={isSaveDisabled}>
            {patchProfile.isPending ? profileLabels.form.savingButton : profileLabels.form.saveButton}
          </Button>
        </div>
      </form>
    </div>
  )
}
