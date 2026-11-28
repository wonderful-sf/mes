package com.edu.scnu.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

public interface AssistantPlanner {
    boolean isConfigured();

    Decision plan(Context context);

    record Context(String request, List<Turn> recentTurns, AssistantAppDesign draft,
                   String selectedTenantId, List<TenantChoice> tenants, ExistingTarget existingTarget,
                   IamChange pendingIam, boolean studioAccess, AssistantAppDesign.Table pendingTable,
                   List<AssistantRuntimeActions.PageChoice> runtimePages, BusinessChange previousBusiness,
                   String currentMenu, String today) {}

    record Turn(String role, String message) {}

    record TenantChoice(String id, String name, String code) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Decision(String message, AssistantAppDesign draft, String tenantId, ExistingChange edit, IamChange iam,
                    BusinessChange business) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IamChange(String action, String name, String code, String tenantQuery, String roleQuery,
                     String dataScope, List<MenuAccess> access, boolean replaceAccess) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MenuAccess(String menuQuery, List<String> actions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExistingChange(String appQuery, String tableQuery, List<AssistantAppDesign.Field> addFields,
                          List<FieldRename> renameFields, AssistantAppDesign.Table newTable) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessChange(String menu, String operation, List<Condition> where, List<String> columns,
                          Map<String, Object> values, Integer limit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Condition(String field, String operator, Object value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FieldRename(String fieldQuery, String displayName) {}

    record ExistingTarget(String appKey, String appName, String tableKey, String tableName) {}

    final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
