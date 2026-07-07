// 사용자 프로필 편집 폼 — 조회 프리필 + 3-state PATCH + 아바타 업로드/삭제 (FR-PR-01 D6 Task 7)
// + LDAP 출처 배지/재설정(FR-PR-04)
import type { JSX, ChangeEvent, FormEvent } from 'react'
import { useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useProfile, usePatchProfile, useUploadAvatar, useDeleteAvatar, PROFILE_QUERY_KEY } from '@/api/useProfile'
import { buildPatchBody, resyncDisplayName } from '@/api/profile'
import type { ProfileResponse } from '@/api/profile'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/auth/authStore'
import { Avatar } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { profileLabels, mapProfileError, mapAvatarError } from '@/i18n/profile-labels'

/** displayName 입력 필드의 출처 힌트 문구를 연결하는 `aria-describedby` 대상 id */
const DISPLAY_NAME_SOURCE_HINT_ID = 'profile-display-name-source-hint'

/** `Intl.supportedValuesOf('timeZone')` 목록에 현재 값이 없으면 추가한다(EC3 방어). */
function buildTimezoneOptions(currentTimezone: string): string[] {
  const zones = Intl.supportedValuesOf('timeZone')
  return zones.includes(currentTimezone) ? zones : [currentTimezone, ...zones]
}

/**
 * 프로필 편집 폼 진입점 — 조회 로딩/에러 게이트 후 {@link ProfileFormContent}를 렌더한다.
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

/** 실제 편집 폼 — 조회 성공 후에만 렌더. displayName/timezone/department 편집 상태 소유. */
function ProfileFormContent({ profile }: ProfileFormContentProps): JSX.Element {
  const patchProfile = usePatchProfile()
  const uploadAvatar = useUploadAvatar()
  const deleteAvatar = useDeleteAvatar()
  const avatarVersion = useAuthStore((s) => s.avatarVersion)
  const queryClient = useQueryClient()

  // FR-PR-04 — "LDAP 값으로 재설정". invalidate-only(캐시 직접 덮어쓰기 금지) —
  // 응답을 setQueryData로 그대로 반영하면 이 요청에 없는 다른 파생 필드가 유실될 수 있다
  // (mutation-setquerydata-partial-response-flicker 선례). refetch된 profile.displayNameSource가
  // 곧바로 배지에 반영된다.
  const resyncDisplayNameMutation = useMutation<ProfileResponse, ApiError, void>({
    mutationFn: resyncDisplayName,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PROFILE_QUERY_KEY }),
  })

  const [displayName, setDisplayName] = useState(profile.displayName)
  const [timezone, setTimezone] = useState(profile.timezone)
  const [department, setDepartment] = useState(profile.department ?? '')
  const [saveSuccess, setSaveSuccess] = useState(false)

  // Intl.supportedValuesOf 결과(417개 고정 목록)는 profile.timezone이 바뀔 때만 재계산한다.
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

  function handleResyncDisplayName(): void {
    resyncDisplayNameMutation.reset()
    resyncDisplayNameMutation.mutate()
  }

  const avatarBusy = uploadAvatar.isPending || deleteAvatar.isPending
  const patchErrorMessage =
    patchProfile.error instanceof ApiError ? mapProfileError(patchProfile.error) : null
  const avatarError = uploadAvatar.error ?? deleteAvatar.error
  const avatarErrorMessage = avatarError instanceof ApiError ? mapAvatarError(avatarError) : null
  const resyncErrorMessage =
    resyncDisplayNameMutation.error instanceof ApiError
      ? mapProfileError(resyncDisplayNameMutation.error)
      : null
  const errorMessage = patchErrorMessage ?? avatarErrorMessage ?? resyncErrorMessage

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Avatar
          avatarUrl={profile.avatarUrl} displayName={profile.displayName}
          username={profile.username} size="lg" cacheBust={avatarVersion}
        />
        <div className="space-y-2">
          <Label htmlFor="profile-avatar-file">{profileLabels.avatar.fileInputLabel}</Label>
          <input
            id="profile-avatar-file" type="file" accept="image/*"
            disabled={avatarBusy} onChange={handleAvatarChange}
          />
          <div>
            <Button
              type="button" variant="outline" size="sm"
              disabled={avatarBusy} onClick={handleAvatarDelete}
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
        <div role="alert" aria-live="polite" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
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
            id="profile-display-name" value={displayName} disabled={patchProfile.isPending}
            aria-describedby={profile.ldapLinked ? DISPLAY_NAME_SOURCE_HINT_ID : undefined}
            onChange={(e) => { setDisplayName(e.target.value) }}
          />
          {profile.ldapLinked && (
            <div className="space-y-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
                  {profile.displayNameSource === 'LDAP'
                    ? profileLabels.ldapSource.syncedBadge
                    : profileLabels.ldapSource.overriddenBadge}
                </span>
                {profile.displayNameSource === 'USER' && (
                  <Button
                    type="button" variant="link" size="sm" className="h-auto p-0 text-xs"
                    disabled={resyncDisplayNameMutation.isPending} onClick={handleResyncDisplayName}
                  >
                    {resyncDisplayNameMutation.isPending
                      ? profileLabels.ldapSource.resyncingButton
                      : profileLabels.ldapSource.resyncButton}
                  </Button>
                )}
              </div>
              <p id={DISPLAY_NAME_SOURCE_HINT_ID} className="text-xs text-muted-foreground">
                {profile.displayNameSource === 'LDAP'
                  ? profileLabels.ldapSource.syncedHint
                  : profileLabels.ldapSource.overriddenHint}
              </p>
            </div>
          )}
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="profile-timezone">{profileLabels.form.timezoneLabel}</Label>
          <select
            id="profile-timezone" value={timezone} disabled={patchProfile.isPending}
            onChange={(e) => { setTimezone(e.target.value) }}
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-sm"
          >
            {timezoneOptions.map((zone) => (
              <option key={zone} value={zone}>{zone}</option>
            ))}
          </select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="profile-department">{profileLabels.form.departmentLabel}</Label>
          <Input
            id="profile-department" value={department} disabled={patchProfile.isPending}
            onChange={(e) => { setDepartment(e.target.value) }}
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
