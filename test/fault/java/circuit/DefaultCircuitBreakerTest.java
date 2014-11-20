package fault.java.circuit;

import fault.java.circuit.BreakerConfig;
import fault.java.circuit.DefaultCircuitBreaker;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

/**
 * Created by timbrooks on 11/20/14.
 */
public class DefaultCircuitBreakerTest {

    @Mock
    private fault.java.metrics.IActionMetrics IActionMetrics;

    private DefaultCircuitBreaker defaultCircuitBreaker;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testCircuitIsClosedByDefault() {
        BreakerConfig breakerConfig = new BreakerConfig.BreakerConfigBuilder().failureThreshold(20)
                .timePeriodInMillis(5000).build();
        defaultCircuitBreaker = new DefaultCircuitBreaker(IActionMetrics, breakerConfig);
        assertFalse(defaultCircuitBreaker.isOpen());
    }

    @Test
    public void testCircuitOpensWhenFailuresGreaterThanThreshold() {
        int timePeriodInMillis = 1000;
        BreakerConfig breakerConfig = new BreakerConfig.BreakerConfigBuilder().failureThreshold(5).timePeriodInMillis
                (timePeriodInMillis).build();
        defaultCircuitBreaker = new DefaultCircuitBreaker(IActionMetrics, breakerConfig);

        assertFalse(defaultCircuitBreaker.isOpen());

        when(IActionMetrics.getFailuresForTimePeriod(timePeriodInMillis)).thenReturn(6);
        defaultCircuitBreaker.informBreakerOfResult(false);

        assertTrue(defaultCircuitBreaker.isOpen());
    }

    @Test
    public void testOpenCircuitClosesAfterSuccess() {
        int timePeriodInMillis = 1000;
        BreakerConfig breakerConfig = new BreakerConfig.BreakerConfigBuilder().failureThreshold(5).timePeriodInMillis
                (timePeriodInMillis).build();
        defaultCircuitBreaker = new DefaultCircuitBreaker(IActionMetrics, breakerConfig);

        assertFalse(defaultCircuitBreaker.isOpen());

        when(IActionMetrics.getFailuresForTimePeriod(timePeriodInMillis)).thenReturn(6);
        defaultCircuitBreaker.informBreakerOfResult(false);

        assertTrue(defaultCircuitBreaker.isOpen());

        defaultCircuitBreaker.informBreakerOfResult(true);

        assertFalse(defaultCircuitBreaker.isOpen());
    }

}