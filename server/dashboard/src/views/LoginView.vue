<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth.js';
import { useSetupStore } from '../stores/setup.js';
import { formatApiError } from '../api/client.js';

const router = useRouter();
const authStore = useAuthStore();
const setupStore = useSetupStore();

const username = ref('');
const password = ref('');
const error = ref<string | null>(null);
const isLoading = ref(false);

async function handleLogin() {
  error.value = null;
  if (!username.value || !password.value) {
    error.value = 'Please enter both username and password.';
    return;
  }

  isLoading.value = true;
  try {
    const res = await authStore.login({
      username: username.value,
      password: password.value,
    });

    const state = await setupStore.fetchState();
    if (state === 'NEEDS_FIREBASE') {
      router.push('/firebase');
    } else {
      router.push('/');
    }
  } catch (err: any) {
    error.value = formatApiError(err);
  } finally {
    isLoading.value = false;
  }
}
</script>

<template>
  <div class="auth-wrapper">
    <div class="card auth-card">
      <div class="auth-header">
        <div class="logo-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
          </svg>
        </div>
        <h1 class="auth-title">CrossNotify</h1>
        <p class="auth-subtitle">Sign in to manage your relay server</p>
      </div>

      <form @submit.prevent="handleLogin" class="auth-form">
        <div v-if="error" class="alert-error">
          {{ error }}
        </div>

        <div class="form-group">
          <label for="username">Username</label>
          <input
            id="username"
            v-model="username"
            type="text"
            required
            autocomplete="username"
          />
        </div>

        <div class="form-group">
          <label for="password">Password</label>
          <input
            id="password"
            v-model="password"
            type="password"
            required
            autocomplete="current-password"
          />
        </div>

        <button type="submit" class="btn btn-primary btn-block" :disabled="isLoading">
          <span v-if="isLoading">Signing in...</span>
          <span v-else>Sign In</span>
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.auth-wrapper {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
}

.auth-card {
  width: 100%;
  max-width: 400px;
}

.auth-header {
  text-align: center;
  margin-bottom: 2rem;
}

.logo-icon {
  width: 48px;
  height: 48px;
  margin: 0 auto 1rem;
  border-radius: var(--radius-lg);
  background-color: var(--primary-light);
  color: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
}
.logo-icon svg {
  width: 28px;
  height: 28px;
}

.auth-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
}

.auth-subtitle {
  font-size: 0.875rem;
  color: var(--text-muted);
  margin-top: 0.25rem;
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.form-group {
  display: flex;
  flex-direction: column;
}

.btn-block {
  width: 100%;
  padding: 0.75rem;
  font-size: 0.9375rem;
}

.alert-error {
  background-color: var(--danger-light);
  color: var(--danger);
  padding: 0.75rem 1rem;
  border-radius: var(--radius-md);
  font-size: 0.8125rem;
}
</style>
