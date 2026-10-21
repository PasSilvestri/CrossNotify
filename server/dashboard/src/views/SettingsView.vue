<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth.js';
import { useSetupStore } from '../stores/setup.js';
import { apiRequest, formatApiError } from '../api/client.js';
import { LIMITS } from '../constants.js';

const router = useRouter();
const authStore = useAuthStore();
const setupStore = useSetupStore();

// Server settings
const publicUrl = ref('');
const serverName = ref('');
const notificationTtlHours = ref(48);
const isSavingSettings = ref(false);
const settingsSuccess = ref(false);
const settingsError = ref<string | null>(null);

// Password form
const currentPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const isUpdatingPassword = ref(false);
const passwordSuccess = ref(false);
const passwordError = ref<string | null>(null);

async function loadSettings() {
  try {
    const data = await apiRequest<{
      publicUrl: string | null;
      serverName: string;
      notificationTtlHours: number;
    }>('/admin/api/settings');

    publicUrl.value = data.publicUrl || '';
    serverName.value = data.serverName;
    notificationTtlHours.value = data.notificationTtlHours;
  } catch (err) {
    console.error('Failed to load settings:', err);
  }
}

async function handleSaveSettings() {
  isSavingSettings.value = true;
  settingsSuccess.value = false;
  settingsError.value = null;

  try {
    const cleanUrl = publicUrl.value.trim().replace(/\/+$/, '');
    await apiRequest('/admin/api/settings', {
      method: 'PUT',
      body: JSON.stringify({
        publicUrl: cleanUrl,
        serverName: serverName.value.trim(),
        notificationTtlHours: Number(notificationTtlHours.value),
      }),
    });
    settingsSuccess.value = true;
    await setupStore.fetchState();
    setTimeout(() => {
      settingsSuccess.value = false;
    }, 3000);
  } catch (err: any) {
    settingsError.value = formatApiError(err);
  } finally {
    isSavingSettings.value = false;
  }
}

async function handleUpdatePassword() {
  passwordError.value = null;
  passwordSuccess.value = false;

  if (newPassword.value.length < LIMITS.PASSWORD_MIN) {
    passwordError.value = `New password must be at least ${LIMITS.PASSWORD_MIN} characters.`;
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    passwordError.value = 'Passwords do not match.';
    return;
  }

  isUpdatingPassword.value = true;
  try {
    await authStore.updatePassword({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
    });
    passwordSuccess.value = true;
    currentPassword.value = '';
    newPassword.value = '';
    confirmPassword.value = '';
    setTimeout(() => {
      passwordSuccess.value = false;
    }, 3000);
  } catch (err: any) {
    passwordError.value = formatApiError(err);
  } finally {
    isUpdatingPassword.value = false;
  }
}

async function handleLogout() {
  await authStore.logout();
  router.push('/login');
}

onMounted(loadSettings);
</script>

