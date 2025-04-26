
import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.threadpool.TtlExecutors;

import javax.validation.constraints.NotNull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public class SafeThreadPoolExecutorForThreadLocal extends ThreadPoolExecutor {
    private static final MethodHandle threadLocalsGetter;
    private static final MethodHandle threadLocalsSetter;
    private static final MethodHandle inheritableThreadLocalsGetter;
    private static final MethodHandle inheritableThreadLocalsSetter;

    static {
        try {
            Field threadLocalsField = Thread.class.getDeclaredField("threadLocals");
            Field inheritableThreadLocalsField = Thread.class.getDeclaredField("inheritableThreadLocals");
            threadLocalsField.setAccessible(true);
            inheritableThreadLocalsField.setAccessible(true);

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            threadLocalsGetter = lookup.unreflectGetter(threadLocalsField);
            threadLocalsSetter = lookup.unreflectSetter(threadLocalsField);
            inheritableThreadLocalsGetter = lookup.unreflectGetter(inheritableThreadLocalsField);
            inheritableThreadLocalsSetter = lookup.unreflectSetter(inheritableThreadLocalsField);
        } catch (Exception e) {
            throw new RuntimeException("初始化 MethodHandle 失败", e);
        }
    }

    public SafeThreadPoolExecutorForThreadLocal(int corePoolSize, int maxPoolSize, long keepAliveTime,
                                                TimeUnit unit, BlockingQueue<Runnable> workQueue) {
        super(corePoolSize, maxPoolSize, keepAliveTime, unit, workQueue);
    }

    @Override
    public void execute(@NotNull Runnable command) {
        try {
            final Object parentThreadLocals = threadLocalsGetter.invoke(Thread.currentThread());
            final Object parentInheritableThreadLocals = inheritableThreadLocalsGetter.invoke(Thread.currentThread());

            Runnable wrapped = () -> {
                Thread t = Thread.currentThread();
                try {
                    threadLocalsSetter.invoke(t, parentThreadLocals);
                    inheritableThreadLocalsSetter.invoke(t, parentInheritableThreadLocals);
                    command.run();
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    // 任务执行后，清除 ThreadLocal 避免污染
                    try {
                        threadLocalsSetter.invoke(t, null);
                        inheritableThreadLocalsSetter.invoke(t, null);
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }
            };

            super.execute(wrapped);
        } catch (Throwable e) {
            throw new RuntimeException("任务包装失败", e);
        }
    }

    public static SafeThreadPoolExecutorForThreadLocal newFixedThreadPool(int nThreads) {
        return new SafeThreadPoolExecutorForThreadLocal(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    public <T> Future<T> submit(Callable<T> task) {
        RunnableFuture<T> future = newTaskFor(task);
        execute(future);
        return future;
    }

    public Future<?> submit(Runnable task) {
        RunnableFuture<?> future = newTaskFor(task, null);
        execute(future);
        return future;
    }

    public <T> Future<T> submit(Runnable task, T result) {
        RunnableFuture<T> future = newTaskFor(task, result);
        execute(future);
        return future;
    }

    public static void main(String[] args) throws Exception {
        // test1();
        // test2();
        compare();
    }

    public static void test1() throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        ThreadLocal<String> contextI = new InheritableThreadLocal<>();
        ThreadLocal<String> contextT = new TransmittableThreadLocal<>();
        ExecutorService executor = SafeThreadPoolExecutorForThreadLocal.newFixedThreadPool(1);
        byte[] bytes = Files.readAllBytes(Paths.get("/Users/cheteng/chen/project/mycode/idea/mylearning/leetcode/src/main/java/com/example/leetcode/utils/SafeThreadPoolExecutorForThreadLocal.java"));
        String content = new String(bytes);
        for (int j = 0; j < 10000; j++) {
            ThreadLocal<String> context2 = new ThreadLocal<>();
            context2.set("ThreadLocal-from-main-inner:" + j);
            ThreadLocal<Map<String, Object>> context3 = new ThreadLocal<>();
            Map<String, Object> data = new HashMap<>();
            data.put("field1", "123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123");
            data.put("field2", "123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123w");
            data.put("field3", content);
            context3.set(data);
        }
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10000; i++) {
            // context.set("ThreadLocal-from-main:" + i);
            // contextI.set("ThreadLocal-from-main:" + i);
            contextT.set("ThreadLocal-from-main:" + i);
            Future<Integer> submit = executor.submit(() -> {
                System.out.println("contextI = " + contextT.get());
                return 1;
            });
            submit.get();
        }
        long end = System.currentTimeMillis();
        System.out.println("cost:" + (end - start));
        executor.shutdown();
    }

    public static void test2() throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        ThreadLocal<String> contextI = new InheritableThreadLocal<>();
        ThreadLocal<String> contextT = new TransmittableThreadLocal<>();
        ExecutorService executor = Executors.newFixedThreadPool(1);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            // context.set("ThreadLocal-from-main:" + i);
            contextI.set("ThreadLocal-from-main:" + i);
            // contextT.set("ThreadLocal-from-main:" + i);
            Future<Integer> submit = executor.submit(() -> {
                System.out.println("contextI = " + contextT.get());
                return 1;
            });
            submit.get();
        }
        long end = System.currentTimeMillis();
        System.out.println("cost:" + (end - start));
        executor.shutdown();
    }

    public static void test3() throws Exception {

        ThreadLocal<String> context = new ThreadLocal<>();
        ThreadLocal<String> contextI = new InheritableThreadLocal<>();
        ThreadLocal<String> contextT = new TransmittableThreadLocal<>();
        ExecutorService executor = Executors.newFixedThreadPool(1);
        executor = TtlExecutors.getTtlExecutorService(executor);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            // context.set("ThreadLocal-from-main:" + i);
            // contextI.set("ThreadLocal-from-main:" + i);
            contextT.set("ThreadLocal-from-main:" + i);
            Future<Integer> submit = executor.submit(() -> {
                System.out.println("context value = " + contextT.get());
                return 1;
            });
            submit.get();
        }
        long end = System.currentTimeMillis();
        System.out.println("cost:" + (end - start));
        executor.shutdown();
    }

    public static void compare() throws Exception {
        for (int j = 0; j < 1000; j++) {
            ThreadLocal<String> context2 = new TransmittableThreadLocal<>();
            context2.set("ThreadLocal-from-main-inner:" + j);
            // byte[] bytes = Files.readAllBytes(Paths.get("/Users/cheteng/chen/project/mycode/idea/mylearning/leetcode/src/main/java/com/example/leetcode/utils/SafeThreadPoolExecutorForThreadLocal.java"));
            // String content = new String(bytes);
            // ThreadLocal<Map<String, Object>> context3 = new TransmittableThreadLocal<>();
            // Map<String, Object> data = new HashMap<>();
            // data.put("field1", new Object());
            // data.put("field2", new Object());
            // data.put("field3", content);
            // context3.set(data);
        }
        int rounds = 10000;
        for (int i = 0; i < 10; i++) {
            System.out.println("-------------" + i + "------------");
            compareSafe(rounds);
            compareTTL(rounds);
        }
    }

    public static void compareTTL(int rounds) throws Exception {
        ThreadLocal<String> contextT = new TransmittableThreadLocal<>();
        ExecutorService executor = Executors.newFixedThreadPool(1);
        executor = TtlExecutors.getTtlExecutorService(executor);
        long start = System.currentTimeMillis();
        for (int i = 0; i < rounds; i++) {
            contextT.set("ThreadLocal-from-main:" + i);
            Future<String> submit = executor.submit(() -> {
                // System.out.println("context value = " + contextT.get());
                return contextT.get();
            });
            submit.get();
        }
        long end = System.currentTimeMillis();
        System.out.println("compareTTL cost:" + (end - start));
    }

    public static void compareSafe(int rounds) throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        ExecutorService executor = SafeThreadPoolExecutorForThreadLocal.newFixedThreadPool(1);
        long start = System.currentTimeMillis();
        for (int i = 0; i < rounds; i++) {
            context.set("ThreadLocal-from-main:" + i);
            Future<String> submit = executor.submit(() -> {
                // System.out.println("contextI = " + context.get());
                return context.get();
            });
            submit.get();
        }
        long end = System.currentTimeMillis();
        System.out.println("compareSafe cost:" + (end - start));
    }

}
