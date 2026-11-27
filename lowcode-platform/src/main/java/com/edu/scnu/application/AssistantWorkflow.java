package com.edu.scnu.application;

import com.edu.scnu.application.AssistantPlanner.TenantChoice;
import com.edu.scnu.application.AssistantPlanner.Turn;
import com.edu.scnu.application.AssistantPlanner.ExistingTarget;
import com.edu.scnu.application.AssistantExistingAppActions.EditPreview;
import com.edu.scnu.application.AssistantExistingAppActions.SavedEdit;
import com.edu.scnu.application.AssistantPlatformActions.PageLink;
import com.edu.scnu.application.AssistantPlatformActions.SavedDraft;
import com.edu.scnu.domain.shared.DomainException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

@Service
public class AssistantWorkflow {
    private static final int MAX_SESSIONS = 120;
    private static final int MAX_TURNS = 20;
    private static final long SESSION_TTL = Duration.ofHours(2).toMillis();
    private final Map<SessionKey, Session> sessions = new HashMap<>();
    private final AssistantPlanner planner;
    private final AssistantPlatformActions actions;
    private final AssistantExistingAppActions existingApps;
    private final AssistantIamActions iamActions;

    public AssistantWorkflow(AssistantPlanner planner, AssistantPlatformActions actions, AssistantExistingAppActions existingApps,
                             AssistantIamActions iamActions) {
        this.planner = planner;
        this.actions = actions;
        this.existingApps = existingApps;
        this.iamActions = iamActions;
    }

    public State read(String owner, UUID sessionId, String authorization) {
        return withSession(owner, sessionId, session -> state(session, tenantChoices(authorization)));
    }

    public State ask(String owner, UUID sessionId, String authorization, boolean studioAccess, String request) {
        if (request == null || request.isBlank() || request.length() > 1200) {
            throw DomainException.invalidParams("请输入 1 到 1200 字的应用需求。");
        }
        return withSession(owner, sessionId, session -> {
            List<TenantChoice> tenants = tenantChoices(authorization);
            AssistantAppDesign draft = session.draft != null && !session.published ? session.draft.design() : null;
            ExistingTarget target = existingTarget(session);
            var context = new AssistantPlanner.Context(request.strip(), List.copyOf(session.turns), draft,
                    session.tenantId, tenants, target, session.iamApplied ? null : session.iamRequest, studioAccess);
            var decision = planner.plan(context);
            String message = decision.message();
            if (decision.iam() != null) {
                session.iamPlan = iamActions.prepare(authorization, decision.iam());
                session.iamRequest = decision.iam();
                session.iamRevision = UUID.randomUUID();
                session.iamApplied = false;
            } else if (!studioAccess && (decision.edit() != null || decision.draft() != null)) {
                throw DomainException.permissionDenied("当前账号没有应用设计权限。");
            } else if (decision.edit() != null) {
                try {
                    session.edit = existingApps.stage(authorization, decision.edit(), target, session.published ? null : session.edit);
                    session.draft = null;
                    session.tenantId = null;
                    session.publishedTenantName = null;
                    session.links = List.of();
                    session.published = false;
                    session.revision = UUID.randomUUID();
                } catch (AssistantExistingAppActions.Clarification e) {
                    message = e.getMessage();
                }
            } else if (session.edit == null && !session.published) {
                if (decision.draft() != null && !decision.draft().equals(draft)) {
                    session.draft = actions.saveDraft(authorization, session.appKey, decision.draft(), session.draft);
                    session.revision = UUID.randomUUID();
                }
                if (findTenant(tenants, decision.tenantId()) != null && !Objects.equals(session.tenantId, decision.tenantId())) {
                    session.tenantId = decision.tenantId();
                    session.revision = UUID.randomUUID();
                }
            } else if (decision.draft() != null) {
                message = "要另建一个应用，请先开启新会话。";
            }
            addTurn(session, new Turn("user", request.strip()));
            if (message != null && !message.isBlank()) {
                String text = message.strip();
                addTurn(session, new Turn("assistant", text.substring(0, Math.min(600, text.length()))));
            }
            return state(session, tenants);
        });
    }

    public State selectTenant(String owner, UUID sessionId, String authorization, String tenantId) {
        return withSession(owner, sessionId, session -> {
            if (session.published || session.edit != null) throw DomainException.stateGuardFailed("已有应用的授权请在管理中心维护。");
            List<TenantChoice> tenants = tenantChoices(authorization);
            if (tenantId != null && findTenant(tenants, tenantId) == null) {
                throw DomainException.permissionDenied("该租户当前不可授权。");
            }
            if (!Objects.equals(session.tenantId, tenantId)) {
                session.tenantId = tenantId;
                session.revision = UUID.randomUUID();
            }
            return state(session, tenants);
        });
    }

