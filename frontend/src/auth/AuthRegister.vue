<template>
  <AuthForm
    title="Create account"
    submit-label="Register"
    autocomplete="new-password"
    :schema="RegisterSchema"
    :submit="auth.register"
  >
    Already registered?
    <RouterLink to="/login" class="text-emerald-400 hover:underline">Sign in</RouterLink>
  </AuthForm>
</template>

<script setup lang="ts">
import AuthForm from '@/auth/AuthForm.vue'
import { useAuthStore } from '@/auth/store'
import { RegisterSchema } from '@/auth/validation'

const auth = useAuthStore()
</script>
