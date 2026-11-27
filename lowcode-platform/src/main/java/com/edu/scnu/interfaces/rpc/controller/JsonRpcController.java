package com.edu.scnu.interfaces.rpc.controller;

import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.interfaces.rpc.chain.RpcDispatcher;
import com.edu.scnu.interfaces.rpc.dto.JsonRpcError;
import com.edu.scnu.interfaces.rpc.dto.JsonRpcRequest;
import com.edu.scnu.interfaces.rpc.dto.JsonRpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 统一 JSON-RPC 2.0 协议接入网关（对标 sie-snest-engine RpcController）：
 * POST /{tenant}/rpc/service | POST /rpc/service | POST /rpc/open/service
 */
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class JsonRpcController {
    private static final Logger log = LoggerFactory.getLogger(JsonRpcController.class);
    private final RpcDispatcher dispatcher;

    public JsonRpcController(RpcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @io.swagger.v3.oas.annotations.Operation(
            summary = "租户隔离 JSON-RPC 2.0 统一调用入口",
            description = "支持通过 method='service' + params:{model,service,args} 或 method='<model>.<service>' 调用所有模型及平台服务。")
    @PostMapping("/{tenant}/rpc/service")
    public JsonRpcResponse tenantService(@PathVariable("tenant") String tenant,
                                         @RequestHeader(value = "Authorization", required = false) String authHeader,
                                         @RequestBody JsonRpcRequest request) {
        return executeChain(tenant, authHeader, false, request);
    }

    @io.swagger.v3.oas.annotations.Operation(
            summary = "默认租户 JSON-RPC 2.0 统一调用入口",
            description = "默认路由至 rbac_tenant_root 租户。")
    @PostMapping("/rpc/service")
    public JsonRpcResponse rootService(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       @RequestBody JsonRpcRequest request) {
        return executeChain("rbac_tenant_root", authHeader, false, request);
    }

    @io.swagger.v3.oas.annotations.Operation(
            summary = "匿名开放 JSON-RPC 2.0 入口",
            description = "仅放行 rbac_user.login、auth.login、sys_portal.publicApps 匿名服务。")
    @PostMapping("/rpc/open/service")
    public JsonRpcResponse openService(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       @RequestBody JsonRpcRequest request) {
        return executeChain("rbac_tenant_root", authHeader, true, request);
    }

    @io.swagger.v3.oas.annotations.Hidden
    @PostMapping("/{tenant}/rpc/service/{model}/{service}")
    public JsonRpcResponse tenantModelService(@PathVariable("tenant") String tenant,
                                              @PathVariable("model") String model,
                                              @PathVariable("service") String service,
                                              @RequestHeader(value = "Authorization", required = false) String authHeader,
                                              @RequestBody(required = false) Map<String, Object> rawBody) {
        return executeChain(tenant, authHeader, false, toPerServiceRequest(model, service, rawBody));
    }

    @io.swagger.v3.oas.annotations.Hidden
    @PostMapping("/rpc/service/{model}/{service}")
    public JsonRpcResponse rootModelService(@PathVariable("model") String model,
                                            @PathVariable("service") String service,
                                            @RequestHeader(value = "Authorization", required = false) String authHeader,
                                            @RequestBody(required = false) Map<String, Object> rawBody) {
        return executeChain("rbac_tenant_root", authHeader, false, toPerServiceRequest(model, service, rawBody));
    }

    @io.swagger.v3.oas.annotations.Hidden
    @PostMapping("/rpc/open/service/{model}/{service}")
    public JsonRpcResponse openModelService(@PathVariable("model") String model,
                                            @PathVariable("service") String service,
                                            @RequestHeader(value = "Authorization", required = false) String authHeader,
                                            @RequestBody(required = false) Map<String, Object> rawBody) {
        return executeChain("rbac_tenant_root", authHeader, true, toPerServiceRequest(model, service, rawBody));
    }

    @SuppressWarnings("unchecked")
    private JsonRpcRequest toPerServiceRequest(String model, String service, Map<String, Object> rawBody) {
        JsonRpcRequest req = new JsonRpcRequest();
        req.setJsonrpc("2.0");
        req.setMethod("service");

        if (rawBody == null || rawBody.isEmpty()) {
            req.setId(java.util.UUID.randomUUID().toString());
            java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("app", "openapi");
            params.put("tag", "master");
            params.put("model", model);
            params.put("service", service);
            params.put("args", java.util.Collections.emptyMap());
            req.setParams(params);
            return req;
        }

        Object idVal = rawBody.get("id");
        req.setId(idVal != null ? idVal : java.util.UUID.randomUUID().toString());

        boolean hasEnvelope = rawBody.containsKey("jsonrpc") || rawBody.containsKey("method")
                || (rawBody.get("params") instanceof Map);
        if (hasEnvelope) {
            java.util.Map<String, Object> incomingParams = rawBody.get("params") instanceof Map
                    ? new java.util.LinkedHashMap<>((Map<String, Object>) rawBody.get("params"))
                    : new java.util.LinkedHashMap<>();
            incomingParams.put("model", model);
            incomingParams.put("service", service);
            incomingParams.putIfAbsent("app", "openapi");
            incomingParams.putIfAbsent("tag", "master");
            req.setParams(incomingParams);
            return req;
        }

        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("app", rawBody.getOrDefault("app", "openapi"));
        params.put("tag", rawBody.getOrDefault("tag", "master"));
        params.put("model", model);
        params.put("service", service);
        if (rawBody.get("context") instanceof Map) {
            params.put("context", rawBody.get("context"));
        }
        Object argsObj = rawBody.get("args") instanceof Map ? rawBody.get("args") : rawBody;
        params.put("args", argsObj);
        req.setParams(params);
        return req;
    }

    @io.swagger.v3.oas.annotations.Operation(
            summary = "服务健康检查",
            description = "返回平台引擎与协议健康状态。")
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "engine", "sie-lowcode-ddd", "protocol", "JSON-RPC 2.0", "openapi", "3.0.3");
    }

    private JsonRpcResponse executeChain(String tenant, String authHeader, boolean openEndpoint, JsonRpcRequest request) {
        Object reqId = request != null ? request.getId() : null;
        try {
            Object result = dispatcher.execute(tenant, authHeader, openEndpoint, request);
            return JsonRpcResponse.ok(result, reqId);
        } catch (DomainException de) {
            log.warn("RPC DomainException [{}]: {}", de.getErrorKey(), de.getMessage());
            return JsonRpcResponse.fail(
                    new JsonRpcError(de.getRpcCode(), de.getMessage(), de.getErrorKey(), de.getDetails()),
                    reqId);
        } catch (org.springframework.dao.DataAccessException dae) {
            String msg = dae.getMostSpecificCause() != null ? dae.getMostSpecificCause().getMessage() : dae.getMessage();
            if (msg != null && (msg.contains("UNIQUE") || msg.contains("CONSTRAINT") || msg.contains("NOT NULL"))) {
                log.warn("RPC Constraint Violation: {}", msg);
                return JsonRpcResponse.fail(
                        new JsonRpcError(-32602, "唯一性或数据完整性约束冲突: " + msg, "ERR_VALIDATION_FAILED", null),
                        reqId);
            }
            log.error("RPC DataAccess Error: ", dae);
            return JsonRpcResponse.fail(
                    new JsonRpcError(-32603, "数据库访问异常: " + msg, "ERR_INTERNAL", null),
                    reqId);
        } catch (Exception ex) {
            log.error("RPC Internal Error: ", ex);
            return JsonRpcResponse.fail(
                    new JsonRpcError(-32603, "系统内部异常: " + ex.getMessage(), "ERR_INTERNAL", null),
                    reqId);
        }
    }

}
