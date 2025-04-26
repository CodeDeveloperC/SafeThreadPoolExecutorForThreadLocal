# SafeThreadPoolExecutorForThreadLocal
SafeThreadPoolExecutorForThreadLocal是一个专门解决ThreadLocal在线程池中运行存在并发安全的线程池。
1. ## 问题

当ThreadLocal和InheritableThreadLocal和Java原生的线程池使用时，就会出现并发安全问题，例如：

下面这段代码在线程池中获取的数据都为空

```java
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
```

执行结果如下：

```Properties
contextI = null
contextI = null
contextI = null
contextI = null
contextI = null
contextI = null
contextI = null
contextI = null
contextI = null
contextI = null
cost:21
```

1. ## 业内解法

最常见的就是阿里的TransmittableThreadLocal，其使用方式是将ThreadLocal定义成TransmittableThreadLocal，同时将线程池包装成ExecutorServiceTtlWrapper，两者缺一不可。

例如，下面这段代码可以保证并发安全

```Java
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
```

执行结果如下：

```Properties
context value = ThreadLocal-from-main:0
context value = ThreadLocal-from-main:1
context value = ThreadLocal-from-main:2
context value = ThreadLocal-from-main:3
context value = ThreadLocal-from-main:4
context value = ThreadLocal-from-main:5
context value = ThreadLocal-from-main:6
context value = ThreadLocal-from-main:7
context value = ThreadLocal-from-main:8
context value = ThreadLocal-from-main:9
cost:26
```

**而在我们项目中很多脚手架，以及一些优秀的开源框架例如：Spring、MyBatis等，底层使用的都是JDK原生的ThreadLocal，此时无法修改其定义，还是会造成并发安全问题。**

其核心思想是封装了自定义的TTLRunable对象，在执行任务之前，先复制当前线程存储在holder中的值，`holder`中的值是在用户set的时候保存进去的，类似于Thread线程级别的map。然后在线程池中线程执行具体任务前，先将当前线程Holder中的数据备份，然后用之前复制的`holder`中的数据替换掉当前线程的Holder数据。执行完再通过备份的数据恢复当前线程的`holder`数据。

这样就可以在线程池中共享数据。解决线程池调用无法共享数据的问题。

1. ## 我们的解法

为了解决这个问题，我们自定义了SafeThreadPoolExecutorForThreadLocal线程池，确保针对ThreadLocal和InheritableThreadLocal都能保证并发安全。源码如下：

```Java
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
        test3();
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

}
```

其核心思想是在执行任务之前，先复制当前线程threadLocals和inheritableThreadLocals中的数据存储在临时变量中，然后在线程池中线程执行具体任务前，用临时变量数据覆盖当前线程的threadLocals和inheritableThreadLocals变量。**执行完将threadLocals和inheritableThreadLocals数据全部删除（因为我们要确保线程池是无状态的，所以没有必要恢复，这里和阿里的TTL不同）**。

这样就可以在线程池中共享数据。解决线程池调用无法共享数据的问题。

1. ## 性能对比

性能对比代码如下：

```java
public static void compare() throws Exception {
    for (int j = 0; j < 10000; j++) {
        ThreadLocal<String> context2 = new TransmittableThreadLocal<>();
        context2.set("ThreadLocal-from-main-inner:" + j);
        ThreadLocal<Map<String, Object>> context3 = new TransmittableThreadLocal<>();
        Map<String, Object> data = new HashMap<>();
        data.put("field1", new Object());
        data.put("field2", new Object());
        context3.set(data);
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
```

10次运行对比的运行结果如下：

```Properties
-------------0------------
compareSafe cost:55
compareTTL cost:2541
-------------1------------
compareSafe cost:12
compareTTL cost:2290
-------------2------------
compareSafe cost:7
compareTTL cost:2242
-------------3------------
compareSafe cost:6
compareTTL cost:2224
-------------4------------
compareSafe cost:5
compareTTL cost:2254
-------------5------------
compareSafe cost:6
compareTTL cost:2461
-------------6------------
compareSafe cost:7
compareTTL cost:2579
-------------7------------
compareSafe cost:5
compareTTL cost:2375
-------------8------------
compareSafe cost:5
compareTTL cost:2314
-------------9------------
compareSafe cost:5
compareTTL cost:2265
```

SafeThreadPoolExecutorForThreadLocal 线程池的性能在ThreadLocal 越多的情况下，性能越高，在ThreadLocal量级为10000的场景下，**性能比阿里的TransmittableThreadLocal快100多倍**，而且随着运行时间的累加，SafeThreadPoolExecutorForThreadLocal性能提升越来越高。
