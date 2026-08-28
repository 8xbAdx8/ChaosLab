package com.chaoslab.audit.application.port;

import com.chaoslab.audit.application.model.AuditContext;

public interface AuditContextProvider {

    AuditContext currentContext();
}