    public State confirm(String owner, UUID sessionId, String authorization, UUID revision) {
        return withSession(owner, sessionId, session -> {
            if (revision == null || !revision.equals(session.revision) || (session.draft == null && session.edit == null)) {
                throw DomainException.concurrentConflict("草稿或授权对象已变化，请重新核对上线预览。");
            }
            List<TenantChoice> tenants = tenantChoices(authorization);
            if (!session.published) {
                if (session.edit != null) {
                    session.links = existingApps.publish(authorization, session.edit);
                } else {
                    TenantChoice tenant = findTenant(tenants, session.tenantId);
                    if (tenant == null) throw DomainException.invalidParams("请选择一个已有租户再确认上线。");
                    session.links = actions.publish(authorization, session.draft, tenant.id());
                    session.publishedTenantName = tenant.name();
                }
                session.published = true;
            }
            return state(session, tenants);
        });
    }

    public State confirmIam(String owner, UUID sessionId, String authorization, UUID revision) {
        return withSession(owner, sessionId, session -> {
            if (revision == null || !revision.equals(session.iamRevision) || session.iamPlan == null) {
                throw DomainException.concurrentConflict("管理方案已变化，请重新核对。");
            }
            if (!session.iamApplied) {
                iamActions.apply(authorization, session.iamRequest, session.iamPlan);
                session.iamApplied = true;
                addTurn(session, new Turn("assistant", "已完成" + session.iamPlan.preview().action() + "「" + session.iamPlan.preview().name() + "」。"));
            }
            return state(session, tenantChoices(authorization));
        });
    }

    private List<TenantChoice> tenantChoices(String authorization) {
        try {
            return actions.listTenants(authorization);
        } catch (DomainException e) {
            if ("ERR_PERMISSION_DENIED".equals(e.getErrorKey())) return List.of();
            throw e;
        }
    }

    private TenantChoice findTenant(List<TenantChoice> tenants, String id) {
        if (id == null) return null;
        return tenants.stream().filter(tenant -> id.equals(tenant.id())).findFirst().orElse(null);
    }

    private void addTurn(Session session, Turn turn) {
        session.turns.addLast(turn);
        while (session.turns.size() > MAX_TURNS) session.turns.removeFirst();
    }

    private State state(Session session, List<TenantChoice> tenants) {
        EditPreview edit = session.edit == null ? null : session.edit.preview();
        return new State(List.copyOf(session.turns), session.draft == null ? null : session.draft.design(),
                edit != null ? edit.target().appKey() : session.draft == null ? null : session.appKey,
                session.revision, session.tenantId, session.publishedTenantName, tenants, session.published, session.links, edit,
                session.iamPlan == null ? null : session.iamPlan.preview(), session.iamRevision, session.iamApplied);
    }

    private ExistingTarget existingTarget(Session session) {
        if (session.edit != null) return session.edit.preview().target();
        if (!session.published || session.draft == null) return null;
        AssistantAppDesign design = session.draft.design();
        AssistantAppDesign.Table table = design.tables().size() == 1 ? design.tables().get(0) : null;
        return new ExistingTarget(session.appKey, design.name(),
                table == null ? null : AssistantAppDesign.modelName(session.appKey, table),
                table == null ? null : table.name());
    }

    private <T> T withSession(String owner, UUID id, Function<Session, T> action) {
        Session session;
        synchronized (sessions) {
            long now = System.currentTimeMillis();
            sessions.values().removeIf(value -> !value.lock.isLocked() && now - value.lastUse > SESSION_TTL);
            SessionKey key = new SessionKey(owner, id);
            session = sessions.get(key);
            if (session == null) {
                if (sessions.size() >= MAX_SESSIONS) throw new AssistantPlanner.Unavailable("助手会话较多，请稍后重试。");
                session = new Session();
                sessions.put(key, session);
            }
            if (!session.lock.tryLock()) throw DomainException.concurrentConflict("该会话有一步正在执行，请稍后重试。");
            session.lastUse = now;
        }
        try {
            return action.apply(session);
        } finally {
            session.lastUse = System.currentTimeMillis();
            session.lock.unlock();
        }
    }

    private record SessionKey(String owner, UUID id) {}

    private static final class Session {
        private final ReentrantLock lock = new ReentrantLock();
        private final String appKey = "agent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        private final ArrayDeque<Turn> turns = new ArrayDeque<>();
        private volatile long lastUse;
        private SavedDraft draft;
        private SavedEdit edit;
        private UUID revision;
        private AssistantPlanner.IamChange iamRequest;
        private AssistantIamActions.Plan iamPlan;
        private UUID iamRevision;
        private boolean iamApplied;
        private String tenantId;
        private boolean published;
        private String publishedTenantName;
        private List<PageLink> links = List.of();
    }

    public record State(List<Turn> turns, AssistantAppDesign draft, String appKey, UUID revision,
                        String tenantId, String publishedTenantName, List<TenantChoice> tenants,
                        boolean published, List<PageLink> links, EditPreview edit,
                        AssistantIamActions.Preview iam, UUID iamRevision, boolean iamApplied) {}
}
