<template>
  <div class="lc-card">
    <el-alert
      v-if="authStore.canManageTenants && !authStore.isSystemAdmin"
      title="分级授权边界：当前作为企业/分站主管，仅可查看与管理本租户及下属分站的应用授权、部门与员工账号，无权操作平台根系统设置或其他无关企业。"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom:12px;"
    />
    <el-alert
      v-else-if="!authStore.isSystemAdmin"
      title="提示：仅系统管理员或授权主管可修改租户应用授权与账号，当前仅提供查看。"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom:12px;"
    />

    <el-tabs v-model="iamSubTab" @tab-change="loadAllIam">
      <el-tab-pane label="租户与应用授权管理" name="tenants">
        <div class="lc-toolbar">
          <span style="font-size:13px;color:var(--text-muted);">
            {{ authStore.isSystemAdmin ? '创建企业租户并精确分配每个租户登录后能使用的应用范围。' : '为下属分站分配已获授权的应用，并管理分站开通状态。' }}
          </span>
          <el-button size="small" type="primary" :disabled="!authStore.canManageTenants" @click="openTenantDialog(null)">
            {{ authStore.isSystemAdmin ? '+ 新增租户' : '+ 新增下属分站' }}
          </el-button>
        </div>
        <el-table :data="appStore.tenantsList" border size="small">
          <el-table-column prop="name" label="租户名称" width="165">
            <template #default="{ row }">
              <strong>{{ row.name }}</strong>
              <el-tag v-if="row.id === authStore.currentUser?.tenantId && !authStore.isSystemAdmin" size="small" effect="plain" style="margin-left:5px;">本级总部</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="code" label="租户编码" width="125" />
          <el-table-column label="上级归属" width="135">
            <template #default="{ row }">{{ row.parentTenantId ? appStore.resolveTenantName(row.parentTenantId) : '平台顶级' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="95">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ row.status === 'ACTIVE' ? '正常启用' : '已停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="已授权可登录的应用" min-width="175">
            <template #default="{ row }">
              <el-tag v-if="row.authorizedApps && row.authorizedApps.includes('*')" size="small" type="primary">全部应用可用 (*)</el-tag>
              <el-tag v-else-if="!row.authorizedApps || row.authorizedApps.length === 0" size="small" type="warning">暂未授权任何应用（无法登录）</el-tag>
              <el-tag v-else v-for="ap in row.authorizedApps" :key="ap" size="small" type="success" style="margin-right:4px;">{{ appStore.resolveAppDisplayName(ap) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="已授权菜单范围" min-width="205">
            <template #default="{ row }">
              <el-tag v-if="!row.authorizedApps || row.authorizedApps.length === 0" size="small" type="info">暂无可用应用</el-tag>
              <el-tag v-else-if="!row.authorizedMenus || row.authorizedMenus.length === 0 || row.authorizedMenus.includes('*')" size="small" type="primary" effect="plain">应用内全部菜单 (*)</el-tag>
              <el-tag v-else-if="row.authorizedMenus.includes('__NONE__')" size="small" type="danger" effect="plain">未勾选任何菜单</el-tag>
              <el-tag v-else v-for="mn in row.authorizedMenus" :key="mn" size="small" type="warning" effect="light" style="margin:2px 4px 2px 0;">{{ appStore.resolveMenuDisplayName(mn) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="340" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain :disabled="!authStore.canManageTenants || (!authStore.isSystemAdmin && row.id === authStore.currentUser?.tenantId)" @click="openAuthAppsDialog(row)">应用与菜单授权</el-button>
              <el-button link type="success" size="small" :disabled="!authStore.canManageTenants" @click="openQuickUserForTenant(row)">分配账号</el-button>
              <el-button link type="primary" size="small" :disabled="!authStore.canManageTenants" @click="openTenantDialog(row)">编辑</el-button>
              <el-button
                v-if="row.id !== 'rbac_tenant_root' && row.id !== authStore.currentUser?.tenantId"
                link
                type="danger"
                size="small"
                :disabled="!authStore.canManageTenants"
                @click="handleDeleteTenant(row)"
              >删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="部门与登录账号" name="users_orgs">
        <div class="lc-toolbar">
          <div style="display:flex;gap:8px;align-items:center;">
            <span style="font-size:12.5px;color:var(--text-muted);">所属租户：</span>
            <el-select v-model="iamFilterTenant" clearable placeholder="全部可见租户" size="small" style="width:190px;" @change="loadOrgsAndUsers">
              <el-option v-for="t in appStore.tenantsList" :key="t.id" :label="t.name + ' (' + t.code + ')'" :value="t.id" />
            </el-select>
          </div>
          <div style="display:flex;gap:8px;">
            <el-button size="small" :disabled="!authStore.canManageTenants && !authStore.canAccessStudio" @click="openOrgDialog(null)">+ 新增部门</el-button>
            <el-button size="small" type="primary" :disabled="!authStore.canManageTenants" @click="openUserDialog(null)">+ 新增账号</el-button>
          </div>
        </div>
        <el-row :gutter="14">
          <el-col :span="9">
            <el-table :data="orgsList" border size="small">
              <el-table-column prop="name" label="部门节点" min-width="140">
                <template #default="{ row }">
                  <span v-if="row.parentId" style="color:var(--text-muted);margin-right:4px;">└─</span><strong>{{ row.name }}</strong>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="95">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openOrgDialog(row)">改</el-button>
                  <el-button link type="danger" size="small" @click="deleteOrg(row.id)">删</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-col>
          <el-col :span="15">
            <el-table :data="usersList" border size="small">
              <el-table-column prop="username" label="登录账号" width="110" />
              <el-table-column prop="displayName" label="姓名" width="125" />
              <el-table-column label="所属租户" width="135"><template #default="{ row }">{{ appStore.resolveTenantName(row.tenantId) }}</template></el-table-column>
              <el-table-column label="角色" min-width="140">
                <template #default="{ row }">
                  <el-tag v-for="rc in (row.roleCodes || [])" :key="rc" size="small" style="margin-right:4px;">{{ friendlyRoleLabel(rc) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="195">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" :disabled="!authStore.canManageTenants" @click="openUserDialog(row)">编辑</el-button>
                  <el-button link type="warning" size="small" :disabled="!authStore.canManageTenants" @click="resetUserPassword(row)">重置密码</el-button>
                  <el-button link type="danger" size="small" :disabled="!authStore.canManageTenants || row.id === authStore.currentUser?.id || row.id === 'usr_admin'" @click="deleteUser(row.id)">删</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-col>
        </el-row>
      </el-tab-pane>

      <el-tab-pane v-if="authStore.canManageTenants" label="角色与操作权限" name="roles">
        <div class="lc-toolbar">
          <span style="font-size:12.5px;color:var(--text-muted);">按角色配置可访问的具体菜单、数据查看范围及可操作的数据表与功能按钮权限（系统内置角色仅只读展示）。</span>
          <el-button size="small" type="primary" :disabled="!authStore.canManageTenants" @click="openRoleDialog(null)">+ 新增角色</el-button>
        </div>
        <el-table :data="rolesList" border size="small">
          <el-table-column prop="code" label="角色编码" width="150" />
          <el-table-column prop="name" label="角色名称" width="210" />
          <el-table-column label="归属范围" width="135">
            <template #default="{ row }">
              <el-tag size="small" :type="isBuiltInRole(row.code) ? 'info' : 'success'" effect="plain">
                {{ isBuiltInRole(row.code) ? '平台内置' : appStore.resolveTenantName(row.tenantId) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="可访问菜单" min-width="200">
            <template #default="{ row }">
              <el-tag v-if="!row.allowedMenus || row.allowedMenus.length === 0 || row.allowedMenus.includes('*')" size="small" type="info" effect="plain">继承租户全部菜单 (*)</el-tag>
              <el-tag v-else-if="row.allowedMenus.includes('__NONE__')" size="small" type="danger" effect="plain">无业务菜单</el-tag>
              <el-tag v-else v-for="mn in row.allowedMenus" :key="mn" size="small" type="warning" effect="light" style="margin:2px 4px 2px 0;">{{ appStore.resolveMenuDisplayName(mn) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="可见数据范围" width="110">
            <template #default="{ row }">{{ row.dataScope === 'SELF' ? '仅本人创建' : '本租户全部' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="185" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" :disabled="!canEditRole(row)" @click="openRoleDialog(row)">配置权限与菜单</el-button>
              <el-button link type="danger" size="small" :disabled="!canEditRole(row) || isBuiltInRole(row.code)" @click="deleteRole(row.code)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 租户信息编辑弹窗 -->
    <el-dialog v-model="tenantDialog.visible" title="租户信息管理" width="450px" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="租户编码" required><el-input v-model="tenantDialog.form.code" :disabled="tenantDialog.form.id === 'rbac_tenant_root'" /></el-form-item>
        <el-form-item label="租户名称" required><el-input v-model="tenantDialog.form.name" /></el-form-item>
        <el-form-item v-if="authStore.isSystemAdmin && tenantDialog.form.id !== 'rbac_tenant_root'" label="上级租户">
          <el-select v-model="tenantDialog.form.parentTenantId" clearable placeholder="默认为平台根租户" style="width:100%;">
            <el-option v-for="t in appStore.tenantsList.filter(x => x.id !== tenantDialog.form.id)" :key="t.id" :label="t.name + ' (' + t.code + ')'" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系人"><el-input v-model="tenantDialog.form.contactName" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="tenantDialog.form.contactPhone" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="tenantDialog.form.status" :disabled="tenantDialog.form.id === 'rbac_tenant_root'" style="width:100%;">
            <el-option value="ACTIVE" label="正常启用" />
            <el-option value="DISABLED" label="已停用（禁止登录）" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="tenantDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveTenant">保存</el-button>
      </template>
    </el-dialog>

    <!-- 应用与菜单勾选分发弹窗 -->
    <el-dialog v-model="authAppsDialog.visible" :title="'设置应用与菜单授权：' + authAppsDialog.tenantName" width="600px" destroy-on-close>
      <el-alert
        :title="authStore.isSystemAdmin ? '勾选允许该租户（含默认平台根租户及 admin 工作区）登录与访问的业务应用，并可按需精细勾选开放的具体功能菜单。' : '仅可向下属分站转授本总部已获授权的应用，并直接勾选要开放的具体菜单（如只开放「包裹入库与取件」和「问题件处理单」，隐藏「货架区位档案」）。'"
        type="info"
        :closable="false"
        style="margin-bottom:12px;"
      />
      <div v-if="authStore.isSystemAdmin" style="margin-bottom:10px;padding:8px 10px;background:var(--bg-muted);border-radius:6px;">
        <el-checkbox v-model="authAppsDialog.allowAll">允许该租户使用全部应用与全部菜单（包括未来新建的应用）</el-checkbox>
      </div>
      <div v-if="!authAppsDialog.allowAll || !authStore.isSystemAdmin">
        <div style="margin-bottom:10px;padding:8px 10px;background:var(--bg-muted);border-radius:6px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;">
          <span style="font-size:12.5px;font-weight:600;color:var(--text-regular);">菜单开放粒度：</span>
          <el-radio-group v-model="authAppsDialog.restrictMenus" size="small">
            <el-radio :value="false" :disabled="authStore.callerHasMenuRestriction">开放所选应用的全部激活菜单</el-radio>
            <el-radio :value="true">精细勾选指定菜单</el-radio>
          </el-radio-group>
        </div>
        <div v-if="appStore.assignableApps.length === 0" style="color:var(--text-muted);font-size:12.5px;padding:10px;">当前没有可分配的业务应用。</div>
        <el-checkbox-group v-else v-model="authAppsDialog.selected" @change="onAuthAppsSelectionChange" style="display:flex;flex-direction:column;gap:10px;padding:2px 4px;max-height:360px;overflow-y:auto;">
          <div v-for="ap in appStore.assignableApps" :key="ap.name" style="border:1px solid var(--border-light);border-radius:8px;padding:10px 12px;background:var(--bg-card);">
            <div style="display:flex;align-items:center;justify-content:space-between;">
              <el-checkbox :value="ap.name">
                <strong>{{ ap.displayName }}</strong> <span style="color:var(--text-muted);font-size:12px;">({{ ap.name }})</span>
              </el-checkbox>
              <div v-if="authAppsDialog.restrictMenus && authAppsDialog.selected.includes(ap.name) && appStore.getMenusForApp(ap.name).length > 0" style="display:flex;gap:8px;">
                <el-button link type="primary" size="small" @click.stop="selectAllAppMenus(ap.name)">全选菜单</el-button>
                <el-button link type="info" size="small" @click.stop="clearAppMenus(ap.name)">清空</el-button>
              </div>
            </div>
            <div v-if="authAppsDialog.restrictMenus && authAppsDialog.selected.includes(ap.name)" style="margin:8px 0 0 22px;padding:8px 10px;background:var(--bg-muted);border-radius:6px;">
              <div v-if="appStore.getMenusForApp(ap.name).length === 0" style="color:var(--text-muted);font-size:12px;">该应用暂无可分配的激活菜单。</div>
              <el-checkbox-group v-else v-model="authAppsDialog.selectedMenus" style="display:flex;flex-wrap:wrap;gap:10px 18px;">
                <el-checkbox v-for="mn in appStore.getMenusForApp(ap.name)" :key="mn.name" :value="mn.name">
                  {{ mn.displayName }} <span style="color:var(--text-muted);font-size:11px;">({{ mn.model }})</span>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
        </el-checkbox-group>
      </div>
      <template #footer>
        <el-button size="small" @click="authAppsDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveAuthApps">保存应用与菜单授权</el-button>
      </template>
    </el-dialog>

    <!-- 部门弹窗 -->
    <el-dialog v-model="orgDialog.visible" title="部门信息" width="430px" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="所属租户">
          <el-select v-model="orgDialog.form.tenantId" style="width:100%;">
            <el-option v-for="t in appStore.tenantsList" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="上级部门">
          <el-select v-model="orgDialog.form.parentId" clearable style="width:100%;">
            <el-option v-for="o in orgsList" :key="o.id" :label="o.name" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门名称" required><el-input v-model="orgDialog.form.name" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="orgDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveOrg">保存</el-button>
      </template>
    </el-dialog>

    <!-- 账号设置弹窗 -->
    <el-dialog v-model="userDialog.visible" title="登录账号设置" width="470px" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="登录账号" required><el-input v-model="userDialog.form.username" :disabled="!!userDialog.form.id" /></el-form-item>
        <el-form-item label="姓名" required><el-input v-model="userDialog.form.displayName" /></el-form-item>
        <el-form-item label="所属租户">
          <el-select v-model="userDialog.form.tenantId" style="width:100%;">
            <el-option v-for="t in appStore.tenantsList" :key="t.id" :label="t.name + ' (' + t.code + ')'" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属部门">
          <el-select v-model="userDialog.form.orgId" style="width:100%;">
            <el-option v-for="o in orgsList" :key="o.id" :label="o.name" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="赋予角色">
          <el-select v-model="userDialog.form.roleCodes" multiple style="width:100%;">
            <el-option v-for="r in rolesList" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!userDialog.form.id" label="登录密码">
          <el-input v-model="userDialog.form.password" type="password" show-password placeholder="留空默认 123456" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="userDialog.form.status" style="width:100%;">
            <el-option value="ACTIVE" label="正常启用" />
            <el-option value="DISABLED" label="已停用" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="userDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色菜单与操作权限配置弹窗 -->
    <el-dialog v-model="roleDialog.visible" title="角色菜单与操作权限配置" width="690px" destroy-on-close>
      <el-form label-width="85px" size="small">
        <el-row :gutter="10">
          <el-col :span="7"><el-form-item label="角色编码" required><el-input v-model="roleDialog.form.code" :disabled="!!roleDialog.form.id" placeholder="如 ROLE_EAST_CLERK" /></el-form-item></el-col>
          <el-col :span="9"><el-form-item label="角色名称" required><el-input v-model="roleDialog.form.name" placeholder="如 东区接单员" /></el-form-item></el-col>
          <el-col :span="8">
            <el-form-item label="归属租户">
              <el-select v-model="roleDialog.form.tenantId" style="width:100%;" :disabled="!authStore.isSystemAdmin && appStore.tenantsList.length <= 1">
                <el-option v-for="t in appStore.tenantsList" :key="t.id" :label="t.name" :value="t.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="10">
          <el-col :span="8">
            <el-form-item label="可见范围">
              <el-select v-model="roleDialog.form.dataScope" style="width:100%;">
                <el-option value="ALL" label="全租户" />
                <el-option value="SELF" label="仅本人" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="菜单范围">
              <el-radio-group v-model="roleDialog.restrictMenus" size="small">
                <el-radio :value="false" :disabled="authStore.callerHasMenuRestriction">全部已授权菜单</el-radio>
                <el-radio :value="true">勾选指定菜单</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <div v-if="roleDialog.restrictMenus" class="lc-perm-group" style="margin-bottom:10px;background:var(--el-color-warning-light-9);border-color:var(--el-color-warning-light-8);">
          <div style="font-weight:600;font-size:12.5px;margin-bottom:6px;color:var(--el-color-warning);">勾选允许该角色看到的导航菜单（未勾选菜单隐藏且禁止直接打开）</div>
          <div v-for="grp in appStore.assignableMenuGroups" :key="grp.appName" style="margin-bottom:6px;">
            <div style="font-size:12px;color:var(--text-muted);font-weight:600;margin-bottom:4px;">{{ grp.appDisplayName }}</div>
            <el-checkbox-group v-model="roleDialog.form.allowedMenus">
              <el-checkbox v-for="mn in grp.menus" :key="mn.name" :value="mn.name" style="margin-right:14px;">{{ mn.displayName }}</el-checkbox>
            </el-checkbox-group>
          </div>
        </div>
        <div style="max-height:265px;overflow-y:auto;">
          <div v-for="grp in permissionCatalog" :key="grp.groupKey" class="lc-perm-group">
            <div style="font-weight:600;font-size:12.5px;margin-bottom:6px;">{{ grp.groupLabel }}</div>
            <el-checkbox-group v-model="roleDialog.form.allowedServices">
              <el-checkbox v-for="it in grp.items" :key="it.authExpr" :value="it.authExpr" style="margin-right:14px;">{{ it.label }}</el-checkbox>
            </el-checkbox-group>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button size="small" @click="roleDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveRole">保存权限与菜单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';
import { friendlyRoleLabel, isBuiltInRole } from '@/utils/formatters';

const authStore = useAuthStore();
const appStore = useAppStore();

const iamSubTab = ref('tenants');
const iamFilterTenant = ref('');
const orgsList = ref([]);
const usersList = ref([]);
const rolesList = ref([]);
const permissionCatalog = ref([]);

const tenantDialog = reactive({
  visible: false,
  form: { id: '', code: '', name: '', parentTenantId: '', contactName: '', contactPhone: '', status: 'ACTIVE', authorizedApps: [], authorizedMenus: [] }
});
const authAppsDialog = reactive({
  visible: false,
  tenantId: '',
  tenantName: '',
  allowAll: false,
  selected: [],
  restrictMenus: false,
  selectedMenus: []
});
const orgDialog = reactive({
  visible: false,
  form: { id: '', tenantId: 'rbac_tenant_root', parentId: '', name: '', orgType: 'DEPT', sequence: 10 }
});
const userDialog = reactive({
  visible: false,
  form: { id: '', username: '', displayName: '', tenantId: 'rbac_tenant_root', orgId: 'org_root', password: '', status: 'ACTIVE', roleCodes: ['ROLE_APP_USER'] }
});
const roleDialog = reactive({
  visible: false,
  restrictMenus: false,
  form: { id: '', code: '', name: '', tenantId: 'rbac_tenant_root', dataScope: 'ALL', allowedServices: [], allowedMenus: [] }
});

function canEditRole(row) {
  return !!(authStore.isSystemAdmin || (authStore.canManageTenants && row && !isBuiltInRole(row.code)));
}

async function loadOrgsAndUsers() {
  try {
    const tid = iamFilterTenant.value || '';
    const [orgs, users] = await Promise.all([
      rpcCall('rbac_org', 'listOrgs', { tenantId: tid }),
      rpcCall('rbac_user', 'listUsers', { tenantId: tid })
    ]);
    orgsList.value = orgs || [];
    usersList.value = users || [];
  } catch {
    // ignore
  }
}

async function loadRolesAndCatalog() {
  try {
    const roles = await rpcCall('rbac_role', 'listRoles', {});
    rolesList.value = roles || [];
    if (authStore.isSystemAdmin || authStore.canManageTenants) {
      permissionCatalog.value = (await rpcCall('rbac_role', 'permissionCatalog', {})) || [];
    } else {
      permissionCatalog.value = [];
    }
  } catch {
    // ignore
  }
}

async function loadAllIam() {
  await Promise.all([appStore.loadTenants(), loadOrgsAndUsers(), loadRolesAndCatalog(), appStore.loadAssignableMenus()]);
}

function openTenantDialog(row) {
  tenantDialog.form = row
    ? { ...row }
    : {
        id: '',
        code: '',
        name: '',
        parentTenantId: authStore.isSystemAdmin ? 'rbac_tenant_root' : authStore.currentUser?.tenantId || '',
        contactName: '',
        contactPhone: '',
        status: 'ACTIVE',
        authorizedApps: [],
        authorizedMenus: []
      };
  tenantDialog.visible = true;
}

async function saveTenant() {
  const { code, name } = tenantDialog.form;
  if (!/^[A-Za-z][A-Za-z0-9_-]{0,31}$/.test(code?.trim() || '')) {
    ElMessage.warning('租户编码须以字母开头，最多 32 个字母、数字、下划线或短横线。');
    return;
  }
  if (!name?.trim() || [...name.trim()].length > 40) {
    ElMessage.warning('租户名称须为 1 至 40 个字符。');
    return;
  }
  try {
    await rpcCall('rbac_tenant', 'saveTenant', { values: tenantDialog.form });
    ElMessage.success('租户保存成功');
    tenantDialog.visible = false;
    await loadAllIam();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function handleDeleteTenant(row) {
  if (!row?.id || row.id === 'rbac_tenant_root') return;
  try {
    await ElMessageBox.confirm(
      `确认删除租户「${row.name || row.code}」(${row.code}) 吗？将同步删除该租户下的部门组织、自定义角色与登录账号，且不可恢复！`,
      '删除租户确认',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    );
    const res = await rpcCall('rbac_tenant', 'deleteTenant', { tenantId: row.id });
    if (iamFilterTenant.value === row.id) iamFilterTenant.value = '';
    await loadAllIam();
    ElMessage.success(
      `已删除租户「${row.name || row.code}」（同步清理 ${res.deletedOrgs ?? 0} 个部门、${res.deletedUsers ?? 0} 个账号、${res.deletedRoles ?? 0} 个角色）`
    );
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.message || '删除租户失败');
    }
  }
}

async function openAuthAppsDialog(row) {
  await appStore.loadAssignableMenus();
  authAppsDialog.tenantId = row.id;
  authAppsDialog.tenantName = row.name;
  const curApps = Array.isArray(row.authorizedApps) ? row.authorizedApps : [];
  authAppsDialog.allowAll = authStore.isSystemAdmin ? curApps.includes('*') : false;
  authAppsDialog.selected = curApps.filter((x) => x !== '*');
  const curMenus = Array.isArray(row.authorizedMenus) ? row.authorizedMenus : [];
  const hasExplicit = curMenus.length > 0 && !curMenus.includes('*');
  authAppsDialog.restrictMenus = hasExplicit || authStore.callerHasMenuRestriction;
  if (hasExplicit) {
    authAppsDialog.selectedMenus = curMenus.filter((x) => x !== '__NONE__');
  } else {
    const allDefaults = [];
    for (const apName of authAppsDialog.selected) {
      for (const m of appStore.getMenusForApp(apName)) allDefaults.push(m.name);
    }
    authAppsDialog.selectedMenus = allDefaults;
  }
  authAppsDialog.visible = true;
}

function onAuthAppsSelectionChange(newSelectedApps) {
  const validSet = new Set();
  const curSet = new Set(authAppsDialog.selectedMenus);
  for (const apName of newSelectedApps || []) {
    const appMenus = appStore.getMenusForApp(apName);
    const hadAny = appMenus.some((m) => curSet.has(m.name));
    for (const m of appMenus) {
      validSet.add(m.name);
      if (!hadAny && !authAppsDialog.restrictMenus) curSet.add(m.name);
    }
  }
  if (!authAppsDialog.restrictMenus) {
    authAppsDialog.selectedMenus = [...validSet];
  } else {
    const next = [];
    for (const apName of newSelectedApps || []) {
      const appMenus = appStore.getMenusForApp(apName);
      const alreadyHad = appMenus.some((m) => curSet.has(m.name));
      for (const m of appMenus) {
        if (alreadyHad ? curSet.has(m.name) : true) next.push(m.name);
      }
    }
    authAppsDialog.selectedMenus = [...new Set(next)];
  }
}

function selectAllAppMenus(appName) {
  const names = appStore.getMenusForApp(appName).map((m) => m.name);
  authAppsDialog.selectedMenus = [...new Set([...authAppsDialog.selectedMenus, ...names])];
}

function clearAppMenus(appName) {
  const toRemove = new Set(appStore.getMenusForApp(appName).map((m) => m.name));
  authAppsDialog.selectedMenus = authAppsDialog.selectedMenus.filter((x) => !toRemove.has(x));
}

async function saveAuthApps() {
  try {
    if (authStore.isSystemAdmin && authAppsDialog.allowAll) {
      await rpcCall('rbac_tenant', 'authorizeApps', {
        tenantId: authAppsDialog.tenantId,
        authorizedApps: ['*'],
        authorizedMenus: ['*']
      });
    } else {
      const allowedSet = new Set();
      for (const apName of authAppsDialog.selected) {
        for (const m of appStore.getMenusForApp(apName)) allowedSet.add(m.name);
      }
      let menusPayload = ['*'];
      if (authAppsDialog.restrictMenus) {
        const picked = (authAppsDialog.selectedMenus || []).filter((m) => allowedSet.has(m));
        menusPayload = picked.length > 0 ? picked : ['__NONE__'];
      }
      await rpcCall('rbac_tenant', 'authorizeApps', {
        tenantId: authAppsDialog.tenantId,
        authorizedApps: authAppsDialog.selected,
        authorizedMenus: menusPayload
      });
    }
    ElMessage.success('应用与菜单授权已生效');
    authAppsDialog.visible = false;
    await appStore.loadTenants();
    if (authAppsDialog.tenantId === authStore.currentUser?.tenantId) {
      await Promise.all([authStore.refreshProfile(), appStore.loadMenuTree()]);
      appStore.openTabs = appStore.openTabs.filter(
        (t) => t.mode !== 'runtime' || !!appStore.findMenuNode('', t.key)
      );
    }
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function openQuickUserForTenant(row) {
  iamFilterTenant.value = row.id;
  iamSubTab.value = 'users_orgs';
  await loadOrgsAndUsers();
  openUserDialog(null);
}

function openOrgDialog(row) {
  const defTid = iamFilterTenant.value || authStore.currentUser?.tenantId || 'rbac_tenant_root';
  orgDialog.form = row ? { ...row } : { id: '', tenantId: defTid, parentId: orgsList.value[0]?.id || '', name: '', orgType: 'DEPT', sequence: 10 };
  orgDialog.visible = true;
}

async function saveOrg() {
  try {
    await rpcCall('rbac_org', 'saveOrg', { values: orgDialog.form });
    ElMessage.success('部门保存成功');
    orgDialog.visible = false;
    await loadOrgsAndUsers();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function deleteOrg(orgId) {
  try {
    await rpcCall('rbac_org', 'deleteOrg', { orgId });
    ElMessage.success('部门已删除');
    await loadOrgsAndUsers();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function openUserDialog(row) {
  const defTid = iamFilterTenant.value || authStore.currentUser?.tenantId || 'rbac_tenant_root';
  userDialog.form = row
    ? { ...row, roleCodes: [...(row.roleCodes || [])] }
    : {
        id: '',
        username: '',
        displayName: '',
        tenantId: defTid,
        orgId: orgsList.value.find((o) => o.tenantId === defTid)?.id || orgsList.value[0]?.id || 'org_' + defTid,
        password: '',
        status: 'ACTIVE',
        roleCodes: ['ROLE_APP_USER']
      };
  userDialog.visible = true;
}

async function saveUser() {
  try {
    await rpcCall('rbac_user', 'saveUser', { values: userDialog.form });
    ElMessage.success('账号保存成功');
    userDialog.visible = false;
    await loadOrgsAndUsers();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function resetUserPassword(row) {
  try {
    const { value } = await ElMessageBox.prompt('请输入账号 [' + row.username + '] 的新密码：', '重置密码', { inputValue: '123456' });
    await rpcCall('rbac_user', 'resetPassword', { userId: row.id, newPassword: value });
    ElMessage.success('密码重置成功');
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message);
  }
}

async function deleteUser(userId) {
  try {
    await ElMessageBox.confirm('确认删除该账号？', '确认', { type: 'warning' });
    await rpcCall('rbac_user', 'deleteUser', { userId });
    await loadOrgsAndUsers();
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message);
  }
}

async function openRoleDialog(row) {
  await Promise.all([loadRolesAndCatalog(), appStore.loadAssignableMenus()]);
  const allMenus = appStore.assignableMenuGroups.flatMap((g) => (g.menus || []).map((m) => m.name));
  if (row) {
    const curMenus = Array.isArray(row.allowedMenus) ? row.allowedMenus : [];
    const hasExplicit = curMenus.length > 0 && !curMenus.includes('*');
    roleDialog.restrictMenus = hasExplicit || authStore.callerHasMenuRestriction;
    roleDialog.form = {
      ...row,
      tenantId: row.tenantId || authStore.currentUser?.tenantId || 'rbac_tenant_root',
      allowedServices: [...(row.allowedServices || [])],
      allowedMenus: hasExplicit ? curMenus.filter((x) => x !== '__NONE__') : [...allMenus]
    };
  } else {
    roleDialog.restrictMenus = true;
    roleDialog.form = {
      id: '',
      code: '',
      name: '',
      tenantId: iamFilterTenant.value || authStore.currentUser?.tenantId || 'rbac_tenant_root',
      dataScope: 'ALL',
      allowedServices: [
        'ui_*:*',
        '*:lookup',
        '*:addMissingDefaultValues',
        '*:getSelectionOptions',
        'meta_dict:itemsByType',
        'meta_dict:listTypes',
        'meta_code_rule:generateCode',
        'meta_file:*',
        'rbac_org:listOrgs'
      ],
      allowedMenus: [...allMenus]
    };
  }
  roleDialog.visible = true;
}

async function saveRole() {
  try {
    const payload = { ...roleDialog.form };
    payload.allowedMenus = roleDialog.restrictMenus
      ? payload.allowedMenus && payload.allowedMenus.length
        ? payload.allowedMenus
        : ['__NONE__']
      : [];
    await rpcCall('rbac_role', 'saveRole', { values: payload });
    ElMessage.success('角色权限与菜单保存成功');
    roleDialog.visible = false;
    await loadRolesAndCatalog();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function deleteRole(roleCode) {
  try {
    await ElMessageBox.confirm('确认删除角色 [' + roleCode + '] ？', '确认', { type: 'warning' });
    await rpcCall('rbac_role', 'deleteRole', { roleCode });
    ElMessage.success('角色已删除');
    await loadRolesAndCatalog();
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message);
  }
}

onMounted(loadAllIam);
</script>
