import axios, { type AxiosResponse } from 'axios'
import router from '@/router'
import {ACCESS_KEY, REFRESH_KEY} from "@/shared/localStorage.ts";

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_URL ?? '',
  timeout: 10_000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(ACCESS_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (r) => r,
  async (error) => {
    const original = error.config
    if (error.response?.status !== 401 || original._retry) {
      return Promise.reject(error)
    }
    original._retry = true

    const { useAuthStore } = await import('@/auth/store')
    const { refreshAccessToken } = useAuthStore()

    try {
      const newToken = await refreshAccessToken()
      original.headers.Authorization = `Bearer ${newToken}`
      return http(original)
    } catch (e) {
      localStorage.removeItem(ACCESS_KEY)
      localStorage.removeItem(REFRESH_KEY)
      await router.push('/login')
      return Promise.reject(e)
    }
  },
)
