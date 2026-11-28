<template>
  <div v-if="authStore.currentUser" class="lc-shell">
    <header class="lc-header">
      <div class="lc-header-identity">
        <el-button
          ref="menuToggle"
          class="lc-menu-toggle"
          :aria-label="sidebarOpen ? '收起导航' : '展开导航'"
          :aria-expanded="sidebarOpen"
          aria-controls="lc-navigation"
          @click="sidebarOpen = !sidebarOpen"
        >
          <el-icon><Fold v-if="sidebarOpen" /><Expand v-else /></el-icon>
        </el-button>
        <div class="lc-brand">
          <div class="lc-logo" aria-hidden="true">
            <el-icon :size="17"><Grid /></el-icon>
          </div>
          <div>
            <span class="lc-title">云枢低代码应用平台</span>
            <span class="lc-sub">{{ authStore.currentUser.tenantName || '默认空间' }}</span>
          </div>
        </div>
      </div>

      <div class="lc-header-actions">
        <el-radio-group v-if="authStore.canAccessStudio" v-model="workspaceModel" size="small" @change="onWorkspaceChange">
          <el-radio-button value="studio">管理中心</el-radio-button>
          <el-radio-button value="runtime">应用工作区</el-radio-button>
        </el-radio-group>
        <el-radio-group v-else-if="authStore.canManageTenants" v-model="workspaceModel" size="small" @change="onWorkspaceChange">
          <el-radio-button value="tenant_admin">分站与授权</el-radio-button>
          <el-radio-button value="runtime">应用工作区</el-radio-button>
        </el-radio-group>
        <el-tag v-else type="primary" effect="light" size="small">
          当前租户：{{ authStore.currentUser.tenantName || authStore.currentUser.tenantId }}
        </el-tag>

        <el-select v-model="appStore.themeName" size="small" aria-label="界面配色" style="width:86px;" @change="appStore.applyTheme">
          <el-option value="indigo" label="工业青" />
          <el-option value="crimson" label="赤陶" />
          <el-option value="slate" label="钢蓝" />
        </el-select>

        <el-dropdown trigger="click" @command="handleUserMenuCommand">
          <el-button size="small" :aria-label="'账户菜单，' + authStore.currentUser.displayName">
            <el-icon style="margin-right:4px;"><User /></el-icon>
            <span class="lc-user-name">{{ authStore.currentUser.displayName }}</span>
            <el-icon style="margin-left:4px;"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="authStore.isSystemAdmin" disabled>角色：系统管理员</el-dropdown-item>
              <el-dropdown-item v-else-if="authStore.canManageTenants" disabled>角色：租户主管</el-dropdown-item>
              <el-dropdown-item disabled>所属租户：{{ authStore.currentUser.tenantName || authStore.currentUser.tenantId }}</el-dropdown-item>
              <el-dropdown-item disabled>可用应用：{{ authStore.authorizedAppLabelsText }}</el-dropdown-item>
              <el-dropdown-item divided command="openChangePwd">修改个人密码</el-dropdown-item>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="lc-body">
      <!-- 左侧导航栏 -->
      <button v-if="sidebarOpen" class="lc-sidebar-scrim" type="button" aria-label="收起导航" @click="closeSidebar" />
      <nav id="lc-navigation" ref="navigation" class="lc-sidebar" aria-label="工作区导航" :class="{ 'is-open': sidebarOpen }" @keydown.esc.stop="closeSidebar">
        <template v-if="appStore.workspaceMode === 'studio' && authStore.canAccessStudio">
          <div class="lc-side-section">工作台</div>
          <div class="lc-side-scroll">
            <button type="button" class="lc-nav-item" :class="{ active: appStore.studioTab === 'overview' }" @click="switchStudioTab('overview')">
              <el-icon><House /></el-icon><span>工作台概览</span>
            </button>

            <div class="lc-side-section">应用设计中心</div>
            <button type="button" class="lc-nav-item" :class="{ active: appStore.studioTab === 'apps' }" @click="switchStudioTab('apps')">
              <el-icon><FolderOpened /></el-icon><span>应用管理</span>
            </button>
            <button type="button" class="lc-nav-item" :class="{ active: appStore.studioTab === 'models' }" @click="switchStudioTab('models')">
              <el-icon><Box /></el-icon><span>表单与数据表</span>
            </button>
            <button type="button" class="lc-nav-item" :class="{ active: appStore.studioTab === 'views_menus' }" @click="switchStudioTab('views_menus')">
              <el-icon><Grid /></el-icon><span>页面与菜单编排</span>
            </button>

            <div class="lc-side-section">租户与基础数据</div>
            <button type="button" class="lc-nav-item" :class="{ active: appStore.studioTab === 'iam' }" @click="switchStudioTab('iam')">
              <el-icon><UserFilled /></el-icon><span>租户与应用授权</span>
            </button>
            <button type="button" class="lc-nav-item" :class="{ active: appStore.studioTab === 'dict' }" @click="switchStudioTab('dict')">
              <el-icon><Collection /></el-icon><span>常用选项字典</span>
            </button>
            <button type="button" class="lc-nav-item" :class="{ active: appStore.studioTab === 'code_rule' }" @click="switchStudioTab('code_rule')">
              <el-icon><Ticket /></el-icon><span>自动编号规则</span>
            </button>


          </div>
        </template>

        <template v-else-if="appStore.workspaceMode === 'tenant_admin' && authStore.canManageTenants">
          <div class="lc-side-section">本级与下属管理</div>
          <div class="lc-side-scroll">
            <button type="button" class="lc-nav-item active" @click="router.push('/tenant-admin')">
              <el-icon><UserFilled /></el-icon><span>分站与应用授权</span>
            </button>
            <button type="button" class="lc-nav-item" @click="onWorkspaceChange('runtime')">
              <el-icon><House /></el-icon><span>进入应用工作区</span>
            </button>
          </div>
        </template>

        <template v-else>
          <div class="lc-side-section">应用功能导航</div>
          <div class="lc-side-scroll">
            <button type="button" class="lc-nav-item" :class="{ active: route.name === 'runtime-home' }" @click="router.push('/runtime')">
              <el-icon><House /></el-icon><span>全部授权应用</span>
            </button>
            <div v-if="appStore.menuTree.length === 0" class="lc-side-empty">
              暂无可用业务页面，请联系管理员授权。
            </div>
            <template v-for="{ node, depth } in appStore.runtimeNavigation" :key="node.name">
              <button
                v-if="node.routePath"
                type="button"
                class="lc-nav-item"
                :class="{ active: activeRuntimeMenu === node.name }"
                :aria-current="activeRuntimeMenu === node.name ? 'page' : undefined"
              :style="{ '--nav-indent': 10 + depth * 11 + 'px' }"
                @click="openRuntimeMenu(node)"
              >
                <el-icon><Document /></el-icon>
                <span>{{ node.displayName }}</span>
              </button>
              <div
                v-else
                class="lc-nav-group-title"
                :style="{ '--nav-indent': 10 + depth * 11 + 'px' }"
              >
                <el-icon><FolderOpened /></el-icon>
                <span>{{ node.displayName }}</span>
              </div>
            </template>
          </div>
        </template>
        <div v-if="assistantAvailable" class="lc-side-footer">
          <button
            ref="assistantToggle"
            type="button"
            class="lc-nav-item lc-assistant-toggle"
            :class="{ active: assistantOpen }"
            :aria-expanded="assistantOpen"
            :aria-controls="assistantOpen ? 'mes-assistant-panel' : undefined"
            @click="toggleAssistant"
          >
            <el-icon aria-hidden="true"><ChatDotRound /></el-icon><span>云枢小助手</span>
          </button>
        </div>
      </nav>

      <div class="lc-workspace">
        <nav v-if="appStore.openTabs.length > 0" class="lc-tabsbar" aria-label="已打开的页面">
          <div v-for="t in appStore.openTabs" :key="t.id" class="lc-tab-pill" :class="{ active: appStore.activeTabId === t.id }">
            <button type="button" class="lc-tab-open" :aria-current="appStore.activeTabId === t.id ? 'page' : undefined" @click="activateTab(t)">
              {{ t.title }}
            </button>
            <button v-if="appStore.openTabs.length > 1" type="button" class="lc-tab-close" :aria-label="'关闭' + t.title" @click="handleCloseTab(t.id)">
              <el-icon><Close /></el-icon>
            </button>
          </div>
        </nav>
        <main class="lc-main">
          <router-view />
        </main>
      </div>
      <MesAssistantPanel
        v-if="assistantAvailable"
        :key="runtimeAssistant ? 'runtime' : 'management'"
        :open="assistantOpen"
        :runtime="runtimeAssistant"
        :menu="activeRuntimeMenu"
        @close="closeAssistant"
        @navigate="assistantOpen = false"
      />
    </div>

    <!-- 全局模型设计器与版本中心弹窗 -->
    <ModelDesignerModal />

    <!-- 修改个人密码弹窗 -->
    <el-dialog v-model="changePwdDialog.visible" title="修改个人登录密码" width="min(420px, calc(100vw - 24px))" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="当前密码" required>
          <el-input v-model="changePwdDialog.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input v-model="changePwdDialog.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" required>
          <el-input v-model="changePwdDialog.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="changePwdDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="submitChangePassword">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';
