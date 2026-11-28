package com.edu.scnu.application;

import com.edu.scnu.application.AssistantPlanner.BusinessChange;
import com.edu.scnu.application.AssistantPlanner.Condition;
import com.edu.scnu.application.AssistantPlatformActions.PageLink;
import com.edu.scnu.domain.metamodel.model.DictItemDef;
import com.edu.scnu.domain.metamodel.model.PropertyDef;
import com.edu.scnu.domain.metamodel.model.ServiceDef;
import com.edu.scnu.domain.runtime.RecordData;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.uischema.UiViewDef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AssistantRuntimeActions {
    private static final Set<String> FIELD_TYPES = Set.of(
            "String", "Text", "Integer", "Long", "Decimal", "BigDecimal", "Float", "Double", "Boolean",
            "Date", "DateTime", "Selection", "Enum", "Dict", "ManyToOne");
    private static final Set<String> OPERATORS = Set.of("=", "!=", "<", ">", "<=", ">=", "like", "in");
    private static final Set<String> RESERVED = Set.of("id", "version", "tenant_id", "bound_version_id",
            "create_user", "create_date", "update_user", "update_date", "delete_flag");
    private static final Pattern EQUALS = Pattern.compile("row\\.([a-zA-Z0-9_]+)\\s*===\\s*'([^']*)'");
    private static final Pattern INCLUDES = Pattern.compile("\\[('[-A-Za-z0-9_]+'(?:\\s*,\\s*'[-A-Za-z0-9_]+')*)]\\.includes\\(row\\.([a-zA-Z0-9_]+)\\)");
    private static final Pattern BOOLEAN = Pattern.compile("(!?)row\\.([a-zA-Z0-9_]+)");
    private final AssistantPlatformActions platform;
    private final ObjectMapper json;

    public AssistantRuntimeActions(AssistantPlatformActions platform, ObjectMapper json) {
        this.platform = platform;
        this.json = json;
    }

    public List<PageChoice> choices(String authorization, String currentMenu) {
        List<Menu> menus = menus(authorization);
        Menu current = currentMenu == null || currentMenu.isBlank() ? null : requireMenu(menus, currentMenu);
        List<Menu> scope = new ArrayList<>();
        if (current != null) scope.add(current);
        menus.stream().filter(menu -> current == null || (!menu.equals(current) && menu.app().equals(current.app())))
                .limit(18).forEach(scope::add);
        List<PageChoice> choices = new ArrayList<>();
        Map<String, String> appLabels = appLabels(authorization);
        for (Menu menu : scope) {
            Page page = loadPage(authorization, menu);
            if (page != null && !page.operations().isEmpty()) {
                choices.add(new PageChoice(menu.key(), menu.label(), menu.app(), appLabels.get(menu.app()), page.filters().values().stream()
                        .map(FieldChoice::of).toList(), page.operations().values().stream().map(Operation::choice).toList()));
            }
        }
        return choices;
    }

    public Result query(String authorization, BusinessChange change) {
        Page page = requirePage(authorization, change.menu());
        requireOperation(page, change.operation());
        var filters = filters(page, change.where());
        if ("count".equals(change.operation())) {
            Number total = (Number) platform.call(authorization, page.menu().model(), "count",
                    Map.of("menu", page.menu().key(), "filter", filters));
            return new Result(page.menu().label(), total.longValue(), List.of(), page.menu().link());
        }
        List<String> columns = change.columns() == null || change.columns().isEmpty()
                ? defaultColumns(page) : change.columns().stream().distinct().toList();
        if (columns.size() > 8 || columns.stream().anyMatch(key -> !page.filters().containsKey(key))) {
            throw DomainException.invalidParams("请选择页面提供的字段，每次最多显示 8 列。");
        }
        int limit = Math.max(1, Math.min(20, change.limit() == null ? 10 : change.limit()));
        Search result = search(authorization, page.menu(), filters, columns, limit);
        return new Result(page.menu().label(), result.total(), result.rows().stream()
                .map(row -> describeRow(page, row, columns)).toList(), page.menu().link());
    }

    public Plan prepare(String authorization, BusinessChange change) {
        Page page = requirePage(authorization, change.menu());
        Operation operation = requireOperation(page, change.operation());
        if (isQuery(operation.key())) throw DomainException.invalidParams("请选择可执行的业务操作。");
        List<List<Object>> filters = filters(page, change.where());
        RecordData row = null;
        if ("create".equals(operation.key())) {
            if (!filters.isEmpty()) throw DomainException.invalidParams("新增记录无需提供筛选条件。");
        } else {
            if (filters.isEmpty()) throw DomainException.invalidParams("请提供能确定单条记录的条件，例如单号。");
            Set<String> columns = new LinkedHashSet<>(defaultColumns(page));
            filters.forEach(filter -> columns.add(filter.get(0).toString()));
            operation.rules().forEach(rule -> columns.add(rule.field()));
            columns.add("id");
            columns.add("version");
            Search result = search(authorization, page.menu(), filters, List.copyOf(columns), 2);
            if (result.rows().size() != 1) {
                throw DomainException.invalidParams(result.rows().isEmpty()
                        ? "当前页面没有符合条件的可操作记录。" : "找到多条记录，请补充唯一单号或其他条件。");
            }
            row = result.rows().get(0);
            if (operation.ruleAlternatives().stream().noneMatch(rules -> rules.stream().allMatch(rule -> rule.matches(result.rows().get(0))))) {
                throw DomainException.stateGuardFailed("这条记录当前状态不允许执行「" + operation.label() + "」。");
            }
            if (row.getId() == null || row.getInt("version") == null) {
                throw DomainException.stateGuardFailed("无法验证这条记录的当前版本，请从页面操作。");
            }
        }
        Map<String, Object> incoming = change.values() == null ? Map.of() : change.values();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (String name : incoming.keySet()) {
            if (!operation.fields().containsKey(name)) throw DomainException.invalidParams("当前操作没有这个可填写字段：" + name);
            PropertyDef field = operation.fields().get(name);
            Object value = normalize(field, incoming.get(name));
            if (field.isManyToOne()) {
                if (value == null) throw DomainException.invalidParams("请指定「" + field.getDisplayName() + "」关联的记录。");
                Reference reference = resolveReference(authorization, page.menu(), field, value.toString());
                values.put(name, reference.id());
                display.put(field.getDisplayName(), reference.label());
            } else {
                values.put(name, value);
                display.put(field.getDisplayName(), labelValue(field, value));
            }
        }
        for (PropertyDef field : operation.fields().values()) {
            if (needsValue(field) && !("update".equals(operation.key())) && !values.containsKey(field.getName())) {
                throw DomainException.invalidParams("请补充必填项「" + field.getDisplayName() + "」。");
            }
        }
        if ("update".equals(operation.key()) && values.isEmpty()) {
            throw DomainException.invalidParams("请说明要修改的字段和新值。");
        }
        String id = row == null ? null : row.getId();
        Integer version = row == null ? null : row.getInt("version");
        if (row != null) display = withTarget(page, operation, row, filters, display);
        Preview preview = new Preview(operation.label(), page.menu().label(), display, page.menu().link(), operation.confirm());
        return new Plan(page.menu(), page.version(), operation.key(), id, version, values, preview);
    }

    @Transactional(rollbackFor = Exception.class)
    public String apply(String authorization, BusinessChange change, Plan expected) {
        Plan current = prepare(authorization, change);
        if (!expected.equals(current)) {
            throw DomainException.concurrentConflict("记录、权限或页面配置已变化，请重新生成并核对操作。");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (current.id() != null) args.put("id", current.id());
        if ("create".equals(current.operation()) || "update".equals(current.operation())) {
            args.put("values", current.values());
            if (current.version() != null) args.put("version", current.version());
        } else {
            args.putAll(current.values());
        }
        Object output = platform.call(authorization, current.menu().model(), current.operation(), args);
        if (output instanceof Map<?, ?> response && response.containsKey("_uiAction") && response.get("message") instanceof String message) {
            return message.substring(0, Math.min(300, message.length()));
        }
        return "已完成「" + current.preview().operation() + "」。";
    }

    private Map<String, String> withTarget(Page page, Operation operation, RecordData row, List<List<Object>> filters,
                                           Map<String, String> input) {
        Set<String> columns = new LinkedHashSet<>();
        filters.forEach(filter -> columns.add(filter.get(0).toString()));
        operation.rules().forEach(rule -> columns.add(rule.field()));
        columns.addAll(defaultColumns(page));
        Map<String, String> details = new LinkedHashMap<>();
        details.put("记录标识", row.getId());
        describeRow(page, row, List.copyOf(columns)).forEach((label, value) -> details.put("当前 · " + label, value));
        input.forEach((label, value) -> details.put("填写 · " + label, value));
        return details;
    }

    private List<List<Object>> filters(Page page, List<Condition> conditions) {
        if (conditions == null) return List.of();
        if (conditions.size() > 8) throw DomainException.invalidParams("每次最多使用 8 个筛选条件。");
        List<List<Object>> result = new ArrayList<>();
        for (Condition item : conditions) {
            if (item == null || item.operator() == null || !OPERATORS.contains(item.operator()) || item.value() == null) {
                throw DomainException.invalidParams("筛选条件或运算符无效。");
            }
            PropertyDef field = page.filters().get(item.field());
            if (field == null) throw DomainException.invalidParams("当前页面不提供这个筛选字段：" + item.field());
            Object value;
            if ("in".equals(item.operator())) {
                if (!(item.value() instanceof List<?> items) || items.isEmpty() || items.size() > 12 || items.stream().anyMatch(Objects::isNull)) {
                    throw DomainException.invalidParams("多值筛选需提供 1 至 12 个有效值。");
                }
                value = items.stream().map(raw -> normalize(field, raw)).toList();
            } else {
                value = normalize(field, item.value());
            }
            result.add(List.of(field.getName(), item.operator(), value));
        }
        return result;
    }

    private Object normalize(PropertyDef field, Object raw) {
        if (raw == null && !field.isRequired()) return null;
        if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean)
                || (raw instanceof String text && (text.isBlank() || text.length() > 500))) {
            throw DomainException.invalidParams("「" + field.getDisplayName() + "」需要有效的单个值。");
        }
        if (!field.getValues().isEmpty()) {
            String value = raw.toString();
            return field.getValues().stream().filter(option -> value.equalsIgnoreCase(option.getValue()) || value.equals(option.getLabel()))
                    .map(PropertyDef.SelectionOption::getValue).findFirst()
                    .orElseThrow(() -> DomainException.invalidParams("「" + field.getDisplayName() + "」请选择页面提供的选项。"));
        }
        return raw;
    }

    private Reference resolveReference(String authorization, Menu source, PropertyDef field, String value) {
        for (Menu menu : menus(authorization)) {
            if (!source.app().equals(menu.app()) || !menu.model().equals(field.getRelateModel())) continue;
            Page page = loadPage(authorization, menu);
            if (page == null || !page.operations().containsKey("search")) continue;
            String label = page.filters().values().stream().filter(PropertyDef::isDisplayForModel)
                    .map(PropertyDef::getName).findFirst().orElse(null);
            var properties = label == null ? List.of("id") : List.of("id", label);
            Search match = search(authorization, menu, List.of(List.of("id", "=", value)), properties, 2);
            if (match.rows().isEmpty() && label != null) {
                match = search(authorization, menu, List.of(List.of(label, "=", value)), properties, 2);
            }
            if (match.rows().size() == 1) {
                RecordData row = match.rows().get(0);
                return new Reference(row.getId(), label == null ? row.getId() : Objects.toString(row.get(label), row.getId()));
            }
            if (match.rows().size() > 1) break;
        }
        throw DomainException.invalidParams("找不到唯一可见的「" + field.getDisplayName() + "」记录，请核对名称或记录标识。");
    }

    @SuppressWarnings("unchecked")
    private Search search(String authorization, Menu menu, List<List<Object>> filters, List<String> columns, int limit) {
        Map<String, Object> result = (Map<String, Object>) platform.call(authorization, menu.model(), "search",
                Map.of("menu", menu.key(), "filter", filters, "properties", columns, "limit", limit, "useDisplayForModel", true));
        return new Search((List<RecordData>) result.get("records"), ((Number) result.get("total")).longValue());
    }

    private Map<String, String> describeRow(Page page, RecordData row, List<String> columns) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (String key : columns) {
            PropertyDef field = page.filters().get(key);
            if (field != null) cells.put(field.getDisplayName(), labelValue(field, row.get(key)));
        }
        return cells;
    }

    private String labelValue(PropertyDef field, Object value) {
        if (value instanceof Map<?, ?> option) value = option.get("label") == null ? option.get("id") : option.get("label");
        String text = Objects.toString(value, "—");
        String label = field.getValues().stream().filter(option -> text.equals(option.getValue()))
                .map(PropertyDef.SelectionOption::getLabel).findFirst().orElse(text);
        return label.substring(0, Math.min(160, label.length()));
    }

    private List<String> defaultColumns(Page page) {
        List<String> columns = page.gridColumns().stream().filter(page.filters()::containsKey).limit(6).toList();
        return columns.isEmpty() ? page.filters().keySet().stream().limit(6).toList() : columns;
    }

    private List<Menu> menus(String authorization) {
        Object raw = platform.call(authorization, "ui_menu", "loadMenu", Map.of());
        List<Menu> result = new ArrayList<>();
        if (!(raw instanceof List<?> roots)) return result;
        var remaining = new ArrayDeque<Object>(roots);
        while (!remaining.isEmpty()) {
            if (!(remaining.removeFirst() instanceof Map<?, ?> node)) continue;
            if (node.get("children") instanceof List<?> children) remaining.addAll(children);
            if (node.get("name") instanceof String key && node.get("appName") instanceof String app
                    && !"platform_kernel".equals(app) && node.get("model") instanceof String model
                    && node.get("routePath") instanceof String path) {
                result.add(new Menu(key, Objects.toString(node.get("displayName"), key), app, model, path));
            }
        }
        return result;
    }

    private Map<String, String> appLabels(String authorization) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (platform.call(authorization, "sys_portal", "publicApps", Map.of()) instanceof List<?> apps) {
            for (Object item : apps) {
                if (item instanceof Map<?, ?> app && app.get("name") instanceof String key) {
                    labels.put(key, Objects.toString(app.get("displayName"), key));
                }
            }
        }
        return labels;
    }

    private Menu requireMenu(List<Menu> menus, String key) {
        return menus.stream().filter(menu -> menu.key().equals(key)).findFirst()
                .orElseThrow(() -> DomainException.permissionDenied("该业务页面当前不可用或不在你的授权范围内。"));
    }

    private Page requirePage(String authorization, String key) {
        if (key == null || key.isBlank()) throw DomainException.invalidParams("请指定业务页面，或打开相关页面后再试。");
        Page page = loadPage(authorization, requireMenu(menus(authorization), key));
        if (page == null) throw DomainException.permissionDenied("当前账号无法操作该页面。");
        return page;
    }

    @SuppressWarnings("unchecked")
    private Page loadPage(String authorization, Menu menu) {
        Map<String, Object> view;
        try {
            view = (Map<String, Object>) platform.call(authorization, "ui_view", "loadView", Map.of("menu", menu.key()));
        } catch (DomainException e) {
            if ("ERR_PERMISSION_DENIED".equals(e.getErrorKey())) return null;
            throw e;
        }
        var views = (Map<String, UiViewDef>) view.get("views");
        var auth = (Map<String, Object>) view.get("auth");
        var fields = (Map<String, PropertyDef>) view.get("fields");
        var version = (Map<String, Object>) view.get("modelMeta");
        var dictionaries = (Map<String, List<DictItemDef>>) view.get("dictOptions");
        UiViewDef gridView = views.get("grid");
        List<String> grid = columns(gridView, false);
        List<String> form = columns(views.get("form"), false);
        List<String> editableForm = columns(views.get("form"), true);
        Set<String> workflowFields = workflowFields(gridView);
        Set<String> visible = new LinkedHashSet<>(grid);
        visible.addAll(columns(views.get("search"), false));
        visible.addAll(form);
        Map<String, PropertyDef> filterFields = new LinkedHashMap<>();
        Map<String, PropertyDef> editable = new LinkedHashMap<>();
        PropertyDef id = new PropertyDef();
        id.setName("id");
        id.setDisplayName("记录标识");
        filterFields.put("id", id);
        for (String name : visible) {
            PropertyDef field = fields.get(name);
            if (field == null || RESERVED.contains(name) || !field.isStoredColumn() || !field.isDisplay()
                    || field.getDataType() == null || !FIELD_TYPES.contains(field.getDataType())) continue;
            List<DictItemDef> items = dictionaries.get(name);
            if (items != null && !items.isEmpty()) {
                field = json.convertValue(field, PropertyDef.class);
                field.setValues(items.stream().map(item -> new PropertyDef.SelectionOption(
                        item.getItemLabel(), item.getItemValue(), null)).toList());
            }
            filterFields.put(name, field);
            if (editableForm.contains(name) && !workflowFields.contains(name) && !field.isReadonly() && !field.isAutoColumn()
                    && field.getComputeMethod() == null && field.getComputeScript() == null && field.getRelated() == null) {
                editable.put(name, field);
            }
        }
        Map<String, Operation> operations = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(auth.get("search"))) {
            operations.put("search", Operation.builtin("search", "查询记录", Map.of()));
            if (views.containsKey("form") && Boolean.TRUE.equals(auth.get("update"))) {
                operations.put("update", Operation.builtin("update", "编辑记录", editable));
            }
            addRowActions(menu, view, gridView, auth, operations);
        }
        if (Boolean.TRUE.equals(auth.get("count"))) {
            operations.put("count", Operation.builtin("count", "统计记录数", Map.of()));
        }
        if (views.containsKey("form") && Boolean.TRUE.equals(auth.get("create"))) {
            operations.put("create", Operation.builtin("create", "新增记录", editable));
        }
        return new Page(menu, Objects.toString(version.get("activeVersionId")), grid, filterFields, operations);
    }

    @SuppressWarnings("unchecked")
    private void addRowActions(Menu menu, Map<String, Object> payload, UiViewDef grid, Map<String, Object> auth,
                               Map<String, Operation> operations) {
        if (grid == null || !(grid.getBody().get("buttons") instanceof List<?> buttons)
                || !(auth.get("services") instanceof Map<?, ?> permitted)) return;
        var services = (List<ServiceDef>) payload.get("services");
        for (Object item : buttons) {
            if (!(item instanceof Map<?, ?> button) || !"custom".equals(button.get("action"))
                    || !(button.get("service") instanceof String key) || !Boolean.TRUE.equals(permitted.get(key))
                    || (button.get("model") != null && !menu.model().equals(button.get("model")))
                    || !Map.of("id", "$row.id").equals(button.get("args"))) continue;
            ServiceDef service = services.stream().filter(candidate -> key.equals(candidate.getName())).findFirst().orElse(null);
            List<Rule> rules = rules(button.get("enableCondition"));
            if (service == null || rules == null) continue;
            List<PropertyDef> params = service.getParamsSchema();
            if (button.get("paramsForm") instanceof List<?> form && !form.isEmpty()) {
                params = form.stream().map(field -> json.convertValue(field, PropertyDef.class)).toList();
            }
            Map<String, PropertyDef> input = new LinkedHashMap<>();
            for (PropertyDef field : params) {
                if (field.getName() != null && !RESERVED.contains(field.getName()) && field.getDataType() != null
                        && FIELD_TYPES.contains(field.getDataType())) input.put(field.getName(), field);
            }
            if (input.size() != params.size()) continue;
            String label = Objects.toString(button.get("name"), service.getDisplayName());
            String confirm = button.get("confirm") instanceof String text ? text : null;
            Operation prior = operations.get(key);
            List<List<Rule>> alternatives = new ArrayList<>(prior == null ? List.of() : prior.ruleAlternatives());
            alternatives.add(rules);
            operations.put(key, new Operation(key, label, input, alternatives, confirm));
        }
    }

    private List<Rule> rules(Object expression) {
        if (expression == null || expression.toString().isBlank()) return List.of();
        List<Rule> result = new ArrayList<>();
        for (String clause : expression.toString().split("&&")) {
            var equals = EQUALS.matcher(clause.strip());
            var includes = INCLUDES.matcher(clause.strip());
            var bool = BOOLEAN.matcher(clause.strip());
            if (equals.matches()) result.add(new Rule(equals.group(1), List.of(equals.group(2)), null));
            else if (includes.matches()) {
                List<String> values = Pattern.compile("'([^']*)'").matcher(includes.group(1)).results().map(match -> match.group(1)).toList();
                result.add(new Rule(includes.group(2), values, null));
            } else if (bool.matches()) result.add(new Rule(bool.group(2), List.of(), bool.group(1).isEmpty()));
            else return null;
        }
        return result;
    }

    private Set<String> workflowFields(UiViewDef grid) {
        Set<String> output = new LinkedHashSet<>();
        if (grid == null || !(grid.getBody().get("buttons") instanceof List<?> buttons)) return output;
        for (Object item : buttons) {
            if (item instanceof Map<?, ?> button && "custom".equals(button.get("action"))) {
                List<Rule> rules = rules(button.get("enableCondition"));
                if (rules != null) rules.forEach(rule -> output.add(rule.field()));
            }
        }
        return output;
    }

    private List<String> columns(UiViewDef view, boolean editable) {
        if (view == null) return List.of();
        Set<String> result = new LinkedHashSet<>();
        if (view.getBody().get("columns") instanceof List<?> columns) {
            for (Object item : columns) {
                if (item instanceof Map<?, ?> column && column.get("name") instanceof String name
                        && !Boolean.TRUE.equals(column.get("hidden"))
                        && (!editable || (!Boolean.TRUE.equals(column.get("detailOnly")) && !Boolean.TRUE.equals(column.get("readonly"))))) {
                    result.add(name);
                }
            }
        }
        return List.copyOf(result);
    }

    private Operation requireOperation(Page page, String key) {
        Operation operation = page.operations().get(key);
        if (operation == null) throw DomainException.permissionDenied("当前页面没有该操作，或你的账号没有执行权限。");
        return operation;
    }

    static boolean isQuery(String operation) {
        return "count".equals(operation) || "search".equals(operation);
    }

    private static boolean needsValue(PropertyDef field) {
        return field.isRequired() && field.getDefaultValue() == null && field.getDefaultMethod() == null && field.getCodeRule() == null;
    }

    private record Rule(String field, List<String> values, Boolean truthy) {
        boolean matches(RecordData row) {
            if (truthy != null) return truthy.equals(row.getBool(field));
            Object value = row.get(field);
            if (value instanceof Map<?, ?> option) value = option.get("value");
            return values.contains(Objects.toString(value, ""));
        }
    }
    private record Search(List<RecordData> rows, long total) {}
    private record Reference(String id, String label) {}
    private record Page(Menu menu, String version, List<String> gridColumns, Map<String, PropertyDef> filters,
                        Map<String, Operation> operations) {}
    record Menu(String key, String label, String app, String model, String path) {
        PageLink link() { return new PageLink(label, path); }
    }
    private record Operation(String key, String label, Map<String, PropertyDef> fields, List<List<Rule>> ruleAlternatives, String confirm) {
        static Operation builtin(String key, String label, Map<String, PropertyDef> fields) {
            return new Operation(key, label, fields, List.of(List.of()), null);
        }

        List<Rule> rules() { return ruleAlternatives.stream().flatMap(List::stream).toList(); }

        OperationChoice choice() {
            return new OperationChoice(key, label, fields.values().stream().map(FieldChoice::of).toList());
        }
    }
    public record OptionChoice(String value, String label) {}
    public record FieldChoice(String key, String label, String type, boolean required, List<OptionChoice> options) {
        static FieldChoice of(PropertyDef field) {
            return new FieldChoice(field.getName(), field.getDisplayName(), field.getDataType(), needsValue(field),
                    field.getValues().stream().limit(40).map(value -> new OptionChoice(value.getValue(), value.getLabel())).toList());
        }
    }
    public record OperationChoice(String key, String label, List<FieldChoice> fields) {}
    public record PageChoice(String menu, String label, String app, String appLabel, List<FieldChoice> columns,
                             List<OperationChoice> operations) {}
    public record Preview(String operation, String page, Map<String, String> details, PageLink link, String confirmText) {}
    public record Result(String page, long total, List<Map<String, String>> rows, PageLink link) {}
    public record Plan(Menu menu, String pageVersion, String operation, String id, Integer version,
                       Map<String, Object> values, Preview preview) {}
}
