package com.edu.scnu.application;

import com.edu.scnu.application.AssistantPlanner.ExistingChange;
import com.edu.scnu.application.AssistantPlanner.ExistingTarget;
import com.edu.scnu.application.AssistantPlatformActions.PageLink;
import com.edu.scnu.domain.metamodel.model.AppAggregate;
import com.edu.scnu.domain.metamodel.model.ModelAggregate;
import com.edu.scnu.domain.metamodel.model.ModelSnapshot;
import com.edu.scnu.domain.metamodel.model.ModelVersion;
import com.edu.scnu.domain.metamodel.model.PropertyDef;
import com.edu.scnu.domain.metamodel.repository.ModelRepository;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.uischema.MenuNode;
import com.edu.scnu.domain.uischema.UiSchemaRepository;
import com.edu.scnu.domain.uischema.UiViewDef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

@Service
public class AssistantExistingAppActions {
    private static final Pattern SEMVER = Pattern.compile("([0-9]+\\.[0-9]+\\.)([0-9]+)");
    private static final Set<String> EDITED_VIEWS = Set.of("form", "grid", "search");
    private final AssistantPlatformActions platform;
    private final ModelRepository models;
    private final UiSchemaRepository ui;
    private final ObjectMapper json;

    public AssistantExistingAppActions(AssistantPlatformActions platform, ModelRepository models, UiSchemaRepository ui, ObjectMapper json) {
        this.platform = platform;
        this.models = models;
        this.ui = ui;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class)
    public SavedEdit stage(String authorization, ExistingChange change, ExistingTarget reference, SavedEdit previous) {
        String appQuery = suppliedOr(change.appQuery(), reference == null ? null : reference.appKey());
        if (appQuery == null) throw new Clarification("请给出要修改的应用名称。");
        AppAggregate app = choose(availableApps(authorization), appQuery, AppAggregate::getName, AppAggregate::getDisplayName, "应用");
        platform.call(authorization, "meta_model", "listModels", Map.of());
        List<ModelAggregate> candidates = models.findModelsByApp(app.getName()).stream()
                .filter(model -> model.isAvailableForRuntime() && !model.isKernelModel()).toList();
        String defaultTable = reference != null && app.getName().equals(reference.appKey()) ? reference.tableKey() : null;
        String tableQuery = suppliedOr(change.tableQuery(), defaultTable);
        if (tableQuery == null && candidates.size() != 1) {
            if (candidates.isEmpty()) throw new Clarification("「" + app.getDisplayName() + "」没有可修改的已上线数据表。");
            throw new Clarification("「" + app.getDisplayName() + "」有多张数据表。请选择：" + names(candidates, ModelAggregate::getDisplayName) + "。");
        }
        ModelAggregate model = tableQuery == null ? candidates.get(0)
                : choose(candidates, tableQuery, ModelAggregate::getName, ModelAggregate::getDisplayName, "数据表");
        ExistingTarget target = new ExistingTarget(app.getName(), app.getDisplayName(), model.getName(), model.getDisplayName());
        ModelVersion base = models.findVersionById(model.getActiveVersionId()).orElseThrow(this::stale);
        ModelVersion working = base;
        if (previous != null) {
            if (!previous.preview().target().tableKey().equals(model.getName())) {
                throw new Clarification("当前会话还有一张表的待发布草稿。请发布它，或开启新会话修改另一张表。");
            }
            working = verify(previous);
        }
        ModelSnapshot snapshot = json.readValue(working.getSnapshotJson(), ModelSnapshot.class);
        if (snapshot.isAbstractModel() || snapshot.isExtensionPackage()) {
            throw DomainException.invalidParams("请选择可直接使用的业务数据表。");
        }
        applyEdits(change, candidates, snapshot);
        List<FieldChange> changes = summarize(base.getSnapshot(), snapshot);
        if (changes.isEmpty()) throw new Clarification("已找到「" + target.tableName() + "」。请说明要新增或更名的字段。");
        String version = previous != null ? working.getVersionNo() : nextVersion(base, models.findVersionsByModel(model.getName()));
        snapshot.setVersionNo(version);
        ModelVersion saved = (ModelVersion) platform.call(authorization, "meta_model", "saveDraft", Map.of("snapshot", snapshot));
        return new SavedEdit(base.getId(), saved.getId(), saved.getSnapshotJson(), saved.getPendingUiJson(),
                new EditPreview(target, base.getVersionNo(), version, changes));
    }

