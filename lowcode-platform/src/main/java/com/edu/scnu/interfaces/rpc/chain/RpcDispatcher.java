package com.edu.scnu.interfaces.rpc.chain;

import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.interfaces.rpc.dto.JsonRpcRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RpcDispatcher {
    private final List<RpcHandler> handlers;

    public RpcDispatcher(List<RpcHandler> handlers) {
        this.handlers = handlers;
    }

    public Object execute(String tenant, String authorization, boolean open, JsonRpcRequest request) {
        RpcRequestContext context = new RpcRequestContext(tenant, authorization, open, request);
        RpcHandler.normalizeEnvelope(context);
        return invokeAt(0, context);
    }

    private Object invokeAt(int index, RpcRequestContext context) {
        if (index >= handlers.size()) {
            throw DomainException.methodNotFound("责任链终点未处理请求");
        }
        return handlers.get(index).handle(context, next -> invokeAt(index + 1, next));
    }
}
