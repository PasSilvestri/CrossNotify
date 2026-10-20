<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useSetupStore } from '../stores/setup.js';
import { apiRequest, formatApiError } from '../api/client.js';
import FileUploadCard from '../components/FileUploadCard.vue';
import ConfirmDialog from '../components/ConfirmDialog.vue';

const router = useRouter();
const setupStore = useSetupStore();

interface FirebaseStatus {
  serviceAccount: {
    uploaded: boolean;
    projectId?: string;
    clientEmail?: string;
    verified?: boolean;
    error?: string;
  };
  clientConfig: {
    uploaded: boolean;
    projectId?: string;
    appId?: string;
  };
  projectsMatch: boolean;
}

const status = ref<FirebaseStatus | null>(null);
const publicUrl = ref('');
const isSavingUrl = ref(false);
const isLoadingSa = ref(false);
const isLoadingClient = ref(false);
const saError = ref<string | null>(null);
const clientError = ref<string | null>(null);

// Reset confirmation
const showResetDialog = ref(false);
const pendingResetAction = ref<(() => Promise<void>) | null>(null);
const resetMessage = ref('');
const copiedPackage = ref(false);

const isReady = computed(() => {
  return (
    status.value?.serviceAccount.uploaded &&
    status.value?.serviceAccount.verified &&
    status.value?.clientConfig.uploaded &&
    status.value?.projectsMatch
  );
});

async function fetchStatus() {
  try {
    status.value = await apiRequest<FirebaseStatus>('/admin/api/firebase/status');
    const settings = await apiRequest<{ publicUrl: string | null }>('/admin/api/settings');
    if (settings.publicUrl) {
      publicUrl.value = settings.publicUrl;
    } else {
      // Inferred default
      publicUrl.value = window.location.origin;
    }
  } catch (err) {
    console.error('Failed to fetch firebase status:', err);
  }
}

async function uploadServiceAccount(json: any, force = false) {
  isLoadingSa.value = true;
  saError.value = null;
  try {
    const url = `/admin/api/firebase/service-account${force ? '?force=true' : ''}`;
    status.value = await apiRequest<FirebaseStatus>(url, {
      method: 'POST',
      body: JSON.stringify(json),
    });
    await setupStore.fetchState();
  } catch (err: any) {
    if (err.error === 'project_change_requires_reset' && !force) {
      resetMessage.value = `This file belongs to a different Firebase project. Uploading it will revoke ${err.channels || 0} active channel(s) and all paired phones must re-pair.`;
      pendingResetAction.value = () => uploadServiceAccount(json, true);
      showResetDialog.value = true;
    } else {
      saError.value = formatApiError(err);
    }
  } finally {
    isLoadingSa.value = false;
  }
}

async function uploadClientConfig(json: any, force = false) {
  isLoadingClient.value = true;
  clientError.value = null;
  try {
    const url = `/admin/api/firebase/client-config${force ? '?force=true' : ''}`;
    status.value = await apiRequest<FirebaseStatus>(url, {
      method: 'POST',
      body: JSON.stringify(json),
    });
    await setupStore.fetchState();
  } catch (err: any) {
    if (err.error === 'project_change_requires_reset' && !force) {
      resetMessage.value = `This file belongs to a different Firebase project. Uploading it will revoke ${err.channels || 0} active channel(s) and all paired phones must re-pair.`;
      pendingResetAction.value = () => uploadClientConfig(json, true);
      showResetDialog.value = true;
    } else {
      clientError.value = formatApiError(err);
    }
  } finally {
    isLoadingClient.value = false;
  }
}

async function confirmReset() {
  showResetDialog.value = false;
  if (pendingResetAction.value) {
    await pendingResetAction.value();
    pendingResetAction.value = null;
  }
}

function cancelReset() {
  showResetDialog.value = false;
  pendingResetAction.value = null;
}

async function savePublicUrlAndProceed() {
  isSavingUrl.value = true;
  try {
    const cleanUrl = publicUrl.value.trim().replace(/\/+$/, '');
    await apiRequest('/admin/api/settings', {
      method: 'PUT',
      body: JSON.stringify({ publicUrl: cleanUrl }),
    });
    await setupStore.fetchState();
    router.push('/');
  } catch (err: any) {
    alert(formatApiError(err));
  } finally {
    isSavingUrl.value = false;
  }
}

function copyPackageName() {
  navigator.clipboard.writeText('com.crossnotify.app');
  copiedPackage.value = true;
  setTimeout(() => {
    copiedPackage.value = false;
  }, 2000);
}

onMounted(fetchStatus);
</script>