import MesAssistantPanel from '@/components/assistant/MesAssistantPanel.vue';
import ModelDesignerModal from '@/components/studio/ModelDesignerModal.vue';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const appStore = useAppStore();
const sidebarOpen = ref(false);
const assistantOpen = ref(false);
const assistantToggle = ref(null);
const menuToggle = ref(null);
const navigation = ref(null);

watch(() => route.path, () => { sidebarOpen.value = false; });
watch(
  () => [route.path, appStore.menuLoaded, sidebarOpen.value],
  async () => {
    await nextTick();
    if (!navigation.value?.getClientRects().length) return;
    navigation.value.querySelector('.lc-nav-item.active')?.scrollIntoView({ block: 'nearest' });
  },
  { immediate: true }
);

function closeSidebar() {
  if (!sidebarOpen.value) return;
  sidebarOpen.value = false;
  menuToggle.value?.ref?.focus();
}

function toggleAssistant() {
  if (assistantOpen.value) {
    closeAssistant();
    return;
  }
  sidebarOpen.value = false;
  assistantOpen.value = true;
}

async function closeAssistant() {
  assistantOpen.value = false;
  await nextTick();
  if (assistantToggle.value?.getClientRects().length) {
    assistantToggle.value.focus();
  } else {
    menuToggle.value?.ref?.focus();
  }
}

