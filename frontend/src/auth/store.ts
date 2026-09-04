import { z } from 'zod'
import {defineStore} from "pinia";
import {useLocalStorage} from "@vueuse/core";
import { ref } from "vue";
import axios from "axios";
import {ACCESS_KEY, REFRESH_KEY} from "@/shared/localStorage.ts";
import type {Result} from "@/shared/result.ts";
import {http} from "@/shared/http.ts";

type User = {
  id: number
  email: string
  createdAt: string
}

export type AuthResponse = {
  accessToken: string
  refreshToken: string
}

export const AuthSchema = z.object({
  email: z.email('Invalid email address'),
  password: z.string().min(1, 'Password is required'),
})

export type AuthFields = keyof z.infer<typeof AuthSchema>

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)

  const accessToken = useLocalStorage<string | null>(ACCESS_KEY, null)
  const refreshToken = useLocalStorage<string | null>(REFRESH_KEY, null)

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
      .post<AuthResponse>('/api/auth/session/refresh', { refreshToken: refresh })
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
    if (refreshToken.value) {
      await http.post<AuthResponse>('/api/auth/credentials/login', {
        refreshToken: refreshToken.value,
      })
    }
    accessToken.value = null
    refreshToken.value = null
    user.value = null
  }

  async function login(
    email: string,
    password: string,
  ): Promise<Result<AuthResponse, AuthError>> {
    const res = await safeRequest<AuthResponse, AuthFields>(
      http.post<AuthResponse>('/api/auth/credentials/login', { email, password }),
    )
    if (res.ok) setTokens(res.data)
    return res
  }

  async function register(
    email: string,
    password: string,
  ): Promise<Result<AuthResponse, AuthError>> {
    const res = await safeRequest<AuthResponse, AuthFields>(
      http.post<AuthResponse>('/api/auth/credentials/register', { email, password }),
    )
    if (res.ok) setTokens(res.data)
    return res
  }

  function getAccessToken() {
    return accessToken
  }

  return { logout, login, register, refreshAccessToken, getAccessToken }
})
