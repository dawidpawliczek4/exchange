import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('@/App.vue')
    },
    {
      path: '/login',
      component: () => import('@/auth/AuthLogin.vue'),
    },
    {
      path: '/register',
      component: () => import('@/auth/AuthRegister.vue'),
    },
  ],
})

export default router