<template>
  <div class="firebase-view">
    <div class="view-header">
      <h1 class="page-title">Firebase Credentials Setup</h1>
      <p class="page-subtitle">
        CrossNotify routes push notifications via your private Firebase project at zero cost.
      </p>
    </div>

    <!-- Quick Guide Section -->
    <div class="card guide-card">
      <h3 class="guide-title">Setup Instructions</h3>
      <ol class="guide-steps">
        <li>
          Open the
          <a href="https://console.firebase.google.com/" target="_blank" rel="noopener noreferrer" class="link">
            Firebase Console ↗
          </a>
          and create or select a project (Spark free plan is sufficient).
        </li>
        <li>
          Add an Android app with package name:
          <div class="code-copy-row">
            <code>com.crossnotify.app</code>
            <button type="button" class="btn btn-ghost btn-xs" @click="copyPackageName">
              {{ copiedPackage ? '✓ Copied' : 'Copy' }}
            </button>
          </div>
        </li>
        <li>
          Download <code>google-services.json</code> from Project Settings → Your apps.
        </li>
        <li>
          Go to Project Settings → <strong>Service accounts</strong> and click
          <strong>Generate new private key</strong> (downloads <code>serviceAccountKey.json</code>).
        </li>
        <li>
          In Google Cloud Console, ensure <strong>Firebase Cloud Messaging API (v1)</strong> is enabled.
        </li>
      </ol>
    </div>

    <!-- Security Note -->
    <div class="security-banner">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="shield-icon">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
      </svg>
      <div>
        <strong>Security & Privacy:</strong> Both files are encrypted with AES-256-GCM using your master key. The private key is never displayed again. You can safely delete your local copies after uploading.
      </div>
    </div>

    <!-- Upload Cards Grid -->
    <div class="cards-grid">
      <FileUploadCard
        title="1. Client Config"
        filename="google-services.json"
        description="Contains your Firebase project number and API keys embedded into pairing QR codes."
        :is-uploaded="!!status?.clientConfig.uploaded"
        :uploaded-info="status?.clientConfig.projectId ? `Project: ${status?.clientConfig.projectId}` : undefined"
        :error="clientError"
        :is-loading="isLoadingClient"
        @file-selected="uploadClientConfig"
      />

      <FileUploadCard
        title="2. Server Credentials"
        filename="serviceAccountKey.json"
        description="Grants this server permission to dispatch push notifications to subscriber phones."
        :is-uploaded="!!status?.serviceAccount.uploaded"
        :uploaded-info="status?.serviceAccount.clientEmail ? `${status?.serviceAccount.clientEmail}` : undefined"
        :error="saError || status?.serviceAccount.error"
        :is-loading="isLoadingSa"
        @file-selected="uploadServiceAccount"
      />
    </div>

    <!-- Ready / Continue Banner -->
    <div v-if="isReady" class="card ready-card">
      <div class="ready-header">
        <div class="ready-icon">✓</div>
        <div>
          <h3 class="ready-title">Firebase Credentials Verified!</h3>
          <p class="ready-desc">Project: <strong>{{ status?.serviceAccount.projectId }}</strong> is fully connected.</p>
        </div>
      </div>

      <div class="url-confirm-section">
        <label for="pubUrl">Confirm Public Server URL (Relay phones use this URL to contact this server):</label>
        <div class="url-input-row">
          <input id="pubUrl" v-model="publicUrl" type="url" required placeholder="https://notify.example.com" />
          <button type="button" class="btn btn-primary" :disabled="isSavingUrl" @click="savePublicUrlAndProceed">
            <span v-if="isSavingUrl">Saving...</span>
            <span v-else>Continue to Dashboard →</span>
          </button>
        </div>
      </div>
    </div>

    <!-- Confirmation Dialog for Project Reset -->
    <ConfirmDialog
      :open="showResetDialog"
      title="Replace Firebase Project?"
      :message="resetMessage"
      confirm-label="Revoke & Replace"
      :danger="true"
      @confirm="confirmReset"
      @cancel="cancelReset"
    />
  </div>
</template>

<style scoped>
.firebase-view {
  max-width: 860px;
  margin: 0 auto;
  padding: 2rem 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.view-header {
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
  margin-top: 0.25rem;
}

.guide-card {
  background-color: var(--bg-surface);
}

.guide-title {
  font-size: 1.0625rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.75rem;
}

.guide-steps {
  margin-left: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.code-copy-row {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.25rem;
}

.btn-xs {
  padding: 0.2rem 0.5rem;
  font-size: 0.75rem;
}

.link {
  color: var(--primary);
  text-decoration: underline;
}

.security-banner {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  background-color: var(--primary-light);
  border: 1px solid var(--primary);
  color: var(--text-primary);
  padding: 0.875rem 1rem;
  border-radius: var(--radius-md);
  font-size: 0.8125rem;
  line-height: 1.4;
}

.shield-icon {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  color: var(--primary);
}

.cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 1.25rem;
}

.ready-card {
  border-color: var(--success);
  background-color: var(--success-light);
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.ready-header {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.ready-icon {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background-color: var(--success);
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 1.25rem;
  flex-shrink: 0;
}

.ready-title {
  font-size: 1.125rem;
  font-weight: 700;
  color: var(--text-primary);
}

.ready-desc {
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.url-confirm-section {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  background-color: var(--bg-surface);
  padding: 1rem;
  border-radius: var(--radius-md);
}

.url-input-row {
  display: flex;
  gap: 0.75rem;
  align-items: center;
}

@media (max-width: 640px) {
  .url-input-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
