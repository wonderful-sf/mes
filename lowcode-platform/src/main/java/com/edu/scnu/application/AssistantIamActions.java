package com.edu.scnu.application;

import com.edu.scnu.application.AssistantPlanner.IamChange;
import com.edu.scnu.domain.iam.RoleDef;
import com.edu.scnu.domain.iam.TenantDef;
import com.edu.scnu.domain.iam.UserAccount;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.MetaContext;
import com.edu.scnu.domain.shared.MetadataInputRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Service
public class AssistantIamActions {
    private final AssistantPlatformActions platform;
    private final IamAppService iam;

    public AssistantIamActions(AssistantPlatformActions platform, IamAppService iam) {
        this.platform = platform;
        this.iam = iam;
    }

    public Plan prepare(String authorization, IamChange change) {
        UserAccount actor = iam.authenticate(authorization).orElseThrow(() -> DomainException.permissionDenied("请先登录。"));
        var profile = iam.buildProfileData(actor);
        if (!Boolean.TRUE.equals(profile.get("canManageTenants"))) {
            throw DomainException.permissionDenied("当前账号没有租户与角色管理权限。");
        }
        List<TenantDef> tenants = entities(authorization, "rbac_tenant", "listTenants");
        String actorTenant = Objects.requireNonNullElse(actor.getTenantId(), MetaContext.DEFAULT_TENANT);
        String action = Objects.toString(change.action(), "");
        return switch (action) {
            case "createTenant" -> tenantPlan(change, tenants, actorTenant, Boolean.TRUE.equals(profile.get("isSystemAdmin")));
            case "configureRole" -> rolePlan(authorization, change, tenants, actorTenant);
            default -> throw DomainException.invalidParams("请指定创建租户或配置自定义岗位角色。");
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public void apply(String authorization, IamChange request, Plan expected) {
        Plan current = prepare(authorization, request);
        if (!current.equals(expected)) {
            throw DomainException.concurrentConflict("租户或权限范围已变化，请重新生成并核对方案。");
        }
        if (current.role() == null) {
            platform.call(authorization, "rbac_tenant", "saveTenant", Map.of("values", Map.of(
                    "code", current.preview().code(), "name", current.preview().name(), "parentTenantId", current.tenantId())));
            return;
        }
        RoleValues role = current.role();
        Map<String, Object> values = new LinkedHashMap<>();
        if (role.id() != null) values.put("id", role.id());
        values.put("code", role.code());
        values.put("name", current.preview().name());
        values.put("tenantId", current.tenantId());
        values.put("dataScope", role.dataScope());
        values.put("allowedMenus", role.menus());
        values.put("allowedServices", role.services());
        platform.call(authorization, "rbac_role", "saveRole", Map.of("values", values));
    }

    private Plan tenantPlan(IamChange change, List<TenantDef> tenants, String actorTenant, boolean root) {
        String name = MetadataInputRules.tenantName(change.name());
        String code = MetadataInputRules.tenantCode(change.code());
        if (iam.findTenantById(code).isPresent()) throw DomainException.concurrentConflict("租户编码已存在，请使用其他编码。");
        TenantDef parent = pick(tenants, defaulted(change.tenantQuery(), root ? MetaContext.DEFAULT_TENANT : actorTenant),
                "上级租户", TenantDef::getId, TenantDef::getCode, TenantDef::getName);
        requireActive(parent);
        Preview preview = new Preview("创建租户", name, code, parent.getName(), null, List.of(), List.of(), List.of());
        return new Plan(parent.getId(), preview, null, null);
    }

    private Plan rolePlan(String authorization, IamChange change, List<TenantDef> tenants, String actorTenant) {
        TenantDef tenant = pick(tenants, defaulted(change.tenantQuery(), actorTenant),
                "所属租户", TenantDef::getId, TenantDef::getCode, TenantDef::getName);
        requireActive(tenant);
        List<RoleDef> roles = entities(authorization, "rbac_role", "listRoles");
        RoleDef previous = change.roleQuery() == null || change.roleQuery().isBlank() ? null
                : pick(roles.stream().filter(r -> tenant.getId().equals(r.getTenantId())).toList(),
                        change.roleQuery(), "岗位角色", RoleDef::getCode, RoleDef::getName);
        String code = previous == null ? defaulted(change.code(), "") : previous.getCode();
        if (!code.matches("ROLE_[A-Z][A-Z0-9_]{0,34}")) throw DomainException.invalidParams("岗位角色编码须为 ROLE_ 加大写字母、数字或下划线，最多 40 个字符。");
        if (IamAppService.isBuiltInRole(code)) throw DomainException.permissionDenied("请选择自定义岗位角色。");
        if (previous == null && roles.stream().anyMatch(role -> code.equalsIgnoreCase(role.getCode()))) {
            throw DomainException.concurrentConflict("岗位角色编码已存在。请指定要修改的角色，或另选编码。");
        }
        String name = defaulted(change.name(), previous == null ? null : previous.getName());
        String error = MetadataInputRules.nameError(name, "岗位角色名称", 60);
        if (error != null) throw DomainException.invalidParams(error);
        String scope = defaulted(change.dataScope(), previous == null ? "SELF" : previous.getDataScope());
        if (!Set.of("SELF", "ALL").contains(scope)) throw DomainException.invalidParams("数据范围请选择仅本人或全租户。");
        RoleValues baseline = previous == null ? null : roleValues(previous);
        boolean keep = baseline != null && !change.replaceAccess();
        Set<String> menus = new LinkedHashSet<>(keep ? baseline.menus() : List.of());
        Set<String> services = new LinkedHashSet<>(keep ? baseline.services() : List.of());
        boolean inheritedMenus = keep && (menus.isEmpty() || menus.contains("*"));
        if (change.access() != null && !change.access().isEmpty()) {
            if (change.access().size() > 24) throw DomainException.invalidParams("单次最多配置 24 个菜单。");
            List<Menu> available = assignableMenus(authorization, tenant.getId());
            Set<String> customServices = catalogServices(authorization);
            services.add("ui_menu:loadMenu");
            services.add("ui_view:loadView");
            for (AssistantPlanner.MenuAccess access : change.access()) {
                if (access == null || access.actions() == null || access.actions().isEmpty() || access.actions().size() > 16) {
                    throw DomainException.invalidParams("请为每个菜单指定至少一项操作。");
                }
                Menu menu = pick(available, access.menuQuery(), "业务菜单", Menu::name, Menu::label, m -> m.appLabel() + "/" + m.label());
                if (!inheritedMenus) {
                    menus.remove("__NONE__");
                    menus.add(menu.name());
                }
                for (String operation : access.actions()) addOperation(services, menu.model(), operation, customServices);
            }
        }
        if (menus.isEmpty() && !inheritedMenus) menus.add("__NONE__");
        RoleValues role = new RoleValues(previous == null ? null : previous.getId(), code, scope,
                List.copyOf(menus), List.copyOf(services), name);
        List<String> visibleMenus = inheritedMenus ? List.of("继承租户已授权菜单")
                : role.menus().equals(List.of("__NONE__")) ? List.of("无可见菜单") : role.menus();
        List<String> removed = baseline == null ? List.of() : baseline.services().stream().filter(s -> !services.contains(s)).toList();
        Preview preview = new Preview(previous == null ? "创建岗位角色" : "更新岗位角色", name, code, tenant.getName(),
                "ALL".equals(scope) ? "全租户" : "仅本人", visibleMenus, role.services(), removed);
        return new Plan(tenant.getId(), preview, role, baseline);
    }

    private void addOperation(Set<String> services, String model, String operation, Set<String> customServices) {
        String action = defaulted(operation, "");
        switch (action) {
            case "read" -> services.addAll(List.of(model + ":search", model + ":find", model + ":count", model + ":exists", model + ":lookup"));
            case "create", "update" -> services.addAll(List.of(model + ":" + action, model + ":addMissingDefaultValues", model + ":getSelectionOptions"));
            case "delete" -> services.add(model + ":delete");
            default -> {
                String expression = model + ":" + action;
                if (!action.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || !customServices.contains(expression)) {
                    throw DomainException.invalidParams("权限目录里没有这项业务操作：" + action);
                }
                services.add(expression);
            }
        }
    }

    private RoleValues roleValues(RoleDef role) {
        return new RoleValues(role.getId(), role.getCode(), role.getDataScope(), List.copyOf(role.getAllowedMenus()),
                List.copyOf(role.getAllowedServices()), role.getName());
    }

    private List<Menu> assignableMenus(String authorization, String tenant) {
        Set<String> apps = iam.resolveAllowedAppNames(tenant);
        Set<String> permitted = iam.resolveAllowedMenuNames(tenant);
        List<Menu> result = new ArrayList<>();
        for (Map<String, Object> group : groups(authorization, "ui_menu", "listAssignableMenus")) {
            String app = (String) group.get("appName");
            if (apps != null && !apps.contains(app)) continue;
            if (!(group.get("menus") instanceof List<?> menus)) continue;
            for (Object item : menus) {
                if (!(item instanceof Map<?, ?> row) || !(row.get("name") instanceof String name)) continue;
                if (permitted == null || permitted.contains(name) || permitted.contains(row.get("id"))) {
                    result.add(new Menu(name, (String) row.get("displayName"), (String) row.get("model"), (String) group.get("appDisplayName")));
                }
            }
        }
        return result;
    }

    private Set<String> catalogServices(String authorization) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> group : groups(authorization, "rbac_role", "permissionCatalog")) {
            if (!(group.get("items") instanceof List<?> items)) continue;
            for (Object item : items) {
                if (item instanceof Map<?, ?> row && row.get("authExpr") instanceof String expression) result.add(expression);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> entities(String authorization, String model, String service) {
        return (List<T>) platform.call(authorization, model, service, Map.of());
    }

    private List<Map<String, Object>> groups(String authorization, String model, String service) {
        return entities(authorization, model, service);
    }

    private void requireActive(TenantDef tenant) {
        if (!"ACTIVE".equalsIgnoreCase(tenant.getStatus())) throw DomainException.invalidParams("请选择已启用的租户。");
    }

    @SafeVarargs
    private <T> T pick(List<T> values, String query, String label, Function<T, String>... names) {
        List<T> matches = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            for (T value : values) {
                for (Function<T, String> name : names) {
                    if (query.strip().equalsIgnoreCase(name.apply(value))) {
                        matches.add(value);
                        break;
                    }
                }
            }
        }
        if (matches.size() == 1) return matches.get(0);
        throw DomainException.invalidParams(matches.isEmpty() ? "找不到可操作的" + label + "，请核对名称或标识。"
                : "找到多个同名" + label + "，请提供唯一标识。");
    }

    private String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private record Menu(String name, String label, String model, String appLabel) {}
    public record Preview(String action, String name, String code, String tenantName, String dataScope,
                          List<String> menuScope, List<String> services, List<String> removedServices) {}
    public record RoleValues(String id, String code, String dataScope, List<String> menus, List<String> services, String name) {}
    public record Plan(String tenantId, Preview preview, RoleValues role, RoleValues prior) {}
}
