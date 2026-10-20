import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useSetupStore } from './stores/setup.js';
import { useAuthStore } from './stores/auth.js';

import SetupAdminView from './views/SetupAdminView.vue';
import LoginView from './views/LoginView.vue';
import FirebaseSetupView from './views/FirebaseSetupView.vue';
import ChannelsView from './views/ChannelsView.vue';
import AddRelayView from './views/AddRelayView.vue';
import ChannelDetailView from './views/ChannelDetailView.vue';
import SettingsView from './views/SettingsView.vue';

const routes: RouteRecordRaw[] = [
  { path: '/setup', component: SetupAdminView, meta: { public: true } },
  { path: '/login', component: LoginView, meta: { public: true } },
  { path: '/firebase', component: FirebaseSetupView },
  { path: '/', component: ChannelsView },
  { path: '/relays/new', component: AddRelayView },
  { path: '/channels/:id', component: ChannelDetailView },
  { path: '/settings', component: SettingsView },
  { path: '/:pathMatch(.*)*', redirect: '/' },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach(async (to, from, next) => {
  const setupStore = useSetupStore();
  const authStore = useAuthStore();

  if (!setupStore.isLoaded) {
    try {
      await setupStore.fetchState();
    } catch {
      // transient network error
    }
  }

  if (!authStore.isChecked) {
    await authStore.checkAuth();
  }

  const state = setupStore.state;
  const isAuthenticated = !!authStore.user;

  // 1. If server needs first-run admin creation
  if (state === 'NEEDS_ADMIN') {
    if (to.path !== '/setup') {
      return next('/setup');
    }
    return next();
  }

  // 2. If not authenticated
  if (!isAuthenticated) {
    if (to.path !== '/login') {
      return next('/login');
    }
    return next();
  }

  // 3. If authenticated but Firebase not configured
  if (state === 'NEEDS_FIREBASE') {
    if (to.path !== '/firebase' && to.path !== '/settings') {
      return next('/firebase');
    }
    return next();
  }

  // 4. If authenticated and READY, avoid setup and login
  if (to.path === '/setup' || to.path === '/login') {
    return next('/');
  }

  next();
});
