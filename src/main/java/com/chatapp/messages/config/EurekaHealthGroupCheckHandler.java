package com.chatapp.messages.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;

/**
 * Eureka instance status = the "eureka" health GROUP, not the aggregate. Being a HealthCheckHandler bean, it replaces
 * Spring Cloud's EurekaHealthCheckHandler (@ConditionalOnMissingBean), which aggregates EVERY indicator — rabbit included.
 */
@Component
public class EurekaHealthGroupCheckHandler implements HealthCheckHandler {

    static final String GROUP = "eureka";
    private static final Logger log = LoggerFactory.getLogger(EurekaHealthGroupCheckHandler.class);

    private final ObjectProvider<HealthEndpoint> healthEndpoint;   // lazy: the endpoint is built after the Eureka client

    public EurekaHealthGroupCheckHandler(ObjectProvider<HealthEndpoint> healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @Override
    public InstanceStatus getStatus(InstanceStatus currentStatus) {
        try {
            HealthComponent group = healthEndpoint.getObject().healthForPath(GROUP);
            if (group == null) {
                log.error("health group '{}' not found: check management.endpoint.health.group.{}.include - reporting DOWN", GROUP, GROUP);
                return InstanceStatus.DOWN;   // fail loudly rather than silently aggregating everything
            }
            return map(group.getStatus());
        } catch (RuntimeException e) {
            log.warn("could not evaluate health group '{}': {}", GROUP, e.toString());
            return InstanceStatus.UNKNOWN;
        }
    }

    static InstanceStatus map(Status status) {
        if (Status.UP.equals(status)) return InstanceStatus.UP;
        if (Status.DOWN.equals(status)) return InstanceStatus.DOWN;
        if (Status.OUT_OF_SERVICE.equals(status)) return InstanceStatus.OUT_OF_SERVICE;
        return InstanceStatus.UNKNOWN;
    }
}
