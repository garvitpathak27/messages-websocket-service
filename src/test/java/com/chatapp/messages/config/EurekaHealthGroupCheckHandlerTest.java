package com.chatapp.messages.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import com.netflix.appinfo.InstanceInfo.InstanceStatus;

class EurekaHealthGroupCheckHandlerTest {

    @Test
    void mapsBootStatusesToEurekaStatuses() {
        assertThat(EurekaHealthGroupCheckHandler.map(Status.UP)).isEqualTo(InstanceStatus.UP);
        assertThat(EurekaHealthGroupCheckHandler.map(Status.DOWN)).isEqualTo(InstanceStatus.DOWN);
        assertThat(EurekaHealthGroupCheckHandler.map(Status.OUT_OF_SERVICE)).isEqualTo(InstanceStatus.OUT_OF_SERVICE);
        assertThat(EurekaHealthGroupCheckHandler.map(Status.UNKNOWN)).isEqualTo(InstanceStatus.UNKNOWN);
    }
}
