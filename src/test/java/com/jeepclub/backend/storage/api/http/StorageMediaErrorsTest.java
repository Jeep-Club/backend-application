package com.jeepclub.backend.storage.api.http;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThat;

class StorageMediaErrorsTest {
    @Test void databaseTimeoutProducesSanitizedUnavailableProblem() {
        var handler=new StorageMediaErrors(new com.jeepclub.backend.platform.web.exception.ApiProblemFactory(new StaticMessageSource(),Clock.systemUTC()));
        var response=handler.timedOut();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code","STORAGE_UNAVAILABLE");
    }
}
