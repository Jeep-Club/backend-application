package com.jeepclub.backend.billing.core.application.service;

import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminBillingCommandService {

    /** Keeps the mutation and enrichment in the same transaction. */
    @Transactional
    public <T, R> R execute(Supplier<T> command, Function<T, R> enrich) {
        return enrich.apply(command.get());
    }
}
