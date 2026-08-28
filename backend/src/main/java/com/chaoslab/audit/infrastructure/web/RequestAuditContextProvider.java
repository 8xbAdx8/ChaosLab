package com.chaoslab.audit.infrastructure.web;

import com.chaoslab.audit.application.model.AuditContext;
import com.chaoslab.audit.application.port.AuditContextProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class RequestAuditContextProvider implements AuditContextProvider {

    private static final String ANONYMOUS_ACTOR = "ANONYMOUS";
    private static final String SYSTEM_ACTOR = "SYSTEM";

    @Override
    public AuditContext currentContext() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return new AuditContext(SYSTEM_ACTOR, null);
        }
        HttpServletRequest request = servletAttributes.getRequest();
        return new AuditContext(ANONYMOUS_ACTOR, request.getRemoteAddr());
    }
}
