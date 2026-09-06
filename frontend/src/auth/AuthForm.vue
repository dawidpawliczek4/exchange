<template>
  <main class="flex h-dvh items-center justify-center">
    <Card class="w-full max-w-sm">
      <CardHeader>
        <CardTitle>{{ title }}</CardTitle>
      </CardHeader>
      <CardContent>
        <form class="space-y-4" novalidate @submit.prevent="onSubmit">
          <div class="space-y-2">
            <Label for="email">Email</Label>
            <Input
              id="email"
              v-model="email"
              name="email"
              type="email"
              autocomplete="email"
              :aria-invalid="Boolean(fields.email)"
            />
            <p v-if="fields.email" data-testid="email-error" class="text-destructive text-xs">
              {{ fields.email }}
            </p>
          </div>

          <div class="space-y-2">
            <Label for="password">Password</Label>
            <Input
              id="password"
              v-model="password"
              name="password"
              type="password"
              :autocomplete="autocomplete"
              :aria-invalid="Boolean(fields.password)"
            />
            <p v-if="fields.password" data-testid="password-error" class="text-destructive text-xs">
              {{ fields.password }}
            </p>
          </div>

          <p v-if="message" data-testid="form-error" class="text-destructive text-sm">
            {{ message }}
          </p>

          <Button type="submit" class="w-full" :disabled="pending">
            {{ submitLabel }}
          </Button>
        </form>
      </CardContent>
      <CardFooter class="text-muted-foreground justify-center text-xs">
        <p><slot /></p>
      </CardFooter>
    </Card>
  </main>
</template>

<script setup lang="ts">
import { Button } from '@/shared/ui/button'
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from '@/shared/ui/card'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'
import { useAuthForm } from '@/auth/useAuthForm'
import type { AuthResult } from '@/auth/store'
import type { AuthSchema } from '@/auth/validation'

const props = defineProps<{
  title: string
  submitLabel: string
  autocomplete: 'current-password' | 'new-password'
  schema: AuthSchema
  submit: (email: string, password: string) => Promise<AuthResult>
}>()

const { email, password, fields, message, pending, onSubmit } = useAuthForm(
  props.schema,
  props.submit,
)
</script>
