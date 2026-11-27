package com.edu.scnu.application;

import com.edu.scnu.domain.iam.*;
import com.edu.scnu.domain.metamodel.model.AppAggregate;
import com.edu.scnu.domain.metamodel.repository.AppRepository;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.MetaContext;
import com.edu.scnu.domain.shared.MetadataInputRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class IamAppService {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> BUILTIN_ROLE_CODES = Set.of(
            "ROLE_ADMIN", "ROLE_DEVELOPER", "ROLE_PUBLISHER", "ROLE_APP_USER", "ROLE_TENANT_ADMIN");
    private final IamRepository iamRepository;
    private final AppRepository appRepository;

    public IamAppService(IamRepository iamRepository, AppRepository appRepository) {
        this.iamRepository = iamRepository;
        this.appRepository = appRepository;
    }

    public Map<String, Object> login(String username, String password) {
        return login(username, password, null);
    }

    public Map<String, Object> login(String username, String password, String targetApp) {
        if (username == null || username.isBlank() || password == null) {
            throw DomainException.invalidParams("账号和密码不能为空");
        }
        UserAccount user = iamRepository.findUserByUsername(username.trim())
                .orElseThrow(() -> DomainException.permissionDenied("账号或密码错误"));
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw DomainException.permissionDenied("账号已停用，请联系系统管理员");
        }
        if (!Objects.equals(password, user.getPassword())) {
            throw DomainException.permissionDenied("账号或密码错误");
        }

        String tenantId = (user.getTenantId() == null || user.getTenantId().isBlank())
                ? MetaContext.DEFAULT_TENANT : user.getTenantId();
        TenantDef tenant = findTenantById(tenantId).orElse(null);
        if (tenant != null && !"ACTIVE".equalsIgnoreCase(tenant.getStatus())) {
            throw DomainException.permissionDenied("所属租户 [" + tenant.getName() + "] 已停用，禁止登录");
        }

        boolean isRootAdmin = MetaContext.DEFAULT_TENANT.equals(tenantId) && user.getRoleCodes().contains("ROLE_ADMIN");
        boolean canAccessStudio = MetaContext.DEFAULT_TENANT.equals(tenantId)
                && (user.getRoleCodes().contains("ROLE_ADMIN")
                || user.getRoleCodes().contains("ROLE_DEVELOPER")
                || user.getRoleCodes().contains("ROLE_PUBLISHER"));
        boolean canManageTenants = isRootAdmin || user.getRoleCodes().contains("ROLE_TENANT_ADMIN");

        Set<String> allowedAppNames = resolveAllowedAppNames(tenantId);
        List<Map<String, Object>> authorizedAppsDetail = buildAuthorizedAppList(allowedAppNames);

        if (targetApp != null && !targetApp.isBlank()) {
            String trimmedTarget = targetApp.trim();
            if ("__studio__".equals(trimmedTarget) || "platform_kernel".equals(trimmedTarget)) {
                if (!canAccessStudio) {
                    throw DomainException.permissionDenied("登录拒绝：当前租户账号无权进入管理后台，请选择已授权的业务应用");
                }
            } else {
                if (appRepository.findByName(trimmedTarget).isEmpty()) {
                    throw DomainException.invalidParams("指定登录的应用不存在: " + trimmedTarget);
                }
                if (allowedAppNames != null && !allowedAppNames.contains(trimmedTarget)) {
                    String tName = tenant != null ? tenant.getName() : tenantId;
                    throw DomainException.permissionDenied(
                            "登录拒绝：租户 [" + tName + "] 未被授权登录应用 [" + trimmedTarget + "]，请联系系统管理员授权！");
                }
            }
        } else if (!canAccessStudio && allowedAppNames != null && allowedAppNames.isEmpty()) {
            String tName = tenant != null ? tenant.getName() : tenantId;
            throw DomainException.permissionDenied(
                    "登录拒绝：租户 [" + tName + "] 尚未获得任何应用授权，请联系系统管理员开通应用授权！");
        }

        String token = "lcp-" + UUID.randomUUID().toString().replace("-", "");
        String expiresAt = TS_FMT.format(LocalDateTime.now().plusHours(24));
        iamRepository.saveSession(token, user.getId(), expiresAt);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("expiresAt", expiresAt);
        result.put("user", buildProfileData(user));
        result.put("tenant", tenant);
        result.put("authorizedApps", authorizedAppsDetail);
        result.put("canAccessStudio", canAccessStudio);
        result.put("canManageTenants", canManageTenants);
        result.put("isSystemAdmin", isRootAdmin);
        if (targetApp != null && !targetApp.isBlank()) {
            result.put("targetApp", targetApp.trim());
        }
        return result;
    }

    public Map<String, Object> buildProfileData(UserAccount user) {
        if (user == null) return Collections.emptyMap();
        String tenantId = (user.getTenantId() == null || user.getTenantId().isBlank())
                ? MetaContext.DEFAULT_TENANT : user.getTenantId();
        TenantDef tenant = findTenantById(tenantId).orElse(null);
        boolean isRootAdmin = MetaContext.DEFAULT_TENANT.equals(tenantId) && user.getRoleCodes().contains("ROLE_ADMIN");
        boolean canAccessStudio = MetaContext.DEFAULT_TENANT.equals(tenantId)
                && (user.getRoleCodes().contains("ROLE_ADMIN")
                || user.getRoleCodes().contains("ROLE_DEVELOPER")
                || user.getRoleCodes().contains("ROLE_PUBLISHER"));
        boolean canManageTenants = isRootAdmin || user.getRoleCodes().contains("ROLE_TENANT_ADMIN");
        Set<String> allowed = resolveAllowedAppNames(tenantId);

        Map<String, Object> profile = sanitizeUser(user);
        profile.put("tenant", tenant);
        profile.put("tenantName", tenant != null ? tenant.getName() : tenantId);
        profile.put("authorizedApps", buildAuthorizedAppList(allowed));
        profile.put("canAccessStudio", canAccessStudio);
        profile.put("canManageTenants", canManageTenants);
        profile.put("isSystemAdmin", isRootAdmin);
        return profile;
    }

    public List<Map<String, Object>> listPublicApps() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AppAggregate app : appRepository.findAll()) {
            if ("platform_kernel".equals(app.getName()) || "DRAFT".equals(app.getState())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", app.getName());
            m.put("displayName", app.getDisplayName());
            m.put("category", app.getCategory());
            m.put("version", app.getVersion());
            m.put("description", app.getDescription());
            list.add(m);
        }
        return list;
    }

    /**
     * 解析租户被授权访问的应用名称集合：
     * - 返回 null 表示不受限制（根租户缺失或授权清单包含 "*"）；
     * - 其他情况严格返回显式授权清单（空列表代表 0 个应用可用，包括默认平台根租户未勾选任何应用时）。
     */
    public Set<String> resolveAllowedAppNames(String tenantId) {
        String tid = (tenantId == null || tenantId.isBlank()) ? MetaContext.DEFAULT_TENANT : tenantId;
        Optional<TenantDef> tOpt = findTenantById(tid);
        if (tOpt.isEmpty()) {
            return MetaContext.DEFAULT_TENANT.equals(tid) ? null : Collections.emptySet();
        }
        List<String> apps = tOpt.get().getAuthorizedApps();
        if (apps != null && apps.contains("*")) return null;
        return apps != null ? new LinkedHashSet<>(apps) : Collections.emptySet();
    }

    public boolean isAppAuthorizedForTenant(String tenantId, String appName) {
        if (appName == null || appName.isBlank() || "platform_kernel".equals(appName)) {
            return true;
        }
        Set<String> allowed = resolveAllowedAppNames(tenantId);
        return allowed == null || allowed.contains(appName);
    }

    /**
     * 解析租户级被授权访问的菜单标识集合（沿上级分站向上继承收紧）：
     * - 返回 null 表示未显式限制菜单（按已授权应用显示全部激活菜单）；
     * - 若自身或任一上级租户存在非空且不含 "*" 的 authorizedMenus 列表，则逐级取交集返回。
     */
    public Set<String> resolveAllowedMenuNames(String tenantId) {
        String tid = (tenantId == null || tenantId.isBlank()) ? MetaContext.DEFAULT_TENANT : tenantId;
        Set<String> effective = null;
        String cursor = tid;
        Set<String> visited = new HashSet<>();
        while (cursor != null && !cursor.isBlank() && visited.add(cursor)) {
            Optional<TenantDef> tOpt = findTenantById(cursor);
            if (tOpt.isEmpty()) {
                return MetaContext.DEFAULT_TENANT.equals(cursor) ? effective : Collections.emptySet();
            }
            TenantDef t = tOpt.get();
            List<String> menus = t.getAuthorizedMenus();
            if (menus != null && !menus.isEmpty() && !menus.contains("*")) {
                Set<String> levelAllowed = new LinkedHashSet<>();
                for (String m : menus) {
                    if (m != null && !m.isBlank() && !"__NONE__".equals(m)) {
                        levelAllowed.add(m.trim());
                    }
                }
                if (effective == null) {
                    effective = levelAllowed;
                } else {
                    effective.retainAll(levelAllowed);
                }
            }
            if (MetaContext.DEFAULT_TENANT.equals(cursor)) break;
            cursor = t.getParentTenantId();
        }
        return effective;
    }

    private List<Map<String, Object>> buildAuthorizedAppList(Set<String> allowedAppNames) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AppAggregate app : appRepository.findAll()) {
            if ("platform_kernel".equals(app.getName()) || "DRAFT".equals(app.getState())) continue;
            if (allowedAppNames == null || allowedAppNames.contains(app.getName())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", app.getName());
                m.put("displayName", app.getDisplayName());
                m.put("category", app.getCategory());
                m.put("version", app.getVersion());
                m.put("description", app.getDescription());
                list.add(m);
            }
        }
        return list;
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            iamRepository.deleteSession(sessionId(token));
        }
    }

    public Optional<UserAccount> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Optional<String> uidOpt = iamRepository.findUserIdByToken(sessionId(token));
        if (uidOpt.isEmpty()) return Optional.empty();
        return iamRepository.findUserById(uidOpt.get())
                .filter(u -> "ACTIVE".equalsIgnoreCase(u.getStatus()));
    }

    private String sessionId(String token) {
        return token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
    }

    public Optional<UserAccount> findUserByUsername(String username) {
        return iamRepository.findUserByUsername(username);
    }

    public List<RoleDef> resolveRoles(Set<String> roleCodes) {
        return iamRepository.findRolesByCodes(roleCodes);
    }

    // ==================== 分级租户管理边界辅助 ====================

    private UserAccount currentActor() {
        MetaContext ctx = MetaContext.current();
        if (ctx == null || MetaContext.SUPERUSER.equals(ctx.getUserId())) {
            return null;
        }
        return iamRepository.findUserById(ctx.getUserId()).orElse(null);
    }

    private boolean isRootSystemAdmin(UserAccount actor) {
        if (actor == null) return true;
        String tid = (actor.getTenantId() == null || actor.getTenantId().isBlank())
                ? MetaContext.DEFAULT_TENANT : actor.getTenantId();
        return MetaContext.DEFAULT_TENANT.equals(tid) && actor.getRoleCodes().contains("ROLE_ADMIN");
    }

    /**
     * 解析当前操作者有权管理的租户 ID 集合：
     * - 根系统管理员返回 null（全部租户）
     * - 租户主管 (ROLE_TENANT_ADMIN) 返回自身所属租户 + 所有递归下属分站租户
     * - 普通员工返回空集合
     */
    public Set<String> resolveManagedTenantIds(UserAccount actor) {
        if (isRootSystemAdmin(actor)) return null;
        if (!actor.getRoleCodes().contains("ROLE_TENANT_ADMIN")) return Collections.emptySet();
        String rootId = (actor.getTenantId() == null || actor.getTenantId().isBlank())
                ? MetaContext.DEFAULT_TENANT : actor.getTenantId();
        List<TenantDef> all = iamRepository.findAllTenants();
        Set<String> managed = new LinkedHashSet<>();
        managed.add(rootId);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (TenantDef t : all) {
                if (Objects.equals(cur, t.getParentTenantId()) && managed.add(t.getId())) {
                    queue.add(t.getId());
                }
            }
        }
        return managed;
    }

    public List<Map<String, Object>> listUsers() {
        return listUsers(null);
    }

    public List<Map<String, Object>> listUsers(String tenantId) {
        UserAccount actor = currentActor();
        Set<String> managed = resolveManagedTenantIds(actor);
        List<Map<String, Object>> res = new ArrayList<>();
        if (managed != null && managed.isEmpty()) {
            return res;
        }
        List<UserAccount> users = (tenantId != null && !tenantId.isBlank())
                ? iamRepository.findUsersByTenant(tenantId)
                : iamRepository.findAllUsers();
        for (UserAccount u : users) {
            String uTenant = (u.getTenantId() == null || u.getTenantId().isBlank())
                    ? MetaContext.DEFAULT_TENANT : u.getTenantId();
            if (managed != null && !managed.contains(uTenant)) continue;
            res.add(sanitizeUser(u));
        }
        return res;
    }

    public Map<String, Object> saveUser(UserAccount account) {
        if (account.getUsername() == null || account.getUsername().isBlank()) {
            throw DomainException.invalidParams("用户名不能为空");
        }
        UserAccount actor = currentActor();
        boolean rootAdmin = isRootSystemAdmin(actor);
        Set<String> managed = resolveManagedTenantIds(actor);

        Optional<UserAccount> existing = Optional.empty();
        if (account.getId() != null && !account.getId().isBlank()) {
            existing = iamRepository.findUserById(account.getId());
        }
        if (existing.isEmpty()) {
            existing = iamRepository.findUserByUsername(account.getUsername().trim());
        }

        if (!rootAdmin) {
            if (managed == null || managed.isEmpty()) {
                throw DomainException.permissionDenied("权限拒绝：仅管理员或企业主管可管理账号");
            }
            if (existing.isPresent()) {
                String oldTenant = existing.get().getTenantId() != null ? existing.get().getTenantId() : MetaContext.DEFAULT_TENANT;
                if (!managed.contains(oldTenant) || "usr_admin".equals(existing.get().getId())) {
                    throw DomainException.permissionDenied("越权拒绝：无权修改根系统或其他企业的账号");
                }
            }
            if (account.getTenantId() == null || account.getTenantId().isBlank()) {
                account.setTenantId(actor.getTenantId());
            }
            if (!managed.contains(account.getTenantId())) {
                throw DomainException.permissionDenied("越权拒绝：仅可将账号分配到本租户或下属分站");
            }
            if (account.getRoleCodes().contains("ROLE_ADMIN")
                    || account.getRoleCodes().contains("ROLE_DEVELOPER")
                    || account.getRoleCodes().contains("ROLE_PUBLISHER")) {
                throw DomainException.permissionDenied("越权拒绝：租户主管无权授予平台系统级角色");
            }
        }

        if (account.getId() == null || account.getId().isBlank()) {
            account.setId(existing.map(UserAccount::getId)
                    .orElse("usr_" + UUID.randomUUID().toString().substring(0, 8)));
        }
        if (account.getPassword() == null || account.getPassword().isBlank()) {
            account.setPassword(existing.map(UserAccount::getPassword).orElse("123456"));
        }
        if (account.getDisplayName() == null || account.getDisplayName().isBlank()) {
            account.setDisplayName(account.getUsername());
        }
        if (account.getTenantId() == null || account.getTenantId().isBlank()) {
            account.setTenantId("rbac_tenant_root");
        }
        if (account.getOrgId() == null || account.getOrgId().isBlank()) {
            account.setOrgId("org_" + account.getTenantId());
        }
        iamRepository.saveUser(account);
        return sanitizeUser(account);
    }

    public Map<String, Object> resetPassword(String userId, String newPassword) {
        if (userId == null || userId.isBlank() || newPassword == null || newPassword.isBlank()) {
            throw DomainException.invalidParams("重置密码必须传入 userId 和 newPassword");
        }
        UserAccount u = iamRepository.findUserById(userId)
                .orElseThrow(() -> DomainException.invalidParams("用户不存在: " + userId));
        UserAccount actor = currentActor();
        if (!isRootSystemAdmin(actor)) {
            Set<String> managed = resolveManagedTenantIds(actor);
            String uTenant = u.getTenantId() != null ? u.getTenantId() : MetaContext.DEFAULT_TENANT;
            if (!managed.contains(uTenant) || "usr_admin".equals(u.getId())) {
                throw DomainException.permissionDenied("越权拒绝：无权重置其他企业或平台管理员密码");
            }
        }
        u.setPassword(newPassword);
        iamRepository.saveUser(u);
        return Map.of("reset", true, "userId", userId, "username", u.getUsername());
    }

    public Map<String, Object> changePassword(UserAccount current, String oldPassword, String newPassword) {
        if (current == null) {
            throw DomainException.permissionDenied("请先登录后再修改密码");
        }
        if (oldPassword == null || newPassword == null || newPassword.isBlank()) {
            throw DomainException.invalidParams("原密码和新密码不能为空");
        }
        UserAccount latest = iamRepository.findUserById(current.getId())
                .orElseThrow(() -> DomainException.invalidParams("用户不存在"));
        if (!Objects.equals(oldPassword, latest.getPassword())) {
            throw DomainException.invalidParams("原密码输入不正确");
        }
        latest.setPassword(newPassword);
        iamRepository.saveUser(latest);
        return Map.of("changed", true, "username", latest.getUsername());
    }

    public Map<String, Object> deleteUser(String userId) {
        if ("usr_admin".equals(userId)) {
            throw DomainException.stateGuardFailed("系统默认主管理员账号禁止删除");
        }
        UserAccount actor = currentActor();
        if (!isRootSystemAdmin(actor)) {
            UserAccount target = iamRepository.findUserById(userId)
                    .orElseThrow(() -> DomainException.invalidParams("用户不存在: " + userId));
            if (actor.getId().equals(userId)) {
                throw DomainException.stateGuardFailed("禁止删除当前登录的本人账号");
            }
            Set<String> managed = resolveManagedTenantIds(actor);
            String tTenant = target.getTenantId() != null ? target.getTenantId() : MetaContext.DEFAULT_TENANT;
            if (!managed.contains(tTenant)) {
                throw DomainException.permissionDenied("越权拒绝：无权删除其他企业或根系统账号");
            }
        }
        iamRepository.deleteUser(userId);
        return Map.of("deleted", true, "userId", userId);
    }

    public List<RoleDef> listRoles() {
        List<RoleDef> all = iamRepository.findAllRoles();
        UserAccount actor = currentActor();
        if (isRootSystemAdmin(actor)) return all;
        Set<String> managed = resolveManagedTenantIds(actor);
        List<RoleDef> filtered = new ArrayList<>();
        for (RoleDef r : all) {
            if ("ROLE_ADMIN".equals(r.getCode()) || "ROLE_DEVELOPER".equals(r.getCode()) || "ROLE_PUBLISHER".equals(r.getCode())) {
                continue;
            }
            if ("ROLE_APP_USER".equals(r.getCode()) || "ROLE_TENANT_ADMIN".equals(r.getCode())) {
                filtered.add(r);
                continue;
            }
            if (managed != null && !managed.isEmpty() && managed.contains(r.getTenantId())) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    public RoleDef saveRole(RoleDef role) {
        if (role.getCode() == null || role.getCode().isBlank()) {
            throw DomainException.invalidParams("角色编码不能为空");
        }
        role.setCode(role.getCode().trim());
        if (role.getName() == null || role.getName().isBlank()) {
            role.setName(role.getCode());
        } else {
            role.setName(role.getName().trim());
        }
        if (role.getDataScope() == null || role.getDataScope().isBlank()) {
            role.setDataScope("ALL");
        }

        UserAccount actor = currentActor();
        boolean rootAdmin = isRootSystemAdmin(actor);
        RoleDef existingByCode = iamRepository.findAllRoles().stream()
                .filter(r -> Objects.equals(role.getCode(), r.getCode()))
                .findFirst().orElse(null);

        if (!rootAdmin) {
            Set<String> managed = resolveManagedTenantIds(actor);
            if (managed == null || managed.isEmpty()) {
                throw DomainException.permissionDenied("权限拒绝：仅管理员或租户主管可配置角色");
            }
            if (BUILTIN_ROLE_CODES.contains(role.getCode())) {
                throw DomainException.permissionDenied("越权拒绝：平台内置角色 [" + role.getCode() + "] 禁止修改");
            }
            if (existingByCode != null && !managed.contains(existingByCode.getTenantId())) {
                throw DomainException.permissionDenied("越权拒绝：无权修改根系统或其他企业的自定义角色 [" + role.getCode() + "]");
            }
            if (role.getId() != null && !role.getId().isBlank()) {
                iamRepository.findAllRoles().stream()
                        .filter(r -> Objects.equals(role.getId(), r.getId()))
                        .findFirst()
                        .ifPresent(exById -> {
                            if (BUILTIN_ROLE_CODES.contains(exById.getCode()) || !managed.contains(exById.getTenantId())) {
                                throw DomainException.permissionDenied("越权拒绝：无权篡改系统或他人角色");
                            }
                        });
            }
            if (role.getTenantId() == null || role.getTenantId().isBlank()) {
                role.setTenantId(actor.getTenantId());
            }
            if (!managed.contains(role.getTenantId())) {
                throw DomainException.permissionDenied("越权拒绝：仅可将角色归属在本企业或下属分站范围内");
            }
            validateDelegatedRoleServices(role.getAllowedServices());
            List<String> cleanedMenus = normalizeMenuList(role.getAllowedMenus());
            if (!cleanedMenus.isEmpty()) {
                validateDelegatedMenus(actor.getTenantId(), cleanedMenus);
            } else if (resolveAllowedMenuNames(actor.getTenantId()) != null) {
                throw DomainException.permissionDenied("越权拒绝：上级总部本身受指定菜单范围限制，角色不可留空代表全部菜单");
            }
            role.setAllowedMenus(cleanedMenus);
        } else {
            if (role.getTenantId() == null || role.getTenantId().isBlank()) {
                role.setTenantId(MetaContext.DEFAULT_TENANT);
            }
            role.setAllowedMenus(normalizeMenuList(role.getAllowedMenus()));
        }

        if (role.getId() == null || role.getId().isBlank()) {
            role.setId(existingByCode != null ? existingByCode.getId()
                    : "role_" + UUID.randomUUID().toString().substring(0, 8));
        }
        iamRepository.saveRole(role);
        return role;
    }

    static boolean isBuiltInRole(String code) {
        return code != null && BUILTIN_ROLE_CODES.contains(code);
    }

    public Map<String, Object> deleteRole(String roleCode) {
        if (BUILTIN_ROLE_CODES.contains(roleCode)) {
            throw DomainException.stateGuardFailed("平台基础内置角色 [" + roleCode + "] 禁止删除");
        }
        RoleDef existing = iamRepository.findAllRoles().stream()
                .filter(r -> Objects.equals(roleCode, r.getCode()))
                .findFirst()
                .orElseThrow(() -> DomainException.invalidParams("角色不存在: " + roleCode));
        UserAccount actor = currentActor();
        if (!isRootSystemAdmin(actor)) {
            Set<String> managed = resolveManagedTenantIds(actor);
            if (managed == null || !managed.contains(existing.getTenantId())) {
                throw DomainException.permissionDenied("越权拒绝：无权删除根系统或其他企业的自定义角色");
            }
        }
        iamRepository.deleteRole(roleCode);
        return Map.of("deleted", true, "roleCode", roleCode);
    }

    // --- 多租户管理 ---
    public List<TenantDef> listTenants() {
        List<TenantDef> all = iamRepository.findAllTenants();
        UserAccount actor = currentActor();
        Set<String> managed = resolveManagedTenantIds(actor);
        if (managed == null) return all;
        List<TenantDef> res = new ArrayList<>();
        for (TenantDef t : all) {
            if (managed.contains(t.getId())) res.add(t);
        }
        return res;
    }

    public Optional<TenantDef> findTenantById(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        Optional<TenantDef> byId = iamRepository.findTenantById(tenantId);
        return byId.isPresent() ? byId : iamRepository.findTenantByCode(tenantId);
    }

    public TenantDef saveTenant(TenantDef t) {
        t.setCode(MetadataInputRules.tenantCode(t.getCode()));
        t.setName(MetadataInputRules.tenantName(t.getName()));
        if (t.getId() == null || t.getId().isBlank()) {
            t.setId(t.getCode().trim());
        }
        if (t.getCreateDate() == null || t.getCreateDate().isBlank()) {
            t.setCreateDate(TS_FMT.format(LocalDateTime.now()));
        }

        UserAccount actor = currentActor();
        if (!isRootSystemAdmin(actor)) {
            Set<String> managed = resolveManagedTenantIds(actor);
            if (managed.isEmpty()) {
                throw DomainException.permissionDenied("权限拒绝：仅管理员或企业主管可管理租户");
            }
            Optional<TenantDef> existingOpt = findTenantById(t.getId());
            if (existingOpt.isPresent()) {
                TenantDef ex = existingOpt.get();
                if (!managed.contains(ex.getId())) {
                    throw DomainException.permissionDenied("越权拒绝：无权修改非本企业及其下属分站之外的租户");
                }
                // 保持既有上级层级关系，禁止自我修改已授权应用与菜单范围
                t.setParentTenantId(ex.getParentTenantId());
                if (Objects.equals(ex.getId(), actor.getTenantId())) {
                    t.setAuthorizedApps(ex.getAuthorizedApps());
                    t.setAuthorizedMenus(ex.getAuthorizedMenus());
                } else {
                    validateDelegatedApps(actor.getTenantId(), t.getAuthorizedApps());
                    List<String> cleanedMenus = normalizeMenuList(t.getAuthorizedMenus());
                    if (!cleanedMenus.isEmpty()) {
                        validateDelegatedMenus(actor.getTenantId(), cleanedMenus);
                    }
                    t.setAuthorizedMenus(cleanedMenus);
                }
            } else {
                // 下级新建分站，强制挂在当前总部租户名下
                if (t.getParentTenantId() == null || t.getParentTenantId().isBlank() || !managed.contains(t.getParentTenantId())) {
                    t.setParentTenantId(actor.getTenantId());
                }
                validateDelegatedApps(actor.getTenantId(), t.getAuthorizedApps());
                List<String> cleanedMenus = normalizeMenuList(t.getAuthorizedMenus());
                if (!cleanedMenus.isEmpty()) {
                    validateDelegatedMenus(actor.getTenantId(), cleanedMenus);
                }
                t.setAuthorizedMenus(cleanedMenus);
            }
        } else {
            t.setAuthorizedMenus(normalizeMenuList(t.getAuthorizedMenus()));
        }

        iamRepository.saveTenant(t);
        // 若该租户下尚未建立根组织，自动初始化一个组织根节点
        if (iamRepository.findOrgsByTenant(t.getId()).isEmpty()) {
            OrgNode root = new OrgNode();
            root.setId("org_" + t.getId());
            root.setTenantId(t.getId());
            root.setName(t.getName() + "总部");
            root.setOrgType("ROOT");
            root.setSequence(1);
            iamRepository.saveOrg(root);
        }
        return t;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw DomainException.invalidParams("租户标识 (tenantId) 不能为空");
        }
        TenantDef target = findTenantById(tenantId.trim())
                .orElseThrow(() -> DomainException.invalidParams("租户不存在: " + tenantId));
        if (MetaContext.DEFAULT_TENANT.equals(target.getId())) {
            throw DomainException.stateGuardFailed("默认平台根租户 (rbac_tenant_root) 禁止删除");
        }
        UserAccount actor = currentActor();
        if (Objects.equals(target.getId(), actor.getTenantId())) {
            throw DomainException.stateGuardFailed("不能删除当前登录账号所属的租户");
        }
        if (!isRootSystemAdmin(actor)) {
            Set<String> managed = resolveManagedTenantIds(actor);
            if (managed == null || !managed.contains(target.getId())) {
                throw DomainException.permissionDenied("越权拒绝：仅可删除本总部下属分站租户");
            }
        }
        for (TenantDef other : iamRepository.findAllTenants()) {
            if (!Objects.equals(other.getId(), target.getId())
                    && Objects.equals(target.getId(), other.getParentTenantId())) {
                throw DomainException.stateGuardFailed(
                        "租户 [" + target.getName() + "] 下仍存在下级分站 [" + other.getName() + "]，请先删除下级分站");
            }
        }

        List<UserAccount> users = iamRepository.findUsersByTenant(target.getId());
        int deletedUsers = 0;
        for (UserAccount u : users) {
            if ("usr_admin".equals(u.getId())) continue;
            iamRepository.deleteUser(u.getId());
            deletedUsers++;
        }
        int deletedRoles = 0;
        for (RoleDef role : iamRepository.findAllRoles()) {
            if (Objects.equals(target.getId(), role.getTenantId()) && !BUILTIN_ROLE_CODES.contains(role.getCode())) {
                iamRepository.deleteRole(role.getCode());
                deletedRoles++;
            }
        }
        int deletedOrgs = iamRepository.findOrgsByTenant(target.getId()).size();
        iamRepository.deleteOrgsByTenant(target.getId());
        iamRepository.deleteTenant(target.getId());
        return Map.of(
                "deleted", true,
                "tenantId", target.getId(),
                "tenantName", target.getName(),
                "deletedUsers", deletedUsers,
                "deletedRoles", deletedRoles,
                "deletedOrgs", deletedOrgs
        );
    }

    public void cleanupDeletedMenuReferences(Set<String> deletedMenuKeys) {
        if (deletedMenuKeys == null || deletedMenuKeys.isEmpty()) return;
        for (TenantDef tenant : iamRepository.findAllTenants()) {
            List<String> menus = new ArrayList<>(tenant.getAuthorizedMenus());
            if (!menus.isEmpty() && !menus.contains("*") && !menus.contains("__NONE__")
                    && menus.removeIf(deletedMenuKeys::contains)) {
                tenant.setAuthorizedMenus(menus.isEmpty() ? List.of("__NONE__") : menus);
                iamRepository.saveTenant(tenant);
            }
        }
        for (RoleDef role : iamRepository.findAllRoles()) {
            List<String> menus = new ArrayList<>(role.getAllowedMenus());
            if (!menus.isEmpty() && !menus.contains("*") && !menus.contains("__NONE__")
                    && menus.removeIf(deletedMenuKeys::contains)) {
                role.setAllowedMenus(menus.isEmpty() ? List.of("__NONE__") : menus);
                iamRepository.saveRole(role);
            }
        }
    }

    private void validateDelegatedApps(String parentTenantId, List<String> delegatedApps) {
        if (delegatedApps == null || delegatedApps.isEmpty()) return;
        for (String app : delegatedApps) {
            if ("*".equals(app)) {
                throw DomainException.permissionDenied("越权拒绝：租户主管无权向下级授予全部应用通配权限 (*)");
            }
            if (!isAppAuthorizedForTenant(parentTenantId, app)) {
                throw DomainException.permissionDenied("越权拒绝：应用 [" + app + "] 不在本总部的已授权范围内");
            }
        }
    }

    public TenantDef authorizeApps(String tenantId, List<String> authorizedApps) {
        return authorizeApps(tenantId, authorizedApps, null);
    }

    public TenantDef authorizeApps(String tenantId, List<String> authorizedApps, List<String> authorizedMenus) {
        TenantDef tenant = findTenantById(tenantId)
                .orElseThrow(() -> DomainException.invalidParams("租户不存在: " + tenantId));
        UserAccount actor = currentActor();
        if (!isRootSystemAdmin(actor)) {
            Set<String> managed = resolveManagedTenantIds(actor);
            if (!managed.contains(tenant.getId()) || Objects.equals(tenant.getId(), actor.getTenantId())) {
                throw DomainException.permissionDenied("越权拒绝：仅可为下属分站/租户授权，无权修改自身或其他企业租户授权");
            }
            validateDelegatedApps(actor.getTenantId(), authorizedApps);
            if (authorizedMenus != null) {
                validateDelegatedMenus(actor.getTenantId(), authorizedMenus);
            }
        }
        tenant.setAuthorizedApps(authorizedApps != null ? authorizedApps : new ArrayList<>());
        if (authorizedMenus != null) {
            tenant.setAuthorizedMenus(normalizeMenuList(authorizedMenus));
        }
        iamRepository.saveTenant(tenant);
        return tenant;
    }

    public TenantDef authorizeMenus(String tenantId, List<String> authorizedMenus) {
        TenantDef tenant = findTenantById(tenantId)
                .orElseThrow(() -> DomainException.invalidParams("租户不存在: " + tenantId));
        UserAccount actor = currentActor();
        if (!isRootSystemAdmin(actor)) {
            Set<String> managed = resolveManagedTenantIds(actor);
            if (!managed.contains(tenant.getId()) || Objects.equals(tenant.getId(), actor.getTenantId())) {
                throw DomainException.permissionDenied("越权拒绝：仅可为下属分站/租户配置菜单，无权修改自身或其他企业租户权限");
            }
            validateDelegatedMenus(actor.getTenantId(), authorizedMenus);
        }
        tenant.setAuthorizedMenus(normalizeMenuList(authorizedMenus));
        iamRepository.saveTenant(tenant);
        return tenant;
    }

    private List<String> normalizeMenuList(List<String> raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        Set<String> cleaned = new LinkedHashSet<>();
        for (String s : raw) {
            if (s != null && !s.isBlank()) cleaned.add(s.trim());
        }
        if (cleaned.contains("*")) return new ArrayList<>(List.of("*"));
        if (cleaned.contains("__NONE__") && cleaned.size() > 1) cleaned.remove("__NONE__");
        return new ArrayList<>(cleaned);
    }

    private void validateDelegatedMenus(String parentTenantId, List<String> delegatedMenus) {
        Set<String> parentAllowed = resolveAllowedMenuNames(parentTenantId);
        if (parentAllowed == null) {
            return;
        }
        if (delegatedMenus == null || delegatedMenus.isEmpty() || delegatedMenus.contains("*")) {
            throw DomainException.permissionDenied("越权拒绝：上级总部本身受指定菜单范围限制，不可向下级授予全部菜单 (*)");
        }
        for (String menu : delegatedMenus) {
            if (menu == null || menu.isBlank() || "__NONE__".equals(menu)) continue;
            if (!parentAllowed.contains(menu.trim())) {
                throw DomainException.permissionDenied("越权拒绝：菜单 [" + menu + "] 不在本总部的已授权菜单范围内");
            }
        }
    }

    private void validateDelegatedRoleServices(Set<String> services) {
        if (services == null || services.isEmpty()) return;
        for (String raw : services) {
            if (raw == null || raw.isBlank()) continue;
            String s = raw.trim();
            if ("*".equals(s) || "*:*".equals(s)
                    || "*:create".equals(s) || "*:update".equals(s) || "*:delete".equals(s)
                    || "*:search".equals(s) || "*:find".equals(s) || "*:count".equals(s) || "*:exists".equals(s)
                    || s.startsWith("meta_model") || s.startsWith("meta_app")
                    || s.startsWith("meta_view") || s.startsWith("meta_menu")
                    || s.startsWith("meta_dict:save") || s.startsWith("meta_dict:delete")
                    || s.startsWith("meta_code_rule:save") || s.startsWith("meta_code_rule:delete")
                    || s.startsWith("rbac_tenant") || s.startsWith("rbac_role")
                    || s.startsWith("rbac_user:save") || s.startsWith("rbac_user:delete") || s.startsWith("rbac_user:reset")) {
                throw DomainException.permissionDenied("越权拒绝：租户角色无权配置平台系统或全模型通配权限 [" + s + "]");
            }
        }
    }

    // --- 组织架构树管理 ---
    public List<OrgNode> listOrgs(String tenantId) {
        UserAccount actor = currentActor();
        Set<String> managed = resolveManagedTenantIds(actor);
        if (managed != null && managed.isEmpty()) return Collections.emptyList();
        List<OrgNode> orgs = iamRepository.findOrgsByTenant(tenantId);
        if (managed == null) return orgs;
        List<OrgNode> res = new ArrayList<>();
        for (OrgNode o : orgs) {
            if (managed.contains(o.getTenantId())) res.add(o);
        }
        return res;
    }

    public OrgNode saveOrg(OrgNode org) {
        if (org.getName() == null || org.getName().isBlank()) {
            throw DomainException.invalidParams("组织节点名称不能为空");
        }
        if (org.getId() == null || org.getId().isBlank()) {
            org.setId("org_" + UUID.randomUUID().toString().substring(0, 8));
        }
        UserAccount actor = currentActor();
        if (org.getTenantId() == null || org.getTenantId().isBlank()) {
            org.setTenantId(!isRootSystemAdmin(actor) && actor.getTenantId() != null ? actor.getTenantId() : "rbac_tenant_root");
        }
        if (!isRootSystemAdmin(actor)) {
            Set<String> managed = resolveManagedTenantIds(actor);
            if (!managed.contains(org.getTenantId())) {
                throw DomainException.permissionDenied("越权拒绝：无权操作其他企业或根系统的部门组织");
            }
        }
        iamRepository.saveOrg(org);
        return org;
    }

    public Map<String, Object> deleteOrg(String orgId) {
        if ("org_root".equals(orgId)) {
            throw DomainException.stateGuardFailed("平台默认根组织禁止删除");
        }
        UserAccount actor = currentActor();
        List<OrgNode> allOrgs = iamRepository.findOrgsByTenant(null);
        if (!isRootSystemAdmin(actor)) {
            Set<String> managed = resolveManagedTenantIds(actor);
            OrgNode target = allOrgs.stream().filter(o -> Objects.equals(orgId, o.getId())).findFirst().orElse(null);
            if (target != null && !managed.contains(target.getTenantId())) {
                throw DomainException.permissionDenied("越权拒绝：无权删除其他企业或根系统的部门组织");
            }
        }
        for (OrgNode child : allOrgs) {
            if (Objects.equals(orgId, child.getParentId())) {
                throw DomainException.stateGuardFailed("组织节点下仍存在子部门 [" + child.getName() + "]，禁止直接删除");
            }
        }
        iamRepository.deleteOrg(orgId);
        return Map.of("deleted", true, "orgId", orgId);
    }

    private Map<String, Object> sanitizeUser(UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("tenantId", u.getTenantId());
        m.put("orgId", u.getOrgId());
        m.put("phone", u.getPhone());
        m.put("email", u.getEmail());
        m.put("status", u.getStatus());
        m.put("roleCodes", u.getRoleCodes());
        return m;
    }
}
