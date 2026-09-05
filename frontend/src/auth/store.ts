import { defineStore } from 'pinia'
import { useLocalStorage } from '@vueuse/core'
import { computed } from 'vue'
import axios from 'axios'
import { ACCESS_KEY, REFRESH_KEY } from '@/shared/localStorage'
import type { Result } from '@/shared/result'
import { API_URL, http } from '@/shared/http'
import { safeRequest, type ApiError } from '@/shared/apiError'
import type { AuthFields } from '@/auth/validation'

export type AuthResponse = {
  accessToken: string
  refreshToken: string
}

export type AuthResult = Result<AuthResponse, ApiError<AuthFields>>

export const useAuthStore = defineStore('auth', () => {
  const accessToken = useLocalStorage<string | null>(ACCESS_KEY, null)
  const refreshToken = useLocalStorage<string | null>(REFRESH_KEY, null)

  const isAuthenticated = computed(() => accessToken.value !== null)

  function setTokens(tokens: AuthResponse) {
    accessToken.value = tokens.accessToken
    refreshToken.value = tokens.refreshToken
  }

  let refreshPromise: Promise<string> | null = null
  async function refreshAccessToken(): Promise<string> {
    if (refreshPromise) return refreshPromise

    const refresh = refreshToken.value
    if (!refresh) throw new Error('Refresh token is missing')

    refreshPromise = axios
      .post<AuthResponse>(`${API_URL}/auth/session/refresh`, { refreshToken: refresh })
      .then((res) => {
        setTokens(res.data)
        return res.data.accessToken
      })
      .finally(() => {
        refreshPromise = null
      })

    return refreshPromise
  }

  async function logout() {
    const refresh = refreshToken.value
    accessToken.value = null
    refreshToken.value = null
    if (refresh)
      await http.post('/auth/session/logout', { refreshToken: refresh }).catch(() => undefined)
  }

  async function login(email: string, password: string): Promise<AuthResult> {
    const res = await safeRequest<AuthResponse, AuthFields>(
      http.post<AuthResponse>('/auth/credentials/login', { email, password }),
    )
    if (res.ok) setTokens(res.data)
    return res
  }

  async function register(email: string, password: string): Promise<AuthResult> {
    const res = await safeRequest<AuthResponse, AuthFields>(
      http.post<AuthResponse>('/auth/credentials/register', { email, password }),
    )
    if (res.ok) setTokens(res.data)
    return res
  }

  return { isAuthenticated, login, register, logout, refreshAccessToken }
})