    @Transactional(rollbackFor = Exception.class)
    public List<PageLink> publish(String authorization, SavedEdit edit) {
        verify(edit);
        ExistingTarget target = edit.preview().target();
        platform.reloadRegistryOnRollback();
        platform.publishVersion(authorization, target.tableKey(), edit.versionId());
        syncViewColumns(authorization, target.tableKey(), edit.preview().changes());
        return ui.findAllMenus().stream()
                .filter(menu -> menu.isActive() && target.appKey().equals(menu.getAppName()) && target.tableKey().equals(menu.getModel()))
                .map(menu -> new PageLink(menu.getDisplayName(), menuPath(menu))).toList();
    }

    private void applyEdits(ExistingChange change, List<ModelAggregate> candidates, ModelSnapshot snapshot) {
        List<AssistantAppDesign.Field> added = change.addFields() == null ? List.of() : change.addFields();
        List<AssistantPlanner.FieldRename> renamed = change.renameFields() == null ? List.of() : change.renameFields();
        if (added.size() + renamed.size() > 24) throw DomainException.invalidParams("每次最多修改 24 个字段。");
        Map<String, String> relatedTargets = new LinkedHashMap<>();
        for (ModelAggregate table : candidates) relatedTargets.put(table.getName(), table.getName());
        for (AssistantAppDesign.Field field : added) {
            if (field != null && "ManyToOne".equals(field.type())) {
                ModelAggregate target = choose(candidates, field.relatedTable(), ModelAggregate::getName, ModelAggregate::getDisplayName, "关联表");
                relatedTargets.put(field.relatedTable(), target.getName());
            }
            PropertyDef property = json.convertValue(AssistantAppDesign.property(field, relatedTargets), PropertyDef.class);
            if (snapshot.getProperties().stream().anyMatch(existing -> equalsName(existing.getName(), property.getName())
                    || equalsName(existing.getDisplayName(), property.getDisplayName()))) {
                throw DomainException.invalidParams("已存在同名字段「" + property.getDisplayName() + "」。");
            }
            snapshot.getProperties().add(property);
        }
        for (AssistantPlanner.FieldRename changeLabel : renamed) {
            if (changeLabel == null || changeLabel.displayName() == null || changeLabel.displayName().isBlank()
                    || changeLabel.displayName().length() > 60) throw DomainException.invalidParams("字段显示名称须为 1 到 60 字。");
            PropertyDef property = choose(snapshot.getProperties(), changeLabel.fieldQuery(), PropertyDef::getName, PropertyDef::getDisplayName, "字段");
            property.setDisplayName(changeLabel.displayName().strip());
        }
    }

    private List<FieldChange> summarize(ModelSnapshot base, ModelSnapshot working) {
        List<FieldChange> changes = new ArrayList<>();
        for (PropertyDef field : working.getProperties()) {
            PropertyDef prior = base.getProperty(field.getName());
            if (prior == null || !Objects.equals(prior.getDisplayName(), field.getDisplayName())) {
                changes.add(new FieldChange(field.getName(), field.getDisplayName(), field.getDataType(),
                        field.isRequired(), prior == null ? null : prior.getDisplayName()));
            }
        }
        return List.copyOf(changes);
    }

