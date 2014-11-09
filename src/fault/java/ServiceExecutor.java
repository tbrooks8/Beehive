package fault.java;

import fault.java.circuit.CircuitBreaker;
import fault.java.circuit.NoOpCircuitBreaker;
import fault.java.circuit.ResilientResult;

import java.util.concurrent.*;

/**
 * Created by timbrooks on 11/5/14.
 */
public class ServiceExecutor {

    private final ScheduledExecutorService threadPool;
    private final CircuitBreaker circuitBreaker;
    private final TimeoutService timeoutService;

    public ServiceExecutor(int poolSize) {
        threadPool = Executors.newScheduledThreadPool(poolSize);
        circuitBreaker = new NoOpCircuitBreaker();
        timeoutService = new TimeoutService();
    }

    public <T> ResilientResult<T> performAction(final ResilientAction<T> action, int millisTimeout) {
        if (circuitBreaker.isOpen()) {
            throw new RuntimeException("Circuit is Open");
        }
        final ResilientResult<T> resilientResult = new ResilientResult<>();
        ScheduledFuture<Void> scheduledFuture = threadPool.schedule(new Callable<Void>() {
            @Override
            public Void call() {
                try {
                    T result = action.run();
                    resilientResult.deliverResult(result);
                } catch (Exception e) {
                    resilientResult.deliverError(e);
                } finally {
                    circuitBreaker.informBreakerOfResult(resilientResult.isSuccessful());
                }
                return null;
            }
        }, 0, TimeUnit.MILLISECONDS);

        timeoutService.scheduleTimeout(millisTimeout, resilientResult, scheduledFuture);
        return resilientResult;
    }

    public void shutdown() {
        threadPool.shutdown();
        timeoutService.shutdown();
    }
}
