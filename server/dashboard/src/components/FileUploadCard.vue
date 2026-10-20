<script setup lang="ts">
import { ref } from 'vue';

const props = defineProps<{
  title: string;
  filename: string;
  description: string;
  isUploaded: boolean;
  uploadedInfo?: string;
  error?: string | null;
  isLoading?: boolean;
}>();

const emit = defineEmits<{
  (e: 'fileSelected', json: any): void;
}>();

const fileInputRef = ref<HTMLInputElement | null>(null);
const localError = ref<string | null>(null);

function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  const file = target.files?.[0];
  if (!file) return;

  localError.value = null;

  if (!file.name.endsWith('.json')) {
    localError.value = `File must be a JSON file (${props.filename})`;
    return;
  }

  const reader = new FileReader();
  reader.onload = (e) => {
    try {
      const text = e.target?.result as string;
      const parsed = JSON.parse(text);
      emit('fileSelected', parsed);
    } catch {
      localError.value = 'Failed to parse JSON. Please check the file contents.';
    }
  };
  reader.onerror = () => {
    localError.value = 'Failed to read the file.';
  };

  reader.readAsText(file);
  // Reset input so re-selecting the same file triggers change
  target.value = '';
}

function triggerBrowse() {
  fileInputRef.value?.click();
}
</script>

<template>
  <div :class="['card', 'upload-card', { 'uploaded-card': isUploaded }]">
    <div class="card-header">
      <div class="header-left">
        <h4 class="card-title">{{ title }}</h4>
        <span class="filename-badge"><code>{{ filename }}</code></span>
      </div>
      <div class="header-right">
        <span v-if="isUploaded" class="badge-success">Uploaded</span>
        <span v-else class="badge-missing">Missing</span>
      </div>
    </div>

    <p class="card-desc">{{ description }}</p>

    <div v-if="isUploaded && uploadedInfo" class="uploaded-info">
      <span class="info-label">Active:</span>
      <span class="info-val"><code>{{ uploadedInfo }}</code></span>
    </div>

    <div v-if="error || localError" class="card-error">
      {{ error || localError }}
    </div>

    <div class="card-actions">
      <input
        ref="fileInputRef"
        type="file"
        accept=".json,application/json"
        class="hidden-input"
        @change="handleFileChange"
      />
      <button
        type="button"
        :class="['btn', isUploaded ? 'btn-secondary' : 'btn-primary']"
        :disabled="isLoading"
        @click="triggerBrowse"
      >
        <span v-if="isLoading">Uploading...</span>
        <span v-else>{{ isUploaded ? 'Replace File' : 'Select File' }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.upload-card {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  transition: border-color 0.15s ease-in-out;
}

.uploaded-card {
  border-color: var(--success);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 0.5rem;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.card-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
}

.filename-badge code {
  font-size: 0.75rem;
  background-color: var(--bg-surface-elevated);
  padding: 0.125rem 0.375rem;
  border-radius: var(--radius-sm);
}

.badge-success {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 0.2rem 0.5rem;
  border-radius: var(--radius-full);
  background-color: var(--success-light);
  color: var(--success);
}

.badge-missing {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 0.2rem 0.5rem;
  border-radius: var(--radius-full);
  background-color: var(--warning-light);
  color: var(--warning);
}

.card-desc {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  line-height: 1.4;
}

.uploaded-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8125rem;
  background-color: var(--bg-surface-elevated);
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-sm);
}

.info-label {
  color: var(--text-muted);
  font-weight: 500;
}

.info-val code {
  color: var(--text-primary);
}

.card-error {
  font-size: 0.8125rem;
  color: var(--danger);
  background-color: var(--danger-light);
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-sm);
}

.card-actions {
  margin-top: 0.5rem;
}

.hidden-input {
  display: none;
}
</style>
