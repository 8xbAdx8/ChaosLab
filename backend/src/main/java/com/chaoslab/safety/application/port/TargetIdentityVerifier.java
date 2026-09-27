package com.chaoslab.safety.application.port;

import com.chaoslab.safety.application.model.TargetIdentityVerification;
import com.chaoslab.target.domain.Target;

public interface TargetIdentityVerifier {

    TargetIdentityVerification verify(Target target);
}
