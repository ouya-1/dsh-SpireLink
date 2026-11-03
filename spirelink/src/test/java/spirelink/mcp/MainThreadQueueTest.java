package spirelink.mcp;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Timeout / single-flight behaviour of the main-thread bridge (design 4 / 5). The "game main
 * thread" is simulated by a test thread calling {@link MainThreadQueue#drainOnMainThread()}.
 */
public class MainThreadQueueTest {

    @Test(timeout = 10000)
    public void executesTaskOnDrain() throws Exception {
        final MainThreadQueue queue = new MainThreadQueue();
        Thread gameThread = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(80);
                queue.drainOnMainThread();
            }
        });
        gameThread.start();

        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return "value-42";
            }
        }, 3000);

        gameThread.join(2000);
        assertEquals(MainThreadQueue.Result.Status.OK, result.status);
        assertEquals("value-42", result.value);
        assertFalse(queue.isBusy());
    }

    @Test(timeout = 10000)
    public void timesOutAndDropsTheCanceledJob() {
        final MainThreadQueue queue = new MainThreadQueue();
        final AtomicBoolean ran = new AtomicBoolean(false);

        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                ran.set(true);
                return "late";
            }
        }, 60);

        assertEquals(MainThreadQueue.Result.Status.TIMEOUT, result.status);
        // The client already gave up; the main thread must not execute the stale command.
        queue.drainOnMainThread();
        assertFalse("canceled job must not run", ran.get());
        assertFalse(queue.isBusy());
    }

    @Test(timeout = 10000)
    public void queueIsUsableAfterTimeout() throws Exception {
        final MainThreadQueue queue = new MainThreadQueue();
        queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return "ignored";
            }
        }, 30);

        Thread gameThread = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(50);
                queue.drainOnMainThread();
            }
        });
        gameThread.start();
        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return "after-timeout";
            }
        }, 3000);
        gameThread.join(2000);
        assertEquals(MainThreadQueue.Result.Status.OK, result.status);
        assertEquals("after-timeout", result.value);
    }

    @Test(timeout = 10000)
    public void secondCallWhileQueuedIsRejectedAsBusy() throws Exception {
        final MainThreadQueue queue = new MainThreadQueue();
        final AtomicReference<MainThreadQueue.Result> first = new AtomicReference<MainThreadQueue.Result>();

        Thread httpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                first.set(queue.call(new MainThreadQueue.Task() {
                    @Override
                    public Object run() {
                        return "first";
                    }
                }, 5000));
            }
        });
        httpThread.start();
        sleep(100);

        MainThreadQueue.Result second = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return "second";
            }
        }, 1000);
        assertEquals("single flight", MainThreadQueue.Result.Status.BUSY, second.status);

        queue.drainOnMainThread();
        httpThread.join(3000);
        assertEquals(MainThreadQueue.Result.Status.OK, first.get().status);
        assertEquals("first", first.get().value);
    }

    @Test(timeout = 10000)
    public void secondCallWhileExecutingIsRejectedAsBusy() throws Exception {
        final MainThreadQueue queue = new MainThreadQueue();
        final CountDownLatch insideTask = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final AtomicReference<MainThreadQueue.Result> first = new AtomicReference<MainThreadQueue.Result>();

        Thread httpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                first.set(queue.call(new MainThreadQueue.Task() {
                    @Override
                    public Object run() {
                        insideTask.countDown();
                        try {
                            releaseTask.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "slow";
                    }
                }, 5000));
            }
        });
        httpThread.start();
        sleep(100);

        Thread gameThread = new Thread(new Runnable() {
            @Override
            public void run() {
                queue.drainOnMainThread();
            }
        });
        gameThread.start();
        assertTrue(insideTask.await(3, TimeUnit.SECONDS));

        MainThreadQueue.Result second = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return "second";
            }
        }, 500);
        assertEquals(MainThreadQueue.Result.Status.BUSY, second.status);

        releaseTask.countDown();
        gameThread.join(3000);
        httpThread.join(3000);
        assertEquals(MainThreadQueue.Result.Status.OK, first.get().status);
        assertEquals("slow", first.get().value);
    }

    @Test(timeout = 10000)
    public void failuresAreReportedAsError() throws Exception {
        final MainThreadQueue queue = new MainThreadQueue();
        Thread gameThread = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(60);
                queue.drainOnMainThread();
            }
        });
        gameThread.start();

        final RuntimeException boom = new RuntimeException("boom");
        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                throw boom;
            }
        }, 3000);

        gameThread.join(2000);
        assertEquals(MainThreadQueue.Result.Status.ERROR, result.status);
        assertEquals(boom, result.error);
        assertFalse(queue.isBusy());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
