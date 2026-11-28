package com.edu.scnu.application;

import com.edu.scnu.domain.iam.RoleDef;
import com.edu.scnu.domain.iam.TenantDef;
import com.edu.scnu.domain.metamodel.model.*;
import com.edu.scnu.domain.metamodel.repository.FoundationRepository;
import com.edu.scnu.domain.metamodel.repository.ModelRepository;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.MetaContext;
import com.edu.scnu.domain.uischema.*;
import com.edu.scnu.infrastructure.container.MetaContainerMemoryRegistry;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * UI 契约编排应用服务（严格对标 sie-iidp-iam 的 UiMenu.loadMenu + UiView.loadView）。
 * 集成多租户应用授权过滤、RBAC 细粒度视图按钮权限计算、数据字典与动态下拉选项下发。
 */
@Service
public class UiSchemaAppService {
    private final UiSchemaRepository uiRepo;
    private final ModelRepository modelRepo;
    private final MetaContainerMemoryRegistry memoryRegistry;
    private final IamAppService iamAppService;
    private final FoundationRepository foundationRepo;

    public UiSchemaAppService(UiSchemaRepository uiRepo,
                              ModelRepository modelRepo,
                              MetaContainerMemoryRegistry memoryRegistry,
                              IamAppService iamAppService,
                              FoundationRepository foundationRepo) {
        this.uiRepo = uiRepo;
        this.modelRepo = modelRepo;
        this.memoryRegistry = memoryRegistry;
        this.iamAppService = iamAppService;
        this.foundationRepo = foundationRepo;
    }

    /**
     * 动态加载前端导航菜单树与动态路由信息。
     * 自动过滤：已下线/已清除模型、抽象模型、扩展模型及当前租户未授权应用菜单。
     */
    public List<Map<String, Object>> loadMenu() {
        List<MenuNode> allMenus = uiRepo.findAllMenus();
        Map<String, ModelAggregate> modelMap = new HashMap<>();
        for (ModelAggregate m : modelRepo.findAllModels()) {
            modelMap.put(m.getName(), m);
        }
        Set<String> allowedApps = resolveTenantAllowedApps();
        Set<String> allowedMenus = resolveEffectiveAllowedMenus();

        List<MenuNode> activeMenus = new ArrayList<>();
        for (MenuNode menu : allMenus) {
            if (!menu.isActive()) continue;
            if (allowedApps != null && menu.getAppName() != null
                    && !"platform_kernel".equals(menu.getAppName())
                    && !allowedApps.contains(menu.getAppName())) {
                continue;
            }
            if (allowedMenus != null && !"platform_kernel".equals(menu.getAppName())
                    && menu.getModel() != null && !menu.getModel().isBlank()
                    && !matchesAllowedMenu(allowedMenus, menu)) {
                continue;
            }
            if (menu.getModel() != null && !menu.getModel().isBlank()) {
                ModelAggregate agg = modelMap.get(menu.getModel());
                if (agg == null || !agg.isAvailableForRuntime()) {
                    continue;
                }
                ModelSnapshot snap = memoryRegistry.getActiveSnapshotOrNull(menu.getModel());
                if (snap != null && (snap.isAbstractModel() || snap.isExtensionPackage())) {
                    continue;
                }
            }
            activeMenus.add(menu);
        }

        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        for (MenuNode m : activeMenus) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("name", m.getName());
            item.put("displayName", m.getDisplayName());
            item.put("appName", m.getAppName());
            item.put("parentId", m.getParentId());
            item.put("sequence", m.getSequence());
            item.put("icon", m.getIcon());
            item.put("model", m.getModel());
            item.put("view", m.getView());
            item.put("filter", m.getFilter());
            item.put("routePath", (m.getModel() != null && !m.getModel().isBlank())
                    ? "/menu/" + URLEncoder.encode(m.getName(), StandardCharsets.UTF_8).replace("+", "%20")
                    : null);
            item.put("children", new ArrayList<Map<String, Object>>());
            nodeMap.put(m.getName(), item);
            if (m.getId() != null) {
                nodeMap.putIfAbsent(m.getId(), item);
            }
        }