const changePwdDialog = reactive({
  visible: false,
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
});

const workspaceModel = computed({
  get: () => appStore.workspaceMode,
  set: (val) => {
    appStore.workspaceMode = val;
  }
});

const activeRuntimeMenu = computed(() => {
  if (route.name !== 'runtime-menu') return '';
  const menuName = decodeURIComponent(String(route.params.menuName || ''));
  return appStore.findMenuNode(menuName, route.path)?.name || menuName;
});
const runtimeAssistant = computed(() => appStore.workspaceMode === 'runtime');
const assistantAvailable = computed(() => runtimeAssistant.value || authStore.canAccessStudio || authStore.canManageTenants);

function switchStudioTab(tab) {
  sidebarOpen.value = false;
  router.push('/studio/' + tab);
}

function openRuntimeMenu(menu) {
  if (!menu?.routePath) return;
  sidebarOpen.value = false;
  router.push(menu.routePath);
}

function activateTab(tab) {
  appStore.activeTabId = tab.id;
  if (tab.mode === 'studio') {
    router.push('/studio/' + tab.key);
  } else {
    router.push(tab.key);
  }
}

function handleCloseTab(id) {
  const nextTab = appStore.closeTab(id);
  if (nextTab) {
    activateTab(nextTab);
  }
}

async function onWorkspaceChange(mode) {
  appStore.workspaceMode = mode;
  if (mode === 'runtime') {
    await appStore.loadMenuTree();
    try {
      await authStore.refreshProfile();
    } catch {
      // ignore
    }
    await router.push('/runtime');
  } else if (mode === 'tenant_admin') {
    appStore.openTabs = [];
    await router.push('/tenant-admin');
  } else {
    await router.push('/studio/' + (appStore.studioTab || 'overview'));
    await appStore.loadStudioOverview();
  }
}

async function handleUserMenuCommand(cmd) {
  if (cmd === 'openChangePwd') {
    changePwdDialog.oldPassword = '';
    changePwdDialog.newPassword = '';
    changePwdDialog.confirmPassword = '';
    changePwdDialog.visible = true;
  } else if (cmd === 'logout') {
    await authStore.logout();
    appStore.resetWorkspaceState();
    ElMessage.info('已安全退出登录');
    await router.push('/login');
  }
}

async function submitChangePassword() {
  if (!changePwdDialog.oldPassword || !changePwdDialog.newPassword) {
    ElMessage.warning('请填写当前密码和新密码');
    return;
  }
  if (changePwdDialog.newPassword !== changePwdDialog.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致');
    return;
  }
  try {
    await authStore.changePassword(changePwdDialog.oldPassword, changePwdDialog.newPassword);
    ElMessage.success('密码修改成功，请妥善保管新密码');
    changePwdDialog.visible = false;
  } catch (e) {
    ElMessage.error(e.message);
  }
}

onMounted(async () => {
  if (!appStore.menuLoaded) {
    await appStore.loadMenuTree();
  }
  if (authStore.canAccessStudio) {
    await appStore.loadStudioOverview();
  }
});
</script>
