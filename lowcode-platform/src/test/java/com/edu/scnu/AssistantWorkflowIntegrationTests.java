package com.edu.scnu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class AssistantWorkflowIntegrationTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer deepSeek;
    @Autowired
    private RestTestClient client;

    @DynamicPropertySource
    static void options(DynamicPropertyRegistry registry) throws IOException {
        deepSeek = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        deepSeek.createContext("/chat/completions", AssistantWorkflowIntegrationTests::simulatePlanner);
        deepSeek.start();
        var db = Files.createTempDirectory("assistant-flow-").resolve("assistant.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
        registry.add("lowcode.assistant.deepseek-key", () -> "integration-test-key");
        registry.add("lowcode.assistant.deepseek-base-url", () -> "http://127.0.0.1:" + deepSeek.getAddress().getPort());
    }

    @AfterAll
    static void stopProvider() {
        if (deepSeek != null) deepSeek.stop(0);
    }

    @Test
    void remembersDraftEditsThenPublishesAndGrantsOnlyAfterConfirmation() {
        String admin = login("admin");
        rpc(admin, "rbac_tenant", "saveTenant", Map.of("values",
                Map.of("id", "workshop", "code", "workshop", "name", "一车间")));
        rpc(admin, "meta_app", "save", Map.of("app", Map.of("name", "existing_app", "displayName", "已有应用")));
        rpc(admin, "rbac_tenant", "authorizeApps", Map.of("tenantId", "workshop",
                "authorizedApps", List.of("existing_app"), "authorizedMenus", List.of("existing_menu")));
        rpc(admin, "rbac_user", "saveUser", Map.of("values", Map.of("username", "worker",
                "tenantId", "workshop", "password", "123456", "roleCodes", List.of("ROLE_APP_USER"))));
        rpc(admin, "rbac_user", "saveUser", Map.of("values", Map.of("username", "other_admin",
                "tenantId", "rbac_tenant_root", "password", "123456", "roleCodes", List.of("ROLE_ADMIN"))));
        String worker = login("worker");
        String path = "/api/assistant/sessions/" + UUID.randomUUID();
        client.get().uri(path).exchange().expectStatus().isUnauthorized();
        client.get().uri(path).headers(headers -> headers.setBearerAuth(worker)).exchange().expectStatus().isForbidden();

        JsonNode first = post(admin, path + "/messages", Map.of("message", "建一个设备点检应用"), 200);
        String app = first.path("appKey").asText();
        String model = app + "_inspection";
        assertThat(first.path("draft").path("tables").size()).isEqualTo(2);
        assertThat(appState(admin, app)).isEqualTo("DRAFT");
        assertThat(rpc(worker, "ui_menu", "loadMenu", Map.of()).toString()).doesNotContain(model + "_menu");
        JsonNode anotherUser = get(login("other_admin"), path);
        assertThat(anotherUser.path("draft").isNull()).isTrue();
        assertThat(anotherUser.path("turns").size()).isZero();

        JsonNode revised = post(admin, path + "/messages", Map.of("message", "再加备注"), 200);
        assertThat(revised.path("appKey").asText()).isEqualTo(app);
        assertThat(revised.path("draft").path("tables").get(1).path("fields").size()).isEqualTo(3);
        assertThat(revised.path("turns").size()).isEqualTo(4);
        JsonNode selection = post(admin, path + "/tenant", Map.of("tenantId", "workshop"), 200);
        post(admin, path + "/publish", Map.of("revision", revised.path("revision").asText()), 409);
        assertThat(appState(admin, app)).isEqualTo("DRAFT");
        assertThat(grantedValues(admin, "authorizedApps")).doesNotContain(app);

        Map<String, String> confirmation = Map.of("revision", selection.path("revision").asText());
        JsonNode release = post(admin, path + "/publish", confirmation, 200);
        assertThat(release.path("published").asBoolean()).isTrue();
        assertThat(release.path("links").size()).isEqualTo(2);
        assertThat(appState(admin, app)).isEqualTo("INSTALLED");
        assertThat(grantedValues(admin, "authorizedApps")).contains("existing_app", app);
        assertThat(grantedValues(admin, "authorizedMenus")).contains("existing_menu", model + "_menu");

        JsonNode menus = rpc(worker, "ui_menu", "loadMenu", Map.of());
        assertThat(menus.toString()).contains("/menu/" + model + "_menu");
        JsonNode view = rpc(worker, "ui_view", "loadView", Map.of("menu", model + "_menu"));
        assertThat(view.path("views").path("form").toString()).contains("notes");
        String deviceId = rpc(worker, app + "_device", "create", Map.of("values", Map.of("name", "一号机"))).path("id").asText();
        String rowId = rpc(worker, model, "create", Map.of("values",
                Map.of("device", deviceId, "date", "2026-11-27", "notes", "首次点检"))).path("id").asText();
        assertThat(rowId).isNotBlank();
        rpc(worker, model, "update", Map.of("id", rowId, "version", 1, "values", Map.of("notes", "复查完成")));
        assertThat(rpc(worker, model, "search", Map.of()).toString()).contains("复查完成");
        assertThat(post(admin, path + "/publish", confirmation, 200).path("published").asBoolean()).isTrue();
        assertThat(rpc(admin, "meta_model", "listPublishRecords", Map.of("modelName", model)).size()).isEqualTo(1);
    }

    @Test
    void findsPublishedApplicationAndReleasesAnAdditiveVersion() {
        String admin = login("admin");
        String app = "published_inspection";
        String model = "published_inspection_record";
        var appPackage = Map.of("app", Map.of("name", app, "displayName", "设备点检"),
                "models", List.of(Map.of("name", model, "displayName", "点检记录", "properties", List.of(
                        Map.of("name", "device_name", "displayName", "设备名称", "dataType", "String", "required", true)))));
        rpc(admin, "meta_app", "importPackage", Map.of("package", appPackage, "autoPublish", true));
        rpc(admin, "rbac_tenant", "authorizeApps", Map.of("tenantId", "rbac_tenant_root",
                "authorizedApps", List.of(app), "authorizedMenus", List.of(model + "_menu")));
        String oldId = rpc(admin, model, "create", Map.of("values", Map.of("device_name", "原有设备"))).path("id").asText();
        String path = "/api/assistant/sessions/" + UUID.randomUUID();

        JsonNode planned = post(admin, path + "/messages", Map.of("message", "对已有设备点检应用加个点检人字段"), 200);
        JsonNode edit = planned.path("edit");
        assertThat(edit.isObject()).withFailMessage("Existing application edit was not staged: %s", planned).isTrue();
        assertThat(planned.path("draft").isNull()).isTrue();
        assertThat(edit.path("target").path("tableKey").asText()).isEqualTo(model);
        assertThat(edit.path("baseVersion").asText()).isEqualTo("1.0.0");
        assertThat(edit.path("newVersion").asText()).isEqualTo("1.0.1");
        assertThat(edit.path("changes").get(0).path("label").asText()).isEqualTo("点检人");
        assertThat(rpc(admin, "meta_model", "listPublishRecords", Map.of("modelName", model)).size()).isEqualTo(1);

        JsonNode revised = post(admin, path + "/messages", Map.of("message", "把点检人的显示名称改成点检人员"), 200);
        assertThat(revised.path("edit").path("newVersion").asText()).isEqualTo("1.0.1");
        assertThat(revised.path("edit").path("changes").get(0).path("label").asText()).isEqualTo("点检人员");
        post(admin, path + "/publish", Map.of("revision", planned.path("revision").asText()), 409);
        JsonNode published = post(admin, path + "/publish", Map.of("revision", revised.path("revision").asText()), 200);
        assertThat(published.path("published").asBoolean()).isTrue();
        assertThat(published.path("links").toString()).contains("/menu/" + model + "_menu");
        assertThat(rpc(admin, "meta_model", "listPublishRecords", Map.of("modelName", model)).size()).isEqualTo(2);

        JsonNode views = rpc(admin, "ui_view", "loadView", Map.of("menu", model + "_menu")).path("views");
        assertThat(views.path("form").toString()).contains("点检人员", "设备名称");
        assertThat(views.path("grid").toString()).contains("点检人员");
        assertThat(rpc(admin, model, "search", Map.of()).toString()).contains(oldId, "原有设备");
        rpc(admin, model, "create", Map.of("values", Map.of("device_name", "新增设备", "inspector", "张三")));
        assertThat(rpc(admin, model, "search", Map.of()).toString()).contains("新增设备", "张三");
        for (JsonNode tenant : rpc(admin, "rbac_tenant", "listTenants", Map.of())) {
            if ("rbac_tenant_root".equals(tenant.path("id").asText())) {
                assertThat(tenant.path("authorizedApps").toString()).isEqualTo("[\"published_inspection\"]");
            }
        }
        rpc(admin, "rbac_tenant", "authorizeApps", Map.of("tenantId", "rbac_tenant_root",
                "authorizedApps", List.of("*"), "authorizedMenus", List.of()));
    }

    @Test
    void validatesMetadataAtTheRpcBoundary() {
        String admin = login("admin");
        Map<String, Object> field = Map.of("name", "title", "displayName", "标题", "dataType", "String");
        JsonNode invalidVersion = rpcResponse(admin, "meta_model", "saveDraft", Map.of("snapshot", Map.of(
                "name", "duty_log", "displayName", "工作台账", "versionNo", "-1", "properties", List.of(field))));
        assertThat(invalidVersion.path("error").toString()).contains("版本号");
        JsonNode invalidIdentifier = rpcResponse(admin, "meta_model", "saveDraft", Map.of("snapshot", Map.of(
                "name", "fuck", "displayName", "工作台账", "versionNo", "1.0.0", "properties", List.of(field))));
        assertThat(invalidIdentifier.path("error").toString()).contains("模型标识", "不适当用词");
        JsonNode invalidTenantCode = rpcResponse(admin, "rbac_tenant", "saveTenant", Map.of("values",
                Map.of("code", "1".repeat(70), "name", "一车间")));
        assertThat(invalidTenantCode.path("error").toString()).contains("租户编码");
        JsonNode invalidTenantName = rpcResponse(admin, "rbac_tenant", "saveTenant", Map.of("values",
                Map.of("code", "workshop_test", "name", "车".repeat(41))));
        assertThat(invalidTenantName.path("error").toString()).contains("租户名称");
    }

    @Test
    void confirmsTenantCreationAndLetsSupervisorsConfigureScopedRoles() {
        String admin = login("admin");
        String path = "/api/assistant/sessions/" + UUID.randomUUID();
        String tenant = "assistant_workshop_qa";
        JsonNode proposed = post(admin, path + "/messages", Map.of("message", "测试创建云枢演示租户"), 200);
        assertThat(proposed.path("iam").path("code").asText()).isEqualTo(tenant);
        assertThat(proposed.path("iamApplied").asBoolean()).isFalse();
        assertThat(rpc(admin, "rbac_tenant", "listTenants", Map.of()).toString()).doesNotContain(tenant);
        post(admin, path + "/iam/confirm", Map.of("revision", UUID.randomUUID().toString()), 409);
        Map<String, String> confirmation = Map.of("revision", proposed.path("iamRevision").asText());
        assertThat(post(admin, path + "/iam/confirm", confirmation, 200).path("iamApplied").asBoolean()).isTrue();
        assertThat(post(admin, path + "/iam/confirm", confirmation, 200).path("iamApplied").asBoolean()).isTrue();

        String app = "assistant_role_app";
        String model = "assistant_role_records";
        var appPackage = Map.of("app", Map.of("name", app, "displayName", "角色验证"),
                "models", List.of(Map.of("name", model, "displayName", "角色验证记录", "properties", List.of(
                        Map.of("name", "title", "displayName", "标题", "dataType", "String")))),
                "menus", Map.of(model + "_menu", Map.of("displayName", "角色验证记录",
                        "model", model, "view", model + "_grid")));
        rpc(admin, "meta_app", "importPackage", Map.of("package", appPackage, "autoPublish", true));
        rpc(admin, "rbac_tenant", "authorizeApps", Map.of("tenantId", tenant,
                "authorizedApps", List.of(app), "authorizedMenus", List.of(model + "_menu")));
        rpc(admin, "rbac_user", "saveUser", Map.of("values", Map.of("username", "assistant_supervisor_qa",
                "tenantId", tenant, "password", "123456", "roleCodes", List.of("ROLE_TENANT_ADMIN"))));
        String supervisor = login("assistant_supervisor_qa");
        String supervisorPath = "/api/assistant/sessions/" + UUID.randomUUID();
        get(supervisor, supervisorPath);
        JsonNode role = post(supervisor, supervisorPath + "/messages", Map.of("message", "测试创建只读巡检员角色"), 200);
        String code = "ROLE_ASSISTANT_INSPECTOR_QA";
        assertThat(role.path("iam").path("services").toString()).contains(model + ":search").doesNotContain(model + ":create");
        assertThat(rpc(supervisor, "rbac_role", "listRoles", Map.of()).toString()).doesNotContain(code);
        assertThat(post(supervisor, supervisorPath + "/iam/confirm", Map.of("revision", role.path("iamRevision").asText()), 200)
                .path("iamApplied").asBoolean()).isTrue();
        assertThat(rpc(supervisor, "rbac_role", "listRoles", Map.of()).toString()).contains(code, "SELF", model + "_menu");
        rpc(admin, "rbac_user", "saveUser", Map.of("values", Map.of("username", "assistant_inspector_qa",
                "tenantId", tenant, "password", "123456", "roleCodes", List.of(code))));
        String inspector = login("assistant_inspector_qa");
        rpc(inspector, model, "search", Map.of("menu", model + "_menu"));
        var newRecord = Map.of("menu", model + "_menu", "values", Map.of("title", "演示记录"));
        assertThat(rpcResponse(inspector, model, "create", newRecord).hasNonNull("error")).isTrue();
        JsonNode increased = post(supervisor, supervisorPath + "/messages", Map.of("message", "测试给巡检员追加新增权限"), 200);
        assertThat(increased.path("iam").path("services").toString()).contains(model + ":search", model + ":create");
        assertThat(post(supervisor, supervisorPath + "/iam/confirm", Map.of("revision", increased.path("iamRevision").asText()), 200)
                .path("iamApplied").asBoolean()).isTrue();
        rpc(inspector, model, "create", newRecord);
    }

    private String appState(String token, String app) {
        for (JsonNode item : rpc(token, "meta_app", "list", Map.of())) {
            if (app.equals(item.path("name").asText())) return item.path("state").asText();
        }
        throw new AssertionError("Application was not found");
    }

    private String grantedValues(String token, String field) {
        for (JsonNode item : rpc(token, "rbac_tenant", "listTenants", Map.of())) {
            if ("workshop".equals(item.path("id").asText())) return item.path(field).toString();
        }
        throw new AssertionError("Tenant was not found");
    }

    private String login(String username) {
        return rpc(null, "rbac_user", "login", Map.of("username", username, "password", "123456")).path("token").asText();
    }

    private JsonNode rpc(String token, String model, String service, Map<String, Object> args) {
        JsonNode response = rpcResponse(token, model, service, args);
        assertThat(response.hasNonNull("error")).withFailMessage("%s.%s returned %s", model, service, response).isFalse();
        return response.path("result");
    }

    private JsonNode rpcResponse(String token, String model, String service, Map<String, Object> args) {
        return post(token, "/rbac_tenant_root/rpc/service", Map.of("jsonrpc", "2.0", "id", "test", "method", "service",
                "params", Map.of("app", "lowcode_studio", "model", model, "service", service, "args", args)), 200);
    }

    private JsonNode post(String token, String path, Object body, int status) {
        byte[] response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> { if (token != null) headers.setBearerAuth(token); }).body(body)
                .exchange().expectStatus().isEqualTo(status).expectBody().returnResult().getResponseBody();
        return JSON.readTree(response);
    }

    private JsonNode get(String token, String path) {
        return JSON.readTree(client.get().uri(path).headers(headers -> headers.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    private static void simulatePlanner(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode call = JSON.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode context = JSON.readTree(call.path("messages").get(1).path("content").asText());
            String request = context.path("request").asText();
            if (request.contains("测试创建云枢演示租户")) {
                respond(exchange, """
                        {"message":"请确认新租户方案。","iam":{"action":"createTenant","name":"云枢演示租户","code":"assistant_workshop_qa"}}
                        """);
                return;
            }
            if (request.contains("测试创建只读巡检员角色")) {
                respond(exchange, """
                        {"message":"请核对岗位角色。","iam":{"action":"configureRole","name":"巡检员","code":"ROLE_ASSISTANT_INSPECTOR_QA",
                        "tenantQuery":"云枢演示租户","access":[{"menuQuery":"角色验证记录","actions":["read"]}]}}
                        """);
                return;
            }
            if (request.contains("测试给巡检员追加新增权限")) {
                respond(exchange, """
                        {"message":"请核对新增权限。","iam":{"action":"configureRole","roleQuery":"巡检员",
                        "tenantQuery":"云枢演示租户","access":[{"menuQuery":"角色验证记录","actions":["create"]}]}}
                        """);
                return;
            }
            if (request.contains("已有设备点检")) {
                respond(exchange, """
                        {"message":"我会在已有应用上新增字段。","edit":{"appQuery":"设备点检",
                        "addFields":[{"key":"inspector","name":"点检人","type":"String","required":false}]}}
                        """);
                return;
            }
            if (request.contains("点检人员")) {
                if (!"published_inspection_record".equals(context.path("existingTarget").path("tableKey").asText())) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                respond(exchange, """
                        {"message":"我会修改显示名称。","edit":{"renameFields":[{"fieldQuery":"点检人","displayName":"点检人员"}]}}
                        """);
                return;
            }
            boolean followUp = request.contains("备注");
            if (followUp && (context.path("recentTurns").size() != 2 || context.path("draft").path("tables").size() != 2)) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            String notes = followUp ? ",{\"key\":\"notes\",\"name\":\"备注\",\"type\":\"Text\",\"required\":false}" : "";
            String content = """
                    {"message":"草稿方案已整理。","draft":{"name":"多轮点检测试","description":"登记点检",
                    "tables":[{"key":"device","name":"设备","fields":[{"key":"name","name":"设备名称","type":"String","required":true}]},
                    {"key":"inspection","name":"点检记录","fields":[
                    {"key":"device","name":"设备","type":"ManyToOne","relatedTable":"device","required":true},
                    {"key":"date","name":"点检日期","type":"Date","required":true}%s]}]},"tenantId":null}
                    """.formatted(notes);
            respond(exchange, content);
        }
    }

    private static void respond(HttpExchange exchange, String content) throws IOException {
        byte[] response = JSON.writeValueAsString(Map.of("id", "stub", "object", "chat.completion", "created", 1,
                "model", "deepseek-flash", "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                "message", Map.of("role", "assistant", "content", content))))).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
    }
}