        List<Map<String, Object>> roots = new ArrayList<>();
        Set<String> seenRootNames = new HashSet<>();
        for (MenuNode m : activeMenus) {
            Map<String, Object> cur = nodeMap.get(m.getName());
            String pid = m.getParentId();
            if (pid != null && !pid.isBlank() && nodeMap.containsKey(pid) && nodeMap.get(pid) != cur) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) nodeMap.get(pid).get("children");
                children.add(cur);
            } else if (seenRootNames.add(m.getName())) {
                roots.add(cur);
            }
        }
        if (allowedApps != null || allowedMenus != null) {
            pruneEmptyFolderMenus(roots);
        }
        return roots;
    }

    /**
     * 按当前操作者自身被授权的范围，返回可继续向下级分站或角色分发的应用与激活叶子菜单分组。
     */
    public List<Map<String, Object>> listAssignableMenus() {
        Set<String> allowedApps = isRootAdminCaller() ? null : resolveTenantAllowedApps();
        Set<String> allowedMenus = isRootAdminCaller() ? null : resolveEffectiveAllowedMenus();

        Map<String, String> appDisplayNames = new LinkedHashMap<>();
        for (Map<String, Object> ap : iamAppService.listPublicApps()) {
            String appName = String.valueOf(ap.get("name"));
            if (allowedApps == null || allowedApps.contains(appName)) {
                String disp = ap.get("displayName") != null ? String.valueOf(ap.get("displayName")) : appName;
                appDisplayNames.put(appName, disp);
            }
        }

        Map<String, ModelAggregate> modelMap = new HashMap<>();
        for (ModelAggregate m : modelRepo.findAllModels()) {
            modelMap.put(m.getName(), m);
        }

        Map<String, List<Map<String, Object>>> menusByApp = new LinkedHashMap<>();
        for (String appName : appDisplayNames.keySet()) {
            menusByApp.put(appName, new ArrayList<>());
        }

        List<MenuNode> allMenus = new ArrayList<>(uiRepo.findAllMenus());
        allMenus.sort(Comparator.comparingInt(MenuNode::getSequence));
        for (MenuNode menu : allMenus) {
            if (!menu.isActive()) continue;
            if (menu.getModel() == null || menu.getModel().isBlank()) continue;
            String appName = menu.getAppName();
            if (appName == null || "platform_kernel".equals(appName)) continue;
            if (allowedApps != null && !allowedApps.contains(appName)) continue;
            if (allowedMenus != null && !matchesAllowedMenu(allowedMenus, menu)) continue;
            ModelAggregate agg = modelMap.get(menu.getModel());
            if (agg == null || !agg.isAvailableForRuntime()) continue;
            ModelSnapshot snap = memoryRegistry.getActiveSnapshotOrNull(menu.getModel());
            if (snap != null && (snap.isAbstractModel() || snap.isExtensionPackage())) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", menu.getId());
            item.put("name", menu.getName());
            item.put("displayName", menu.getDisplayName());
            item.put("model", menu.getModel());
            item.put("parentId", menu.getParentId());
            item.put("icon", menu.getIcon());
            item.put("sequence", menu.getSequence());
            menusByApp.computeIfAbsent(appName, k -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : menusByApp.entrySet()) {
            Map<String, Object> grp = new LinkedHashMap<>();
            grp.put("appName", e.getKey());
            grp.put("appDisplayName", appDisplayNames.getOrDefault(e.getKey(), e.getKey()));
            grp.put("menus", e.getValue());
            result.add(grp);
        }
        return result;
    }

    /**
     * 加载模型三件套视图契约 + 有效字段元数据 + 字典/动态枚举项 + 服务清单 + RBAC 细粒度权限上下文。
     */
    public Map<String, Object> loadView(String modelName, String viewKeysCsv, String menuName) {
        Set<String> allowedApps = resolveTenantAllowedApps();
        Set<String> allowedMenus = resolveEffectiveAllowedMenus();
        if (menuName != null && !menuName.isBlank()) {
            MenuNode menu = requireRuntimeMenu(menuName, modelName, allowedApps, allowedMenus);
            modelName = menu.getModel();
            viewKeysCsv = menu.getView();
        }
        if (modelName == null || modelName.isBlank()) {
            throw DomainException.invalidParams("loadView 必须传入 model 参数");
        }
        ModelAggregate agg = memoryRegistry.getModelAggregate(modelName);
        if (agg == null) {
            throw DomainException.methodNotFound("模型不存在: " + modelName);
        }
        if (!agg.isAvailableForRuntime()) {
            throw DomainException.stateGuardFailed("模型 [" + agg.getDisplayName() + "] 已下线或未激活，无法访问界面");
        }

        ModelSnapshot snap = memoryRegistry.getActiveSnapshotOrNull(modelName);
        if (snap == null) {
            throw DomainException.stateGuardFailed("模型 [" + modelName + "] 无生效版本快照");
        }
        if (snap.isAbstractModel() || snap.isExtensionPackage()) {
            throw DomainException.stateGuardFailed("抽象父模型或增量扩展包 [" + modelName + "] 无独立运行视图");
        }

        if (allowedApps != null && snap.getAppName() != null
                && !"platform_kernel".equals(snap.getAppName())
                && !allowedApps.contains(snap.getAppName())) {
            throw DomainException.permissionDenied("访问拒绝：当前租户未授权使用应用 [" + snap.getAppName() + "]");
        }
        Set<String> permittedViewKeys = new LinkedHashSet<>();
        boolean restrictViewKeys = false;
        if ((menuName == null || menuName.isBlank()) && allowedMenus != null
                && snap.getAppName() != null && !"platform_kernel".equals(snap.getAppName())) {
            List<MenuNode> boundMenus = new ArrayList<>();
            for (MenuNode m : uiRepo.findAllMenus()) {
                if (m.isActive() && modelName.equals(m.getModel())) {
                    boundMenus.add(m);
                }
            }
            if (!boundMenus.isEmpty()) {
                boolean permitted = false;
                restrictViewKeys = true;
                for (MenuNode m : boundMenus) {
                    if (matchesAllowedMenu(allowedMenus, m)) {
                        permitted = true;
                        Set<String> menuKeys = viewKeys(m.getView());
                        if (menuKeys.isEmpty()) {
                            restrictViewKeys = false;
                            break;
                        }
                        permittedViewKeys.addAll(menuKeys);
                    }
                }
                if (!permitted) {
                    throw DomainException.permissionDenied("访问拒绝：当前账号未获授权打开页面 [" + agg.getDisplayName() + "]");
                }
            }
        }

        Map<String, Object> viewsMap = new LinkedHashMap<>();
        List<UiViewDef> modelViews = uiRepo.findViewsByModel(modelName);
        Set<String> requestedKeys = viewKeys(viewKeysCsv);
        if (restrictViewKeys && !permittedViewKeys.containsAll(requestedKeys)) {
            throw DomainException.permissionDenied("当前账号未获授权打开所请求的视图");
        }

        for (UiViewDef v : modelViews) {
            if (!v.isActive()) continue;
            if (restrictViewKeys && !permittedViewKeys.contains(v.getKey())) continue;
            if (requestedKeys.isEmpty() || requestedKeys.contains(v.getKey())) {
                viewsMap.put(v.getType(), v);
                viewsMap.put(v.getKey(), v);
            }
        }

        Map<String, PropertyDef> fieldsMap = new LinkedHashMap<>();
        Map<String, List<DictItemDef>> dictOptions = new LinkedHashMap<>();
        Map<String, Object> dynamicOptions = new LinkedHashMap<>();

        for (PropertyDef p : snap.getProperties()) {
            fieldsMap.put(p.getName(), p);
            if (p.isDict() && p.getDictType() != null && !p.getDictType().isBlank()) {
                List<DictItemDef> activeItems = new ArrayList<>();
                for (DictItemDef di : foundationRepo.findDictItemsByType(p.getDictType())) {
                    if ("ACTIVE".equalsIgnoreCase(di.getStatus())) activeItems.add(di);
                }
                dictOptions.put(p.getName(), activeItems);
                dictOptions.put(p.getDictType(), activeItems);
            }
            if (p.getSelectionMethod() != null && !p.getSelectionMethod().isBlank() && MetaContext.current() != null) {
                try {
                    Object opts = MetaContext.current().get(modelName).call("getSelectionOptions", Map.of("field", p.getName()));
                    dynamicOptions.put(p.getName(), opts);
                } catch (Exception ignored) {}
            }
        }

        Map<String, Object> modelMeta = new LinkedHashMap<>();
        modelMeta.put("name", snap.getName());
        modelMeta.put("displayName", snap.getDisplayName());
        modelMeta.put("tableName", snap.resolvedTableName());
        modelMeta.put("appName", snap.getAppName());
        modelMeta.put("versionNo", snap.getVersionNo());
        modelMeta.put("activeVersionId", agg.getActiveVersionId());
        modelMeta.put("lifecycleState", agg.getLifecycleState().name());
        modelMeta.put("autoLog", snap.isAutoLog());
        modelMeta.put("logicDelete", snap.isLogicDelete());
        modelMeta.put("parents", snap.getParents());

        Map<String, Object> auth = buildGranularAuth(modelName, snap);
        auth.put("targetServices", buildTargetServiceAuth(modelName, viewsMap.values(), allowedApps));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelMeta", modelMeta);
        result.put("views", viewsMap);
        result.put("fields", fieldsMap);
        result.put("dictOptions", dictOptions);
        result.put("dynamicOptions", dynamicOptions);
        result.put("services", snap.getServices());
        result.put("auth", auth);
        return result;
    }

    private Set<String> viewKeys(String csv) {
        Set<String> keys = new LinkedHashSet<>();
        if (csv != null) {
            for (String key : csv.split(",")) {
                if (!key.isBlank()) keys.add(key.trim());
            }
        }
        return keys;
    }

    public String loadMenuFilter(String menuName, String modelName) {
        return requireRuntimeMenu(menuName, modelName, resolveTenantAllowedApps(), resolveEffectiveAllowedMenus()).getFilter();
    }

    public Map<String, Object> deleteView(String viewKey) {
        if (viewKey == null || viewKey.isBlank()) {
            throw DomainException.invalidParams("视图标识 (key) 不能为空");
        }
        String key = viewKey.trim();
        UiViewDef existing = uiRepo.findViewByKey(key)
                .orElseThrow(() -> DomainException.invalidParams("页面布局不存在: " + key));
        uiRepo.deleteViewByKey(existing.getKey());
        return Map.of("deleted", true, "key", existing.getKey(), "name", existing.getName());
    }

    public Map<String, Object> deleteMenu(String menuName) {
        if (menuName == null || menuName.isBlank()) {
            throw DomainException.invalidParams("菜单标识 (name) 不能为空");
        }
        String target = menuName.trim();
        MenuNode existing = uiRepo.findMenuByName(target)
                .orElseThrow(() -> DomainException.invalidParams("菜单不存在: " + target));
        for (MenuNode other : uiRepo.findAllMenus()) {
            if (!Objects.equals(other.getName(), existing.getName())
                    && (Objects.equals(existing.getName(), other.getParentId())
                        || Objects.equals(existing.getId(), other.getParentId()))) {
                throw DomainException.stateGuardFailed(
                        "菜单 [" + existing.getDisplayName() + "] 下仍存在子菜单 [" + other.getDisplayName() + "]，请先删除子菜单");
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        if (existing.getName() != null) keys.add(existing.getName());
        if (existing.getId() != null) keys.add(existing.getId());
        uiRepo.deleteMenuByName(existing.getName());
        iamAppService.cleanupDeletedMenuReferences(keys);
        return Map.of("deleted", true, "name", existing.getName(), "displayName", existing.getDisplayName());
    }

    private MenuNode requireRuntimeMenu(String menuName, String modelName, Set<String> allowedApps, Set<String> allowedMenus) {
        MenuNode menu = uiRepo.findMenuByName(menuName)
                .filter(m -> m.isActive() && m.getModel() != null && !m.getModel().isBlank())
                .orElseThrow(() -> DomainException.permissionDenied("页面不存在或未授权"));
        if (!"platform_kernel".equals(menu.getAppName())
                && ((allowedApps != null && !allowedApps.contains(menu.getAppName()))
                    || !matchesAllowedMenu(allowedMenus, menu))) {
            throw DomainException.permissionDenied("页面不存在或未授权");
        }
        if (modelName != null && !modelName.isBlank() && !modelName.equals(menu.getModel())) {
            throw DomainException.invalidParams("菜单与模型不匹配");
        }
        return menu;
    }

    private boolean isRootAdminCaller() {
        MetaContext ctx = MetaContext.current();
        return ctx != null && MetaContext.DEFAULT_TENANT.equals(ctx.getTenantId()) && ctx.isSuperUser();
    }

    private Set<String> resolveTenantAllowedApps() {
        MetaContext ctx = MetaContext.current();
        if (ctx == null) return null;
        return iamAppService.resolveAllowedAppNames(ctx.getTenantId());
    }

    /**
     * 计算当前用户生效的菜单白名单（返回 null 表示按租户已授权应用展示全部激活菜单）：
     * 1) 租户级 authorizedMenus（递归继承上级分站限制，默认平台根租户同样受自身勾选约束）与
     * 2) 用户所分配角色中的显式 allowedMenus（多受限角色取并集）
     * 二者均存在限制时取交集，防止越过上级分站或自身角色越权查看/打开页面。
     */
    private Set<String> resolveEffectiveAllowedMenus() {
        MetaContext ctx = MetaContext.current();
        if (ctx == null) {
            return null;
        }
        Set<String> tenantMenus = iamAppService.resolveAllowedMenuNames(ctx.getTenantId());
        Set<String> roleMenus = null;
        if (!ctx.isSuperUser() && ctx.getRoleCodes() != null && !ctx.getRoleCodes().isEmpty()) {
            List<RoleDef> roles = iamAppService.resolveRoles(ctx.getRoleCodes());
            Set<String> unionExplicit = new LinkedHashSet<>();
            boolean hasExplicitRestriction = false;
            for (RoleDef r : roles) {
                List<String> rm = r.getAllowedMenus();
                if (rm != null && !rm.isEmpty() && !rm.contains("*")) {
                    hasExplicitRestriction = true;
                    for (String m : rm) {
                        if (m != null && !m.isBlank() && !"__NONE__".equals(m)) {
                            unionExplicit.add(m.trim());
                        }
                    }
                }
            }
            if (hasExplicitRestriction) {
                roleMenus = unionExplicit;
            }
        }
        if (tenantMenus == null && roleMenus == null) {
            return null;
        }
        if (tenantMenus != null && roleMenus != null) {
            Set<String> intersected = new LinkedHashSet<>(tenantMenus);
            intersected.retainAll(roleMenus);
            return intersected;
        }
        return tenantMenus != null ? tenantMenus : roleMenus;
    }

    private boolean matchesAllowedMenu(Set<String> allowedMenus, MenuNode menu) {
        if (allowedMenus == null) return true;
        if (menu.getName() != null && allowedMenus.contains(menu.getName())) return true;
        return menu.getId() != null && allowedMenus.contains(menu.getId());
    }

    @SuppressWarnings("unchecked")
    private void pruneEmptyFolderMenus(List<Map<String, Object>> nodes) {
        Iterator<Map<String, Object>> it = nodes.iterator();
        while (it.hasNext()) {
            Map<String, Object> node = it.next();
            Object rawChildren = node.get("children");
            if (rawChildren instanceof List<?> childList && !childList.isEmpty()) {
                pruneEmptyFolderMenus((List<Map<String, Object>>) rawChildren);
            }
            boolean isFolder = node.get("model") == null || String.valueOf(node.get("model")).isBlank();
            boolean hasChildren = node.get("children") instanceof List<?> cl && !cl.isEmpty();
            if (isFolder && !hasChildren) {
                it.remove();
            }
        }
    }

    private Map<String, Object> buildGranularAuth(String modelName, ModelSnapshot snap) {
        MetaContext ctx = MetaContext.current();
        Map<String, Object> auth = new LinkedHashMap<>();
        Map<String, Boolean> svcPerms = new LinkedHashMap<>();
        if (ctx == null || ctx.isSuperUser()) {
            auth.put("read", true);
            auth.put("search", true);
            auth.put("count", true);
            auth.put("create", true);
            auth.put("update", true);
            auth.put("delete", true);
            for (ServiceDef s : snap.getServices()) {
                svcPerms.put(s.getAuth(), true);
                svcPerms.put(s.getName(), true);
            }
            auth.put("services", svcPerms);
            return auth;
        }

        List<RoleDef> roles = iamAppService.resolveRoles(ctx.getRoleCodes());
        auth.put("read", hasPerm(roles, modelName, "search") || hasPerm(roles, modelName, "find") || hasPerm(roles, modelName, "read"));
        auth.put("search", hasPerm(roles, modelName, "search"));
        auth.put("count", hasPerm(roles, modelName, "count"));
        auth.put("create", hasPerm(roles, modelName, "create"));
        auth.put("update", hasPerm(roles, modelName, "update"));
        auth.put("delete", hasPerm(roles, modelName, "delete"));
        for (ServiceDef s : snap.getServices()) {
            boolean ok = hasPerm(roles, modelName, s.getAuth()) || hasPerm(roles, modelName, s.getName());
            svcPerms.put(s.getAuth(), ok);
            svcPerms.put(s.getName(), ok);
        }
        auth.put("services", svcPerms);
        return auth;
    }

    private Map<String, Map<String, Boolean>> buildTargetServiceAuth(String sourceModel, Collection<Object> views,
                                                                    Set<String> allowedApps) {
        MetaContext ctx = MetaContext.current();
        boolean superUser = ctx == null || ctx.isSuperUser();
        List<RoleDef> roles = superUser ? List.of() : iamAppService.resolveRoles(ctx.getRoleCodes());
        Map<String, Map<String, Boolean>> permissions = new LinkedHashMap<>();

        for (Object configuredView : new LinkedHashSet<>(views)) {
            if (!(configuredView instanceof UiViewDef view) || !"grid".equals(view.getType())) continue;
            for (String location : List.of("buttons", "tbar")) {
                if (!(view.getBody().get(location) instanceof List<?> buttons)) continue;
                for (Object item : buttons) {
                    if (!(item instanceof Map<?, ?> button) || !(button.get("model") instanceof String targetModel)
                            || !(button.get("service") instanceof String service) || targetModel.equals(sourceModel)) continue;
                    ModelAggregate target = memoryRegistry.getModelAggregate(targetModel);
                    ModelSnapshot targetSnapshot = memoryRegistry.getActiveSnapshotOrNull(targetModel);
                    boolean available = target != null && target.isAvailableForRuntime() && targetSnapshot != null
                            && !targetSnapshot.isAbstractModel() && !targetSnapshot.isExtensionPackage()
                            && (allowedApps == null || "platform_kernel".equals(targetSnapshot.getAppName())
                                || allowedApps.contains(targetSnapshot.getAppName()));
                    ServiceDef definition = available ? targetSnapshot.getService(service) : null;
                    boolean allowed = available && (superUser || hasPerm(roles, targetModel, service)
                            || (definition != null && hasPerm(roles, targetModel, definition.getAuth())));
                    permissions.computeIfAbsent(targetModel, key -> new LinkedHashMap<>()).put(service, allowed);
                }
            }
        }
        return permissions;
    }

    private boolean hasPerm(List<RoleDef> roles, String model, String service) {
        if (service == null || service.isBlank()) return true;
        for (RoleDef r : roles) {
            for (String p : r.getAllowedServices()) {
                if ("*".equals(p) || "*:*".equals(p)) return true;
                if ((model + ":" + service).equals(p) || (model + ":*").equals(p) || ("*:" + service).equals(p)) return true;
                if (p.endsWith("*:*") && model.startsWith(p.substring(0, p.length() - 3))) return true;
            }
        }
        return false;
    }
}
