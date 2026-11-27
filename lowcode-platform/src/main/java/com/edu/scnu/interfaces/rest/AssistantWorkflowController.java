package com.edu.scnu.interfaces.rest;

import com.edu.scnu.application.AssistantPlanner;
import com.edu.scnu.application.AssistantWorkflow;
import com.edu.scnu.application.IamAppService;
import com.edu.scnu.domain.shared.DomainException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

@RestController
@RequestMapping("/api/assistant/sessions/{sessionId}")
public class AssistantWorkflowController {
    private final IamAppService iam;
    private final AssistantWorkflow workflow;

    public AssistantWorkflowController(IamAppService iam, AssistantWorkflow workflow) {
        this.iam = iam;
        this.workflow = workflow;
    }

    @GetMapping
    public ResponseEntity<?> read(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                  @PathVariable UUID sessionId) {
        return authenticated(authorization, (owner, studio) -> workflow.read(owner, sessionId, authorization));
    }

    @PostMapping("/messages")
    public ResponseEntity<?> ask(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                 @PathVariable UUID sessionId, @RequestBody AskRequest request) {
        return authenticated(authorization, (owner, studio) -> workflow.ask(owner, sessionId, authorization, studio, request.message()));
    }

    @PostMapping("/tenant")
    public ResponseEntity<?> selectTenant(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                          @PathVariable UUID sessionId, @RequestBody TenantRequest request) {
        return authenticated(authorization, (owner, studio) -> {
            requireStudio(studio);
            return workflow.selectTenant(owner, sessionId, authorization, request.tenantId());
        });
    }

    @PostMapping("/publish")
    public ResponseEntity<?> publish(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                     @PathVariable UUID sessionId, @RequestBody PublishRequest request) {
        return authenticated(authorization, (owner, studio) -> {
            requireStudio(studio);
            return workflow.confirm(owner, sessionId, authorization, request.revision());
        });
    }

    @PostMapping("/iam/confirm")
    public ResponseEntity<?> confirmIam(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                        @PathVariable UUID sessionId, @RequestBody PublishRequest request) {
        return authenticated(authorization, (owner, studio) -> workflow.confirmIam(owner, sessionId, authorization, request.revision()));
    }

    private void requireStudio(boolean studio) {
        if (!studio) throw DomainException.permissionDenied("当前账号没有应用管理权限。");
    }

    private ResponseEntity<?> authenticated(String authorization, BiFunction<String, Boolean, AssistantWorkflow.State> operation) {
        var user = iam.authenticate(authorization).orElse(null);
        if (user == null) return error(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录后再使用助手。");
        var profile = iam.buildProfileData(user);
        boolean studio = Boolean.TRUE.equals(profile.get("canAccessStudio"));
        if (!studio && !Boolean.TRUE.equals(profile.get("canManageTenants"))) {
            return error(HttpStatus.FORBIDDEN, "云枢小助手需要应用或租户管理权限。");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(operation.apply(user.getId() + ":" + user.getTenantId(), studio));
        } catch (AssistantPlanner.Unavailable e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (DomainException e) {
            HttpStatus status = switch (e.getErrorKey()) {
                case "ERR_PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
                case "ERR_CONCURRENT_CONFLICT", "ERR_STATE_GUARD_FAILED", "ERR_IMPACT_BREAKING_CHANGE" -> HttpStatus.CONFLICT;
                default -> HttpStatus.BAD_REQUEST;
            };
            String message = e.getDetails().isEmpty() ? e.getMessage() : e.getMessage() + " " + String.join("；", e.getDetails());
            return error(status, message);
        }
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", message));
    }

    public record AskRequest(String message) {}
    public record TenantRequest(String tenantId) {}
    public record PublishRequest(UUID revision) {}
}
