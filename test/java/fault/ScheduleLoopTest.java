package fault;

import fault.circuit.ICircuitBreaker;
import fault.messages.ResultMessage;
import fault.messages.ScheduleMessage;
import fault.metrics.IActionMetrics;
import fault.utils.TimeProvider;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;


/**
 * Created by timbrooks on 12/12/14.
 */
public class ScheduleLoopTest {

    @Mock
    private ICircuitBreaker circuitBreaker;
    @Mock
    private IActionMetrics actionMetrics;
    @Mock
    private ConcurrentLinkedQueue<ScheduleMessage<Object>> toScheduleQueue;
    @Mock
    private ConcurrentLinkedQueue<ResultMessage<Object>> toReturnQueue;
    @Mock
    private ExecutorService executorService;
    @Captor
    private ArgumentCaptor<ResilientTask<Object>> taskCaptor;
    @Mock
    private ResilientAction<Object> action;
    @Mock
    private ResilientAction<Object> action2;
    @Mock
    private ResilientPromise<Object> promise2;
    @Mock
    private ResilientPromise<Object> promise;
    @Mock
    private TimeProvider timeProvider;

    private ScheduleContext context;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        setContext(2);
    }

    @Test
    public void testRunLoopReturnsFalseIfNothingDone() throws Exception {
        setContext(1);
        when(toScheduleQueue.poll()).thenReturn(null);
        when(toReturnQueue.poll()).thenReturn(null);

        assertFalse(ScheduleLoop.runLoop(context));
    }

    @Test
    public void testRunLoopReturnsTrueIfActionScheduled() throws Exception {
        setContext(1);
        when(toScheduleQueue.poll()).thenReturn(new ScheduleMessage<>(action, promise, 100L));
        when(toReturnQueue.poll()).thenReturn(null);

        assertTrue(ScheduleLoop.runLoop(context));
    }

    @Test
    public void testRunLoopReturnsTrueIfResultHandled() throws Exception {
        setContext(1);
        when(toScheduleQueue.poll()).thenReturn(null);
        when(toReturnQueue.poll()).thenReturn(new ResultMessage<>(ResultMessage.Type.ASYNC));

        assertTrue(ScheduleLoop.runLoop(context));
    }

    @Test
    public void testTimeoutsSetByLoop() throws Exception {
        setContext(1);

        when(timeProvider.currentTimeMillis()).thenReturn(5L);
        when(toScheduleQueue.poll()).thenReturn(new ScheduleMessage<>(action, promise, 10L));
        ScheduleLoop.runLoop(context);

        verify(executorService).submit(any(ResilientTask.class));

        when(timeProvider.currentTimeMillis()).thenReturn(11L);
        when(toScheduleQueue.poll()).thenReturn(null);
        ScheduleLoop.runLoop(context);
        verify(promise).setTimedOut();
    }

    @Test
    public void testActionsScheduled() throws Exception {
        ScheduleMessage<Object> scheduleMessage = new ScheduleMessage<>(action, promise, 100L);
        ScheduleMessage<Object> scheduleMessage2 = new ScheduleMessage<>(action2, promise2, 101L);
        when(toScheduleQueue.poll()).thenReturn(scheduleMessage, scheduleMessage2);
        ScheduleLoop.runLoop(context);

        verify(executorService, times(2)).submit(taskCaptor.capture());

        List<ResilientTask<Object>> tasks = taskCaptor.getAllValues();

        ResilientTask<Object> task1 = tasks.get(0);
        assertEquals(promise, task1.resilientPromise);
        task1.run();
        verify(action).run();


        ResilientTask<Object> task2 = tasks.get(1);
        assertEquals(promise2, task2.resilientPromise);
        task2.run();
        verify(action2).run();
    }

    @Test
    public void testSuccessfulAsyncActionsHandled() {
        Map<ResultMessage<Object>, ResilientTask<Object>> taskmap = mock(HashMap.class);
        context = buildContext(1).setTaskMap(taskmap).build();

        ResultMessage<Object> result = new ResultMessage<>(ResultMessage.Type.ASYNC);
        ResilientPromise promise = new ResilientPromise();
        result.result = "Done";
        promise.status = Status.SUCCESS;
        when(toReturnQueue.poll()).thenReturn(result);
        when(taskmap.remove(result)).thenReturn(new ResilientTask(null, promise));
        ScheduleLoop.runLoop(context);

        verify(actionMetrics).reportActionResult(Status.SUCCESS);
        verify(circuitBreaker).informBreakerOfResult(true);
    }

    private void setContext(int threadNum) {
        context = buildContext(threadNum).build();
    }

    private ScheduleContext.ScheduleContextBuilder buildContext(int threadNum) {
        return new ScheduleContext.ScheduleContextBuilder().setPoolSize(threadNum).setCircuitBreaker(circuitBreaker)
                .setActionMetrics(actionMetrics).setToScheduleQueue(toScheduleQueue).setToReturnQueue(toReturnQueue)
                .setExecutorService(executorService).setTaskMap(new HashMap<ResultMessage<Object>,
                        ResilientTask<Object>>()).setTimeProvider(timeProvider);

    }

}
