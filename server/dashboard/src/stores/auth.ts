import { defineStore } from 'pinia';
import { ref } from 'vue';
import { apiRequest } from '../api/client.js';

export interface AdminUser {
  username: string;
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AdminUser | null>(null);
  const isChecked = ref<boolean>(false);

  async function checkAuth(): Promise<AdminUser | null> {
    try {
      const data = await apiRequest<AdminUser>('/admin/api/auth/me');
      user.value = data;
      isChecked.value = true;
      return data;
    } catch {
      user.value = null;
      isChecked.value = true;
      return null;
    }
  }

  async function login(credentials: { username: string; password: string }) {
    const data = await apiRequest<{ username: string; state: string }>('/admin/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(credentials),
    });
    user.value = { username: data.username };
    return data;
  }

  async function logout() {
    try {
      await apiRequest('/admin/api/auth/logout', { method: 'POST' });
    } finally {
      user.value = null;
    }
  }

  async function updatePassword(passwords: { currentPassword: string; newPassword: string }) {
    return apiRequest('/admin/api/account/password', {
      method: 'PUT',
      body: JSON.stringify(passwords),
    });
  }

  return {
    user,
    isChecked,
    checkAuth,
    login,
    logout,
    updatePassword,
  };
});
