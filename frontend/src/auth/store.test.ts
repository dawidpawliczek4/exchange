import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import axios, { AxiosError, type AxiosResponse } from 'axios'

import { http } from '@/shared/http'
import { ACCESS_KEY, REFRESH_KEY } from '@/shared/localStorage'
import { useAuthStore } from './store'

const tokens = { accessToken: 'access-1', refreshToken: 'refresh-1' }
const response = (data: unknown) => ({ data }) as AxiosResponse

function unauthorized(): AxiosError {
  const res = {
    status: 401,
    data: { status: 401, title: 'Unauthorized', detail: 'Invalid credentials' },
    statusText: '',
    headers: {},
    config: {},
  } as AxiosResponse
  return new AxiosError('request failed', 'ERR_BAD_REQUEST', undefined, undefined, res)
}

describe('auth store', () => {
  const httpPost = vi.spyOn(http, 'post')
  const axiosPost = vi.spyOn(axios, 'post')

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    httpPost.mockReset()
    axiosPost.mockReset()
  })

  it('login stores both tokens as raw strings and authenticates', async () => {
    httpPost.mockResolvedValue(response(tokens))
    const auth = useAuthStore()

    const result = await auth.login('a@b.com', 'Test123!@')

    expect(result.ok).toBe(true)
    expect(httpPost).toHaveBeenCalledWith('/auth/credentials/login', {
      email: 'a@b.com',
      password: 'Test123!@',
    })
    expect(localStorage.getItem(ACCESS_KEY)).toBe('access-1')
    expect(localStorage.getItem(REFRESH_KEY)).toBe('refresh-1')
    expect(auth.isAuthenticated).toBe(true)
  })

  it('register stores the tokens the same way', async () => {
    httpPost.mockResolvedValue(response(tokens))
    const auth = useAuthStore()

    await auth.register('a@b.com', 'Test123!@')

    expect(httpPost).toHaveBeenCalledWith('/auth/credentials/register', {
      email: 'a@b.com',
      password: 'Test123!@',
    })
    expect(localStorage.getItem(ACCESS_KEY)).toBe('access-1')
    expect(auth.isAuthenticated).toBe(true)
  })

  it('a rejected login leaves storage empty', async () => {
    httpPost.mockRejectedValue(unauthorized())
    const auth = useAuthStore()

    const result = await auth.login('a@b.com', 'wrong')

    expect(result.ok).toBe(false)
    expect(localStorage.getItem(ACCESS_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('a fresh store after a reload is authenticated from storage', async () => {
    httpPost.mockResolvedValue(response(tokens))
    await useAuthStore().login('a@b.com', 'Test123!@')

    setActivePinia(createPinia())

    expect(useAuthStore().isAuthenticated).toBe(true)
  })

  it('logout clears both keys and revokes the refresh token', async () => {
    httpPost.mockResolvedValue(response(tokens))
    const auth = useAuthStore()
    await auth.login('a@b.com', 'Test123!@')
    httpPost.mockResolvedValue(response(undefined))

    await auth.logout()

    expect(httpPost).toHaveBeenLastCalledWith('/auth/session/logout', {
      refreshToken: 'refresh-1',
    })
    expect(localStorage.getItem(ACCESS_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('logout still clears the tokens when the revoke request fails', async () => {
    httpPost.mockResolvedValue(response(tokens))
    const auth = useAuthStore()
    await auth.login('a@b.com', 'Test123!@')
    httpPost.mockRejectedValue(new Error('network'))

    await expect(auth.logout()).resolves.toBeUndefined()

    expect(localStorage.getItem(ACCESS_KEY)).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('clearTokens signs out without a request', async () => {
    httpPost.mockResolvedValue(response(tokens))
    const auth = useAuthStore()
    await auth.login('a@b.com', 'Test123!@')
    httpPost.mockClear()

    auth.clearTokens()
    expect(auth.isAuthenticated).toBe(false)
    await nextTick()

    expect(httpPost).not.toHaveBeenCalled()
    expect(localStorage.getItem(ACCESS_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('concurrent refreshes share one request and replace both tokens', async () => {
    httpPost.mockResolvedValue(response(tokens))
    const auth = useAuthStore()
    await auth.login('a@b.com', 'Test123!@')
    axiosPost.mockResolvedValue(response({ accessToken: 'access-2', refreshToken: 'refresh-2' }))

    const [first, second] = await Promise.all([
      auth.refreshAccessToken(),
      auth.refreshAccessToken(),
    ])

    expect(axiosPost).toHaveBeenCalledTimes(1)
    expect(axiosPost).toHaveBeenCalledWith(expect.stringMatching(/\/auth\/session\/refresh$/), {
      refreshToken: 'refresh-1',
    })
    expect(first).toBe('access-2')
    expect(second).toBe('access-2')
    expect(localStorage.getItem(ACCESS_KEY)).toBe('access-2')
    expect(localStorage.getItem(REFRESH_KEY)).toBe('refresh-2')
  })

  it('refresh rejects without a request when there is no refresh token', async () => {
    const auth = useAuthStore()

    await expect(auth.refreshAccessToken()).rejects.toThrow('Refresh token is missing')

    expect(axiosPost).not.toHaveBeenCalled()
  })
})
