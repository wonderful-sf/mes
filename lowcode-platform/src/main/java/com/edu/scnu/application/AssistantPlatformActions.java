package com.edu.scnu.application;

import com.edu.scnu.application.AssistantPlanner.TenantChoice;
import com.edu.scnu.domain.iam.TenantDef;
import com.edu.scnu.domain.metamodel.model.ImpactReport;
import com.edu.scnu.domain.metamodel.model.ModelVersion;
import com.edu.scnu.domain.metamodel.repository.AppRepository;
import com.edu.scnu.domain.metamodel.repository.ModelRepository;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.MetaContext;
import com.edu.scnu.infrastructure.container.MetaContainerMemoryRegistry;
import com.edu.scnu.interfaces.rpc.chain.RpcDispatcher;
import com.edu.scnu.interfaces.rpc.dto.JsonRpcRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AssistantPlatformActions {
    private final RpcDispatcher rpc;
    private final AppRepository apps;
    private final ModelRepository models;
    private final IamAppService iam;
    private final MetaContainerMemoryRegistry registry;

    public AssistantPlatformActions(RpcDispatcher rpc, AppRepository apps, ModelRepository models,
                                    IamAppService iam, MetaContainerMemoryRegistry registry) {
        this.rpc = rpc;
        this.apps = apps;
        this.models = models;
        this.iam = iam;
        this.registry = registry;
    }

    public List<TenantChoice> listTenants(String authorization) {
        return tenants(authorization).stream()
                .filter(t -> "ACTIVE".equalsIgnoreCase(t.getStatus()))
                .map(t -> new TenantChoice(t.getId(), t.getName(), t.getCode())).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public SavedDraft saveDraft(String authorization, String appKey, AssistantAppDesign design, SavedDraft previous) {
        Map<String, Object> appPackage = design.toPackage(appKey);
        List<String> modelNames = design.tables().stream().map(table -> AssistantAppDesign.modelName(appKey, table)).toList();
        if (previous == null) {
            if (apps.findByName(appKey).isPresent()) throw staleDraft();
        } else {
            verifyDraft(previous);
            if (!modelNames.containsAll(previous.versions().stream().map(SavedVersion::modelName).toList())) {
                throw DomainException.invalidParams("可以修改字段或增加表。需要移除整张草稿表时，请在管理中心处理。");
            }
            Map<String, Object> app = new LinkedHashMap<>();
            app.put("name", appKey);
            app.put("displayName", design.name());
            app.put("description", design.description());
            app.put("state", "DRAFT");
            call(authorization, "meta_app", "save", Map.of("app", app));
        }
        for (String model : modelNames) {
            if (models.findModelByName(model).filter(existing -> previous == null
                    || previous.versions().stream().noneMatch(v -> model.equals(v.modelName()))).isPresent()) throw staleDraft();
        }
        call(authorization, "meta_app", "importPackage", Map.of("package", appPackage, "autoPublish", false));
        List<SavedVersion> versions = modelNames.stream().map(name -> {
            ModelVersion version = models.findVersion(name, "1.0.0").orElseThrow(this::staleDraft);
            return new SavedVersion(name, version.getId(), version.getSnapshotJson(), version.getPendingUiJson());
        }).toList();
        return new SavedDraft(appKey, design, versions);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<PageLink> publish(String authorization, SavedDraft draft, String tenantId) {
        verifyDraft(draft);
        TenantDef tenant = tenants(authorization).stream()
                .filter(t -> tenantId.equals(t.getId()) && "ACTIVE".equalsIgnoreCase(t.getStatus()))
                .findFirst().orElseThrow(() -> DomainException.permissionDenied("该租户当前不可授权，请重新选择。"));
        List<String> menuIds = draft.versions().stream().map(version -> version.modelName() + "_menu").toList();
        if (tenant.getParentTenantId() != null && !tenant.getParentTenantId().isBlank()) {
            Set<String> inherited = iam.resolveAllowedMenuNames(tenant.getParentTenantId());
            if (inherited != null && !inherited.containsAll(menuIds)) {
                throw DomainException.permissionDenied("该租户受上级菜单范围限制，请先在管理中心调整上级授权。");
            }
        }
        reloadRegistryOnRollback();
        for (SavedVersion version : draft.versions()) {
            publishVersion(authorization, version.modelName(), version.id());
        }
        Set<String> allowedApps = new LinkedHashSet<>(tenant.getAuthorizedApps());
        if (!allowedApps.contains("*")) allowedApps.add(draft.appKey());
        Set<String> allowedMenus = new LinkedHashSet<>(tenant.getAuthorizedMenus());
        if (!allowedMenus.isEmpty() && !allowedMenus.contains("*")) {
            allowedMenus.remove("__NONE__");
            allowedMenus.addAll(menuIds);
        }
        call(authorization, "rbac_tenant", "authorizeApps", Map.of(
                "tenantId", tenant.getId(), "authorizedApps", List.copyOf(allowedApps),
                "authorizedMenus", List.copyOf(allowedMenus)));
        return draft.design().tables().stream()
                .map(table -> new PageLink(table.name(), "/menu/" + AssistantAppDesign.modelName(draft.appKey(), table) + "_menu"))
                .toList();
    }

    private void verifyDraft(SavedDraft draft) {
        if (apps.findByName(draft.appKey()).filter(app -> "DRAFT".equals(app.getState())).isEmpty()) throw staleDraft();
        if (models.findModelsByApp(draft.appKey()).size() != draft.versions().size()) throw staleDraft();
        for (SavedVersion entry : draft.versions()) {
            boolean active = models.findModelByName(entry.modelName())
                    .filter(model -> draft.appKey().equals(model.getAppName()) && model.getActiveVersionId() == null).isPresent();
            ModelVersion version = models.findVersionById(entry.id()).orElseThrow(this::staleDraft);
            if (!active || version.isImmutable() || !Objects.equals(version.getSnapshotJson(), entry.snapshotJson())
                    || !Objects.equals(version.getPendingUiJson(), entry.pendingUiJson())) throw staleDraft();
        }
    }

    private DomainException staleDraft() {
        return DomainException.concurrentConflict("这个草稿已在其他页面修改、发布或删除。请在管理中心核对，或开启新的助手会话。");
    }

    @SuppressWarnings("unchecked")
    private List<TenantDef> tenants(String authorization) {
        return (List<TenantDef>) call(authorization, "rbac_tenant", "listTenants", Map.of());
    }

    void reloadRegistryOnRollback() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) registry.reloadAll();
            }
        });
    }

    void publishVersion(String authorization, String model, String versionId) {
        Map<String, Object> target = Map.of("modelName", model, "versionId", versionId);
        ImpactReport report = (ImpactReport) call(authorization, "meta_model", "checkImpact", target);
        if (!report.isPassed()) throw DomainException.impactBlocked("数据表未通过发布检查。", report.getBlockingReasons());
        call(authorization, "meta_model", "publish", Map.of(
                "modelName", model, "versionId", versionId, "validationHash", report.getValidationHash()));
    }

    Object call(String authorization, String model, String service, Map<String, Object> args) {
        JsonRpcRequest request = new JsonRpcRequest();
        request.setMethod("service");
        request.setParams(Map.of("app", "lowcode_studio", "tag", "master", "model", model, "service", service, "args", args));
        return rpc.execute(MetaContext.DEFAULT_TENANT, authorization, false, request);
    }

    public record SavedVersion(String modelName, String id, String snapshotJson, String pendingUiJson) {}
    public record SavedDraft(String appKey, AssistantAppDesign design, List<SavedVersion> versions) {}
    public record PageLink(String label, String path) {}
}
