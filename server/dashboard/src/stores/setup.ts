import { defineStore } from 'pinia';
import { ref } from 'vue';
import { apiRequest } from '../api/client.js';

export type SetupState = 'NEEDS_ADMIN' | 'NEEDS_FIREBASE' | 'READY';

export const useSetupStore = defineStore('setup', () => {
  const state = ref<SetupState | null>(null);
  const serverName = ref<string>('CrossNotify Server');
  const version = ref<string>('1.0.0');
  const isLoaded = ref<boolean>(false);

  async function fetchState(): Promise<SetupState> {
    try {
      const data = await apiRequest<{ state: SetupState; serverName?: string; version?: string }>(
        '/admin/api/state'
      );
      state.value = data.state;
      if (data.serverName) serverName.value = data.serverName;
      if (data.version) version.value = data.version;
      isLoaded.value = true;
      return data.state;
    } catch (err) {
      console.error('Failed to fetch setup state:', err);
      throw err;
    }
  }

  async function setupAdmin(payload: { setupToken: string; username: string; password: string }) {
    const data = await apiRequest<{ username: string; state: SetupState }>('/admin/api/setup/admin', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    state.value = data.state;
    return data;
  }

  return {
    state,
    serverName,
    version,
    isLoaded,
    fetchState,
    setupAdmin,
  };
});
