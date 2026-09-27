package com.chaoslab.safety.application.port;

import com.chaoslab.safety.application.model.TargetIdentityVerification;
import com.chaoslab.target.domain.Target;
import java.time.Instant;

public interface TargetIdentityVerifier {

    TargetIdentityVerification verify(Target target);

    default TargetIdentityVerification verifyForWindow(Target target, Instant windowStart) {
        return TargetIdentityVerification.rejected("historical target binding is not supported");
    }
}
