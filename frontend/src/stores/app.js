import { defineStore } from 'pinia';
import { ref, reactive, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAuthStore } from './auth';

export const STUDIO_TITLES = {
  overview: '工作台概览',
  apps: '应用管理',
  models: '表单与数据表',
  views_menus: '页面与菜单编排',
  iam: '租户与应用授权',
  dict: '常用选项字典',
  code_rule: '自动编号规则'
};

export const useAppStore = defineStore('app', () => {
  const authStore = useAuthStore();

  const themeName = ref(localStorage.getItem('lc_theme') || 'indigo');
  const workspaceMode = ref('studio');
  const studioTab = ref('overview');
  const openTabs = ref([]);
  const activeTabId = ref('');

  const menuTree = ref([]);
  const menuLoaded = ref(false);
  const runtimeRecordChange = ref(null);
  const appsList = ref([]);
  const modelsSummary = ref([]);
  const publishTimeline = ref([]);
  const tenantsList = ref([]);
  const dictTypes = ref([]);
  const codeRules = ref([]);
  const assignableMenuGroups = ref([]);

  // Shared cross-view designer / version center / package drawer state
  const designerDialogState = reactive({
    visible: false,
    isEdit: false,
    modelToOpen: ''
  });
  const versionCenterState = reactive({
    visible: false,
    modelName: '',
    impact: null
  });
  const appCreateDialogTrigger = ref(0);

  const greetingText = computed(() => {
    const h = new Date().getHours();
    if (h < 12) return '早上好';
    if (h < 18) return '下午好';
    return '晚上好';
  });

  const businessAppsOnly = computed(() => appsList.value.filter((a) => a.name !== 'platform_kernel'));
  const businessModels = computed(() => modelsSummary.value.filter((m) => !m.kernelModel));
  const kernelModels = computed(() => modelsSummary.value.filter((m) => m.kernelModel));
  const runtimeLeafMenusCount = computed(() =>
    runtimeNavigation.value.filter(({ node }) => !!node.routePath || !!node.model).length
  );

  const runtimeNavigation = computed(() => {
    const entries = [];
    function visit(nodes, depth = 0) {
      for (const node of nodes || []) {
        entries.push({ node, depth });
        visit(node.children || [], depth + 1);
      }
    }
    visit(menuTree.value, 0);
    return entries;
  });

  const assignableApps = computed(() =>
    authStore.isSystemAdmin ? businessAppsOnly.value : (authStore.currentUser?.authorizedApps || [])
  );

  function applyTheme(t) {
    themeName.value = t || 'indigo';
    localStorage.setItem('lc_theme', themeName.value);
    if (themeName.value === 'indigo') {
      document.documentElement.removeAttribute('data-theme');
    } else {
      document.documentElement.setAttribute('data-theme', themeName.value);
    }
  }

  function ensureTab(id, title, mode, key) {
    const idx = openTabs.value.findIndex((x) => x.id === id);
    if (idx === -1) {
      openTabs.value.push({ id, title, mode, key });
    } else {
      openTabs.value[idx].title = title;
    }
    activeTabId.value = id;
  }

  function closeTab(id) {
    const idx = openTabs.value.findIndex((x) => x.id === id);
    if (idx === -1) return null;
    openTabs.value.splice(idx, 1);
    if (activeTabId.value === id && openTabs.value.length > 0) {
      return openTabs.value[openTabs.value.length - 1];
    }
    return null;
  }

  function resolveAppDisplayName(appCode) {
    const found =
      appsList.value.find((a) => a.name === appCode) ||
      (authStore.currentUser?.authorizedApps || []).find((a) => a.name === appCode);
    return found ? found.displayName : appCode;
  }

  function resolveTenantName(tid) {
    const found = tenantsList.value.find((t) => t.id === tid || t.code === tid);
    return found ? found.name : tid;
  }

  function resolveMenuDisplayName(menuName) {
    for (const g of assignableMenuGroups.value) {
      const hit = (g.menus || []).find((m) => m.name === menuName || m.id === menuName);
      if (hit) return hit.displayName;
    }
    const stack = [...(menuTree.value || [])];
    while (stack.length > 0) {
      const node = stack.shift();
      if (node.name === menuName || node.id === menuName) return node.displayName;
      if (Array.isArray(node.children)) stack.push(...node.children);
    }
    return menuName;
  }

  function getTenantsForApp(appName) {
    return tenantsList.value.filter(
      (t) => Array.isArray(t.authorizedApps) && (t.authorizedApps.includes('*') || t.authorizedApps.includes(appName))
    );
  }

  function getMenusForApp(appName) {
    const g = assignableMenuGroups.value.find((x) => x.appName === appName);
    return g ? g.menus || [] : [];
  }

  function findFirstLeafModel(nodes, targetApp = null) {
    for (const n of nodes || []) {
      if (n.model && (!targetApp || n.appName === targetApp)) return n;
      if (n.children && n.children.length) {
        const f = findFirstLeafModel(n.children, targetApp);
        if (f) return f;
      }
    }
    return null;
  }

  function findFirstLeafMenu(targetApp = null) {
    return (
      runtimeNavigation.value.find(
        ({ node }) => node.routePath && (!targetApp || node.appName === targetApp)
      )?.node || null
    );
  }

  function findMenuNode(menuName, routePath = '') {
    return (
      runtimeNavigation.value.find(
        ({ node }) => (routePath && node.routePath === routePath) || (menuName && node.name === menuName)
      )?.node || null
    );
  }

  async function loadMenuTree() {
    try {
      menuTree.value = (await rpcCall('ui_menu', 'loadMenu', {})) || [];
    } catch {
      menuTree.value = [];
    } finally {
      menuLoaded.value = true;
    }
  }

  async function loadPublishTimeline() {
    try {
      publishTimeline.value = (await rpcCall('meta_model', 'listPublishRecords', { modelName: '' })) || [];
    } catch {
      // ignore
    }
  }

  async function loadStudioOverview() {
    if (!authStore.canAccessStudio) return;
    try {
      const [apps, models, dts, crs, tns] = await Promise.all([
        rpcCall('meta_app', 'list', {}),
        rpcCall('meta_model', 'listModels', {}),
        rpcCall('meta_dict', 'listTypes', {}).catch(() => []),
        rpcCall('meta_code_rule', 'listRules', {}).catch(() => []),
        rpcCall('rbac_tenant', 'listTenants', {}).catch(() => [])
      ]);
      appsList.value = apps || [];
      modelsSummary.value = models || [];
      dictTypes.value = dts || [];
      codeRules.value = crs || [];
      tenantsList.value = tns || [];
    } catch (e) {
      ElMessage.error('加载平台数据失败：' + e.message);
    }
  }

  async function loadDictTypes() {
    try {
      dictTypes.value = (await rpcCall('meta_dict', 'listTypes', {})) || [];
    } catch {
      // ignore
    }
  }

  async function loadCodeRules() {
    try {
      codeRules.value = (await rpcCall('meta_code_rule', 'listRules', {})) || [];
    } catch {
      // ignore
    }
  }

  async function loadTenants() {
    try {
      tenantsList.value = (await rpcCall('rbac_tenant', 'listTenants', {})) || [];
    } catch {
      // ignore
    }
  }

  async function loadAssignableMenus() {
    try {
      assignableMenuGroups.value = (await rpcCall('ui_menu', 'listAssignableMenus', {})) || [];
    } catch {
      assignableMenuGroups.value = [];
    }
  }

  async function refreshMenusAndOverview() {
    await loadMenuTree();
    if (authStore.canAccessStudio) {
      await loadStudioOverview();
      await loadPublishTimeline();
    }
  }

  function resetWorkspaceState() {
    openTabs.value = [];
    activeTabId.value = '';
    menuTree.value = [];
    menuLoaded.value = false;
    appsList.value = [];
    modelsSummary.value = [];
    publishTimeline.value = [];
    tenantsList.value = [];
    assignableMenuGroups.value = [];
  }

  return {
    themeName,
    workspaceMode,
    studioTab,
    openTabs,
    activeTabId,
    menuTree,
    menuLoaded,
    runtimeNavigation,
    runtimeRecordChange,
    appsList,
    modelsSummary,
    publishTimeline,
    tenantsList,
    dictTypes,
    codeRules,
    assignableMenuGroups,
    designerDialogState,
    versionCenterState,
    appCreateDialogTrigger,
    greetingText,
    businessAppsOnly,
    businessModels,
    kernelModels,
    runtimeLeafMenusCount,
    assignableApps,
    applyTheme,
    ensureTab,
    closeTab,
    resolveAppDisplayName,
    resolveTenantName,
    resolveMenuDisplayName,
    getTenantsForApp,
    getMenusForApp,
    findFirstLeafModel,
    findFirstLeafMenu,
    findMenuNode,
    loadMenuTree,
    loadPublishTimeline,
    loadStudioOverview,
    loadDictTypes,
    loadCodeRules,
    loadTenants,
    loadAssignableMenus,
    refreshMenusAndOverview,
    resetWorkspaceState
  };
});
