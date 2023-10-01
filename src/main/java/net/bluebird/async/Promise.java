package net.bluebird.async;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Represents the eventual completion (or failure) of an asynchronous operation and its resulting value.
 * @param <T> The value to be returned in promise
 */
public class Promise<T> {
    private volatile Exception rejected = null;
    private volatile T received;
    public volatile boolean isRejected = false;
    public volatile boolean isResolved = false;
    private volatile boolean isCaught = false;
    private final Object lock = new Object();
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile Callable<T> caught;
    private volatile Runnable onFinal = null;
    private volatile  boolean onFinalCalled = false;
    private final List<Consumer<T>> thenablesCallbacks = new ArrayList<>();

    public Promise() {}

    public Promise(@Nonnull Function<Consumer<Exception>, T> callback) {
        this.resolve(callback.apply(this::reject));
    }

    public Promise(@Nonnull BiConsumer<Consumer<T>, Consumer<Exception>> callback) {
        callback.accept(this::resolve, this::reject);
    }

    /**
     * Resolves the promise value
     * @param value The value to be resolved
     */
    public void resolve(T value) {
        this.receive(value);
    }

    private void receive(T t) {
        try {
            Thread thread = new Thread(() -> {
                try {
                    synchronized (lock) {
                        received = t;
                        isResolved = true;
                        lock.notifyAll();
                        latch.countDown();
                    }
                } catch (Exception e) {
                    synchronized (lock) {
                        _reject(e);
                        lock.notifyAll();
                        latch.countDown();
                    }
                }
            });

            thread.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Reject current promise
     * @param e Exception to reject promise
     */
    public void reject(@Nonnull Exception e) {
        synchronized (lock) {
            if (!isResolved && !isRejected) {
                _reject(e);
                lock.notifyAll();
                latch.countDown();
            }
        }
    }

    /**
     * Reject current promise
     * @param e Exception message to reject promise
     */
    public void reject(@Nonnull String e) {
        reject(new Exception(e));
    }

    private void _reject(Exception e) {
        rejected = e;
        isRejected = true;
        isRejected = true;
    }

    /**
     * If the current promise is rejected, the value passed in catch will be returned.
     * @param r Function that will be executed in catch with the value to be returned
     */
    public Promise<T> catchException(@Nonnull Callable<T> r) {
        isCaught = true;
        caught = r;

        Iterator<Consumer<T>> _thenablesCallbacks = thenablesCallbacks.iterator();

        if (_thenablesCallbacks.hasNext()) {
            try {
                Consumer<T> current = _thenablesCallbacks.next();
                thenablesCallbacks.remove(current);
                current.accept(r.call());
            } catch (Exception e) {
                // Ignore
            }

            if (!onFinalCalled) {
                onFinal.run();
            }
        }

        return this;
    }

    /**
     * Executes a function when the current promise ends
     * @param callback Function that will be executed at the end of the promise
     */
    public Promise<T> then(@Nonnull Consumer<T> callback) {
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        Consumer<T> callback2 = (value) -> {
            callback.accept(value);
            executorService.shutdown();
        };

        thenablesCallbacks.add(callback2);

        executorService.submit(() -> {
            Runnable shutdown = () -> {
                if (onFinal != null) {
                    onFinal.run();
                    onFinalCalled = true;
                }

                thenablesCallbacks.remove(callback2);
                executorService.shutdown();
            };

            Runnable closeAndAccept = () -> {
                callback.accept(received);
                shutdown.run();
            };
            Runnable closeAndCatch = () -> {
                try {
                    callback.accept(caught.call());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                shutdown.run();
            };

            synchronized (lock) {
                if (isResolved) {
                    closeAndAccept.run();
                } else if (isRejected) {
                    if (!isCaught) {
                        throw new RuntimeException(rejected.getMessage());
                    }

                    closeAndCatch.run();
                } else {
                    try {
                        lock.wait();

                        if (isResolved) {
                            closeAndAccept.run();
                        } else if (isRejected) {
                            if (!isCaught) {
                                throw new RuntimeException(rejected.getMessage());
                            }

                           closeAndCatch.run();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });

        return this;
    }

    /**
     * Executes a function if the current promise is resolved and executes another if it is rejected
     * @param callback Function that will be executed at the end of the promise
     * @param caught Function that will be executed at the promise is rejected
     */
    public Promise<T> then(@Nonnull Consumer<T> callback, @Nonnull Callable<T> caught) {
        catchException(caught);
        return then(callback);
    }

    /**
     * Executes a function at the end of the current promise regardless of whether it was resolved or rejected
     * @param fn Function that will be executed at the end of the promise
     */
    public Promise<T> onFinally(@Nonnull Runnable fn) {
        onFinal = fn;
        return this;
    }

    /**
     * Wait the result of this promise and return it
     */
    public T await() throws Exception {
        if (isRejected) {
            if (!isCaught) {
                throw rejected;
            }

            return caught.call();
        }

        latch.await();

        if (onFinal != null && !onFinalCalled) {
            onFinal.run();
            onFinalCalled = true;
        }

        return received;
    }

    /**
     * Returns a single promise with all the given promises resolved
     * @param promises The promises to be resolved
     * @param <T> The value to be returned in promise
     */
    @SafeVarargs
    public static <T> PromiseArray<T> all(Promise<T>... promises) {
        return new PromiseArray<>(promises);
    }

    /**
     * Returns a single promise with all the given promises resolved
     * @param values The values to be resolved
     * @param <T> The value to be returned in promise
     */
    @SafeVarargs
    public static <T> PromiseArray<T> all(T... values) {
        ArrayList<Promise<T>> promises = new ArrayList<>();

        for (T value : values) {
            promises.add(Promise.resolver(value));
        }

        Promise<T>[] arr = promises.toArray(new Promise[0]);

        return all(arr);
    }

    /**
     * Creates a new resolved promise
     * @param value The value to resolve promise
     * @param <T> The value to be returned in promise
     */
    public static <T> Promise<T> resolver(T value) {
        Promise<T> promise = new Promise<>();
        promise.resolve(value);
        return promise;
    }

    /**
     * Creates a new resolved promise
     * @param value The promise value to resolve promise
     * @param <T> The value to be returned in promise
     */
    public static <T> Promise<T> resolver(Promise<T> value) {
        Promise<T> promise = new Promise<>();
        try {
            promise.resolve(value.await());
        } catch (Exception e) {
            promise.reject(e);
        }

        return promise;
    }

    /**
     * Creates a new rejected promise
     * @param e The exception value to reject promise
     * @param <T> The value to be returned in promise
     */
    public static <T> @NotNull Promise<T> rejected(@Nonnull Exception e) {
        Promise<T> promise = new Promise<>();
        promise.reject(e);
        return promise;
    }

    /**
     * Creates a new rejected promise
     * @param e The exception message to reject promise
     * @param <T> The value to be returned in promise
     */
    public static <T> @NotNull Promise<T> rejected(@Nonnull String e) {
        Promise<T> promise = new Promise<>();
        promise.reject(e);
        return promise;
    }

    /**
     * Returns a promise that can be resolved or rejected as soon as one of the past promises is resolved or rejected
     * @param promises Promises to be resolved or rejected
     * @param <T> The value to be returned in promise
     */
    @SafeVarargs
    public static <T> Promise<T> race(Promise<T>... promises) {
        ExecutorService executorService = Executors.newFixedThreadPool(promises.length);

        return new Promise<>((resolve, reject) -> {
            try {
                T result = executorService.invokeAny(Arrays.stream(promises).map((cp) -> (Callable<T>) cp::await).toList());
                resolve.accept(result);
            } catch (InterruptedException | ExecutionException e) {
                reject.accept(e);
            }

            executorService.shutdown();
        });
    }
}
