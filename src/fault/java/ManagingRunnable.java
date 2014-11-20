package fault.java;

import fault.java.circuit.ICircuitBreaker;
import fault.java.messages.ResultMessage;
import fault.java.messages.ScheduleMessage;
import fault.java.metrics.IActionMetrics;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.LockSupport;

/**
 * Created by timbrooks on 11/13/14.
 */
public class ManagingRunnable implements Runnable {

    private final int poolSize;
    private final int maxSpin;
    private final ICircuitBreaker circuitBreaker;
    private final IActionMetrics actionMetrics;
    private final ConcurrentLinkedQueue<ScheduleMessage<Object>> toScheduleQueue;
    private final ConcurrentLinkedQueue<ResultMessage<Object>> toReturnQueue;
    private final ExecutorService executorService;
    private volatile boolean isRunning;

    public ManagingRunnable(int poolSize, ICircuitBreaker circuitBreaker, IActionMetrics actionMetrics) {
        this.poolSize = poolSize;
        this.maxSpin = 100;
        this.circuitBreaker = circuitBreaker;
        this.actionMetrics = actionMetrics;
        this.toScheduleQueue = new ConcurrentLinkedQueue<>();
        this.toReturnQueue = new ConcurrentLinkedQueue<>();
        this.executorService = Executors.newFixedThreadPool(poolSize);
    }

    @Override
    public void run() {
        SortedMap<Long, List<ResultMessage<Object>>> scheduled = new TreeMap<>();
        Map<ResultMessage<Object>, ResilientTask<Object>> taskMap = new HashMap<>();
        isRunning = true;
        int spinCount = 1;
        while (isRunning) {
            boolean didSomething = false;

            for (int i = 0; i < poolSize; ++i) {
                if (handleScheduling(scheduled, taskMap)) {
                    didSomething = true;
                } else {
                    break;
                }
            }

            for (int i = 0; i < poolSize; ++i) {
                if (handleReturnResult(taskMap)) {
                    didSomething = true;
                } else {
                    break;
                }
            }

            long now = triggerTimeouts(scheduled, taskMap);

            SortedMap<Long, List<ResultMessage<Object>>> tailView = scheduled.tailMap(now);
            scheduled = new TreeMap<>(tailView);

            if (!didSomething) {
                if (0 == --spinCount) {
                    spinCount = 1000;
                    LockSupport.parkNanos(1);
                } else if (50 > --spinCount) {
                    Thread.yield();
                }
            } else {
                spinCount = maxSpin;
            }

        }

    }

    @SuppressWarnings("unchecked")
    public <T> void submit(ScheduleMessage<T> message) {
        toScheduleQueue.offer((ScheduleMessage<Object>) message);
    }

    private boolean handleScheduling(SortedMap<Long, List<ResultMessage<Object>>> scheduled,
                                     Map<ResultMessage<Object>, ResilientTask<Object>> taskMap) {
        ScheduleMessage<Object> scheduleMessage = toScheduleQueue.poll();
        if (scheduleMessage != null) {
            ActionCallable<Object> actionCallable = new ActionCallable<>(scheduleMessage.action, toReturnQueue);
            FutureTask<Void> futureTask = new FutureTask<>(actionCallable);
            ResilientTask<Object> resilientTask = new ResilientTask<>(futureTask, scheduleMessage.promise);
            ResultMessage<Object> resultMessage = actionCallable.resultMessage;
            taskMap.put(resultMessage, resilientTask);

            executorService.submit(resilientTask);
            long relativeTimeout = scheduleMessage.relativeTimeout;
            if (scheduled.containsKey(relativeTimeout)) {
                scheduled.get(relativeTimeout).add(resultMessage);
            } else {
                List<ResultMessage<Object>> messages = new ArrayList<>();
                messages.add(resultMessage);
                scheduled.put(relativeTimeout, messages);

            }
            return true;
        }
        return false;
    }

    private boolean handleReturnResult(Map<ResultMessage<Object>, ResilientTask<Object>> taskMap) {
        ResultMessage<Object> result = toReturnQueue.poll();
        if (result != null) {
            ResilientTask<Object> resilientTask = taskMap.remove(result);
            if (resilientTask != null) {

                ResilientPromise<Object> promise = resilientTask.resilientPromise;
                if (result.result != null) {
                    promise.deliverResult(result.result);
                } else {
                    promise.deliverError(result.exception);
                }
                actionMetrics.logActionResult(promise);
                circuitBreaker.informBreakerOfResult(result.exception == null);
            }
            return true;
        }
        return false;
    }

    private long triggerTimeouts(SortedMap<Long, List<ResultMessage<Object>>> scheduled, Map<ResultMessage<Object>, ResilientTask<Object>> taskMap) {
        long now = System.currentTimeMillis();
        SortedMap<Long, List<ResultMessage<Object>>> toCancel = scheduled.headMap(now);
        for (Map.Entry<Long, List<ResultMessage<Object>>> entry : toCancel.entrySet()) {
            List<ResultMessage<Object>> toTimeout = entry.getValue();
            for (ResultMessage<Object> messageToTimeout : toTimeout) {
                handleTimeout(taskMap, messageToTimeout);
            }
        }
        return now;
    }

    private void handleTimeout(Map<ResultMessage<Object>, ResilientTask<Object>> taskMap, ResultMessage<Object>
            resultMessage) {
        ResilientTask<Object> task = taskMap.remove(resultMessage);
        if (task != null) {
            ResilientPromise<Object> promise = task.resilientPromise;
            if (!promise.isDone()) {
                promise.setTimedOut();
                task.cancel(true);
                actionMetrics.logActionResult(promise);
                circuitBreaker.informBreakerOfResult(false);
            }
        }
    }

    public void shutdown() {
        isRunning = false;
        executorService.shutdown();
    }
}
