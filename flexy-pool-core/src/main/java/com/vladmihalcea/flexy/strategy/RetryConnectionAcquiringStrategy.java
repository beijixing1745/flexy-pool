package com.vladmihalcea.flexy.strategy;

import com.vladmihalcea.flexy.adaptor.PoolAdapter;
import com.vladmihalcea.flexy.connection.ConnectionRequestContext;
import com.vladmihalcea.flexy.exception.AcquireTimeoutException;
import com.vladmihalcea.flexy.metric.Histogram;
import com.vladmihalcea.flexy.metric.Metrics;
import com.vladmihalcea.flexy.util.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * <code>RetryConnectionAcquiringStrategy</code> extends the {@link AbstractConnectionAcquiringStrategy}
 * and it allows multiple acquiring attempts before giving up by rethrowing the {@link AcquireTimeoutException}
 *
 * @author Vlad Mihalcea
 * @version %I%, %E%
 * @since 1.0
 */
public final class RetryConnectionAcquiringStrategy<T extends DataSource> extends AbstractConnectionAcquiringStrategy {

    public static final String RETRY_ATTEMPTS_HISTOGRAM = "retryAttemptsHistogram";

    private static final Logger LOGGER = LoggerFactory.getLogger(RetryConnectionAcquiringStrategy.class);

    /**
     * The {@link com.vladmihalcea.flexy.strategy.RetryConnectionAcquiringStrategy.Builder} class allows
     * creating this strategy for a given {@link com.vladmihalcea.flexy.util.ConfigurationProperties}
     */
    public static class Builder<T extends DataSource> implements ConnectionAcquiringStrategyBuilder<RetryConnectionAcquiringStrategy, T> {
        private final int retryAttempts;

        public Builder(int retryAttempts) {
            this.retryAttempts = retryAttempts;
        }

        /**
         * Build a {@link com.vladmihalcea.flexy.strategy.RetryConnectionAcquiringStrategy} for a given
         * {@link com.vladmihalcea.flexy.util.ConfigurationProperties}
         *
         * @param configurationProperties configurationProperties
         * @return strategy
         */
        public RetryConnectionAcquiringStrategy build(ConfigurationProperties<T, Metrics, PoolAdapter<T>> configurationProperties) {
            return new RetryConnectionAcquiringStrategy(
                    configurationProperties, retryAttempts
            );
        }
    }

    private final int retryAttempts;

    private final Histogram retryAttemptsHistogram;

    /**
     * Create the strategy for the given configurationProperties and the retryAttempts.
     *
     * @param configurationProperties configurationProperties
     * @param retryAttempts           maximum retry attempts
     */
    private RetryConnectionAcquiringStrategy(ConfigurationProperties<? extends DataSource, Metrics, PoolAdapter> configurationProperties, int retryAttempts) {
        super(configurationProperties);
        this.retryAttempts = validateRetryAttempts(retryAttempts);
        this.retryAttemptsHistogram = configurationProperties.getMetrics().histogram(RETRY_ATTEMPTS_HISTOGRAM);
    }

    private int validateRetryAttempts(int retryAttempts) {
        if (retryAttempts <= 0) {
            throw new IllegalArgumentException("retryAttempts must ge greater than 0!");
        }
        return retryAttempts;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Connection getConnection(ConnectionRequestContext requestContext) throws SQLException {
        int remainingAttempts = retryAttempts;
        try {
            do {
                try {
                    return getConnectionFactory().getConnection(requestContext);
                } catch (AcquireTimeoutException e) {
                    requestContext.incrementAttempts();
                    remainingAttempts--;
                    LOGGER.info("Can't acquire connection, remaining retry attempts {}", remainingAttempts);
                    if (remainingAttempts <= 0) {
                        throw e;
                    }
                }
            } while (true);
        } finally {
            int attemptedRetries = requestContext.getRetryAttempts();
            if (attemptedRetries > 0) {
                retryAttemptsHistogram.update(attemptedRetries);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "RetryConnectionAcquiringStrategy{" +
                "retryAttempts=" + retryAttempts +
                '}';
    }
}
