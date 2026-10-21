<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSetupStore } from '../stores/setup.js';
import { useAuthStore } from '../stores/auth.js';
import { formatApiError } from '../api/client.js';
import { LIMITS } from '../constants.js';

const router = useRouter();
const setupStore = useSetupStore();
const authStore = useAuthStore();

const setupToken = ref('');
const username = ref('admin');
const password = ref('');
const confirmPassword = ref('');
const error = ref<string | null>(null);
const isLoading = ref(false);

async function handleSubmit() {
  error.value = null;

  if (!setupToken.value.trim()) {
    error.value = 'Setup token is required. Check your server logs.';
    return;
  }
  if (username.value.length < LIMITS.USERNAME_MIN || username.value.length > LIMITS.USERNAME_MAX) {
    error.value = `Username must be between ${LIMITS.USERNAME_MIN} and ${LIMITS.USERNAME_MAX} characters.`;
    return;
  }
  if (password.value.length < LIMITS.PASSWORD_MIN) {
    error.value = `Password must be at least ${LIMITS.PASSWORD_MIN} characters.`;
    return;
  }
  if (password.value !== confirmPassword.value) {
    error.value = 'Passwords do not match.';
    return;
  }

  isLoading.value = true;
  try {
    await setupStore.setupAdmin({
      setupToken: setupToken.value.trim(),
      username: username.value.trim(),
      password: password.value,
    });

    // Check auth session created by setup
    await authStore.checkAuth();
    router.push('/firebase');
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
        <h1 class="auth-title">First-Run Setup</h1>
        <p class="auth-subtitle">
          Create your CrossNotify administrator account to secure this server.
        </p>
      </div>

      <div class="info-callout">
        <strong>Setup Token Required:</strong>
        Look for the one-time setup token in your server terminal or <code>docker compose logs</code>:
        <pre class="token-hint">Open the dashboard and enter this setup token: &lt;TOKEN&gt;</pre>
      </div>

      <form @submit.prevent="handleSubmit" class="auth-form">
        <div v-if="error" class="alert-error">
          {{ error }}
        </div>

        <div class="form-group">
          <label for="setupToken">One-Time Setup Token</label>
          <input
            id="setupToken"
            v-model="setupToken"
            type="text"
            required
            placeholder="Paste token from server logs"
            autocomplete="off"
          />
        </div>

        <div class="form-group">
          <label for="username">Administrator Username</label>
          <input
            id="username"
            v-model="username"
            type="text"
            required
            :minlength="LIMITS.USERNAME_MIN"
            :maxlength="LIMITS.USERNAME_MAX"
          />
        </div>

        <div class="form-group">
          <label for="password">Password</label>
          <input
            id="password"
            v-model="password"
            type="password"
            required
            :minlength="LIMITS.PASSWORD_MIN"
            :placeholder="`At least ${LIMITS.PASSWORD_MIN} characters`"
          />
        </div>

        <div class="form-group">
          <label for="confirmPassword">Confirm Password</label>
          <input
            id="confirmPassword"
            v-model="confirmPassword"
            type="password"
            required
            placeholder="Repeat password"
          />
        </div>

        <button type="submit" class="btn btn-primary btn-block" :disabled="isLoading">
          <span v-if="isLoading">Setting up...</span>
          <span v-else>Create Admin Account</span>
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
  max-width: 460px;
}

.auth-header {
  text-align: center;
  margin-bottom: 1.5rem;
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

.info-callout {
  background-color: var(--bg-surface-elevated);
  border-left: 3px solid var(--primary);
  padding: 0.75rem 1rem;
  border-radius: var(--radius-sm);
  font-size: 0.8125rem;
  color: var(--text-secondary);
  margin-bottom: 1.5rem;
  line-height: 1.4;
}

.token-hint {
  margin-top: 0.5rem;
  padding: 0.375rem 0.5rem;
  background-color: var(--bg-primary);
  border-radius: var(--radius-sm);
  font-size: 0.75rem;
  overflow-x: auto;
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
