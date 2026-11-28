package com.edu.scnu.application;

import com.edu.scnu.domain.shared.DomainException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistantAppDesign(String name, String description, List<Table> tables) {
    private static final Set<String> TYPES = Set.of(
            "String", "Text", "Integer", "Decimal", "Boolean", "Date", "DateTime", "Selection", "ManyToOne");
    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "id", "tenant_id", "bound_version_id", "version", "create_user", "create_date", "update_user", "update_date", "delete_flag");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Table(String key, String name, List<Field> fields) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(String key, String name, String type, boolean required, String relatedTable, List<Option> options) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Option(String value, String label) {}

    Map<String, Object> toPackage(String appKey) {
        requireText(name, 60, "应用名称");
        if (description != null && description.length() > 300) invalid("应用描述最多 300 字。");
        if (tables == null || tables.isEmpty() || tables.size() > 8) invalid("应用需要 1 到 8 张数据表。");

        Set<String> tableKeys = new HashSet<>();
        for (Table table : tables) {
            if (table == null) invalid("数据表定义为空。");
            requireKey(table.key(), "数据表标识");
            requireText(table.name(), 60, "数据表名称");
            if (!tableKeys.add(table.key())) invalid("数据表标识重复：" + table.key());
        }
        Map<String, String> targets = new LinkedHashMap<>();
        for (String key : tableKeys) targets.put(key, appKey + "_" + key);
        List<Map<String, Object>> models = new ArrayList<>();
        Map<String, Object> menus = new LinkedHashMap<>();
        String folder = appKey + "_root_menu";
        menus.put(folder, Map.of("name", folder, "display_name", name, "sequence", 20));
        for (int i = 0; i < tables.size(); i++) {
            Table table = tables.get(i);
            String model = modelName(appKey, table);
            models.add(Map.of("name", model, "displayName", table.name(), "appName", appKey,
                    "properties", properties(table, targets)));
            String menu = model + "_menu";
            menus.put(menu, Map.of("name", menu, "display_name", table.name(), "sequence", (i + 1) * 10,
                    "model", model, "view", model + "_grid," + model + "_search," + model + "_form",
                    "parent_ids", Map.of("@ref", folder)));
        }
        return Map.of("app", Map.of("name", appKey, "displayName", name, "description", description == null ? "" : description),
                "models", models, "menus", menus);
    }

    static List<Map<String, Object>> properties(Table table, Map<String, String> targets) {
        List<Field> fields = table.fields();
        if (fields == null || fields.isEmpty() || fields.size() > 24) invalid("每张表需要 1 到 24 个字段。");
        Set<String> keys = new HashSet<>();
        List<Map<String, Object>> properties = new ArrayList<>();
        boolean displayField = false;
        for (Field field : fields) {
            Map<String, Object> property = property(field, targets);
            if (!keys.add(field.key())) invalid("字段标识重复：" + field.key());
            if (!displayField && "String".equals(field.type())) {
                property.put("displayForModel", true);
                displayField = true;
            }
            properties.add(property);
        }
        return properties;
    }

    static void validateTable(Table table) {
        if (table == null) invalid("数据表定义为空。");
        requireKey(table.key(), "数据表标识");
        requireText(table.name(), 60, "数据表名称");
    }

    static Map<String, Object> property(Field field, Map<String, String> targets) {
        if (field == null) invalid("字段定义为空。");
        requireKey(field.key(), "字段标识");
        requireText(field.name(), 60, "字段名称");
        if (SYSTEM_FIELDS.contains(field.key())) invalid("字段标识保留：" + field.key());
        if (field.type() == null || !TYPES.contains(field.type())) invalid("字段类型暂不支持：" + field.name());
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("name", field.key());
        property.put("displayName", field.name());
        property.put("dataType", field.type());
        property.put("required", field.required());
        if ("ManyToOne".equals(field.type())) {
            String target = targets.get(field.relatedTable());
            if (target == null) invalid("关联字段须指向应用内的数据表：" + field.name());
            property.put("relateModel", target);
        }
        if ("Selection".equals(field.type())) property.put("values", selectionOptions(field));
        return property;
    }

    private static List<Option> selectionOptions(Field field) {
        if (field.options() == null || field.options().isEmpty() || field.options().size() > 20) {
            invalid("选项字段需要 1 到 20 个选项：" + field.name());
        }
        Set<String> values = new HashSet<>();
        for (Option option : field.options()) {
            if (option == null) invalid("选项不能为空。");
            requireText(option.value(), 40, "选项值");
            requireText(option.label(), 60, "选项名称");
            if (!values.add(option.value())) invalid("选项值重复：" + field.name());
        }
        return field.options();
    }

    private static void requireText(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) invalid(label + "须为 1 到 " + maximum + " 字。");
    }

    private static void requireKey(String value, String label) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,23}")) invalid(label + "须用小写字母开头，最多 24 个字母、数字或下划线。");
    }

    private static void invalid(String reason) {
        throw DomainException.invalidParams(reason);
    }

    static String modelName(String appKey, Table table) {
        return appKey + "_" + table.key();
    }
}
