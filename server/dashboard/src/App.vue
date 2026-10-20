<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from './stores/auth.js';
import { useSetupStore } from './stores/setup.js';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const setupStore = useSetupStore();

const showNavbar = computed(() => {
  return (
    authStore.user &&
    route.path !== '/setup' &&
    route.path !== '/login'
  );
});

async function handleLogout() {
  await authStore.logout();
  router.push('/login');
}
</script>

<template>
  <div class="app-root">
    <header v-if="showNavbar" class="navbar">
      <div class="nav-container">
        <router-link to="/" class="nav-brand">
          <div class="brand-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
            </svg>
          </div>
          <span class="brand-text">CrossNotify</span>
        </router-link>

        <nav class="nav-links">
          <router-link
            v-if="setupStore.state === 'READY'"
            to="/"
            class="nav-link"
            :class="{ active: route.path === '/' || route.path.startsWith('/channels') || route.path.startsWith('/relays') }"
          >
            Channels
          </router-link>
          <router-link
            to="/settings"
            class="nav-link"
            :class="{ active: route.path === '/settings' || route.path === '/firebase' }"
          >
            Settings
          </router-link>
          <button type="button" class="btn btn-ghost btn-sm" @click="handleLogout">
            Sign Out
          </button>
        </nav>
      </div>
    </header>

    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.app-root {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.navbar {
  background-color: var(--bg-surface);
  border-bottom: 1px solid var(--border-subtle);
  position: sticky;
  top: 0;
  z-index: 50;
}

.nav-container {
  max-width: 1000px;
  margin: 0 auto;
  padding: 0.75rem 1.5rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  text-decoration: none;
  color: var(--text-primary);
  font-weight: 700;
  font-size: 1.125rem;
}

.brand-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  background-color: var(--primary);
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-icon svg {
  width: 18px;
  height: 18px;
}

.nav-links {
  display: flex;
  align-items: center;
  gap: 1.25rem;
}

.nav-link {
  text-decoration: none;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-muted);
  transition: color 0.15s ease-in-out;
  padding: 0.375rem 0.25rem;
  border-bottom: 2px solid transparent;
}

.nav-link:hover {
  color: var(--text-primary);
}

.nav-link.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}

.main-content {
  flex: 1;
}

.btn-sm {
  padding: 0.375rem 0.75rem;
  font-size: 0.8125rem;
}
</style>