    @SuppressWarnings("unchecked")
    private void syncViewColumns(String authorization, String model, List<FieldChange> changes) {
        List<UiViewDef> views = (List<UiViewDef>) platform.call(authorization, "meta_view", "listViews", Map.of("modelName", model));
        for (UiViewDef view : views) {
            if (!view.isActive() || !"primary".equalsIgnoreCase(view.getMode()) || !EDITED_VIEWS.contains(view.getType())
                    || !(view.getBody().get("columns") instanceof List<?> existing)) continue;
            List<Object> columns = new ArrayList<>(existing);
            boolean changed = false;
            for (FieldChange field : changes) {
                int slot = -1;
                for (int i = 0; i < columns.size(); i++) {
                    if (columns.get(i) instanceof Map<?, ?> column && field.key().equals(column.get("name"))) {
                        slot = i;
                        break;
                    }
                }
                if (slot >= 0 && field.previousLabel() != null && columns.get(slot) instanceof Map<?, ?> column
                        && Objects.equals(column.get("displayName"), field.previousLabel())) {
                    Map<Object, Object> renamed = new LinkedHashMap<>(column);
                    renamed.put("displayName", field.label());
                    columns.set(slot, renamed);
                    changed = true;
                } else if (slot < 0 && field.previousLabel() == null && !("search".equals(view.getType()) && "Text".equals(field.type()))) {
                    columns.add(Map.of("name", field.key(), "displayName", field.label()));
                    changed = true;
                }
            }
            if (changed) {
                Map<String, Object> body = new LinkedHashMap<>(view.getBody());
                body.put("columns", columns);
                view.setBody(body);
                platform.call(authorization, "meta_view", "saveView", Map.of("view", view));
            }
        }
    }

    private ModelVersion verify(SavedEdit edit) {
        ExistingTarget target = edit.preview().target();
        ModelAggregate model = models.findModelByName(target.tableKey())
                .filter(m -> m.isAvailableForRuntime() && target.appKey().equals(m.getAppName())
                        && edit.baseId().equals(m.getActiveVersionId())).orElseThrow(this::stale);
        return models.findVersionById(edit.versionId())
                .filter(v -> model.getName().equals(v.getModelName()) && !v.isImmutable()
                        && Objects.equals(v.getSnapshotJson(), edit.snapshotJson())
                        && Objects.equals(v.getPendingUiJson(), edit.pendingUiJson())).orElseThrow(this::stale);
    }

    private String nextVersion(ModelVersion base, List<ModelVersion> versions) {
        var current = SEMVER.matcher(base.getVersionNo());
        if (!current.matches()) throw DomainException.invalidParams("当前版本号不是常见的「主版本.次版本.修订号」，请在管理中心指定版本号。");
        String prefix = current.group(1);
        BigInteger patch = new BigInteger(current.group(2));
        for (ModelVersion version : versions) {
            var candidate = SEMVER.matcher(version.getVersionNo());
            if (candidate.matches() && prefix.equals(candidate.group(1))) patch = patch.max(new BigInteger(candidate.group(2)));
        }
        return prefix + patch.add(BigInteger.ONE);
    }

    @SuppressWarnings("unchecked")
    private List<AppAggregate> availableApps(String authorization) {
        return ((List<AppAggregate>) platform.call(authorization, "meta_app", "list", Map.of())).stream()
                .filter(app -> !"platform_kernel".equals(app.getName())).toList();
    }

    private <T> T choose(List<T> candidates, String query, Function<T, String> key, Function<T, String> label, String kind) {
        if (query == null || query.isBlank()) throw new Clarification("请指定" + kind + "名称。");
        String exact = query.strip();
        List<T> matches = candidates.stream().filter(value -> equalsName(key.apply(value), exact) || equalsName(label.apply(value), exact)).toList();
        if (matches.isEmpty()) throw new Clarification("当前找不到「" + exact + "」" + kind + "。请确认名称或提供标识。");
        if (matches.size() > 1) throw new Clarification("找到多个同名" + kind + "，请提供标识：" + names(matches, key) + "。");
        return matches.get(0);
    }

    private static boolean equalsName(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private <T> String names(List<T> candidates, Function<T, String> label) {
        return String.join("、", candidates.stream().map(label).toList());
    }

    private String suppliedOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private DomainException stale() {
        return DomainException.concurrentConflict("活动版本或草稿已被修改。请核对版本后开启新会话再试。");
    }

    private String menuPath(MenuNode menu) {
        return "/menu/" + URLEncoder.encode(menu.getName(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record FieldChange(String key, String label, String type, boolean required, String previousLabel) {}
    public record EditPreview(ExistingTarget target, String baseVersion, String newVersion, List<FieldChange> changes) {}
    public record SavedEdit(String baseId, String versionId, String snapshotJson, String pendingUiJson, EditPreview preview) {}

    public static final class Clarification extends RuntimeException {
        Clarification(String message) { super(message); }
    }
}