<template>
  <div class="settings-view">
    <div class="view-header">
      <router-link to="/" class="back-link">← Back to Channels</router-link>
      <h1 class="page-title">Settings</h1>
      <p class="page-subtitle">Configure server properties and admin credentials</p>
    </div>

    <!-- Server Properties -->
    <div class="card settings-card">
      <h3 class="card-title">Server Configuration</h3>

      <form @submit.prevent="handleSaveSettings" class="settings-form">
        <div v-if="settingsSuccess" class="alert-success">Settings saved successfully.</div>
        <div v-if="settingsError" class="alert-error">{{ settingsError }}</div>

        <div class="form-group">
          <label for="pubUrl">Public Server URL</label>
          <input
            id="pubUrl"
            v-model="publicUrl"
            type="url"
            required
            placeholder="https://notify.example.com"
          />
          <span class="field-hint">Relay and subscriber phones use this URL to contact your server.</span>
        </div>

        <div class="form-group">
          <label for="srvName">Server Display Name</label>
          <input
            id="srvName"
            v-model="serverName"
            type="text"
            required
            maxlength="100"
            placeholder="CrossNotify Server"
          />
        </div>

        <div class="form-group">
          <label for="ttl">Notification Retention (Hours)</label>
          <input
            id="ttl"
            v-model.number="notificationTtlHours"
            type="number"
            min="1"
            max="168"
            required
          />
          <span class="field-hint">Encrypted notifications are automatically deleted after this time (1-168 hours).</span>
        </div>

        <div class="form-actions">
          <button type="submit" class="btn btn-primary" :disabled="isSavingSettings">
            <span v-if="isSavingSettings">Saving...</span>
            <span v-else>Save Settings</span>
          </button>
        </div>
      </form>
    </div>

    <!-- Firebase Link -->
    <div class="card nav-card">
      <div class="nav-card-info">
        <h3 class="card-title">Firebase Credentials</h3>
        <p class="nav-desc">View or replace your serviceAccountKey.json and google-services.json.</p>
      </div>
      <router-link to="/firebase" class="btn btn-secondary">
        Manage Firebase →
      </router-link>
    </div>

    <!-- Change Password -->
    <div class="card settings-card">
      <h3 class="card-title">Change Admin Password</h3>

      <form @submit.prevent="handleUpdatePassword" class="settings-form">
        <div v-if="passwordSuccess" class="alert-success">Password updated successfully! Other sessions logged out.</div>
        <div v-if="passwordError" class="alert-error">{{ passwordError }}</div>

        <div class="form-group">
          <label for="currPass">Current Password</label>
          <input
            id="currPass"
            v-model="currentPassword"
            type="password"
            required
          />
        </div>

        <div class="form-group">
          <label for="newPass">New Password</label>
          <input
            id="newPass"
            v-model="newPassword"
            type="password"
            required
            :minlength="LIMITS.PASSWORD_MIN"
            :placeholder="`At least ${LIMITS.PASSWORD_MIN} characters`"
          />
        </div>

        <div class="form-group">
          <label for="confPass">Confirm New Password</label>
          <input
            id="confPass"
            v-model="confirmPassword"
            type="password"
            required
            placeholder="Repeat new password"
          />
        </div>

        <div class="form-actions">
          <button type="submit" class="btn btn-secondary" :disabled="isUpdatingPassword">
            <span v-if="isUpdatingPassword">Updating...</span>
            <span v-else>Update Password</span>
          </button>
        </div>
      </form>
    </div>

    <!-- Logout Card -->
    <div class="card logout-card">
      <div>
        <h3 class="card-title">Log Out</h3>
        <p class="nav-desc">Sign out of the admin dashboard session.</p>
      </div>
      <button type="button" class="btn btn-secondary" @click="handleLogout">
        Log Out
      </button>
    </div>
  </div>
</template>

<style scoped>
.settings-view {
  max-width: 700px;
  margin: 0 auto;
  padding: 2rem 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.back-link {
  font-size: 0.875rem;
  color: var(--primary);
  text-decoration: none;
  font-weight: 500;
  display: inline-block;
  margin-bottom: 0.5rem;
}

.page-title {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--text-primary);
}

.page-subtitle {
  font-size: 0.9375rem;
  color: var(--text-muted);
}

.card-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 1rem;
}

.settings-form {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.form-group {
  display: flex;
  flex-direction: column;
}

.field-hint {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.25rem;
}

.form-actions {
  margin-top: 0.5rem;
}

.nav-card, .logout-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.nav-desc {
  font-size: 0.875rem;
  color: var(--text-muted);
}

.alert-success {
  background-color: var(--success-light);
  color: var(--success);
  padding: 0.75rem 1rem;
  border-radius: var(--radius-md);
  font-size: 0.8125rem;
}

.alert-error {
  background-color: var(--danger-light);
  color: var(--danger);
  padding: 0.75rem 1rem;
  border-radius: var(--radius-md);
  font-size: 0.8125rem;
}
</style>
