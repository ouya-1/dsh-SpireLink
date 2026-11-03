package spirelink.mcp;

/**
 * Single-flight bridge between the MCP HTTP threads and the game main thread.
 *
 * <p>Rules from design doc 4 / 5:
 * <ul>
 *   <li>HTTP threads never touch {@code AbstractDungeon}; they enqueue and wait.</li>
 *   <li>At most one command is in flight («单飞»): a concurrent request is rejected with
 *       {@link Result.Status#BUSY} instead of piling up.</li>
 *   <li>A command that waits longer than its timeout returns
 *       {@link Result.Status#TIMEOUT}; the queued job is canceled so the main thread never runs a
 *       stale command for a client that already gave up.</li>
 * </ul>
 *
 * <p>Pure Java on purpose: the unit tests drive {@link #drainOnMainThread()} from a test thread.
 */
public final class MainThreadQueue {

    /** Work to be executed on the game main thread. */
    public interface Task {
        Object run() throws Exception;
    }

    public static final class Result {

        public enum Status {
            OK,
            TIMEOUT,
            BUSY,
            ERROR
        }

        private static final Result TIMEOUT = new Result(Status.TIMEOUT, null, null);
        private static final Result BUSY = new Result(Status.BUSY, null, null);

        public final Status status;
        public final Object value;
        public final Throwable error;

        private Result(Status status, Object value, Throwable error) {
            this.status = status;
            this.value = value;
            this.error = error;
        }

        public static Result ok(Object value) {
            return new Result(Status.OK, value, null);
        }

        public static Result timeout() {
            return TIMEOUT;
        }

        public static Result busy() {
            return BUSY;
        }

        public static Result error(Throwable error) {
            return new Result(Status.ERROR, null, error);
        }

        public boolean isOk() {
            return status == Status.OK;
        }
    }

    private static final class Job {
        final Task task;
        final long deadlineNanos;
        final Object monitor = new Object();
        volatile boolean canceled;
        volatile boolean done;
        volatile Result result;

        Job(Task task, long deadlineNanos) {
            this.task = task;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Object lock = new Object();
    private Job pending;
    private boolean inFlight;

    /**
     * Enqueues {@code task} and blocks the calling (HTTP) thread until it ran, timed out, or was
     * rejected because another command is in flight.
     *
     * @param timeoutMs how long the caller is willing to wait, 1..10000 ms in practice.
     */
    public Result call(Task task, long timeoutMs) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        long timeout = Math.max(1L, timeoutMs);
        Job job = new Job(task, System.nanoTime() + timeout * 1_000_000L);

        synchronized (lock) {
            if (pending != null || inFlight) {
                return Result.busy();
            }
            pending = job;
        }

        synchronized (job.monitor) {
            while (!job.done) {
                long remaining = job.deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    job.monitor.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (job.done) {
            return job.result;
        }

        job.canceled = true;
        synchronized (lock) {
            if (pending == job) {
                pending = null;
            }
        }
        return Result.timeout();
    }

    /** True while a command is queued or running on the main thread. */
    public boolean isBusy() {
        synchronized (lock) {
            return pending != null || inFlight;
        }
    }

    /**
     * Must be called from the game main thread, once per frame.
     */
    public void drainOnMainThread() {
        Job job;
        synchronized (lock) {
            job = pending;
            pending = null;
            if (job != null) {
                inFlight = true;
            }
        }
        if (job == null) {
            return;
        }
        try {
            if (job.canceled || System.nanoTime() > job.deadlineNanos) {
                complete(job, Result.timeout());
                return;
            }
            Result result;
            try {
                result = Result.ok(job.task.run());
            } catch (Throwable t) {
                result = Result.error(t);
            }
            complete(job, result);
        } finally {
            synchronized (lock) {
                inFlight = false;
            }
        }
    }

    private static void complete(Job job, Result result) {
        synchronized (job.monitor) {
            job.result = result;
            job.done = true;
            job.monitor.notifyAll();
        }
    }
}
