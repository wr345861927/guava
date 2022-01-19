package com.google.common.util.concurrent;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;
import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.util.concurrent.ClosingFuture.ClosingCallable;
import com.google.common.util.concurrent.ClosingFuture.DeferredCloser;
import com.google.common.util.concurrent.ClosingFuture.ValueAndCloser;
import com.google.common.util.concurrent.ClosingFuture.ValueAndCloserConsumer;
import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Tests for {@link ClosingFuture} that exercise {@link
 * ClosingFuture#finishToValueAndCloser(ValueAndCloserConsumer, Executor)}.
 */

public class ClosingFutureFinishToValueAndCloserTest extends ClosingFutureTest {

  private final ExecutorService finishToValueAndCloserExecutor = newSingleThreadExecutor();
  private volatile ValueAndCloser<?> valueAndCloser;

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    assertWithMessage("finishToValueAndCloserExecutor was shut down")
        .that(shutdownAndAwaitTermination(finishToValueAndCloserExecutor, 10, SECONDS))
        .isTrue();
  }

  public void testFinishToValueAndCloser_throwsIfCalledTwice() throws Exception {
    ClosingFuture<Closeable> closingFuture =
        ClosingFuture.submit(
            new ClosingCallable<Closeable>() {
              @Override
              public Closeable call(DeferredCloser closer) throws Exception {
                return closer.eventuallyClose(mockCloseable, executor);
              }
            },
            executor);
    closingFuture.finishToValueAndCloser(
        new NoOpValueAndCloserConsumer<>(), finishToValueAndCloserExecutor);
    try {
      closingFuture.finishToValueAndCloser(
          new NoOpValueAndCloserConsumer<>(), finishToValueAndCloserExecutor);
      fail("should have thrown");
    } catch (IllegalStateException expected) {
    }
  }

  public void testFinishToValueAndCloser_throwsAfterCallingFinishToFuture() throws Exception {
    ClosingFuture<Closeable> closingFuture =
        ClosingFuture.submit(
            new ClosingCallable<Closeable>() {
              @Override
              public Closeable call(DeferredCloser closer) throws Exception {
                return closer.eventuallyClose(mockCloseable, executor);
              }
            },
            executor);
    FluentFuture<Closeable> unused = closingFuture.finishToFuture();
    try {
      closingFuture.finishToValueAndCloser(
          new NoOpValueAndCloserConsumer<>(), finishToValueAndCloserExecutor);
      fail("should have thrown");
    } catch (IllegalStateException expected) {
    }
  }

  @Override
  <T> T getFinalValue(ClosingFuture<T> closingFuture) throws ExecutionException {
    return finishToValueAndCloser(closingFuture).get();
  }

  @Override
  void assertFinallyFailsWithException(ClosingFuture<?> closingFuture) {
    assertThatFutureFailsWithException(closingFuture.statusFuture());
    ValueAndCloser<?> valueAndCloser = finishToValueAndCloser(closingFuture);
    try {
      valueAndCloser.get();
      fail();
    } catch (ExecutionException expected) {
      assertThat(expected).hasCauseThat().isSameInstanceAs(exception);
    }
    valueAndCloser.closeAsync();
  }

  @Override
  void assertBecomesCanceled(ClosingFuture<?> closingFuture) throws ExecutionException {
    assertThatFutureBecomesCancelled(closingFuture.statusFuture());
  }

  @Override
  void waitUntilClosed(ClosingFuture<?> closingFuture) {
    if (valueAndCloser != null) {
      valueAndCloser.closeAsync();
    }
    super.waitUntilClosed(closingFuture);
  }

  @Override
  void cancelFinalStepAndWait(ClosingFuture<TestCloseable> closingFuture) {
    assertThat(closingFuture.cancel(false)).isTrue();
    ValueAndCloser<?> unused = finishToValueAndCloser(closingFuture);
    waitUntilClosed(closingFuture);
    futureCancelled.countDown();
  }

  private <V> ValueAndCloser<V> finishToValueAndCloser(ClosingFuture<V> closingFuture) {
    final CountDownLatch valueAndCloserSet = new CountDownLatch(1);
    closingFuture.finishToValueAndCloser(
        new ValueAndCloserConsumer<V>() {
          @Override
          public void accept(ValueAndCloser<V> valueAndCloser) {
            ClosingFutureFinishToValueAndCloserTest.this.valueAndCloser = valueAndCloser;
            valueAndCloserSet.countDown();
          }
        },
        finishToValueAndCloserExecutor);
    assertWithMessage("valueAndCloser was set")
        .that(awaitUninterruptibly(valueAndCloserSet, 10, SECONDS))
        .isTrue();
    @SuppressWarnings("unchecked")
    ValueAndCloser<V> valueAndCloserWithType = (ValueAndCloser<V>) valueAndCloser;
    return valueAndCloserWithType;
  }
}
