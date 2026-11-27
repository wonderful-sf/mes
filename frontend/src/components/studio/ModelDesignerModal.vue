<template>
  <div>
    <!-- 数据表可视化设计器 -->
    <el-dialog v-model="state.visible" :title="state.isEdit ? '编辑数据表：' + snapshot.displayName : '新建业务数据表'" width="960px" destroy-on-close>
      <el-form label-width="110px" size="small">
        <el-row :gutter="10">
          <el-col :span="6"><el-form-item label="英文表名" required><el-input v-model="snapshot.name" :disabled="state.isEdit" placeholder="例如 order_bill" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="中文名称" required><el-input v-model="snapshot.displayName" placeholder="例如 订单台账" /></el-form-item></el-col>
          <el-col :span="6">
            <el-form-item label="所属应用" required>
              <el-select v-model="snapshot.appName" filterable allow-create default-first-option style="width:100%;" placeholder="选择或输入应用">
                <el-option v-for="ap in appStore.businessAppsOnly" :key="ap.name" :label="ap.displayName + ' (' + ap.name + ')'" :value="ap.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6"><el-form-item label="版本号" required><el-input v-model="snapshot.versionNo" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="复用模板表"><el-input v-model="snapshot._parentsCsv" placeholder="可选，如 base_doc" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="扩展已有表"><el-input v-model="snapshot.extendModel" placeholder="可选，追加列到某表" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="默认排序"><el-input v-model="snapshot.orderBy" placeholder="create_date desc" /></el-form-item></el-col>
          <el-col :span="3"><el-form-item label="仅作模板"><el-switch v-model="snapshot.abstractModel" /></el-form-item></el-col>
          <el-col :span="3"><el-form-item label="安全软删"><el-switch v-model="snapshot.logicDelete" /></el-form-item></el-col>
        </el-row>

        <el-tabs>
          <el-tab-pane label="字段设计">
            <div style="margin-bottom:8px;"><el-button size="small" type="primary" plain @click="addProperty">+ 添加字段</el-button></div>
            <el-table :data="snapshot.properties" border size="small" max-height="260">
              <el-table-column label="字段英文名" width="115"><template #default="{ row }"><el-input v-model="row.name" size="small" placeholder="code" /></template></el-table-column>
              <el-table-column label="字段中文名" width="115"><template #default="{ row }"><el-input v-model="row.displayName" size="small" placeholder="编码" /></template></el-table-column>
              <el-table-column label="字段类型" width="145">
                <template #default="{ row }">
                  <el-select v-model="row.dataType" size="small">
                    <el-option v-for="t in DATA_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="必填" width="48" align="center"><template #default="{ row }"><el-checkbox v-model="row.required" /></template></el-table-column>
              <el-table-column label="唯一" width="48" align="center"><template #default="{ row }"><el-checkbox v-model="row.unique" /></template></el-table-column>
              <el-table-column label="可见" width="48" align="center"><template #default="{ row }"><el-checkbox v-model="row.display" /></template></el-table-column>
              <el-table-column label="主显示" width="60" align="center"><template #default="{ row }"><el-checkbox v-model="row.displayForModel" /></template></el-table-column>
              <el-table-column label="选项 / 关联表 / 默认值" min-width="195">
                <template #default="{ row }">
                  <el-input v-if="row.dataType === 'ManyToOne' || row.dataType === 'ManyToMany'" v-model="row.relateModel" size="small" placeholder="目标数据表英文名" />
                  <div v-else-if="row.dataType === 'OneToMany'" style="display:flex;gap:4px;">
                    <el-input v-model="row.relateModel" size="small" placeholder="明细表" />
                    <el-input v-model="row.inverseName" size="small" placeholder="关联字段" />
                  </div>
                  <el-select v-else-if="row.dataType === 'Dict'" v-model="row.dictType" filterable allow-create default-first-option size="small" placeholder="选择字典" style="width:100%;">
                    <el-option v-for="dt in appStore.dictTypes" :key="dt.typeCode" :value="dt.typeCode" :label="dt.name + ' (' + dt.typeCode + ')'" />
                  </el-select>
                  <el-input v-else-if="row.dataType === 'Selection'" v-model="row._valuesCsv" size="small" placeholder="OPEN:待办,DONE:完成" />
                  <el-input v-else v-model="row.defaultValue" size="small" placeholder="默认值" />
                </template>
              </el-table-column>
              <el-table-column label="高级 / 删" width="105" align="center">
                <template #default="{ row, $index }">
                  <el-button link type="primary" size="small" @click="openPropAdvDialog(row)">高级</el-button>
                  <el-button link type="danger" size="small" @click="snapshot.properties.splice($index, 1)">删</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="业务操作与状态流转按钮">
            <div style="margin-bottom:8px;display:flex;gap:8px;">
              <el-button size="small" type="success" plain @click="addService('STATE_TRANSITION')">+ 新增状态流转按钮</el-button>
              <el-button size="small" type="primary" plain @click="addService('GROOVY_SCRIPT')">+ 新增自定义脚本动作</el-button>
            </div>
            <div v-for="(svc, idx) in snapshot.services" :key="idx" style="border:1px solid var(--border-light);border-radius:8px;padding:10px;margin-bottom:8px;">
              <el-row :gutter="8">
                <el-col :span="6"><el-input v-model="svc.name" size="small" placeholder="动作标识" /></el-col>
                <el-col :span="6"><el-input v-model="svc.displayName" size="small" placeholder="按钮名字" /></el-col>
                <el-col :span="5"><el-tag size="small">{{ svc.type === 'STATE_TRANSITION' ? '状态流转' : '脚本逻辑' }}</el-tag></el-col>
                <el-col :span="4"><el-input v-model="svc.auth" size="small" placeholder="权限分组" /></el-col>
                <el-col :span="3" style="text-align:right;"><el-button link type="danger" size="small" @click="snapshot.services.splice(idx, 1)">移除</el-button></el-col>
              </el-row>
              <template v-if="svc.type === 'STATE_TRANSITION'">
                <el-row :gutter="8" style="margin-top:8px;">
                  <el-col :span="6"><el-input v-model="svc.transitionRule.statusField" size="small" placeholder="status" /></el-col>
                  <el-col :span="8"><el-input v-model="svc.transitionRule._fromStatesCsv" size="small" placeholder="原状态 DRAFT,OPEN" /></el-col>
                  <el-col :span="5"><el-input v-model="svc.transitionRule.toState" size="small" placeholder="目标状态" /></el-col>
                  <el-col :span="5"><el-input v-model="svc.transitionRule.errorMessage" size="small" placeholder="拦截提示" /></el-col>
                </el-row>
              </template>
              <template v-else-if="svc.type === 'GROOVY_SCRIPT'">
                <el-input v-model="svc.scriptContent" type="textarea" :rows="3" style="margin-top:8px;" />
              </template>
            </div>
          </el-tab-pane>

          <el-tab-pane label="防重复联合索引">
            <div style="margin-bottom:8px;"><el-button size="small" type="primary" plain @click="addIndex">+ 添加索引组合</el-button></div>
            <el-table :data="snapshot.indexes" border size="small">
              <el-table-column label="索引名称" width="180"><template #default="{ row }"><el-input v-model="row.name" size="small" /></template></el-table-column>
              <el-table-column label="参与字段（逗号分隔）" min-width="260"><template #default="{ row }"><el-input v-model="row._colsCsv" size="small" /></template></el-table-column>
              <el-table-column label="禁止重复" width="90" align="center"><template #default="{ row }"><el-checkbox v-model="row.unique" /></template></el-table-column>
              <el-table-column label="删" width="60"><template #default="{ $index }"><el-button link type="danger" size="small" @click="snapshot.indexes.splice($index, 1)">删</el-button></template></el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <template #footer>
        <el-button size="small" @click="state.visible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="saveDraft">存为草稿</el-button>
        <el-button type="success" size="small" :loading="saving" @click="saveDraftCheckAndPublish">检查影响并立即启用</el-button>
      </template>
    </el-dialog>

    <!-- 字段高级规则设置 -->
    <el-dialog v-model="propAdvDialog.visible" title="字段高级规则与联动设置" width="580px" destroy-on-close>
      <el-form label-width="135px" size="small" v-if="propAdvDialog.prop">
        <el-form-item label="自动编号规则"><el-input v-model="propAdvDialog.prop.codeRule" placeholder="填写编码规则代号" /></el-form-item>
        <el-form-item label="关联自动带出"><el-input v-model="propAdvDialog.prop.related" placeholder="如 dept_id.code" /></el-form-item>
        <el-form-item label="自动计算公式"><el-input v-model="propAdvDialog.prop.computeScript" placeholder="如 (row.qty ?: 0) * (row.price ?: 0)" /></el-form-item>
        <el-form-item label="动态计算服务名"><el-input v-model="propAdvDialog.prop.computeMethod" /></el-form-item>
        <el-form-item label="动态默认值规则"><el-input v-model="propAdvDialog.prop.defaultMethod" /></el-form-item>
        <el-form-item label="支持多选与存库">
          <el-checkbox v-model="propAdvDialog.prop.multiple">允许下拉多选</el-checkbox>
          <el-checkbox v-model="propAdvDialog.prop.store" style="margin-left:12px;">保存到数据库列</el-checkbox>
        </el-form-item>
        <el-form-item label="字数与邮箱校验">
          <el-input-number v-model="propAdvDialog.prop.minLength" :min="0" style="width:110px;margin-right:6px;" />
          <el-input-number v-model="propAdvDialog.prop.maxLength" :min="0" style="width:110px;margin-right:8px;" />
          <el-checkbox v-model="propAdvDialog.prop.email">校验邮箱格式</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer><el-button type="primary" size="small" @click="propAdvDialog.visible = false">确定</el-button></template>
    </el-dialog>

    <!-- 版本历史与影响检查弹窗 -->
    <el-dialog v-model="versionCenterState.visible" :title="'版本历史与影响检查：' + versionCenterState.modelName" width="840px" destroy-on-close>
      <el-table :data="versionList" border size="small">
        <el-table-column prop="versionNo" label="版本号" width="95" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : (row.status === 'VALIDATED' ? 'primary' : 'info')">
              {{ friendlyVersionStatus(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="修改人" width="100" />
        <el-table-column prop="createdAt" label="创建时间" width="160" />
        <el-table-column label="操作" min-width="200">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT' || row.status === 'VALIDATED'" link type="primary" size="small" @click="runCheckImpact(row.id)">影响检查</el-button>
            <el-button v-if="row.status === 'VALIDATED'" link type="success" size="small" @click="runPublishVersion(row)">发布生效</el-button>
            <el-button v-if="row.status === 'RETIRED'" link type="warning" size="small" @click="runRollbackVersion(row.id)">切回此版本</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="versionCenterState.impact" style="margin-top:12px;border:1px solid var(--border-light);border-radius:8px;padding:12px;">
        <div style="display:flex;align-items:center;gap:10px;margin-bottom:8px;">
          <strong>变更影响检查结果：</strong>
          <el-tag :type="versionCenterState.impact.passed ? 'success' : 'danger'">
            {{ versionCenterState.impact.passed ? '检查通过，可安全启用' : '存在冲突风险，已阻止发布' }}
          </el-tag>
        </div>
        <el-alert v-for="(err, idx) in (versionCenterState.impact.blockingReasons || [])" :key="'b' + idx" :title="err" type="error" :closable="false" style="margin-bottom:6px;" />
        <el-alert v-for="(w, idx) in (versionCenterState.impact.warnings || [])" :key="'w' + idx" :title="w" type="warning" :closable="false" style="margin-bottom:6px;" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAppStore } from '@/stores/app';
import { DATA_TYPE_OPTIONS, friendlyVersionStatus } from '@/utils/formatters';

const appStore = useAppStore();
const state = appStore.designerDialogState;
const versionCenterState = appStore.versionCenterState;

const saving = ref(false);
const snapshot = ref(createEmptySnapshot());
const propAdvDialog = reactive({ visible: false, prop: null });
const versionList = ref([]);

function createEmptySnapshot() {
  const defaultApp = appStore.businessAppsOnly[0]?.name || 'custom_app';
  return {
    name: '',
    displayName: '',
    appName: defaultApp,
    versionNo: '1.0.0',
    _parentsCsv: '',
    extendModel: '',
    abstractModel: false,
    orderBy: 'create_date desc',
    logicDelete: false,
    displayFormat: '',
    indexes: [],
    properties: [{ name: 'title', displayName: '名称', dataType: 'String', required: true, display: true, displayForModel: true, store: true }],
    services: []
  };
}

async function initModal() {
  if (appStore.dictTypes.length === 0) await appStore.loadDictTypes();
  if (!state.isEdit || !state.modelToOpen) {
    snapshot.value = createEmptySnapshot();
    return;
  }
  try {
    const detail = await rpcCall('meta_model', 'detail', { modelName: state.modelToOpen });
    const activeId = detail.model?.activeVersionId;
    const ver = (detail.versions || []).find((v) => v.id === activeId) || (detail.versions || [])[0];
    if (!ver || !ver.snapshot) {
      ElMessage.error('未找到配置');
      state.visible = false;
      return;
    }
    const snap = JSON.parse(JSON.stringify(ver.snapshot));
    if (ver.status === 'ACTIVE' || ver.status === 'RETIRED') {
      const parts = (snap.versionNo || '1.0.0').split('.').map((n) => parseInt(n, 10) || 0);
      snap.versionNo = `${parts[0]}.${parts[1]}.${(parts[2] || 0) + 1}`;
    }
    snap._parentsCsv = Array.isArray(snap.parents) ? snap.parents.join(',') : '';
    snap.indexes = (snap.indexes || []).map((ix) => ({ ...ix, _colsCsv: Array.isArray(ix.columnList) ? ix.columnList.join(',') : '' }));
    for (const p of snap.properties || []) {
      if (p.dataType === 'Selection' && Array.isArray(p.values)) {
        p._valuesCsv = p.values.map((o) => `${o.value}:${o.label}`).join(',');
      }
    }
    for (const s of snap.services || []) {
      if (s.transitionRule && Array.isArray(s.transitionRule.fromStates)) {
        s.transitionRule._fromStatesCsv = s.transitionRule.fromStates.join(',');
      }
    }
    snapshot.value = snap;
  } catch (e) {
    ElMessage.error(e.message);
    state.visible = false;
  }
}

watch(() => state.visible, (vis) => {
  if (vis) initModal();
});

function addProperty() {
  snapshot.value.properties.push({
    name: '',
    displayName: '',
    dataType: 'String',
    required: false,
    unique: false,
    display: true,
    displayForModel: false,
    store: true,
    _valuesCsv: ''
  });
}

function openPropAdvDialog(prop) {
  propAdvDialog.prop = prop;
  propAdvDialog.visible = true;
}

function addIndex() {
  if (!snapshot.value.indexes) snapshot.value.indexes = [];
  snapshot.value.indexes.push({ name: '', columnList: [], _colsCsv: '', unique: false });
}

function addService(type) {
  if (type === 'STATE_TRANSITION') {
    snapshot.value.services.push({
      name: '',
      displayName: '',
      type: 'STATE_TRANSITION',
      auth: 'action',
      transitionRule: { statusField: 'status', fromStates: [], _fromStatesCsv: '', toState: '', errorMessage: '' }
    });
  } else {
    snapshot.value.services.push({
      name: '',
      displayName: '',
      type: 'GROOVY_SCRIPT',
      auth: 'action',
      scriptContent: "return JsonRpcServiceResponse.message('执行成功');"
    });
  }
}

function buildSanitizedSnapshot() {
  const snap = JSON.parse(JSON.stringify(snapshot.value));
  const identifier = /^[a-z][a-z0-9_]{0,63}$/;
  if (!identifier.test(snap.name || '')) throw new Error('英文表名须以小写字母开头，最多 64 个字母、数字或下划线。');
  if (!snap.displayName?.trim() || [...snap.displayName.trim()].length > 60) throw new Error('中文名称须为 1 至 60 个字符。');
  if (!/^\d{1,9}\.\d{1,9}\.\d{1,9}$/.test(snap.versionNo || '')) throw new Error('版本号须为三个非负整数，例如 1.0.0。');
  snap.parents = (snap._parentsCsv || '').split(',').map((s) => s.trim()).filter(Boolean);
  delete snap._parentsCsv;
  if (!snap.extendModel || !snap.extendModel.trim()) snap.extendModel = null;
  snap.indexes = (snap.indexes || [])
    .map((ix) => ({
      name: ix.name,
      columnList: (ix._colsCsv || '').split(',').map((s) => s.trim()).filter(Boolean),
      unique: !!ix.unique
    }))
    .filter((ix) => ix.columnList.length > 0);
  for (const p of snap.properties || []) {
    if (!identifier.test(p.name || '')) throw new Error('字段英文名须以小写字母开头，最多 64 个字母、数字或下划线。');
    if (p.dataType === 'Selection' && p._valuesCsv) {
      p.values = p._valuesCsv
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean)
        .map((pair) => {
          const [v, l] = pair.split(':');
          return { value: (v || '').trim(), label: (l || v || '').trim(), color: v === 'DONE' ? 'success' : 'info' };
        });
    }
    delete p._valuesCsv;
  }
  for (const s of snap.services || []) {
    if (s.transitionRule && s.transitionRule._fromStatesCsv !== undefined) {
      s.transitionRule.fromStates = s.transitionRule._fromStatesCsv.split(',').map((x) => x.trim()).filter(Boolean);
      delete s.transitionRule._fromStatesCsv;
    }
  }
  return snap;
}

async function saveDraft() {
  saving.value = true;
  try {
    const snap = buildSanitizedSnapshot();
    await rpcCall('meta_model', 'saveDraft', { snapshot: snap });
    ElMessage.success('草稿已保存');
    state.visible = false;
    await appStore.loadStudioOverview();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}

async function saveDraftCheckAndPublish() {
  saving.value = true;
  try {
    const snap = buildSanitizedSnapshot();
    const ver = await rpcCall('meta_model', 'saveDraft', { snapshot: snap });
    const report = await rpcCall('meta_model', 'checkImpact', { modelName: snap.name, versionId: ver.id });
    if (!report.passed) {
      state.visible = false;
      await openVersionCenter(snap.name, report);
      ElMessage.warning('存在冲突风险，请核查详情');
      return;
    }
    await rpcCall('meta_model', 'publish', { modelName: snap.name, versionId: ver.id, validationHash: report.validationHash });
    ElMessage.success('数据表 [' + snap.displayName + '] 已上线启用！');
    state.visible = false;
    await appStore.refreshMenusAndOverview();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}

async function loadVersionsForCenter(modelName) {
  try {
    const detail = await rpcCall('meta_model', 'detail', { modelName });
    versionList.value = detail.versions || [];
  } catch (e) {
    ElMessage.error(e.message);
  }
}

watch(() => versionCenterState.visible, (vis) => {
  if (vis && versionCenterState.modelName) {
    loadVersionsForCenter(versionCenterState.modelName);
  }
});

async function openVersionCenter(modelName, initialImpact = null) {
  versionCenterState.modelName = modelName;
  versionCenterState.impact = initialImpact;
  await loadVersionsForCenter(modelName);
  versionCenterState.visible = true;
}

async function runCheckImpact(versionId) {
  try {
    const report = await rpcCall('meta_model', 'checkImpact', { modelName: versionCenterState.modelName, versionId });
    await openVersionCenter(versionCenterState.modelName, report);
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function runPublishVersion(ver) {
  try {
    await rpcCall('meta_model', 'publish', { modelName: versionCenterState.modelName, versionId: ver.id, validationHash: ver.validationHash });
    ElMessage.success('版本已启用');
    await openVersionCenter(versionCenterState.modelName, versionCenterState.impact);
    await appStore.refreshMenusAndOverview();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function runRollbackVersion(targetVersionId) {
  try {
    await ElMessageBox.confirm('切回旧版本不会丢失现有业务数据，确认切回？', '版本回滚确认', { type: 'warning' });
    const res = await rpcCall('meta_model', 'rollback', { modelName: versionCenterState.modelName, targetVersionId });
    ElMessage.success('已切回版本 ' + res.rolledBackToVersion);
    await openVersionCenter(versionCenterState.modelName);
    await appStore.refreshMenusAndOverview();
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message);
  }
}

defineExpose({ openVersionCenter });
</script>
