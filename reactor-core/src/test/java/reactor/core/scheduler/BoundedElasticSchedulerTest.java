/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.pivovarit.function.ThrowingRunnable;
import org.assertj.core.data.Offset;
import org.awaitility.Awaitility;
import org.junit.AfterClass;
import org.junit.Test;

import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.BoundedElasticScheduler.BoundedServices;
import reactor.core.scheduler.BoundedElasticScheduler.BoundedState;
import reactor.test.MockUtils;
import reactor.util.Logger;
import reactor.util.Loggers;

import static org.assertj.core.api.Assertions.*;

/**
 * @author Simon Baslé
 */
public class BoundedElasticSchedulerTest extends AbstractSchedulerTest {

	private static final Logger LOGGER = Loggers.getLogger(BoundedElasticSchedulerTest.class);
	private static final AtomicLong COUNTER = new AtomicLong();

	static Stream<String> dumpThreadNames() {
		Thread[] tarray;
		for(;;) {
			tarray = new Thread[Thread.activeCount()];
			int dumped = Thread.enumerate(tarray);
			if (dumped <= tarray.length) {
				break;
			}
		}
		return Arrays.stream(tarray).filter(Objects::nonNull).map(Thread::getName);
	}

	@Override
	protected boolean shouldCheckInterrupted() {
		return true;
	}

	@AfterClass
	public static void dumpThreads() {
		LOGGER.debug("Remaining threads after test class:");
		LOGGER.debug(dumpThreadNames().collect(Collectors.joining(", ")));
	}

	@Override
	protected BoundedElasticScheduler scheduler() {
		return afterTest.autoDispose(
				new BoundedElasticScheduler(
						4, Integer.MAX_VALUE,
						new ReactorThreadFactory("boundedElasticSchedulerTest", COUNTER,
								false, false, Schedulers::defaultUncaughtException),
						10
				));
	}

	@Test
	public void extraWorkersShareBackingExecutorAndBoundedState() throws InterruptedException {
		Scheduler s = schedulerNotCached();

		ExecutorServiceWorker worker1 = (ExecutorServiceWorker) afterTest.autoDispose(s.createWorker());
		ExecutorServiceWorker worker2 = (ExecutorServiceWorker) afterTest.autoDispose(s.createWorker());
		ExecutorServiceWorker worker3 = (ExecutorServiceWorker) afterTest.autoDispose(s.createWorker());
		ExecutorServiceWorker worker4 = (ExecutorServiceWorker) afterTest.autoDispose(s.createWorker());
		ExecutorServiceWorker worker5 = (ExecutorServiceWorker) afterTest.autoDispose(s.createWorker());

		assertThat(worker1.exec)
				.as("worker1")
				.isNotSameAs(worker2.exec)
				.isNotSameAs(worker3.exec)
				.isNotSameAs(worker4.exec)
				.isSameAs(worker5.exec);
		assertThat(worker2.exec)
				.as("worker2")
				.isNotSameAs(worker3.exec)
				.isNotSameAs(worker4.exec);
		assertThat(worker3.exec)
				.as("worker3")
				.isNotSameAs(worker4.exec);

		BoundedState worker1BoundedState = Scannable
				.from(worker1.tasks).inners()
				.findFirst()
				.map(o -> (BoundedState) o)
				.get();

		BoundedState worker5BoundedState = Scannable
				.from(worker5.tasks).inners()
				.findFirst()
				.map(o -> (BoundedState) o)
				.get();

		assertThat(worker1BoundedState)
				.as("w1 w5 same BoundedState in tasks")
				.isSameAs(worker5BoundedState);
	}

	@Test
	public void doubleSubscribeOn() {
		BoundedElasticScheduler scheduler = afterTest.autoDispose(new BoundedElasticScheduler(1, Integer.MAX_VALUE,
				new ReactorThreadFactory("subscriberElastic", new AtomicLong(), false, false, null), 60));

		final Mono<Integer> integerMono = Mono
				.fromSupplier(() -> 1)
				.subscribeOn(scheduler)
				.subscribeOn(scheduler);

		integerMono.block(Duration.ofSeconds(3));
	}

	@Test
	public void testLargeNumberOfWorkers() throws InterruptedException {
		final int maxThreads = 3;
		final int maxQueue = 10;

		BoundedElasticScheduler scheduler = afterTest.autoDispose(new BoundedElasticScheduler(maxThreads, maxQueue,
				new ReactorThreadFactory("largeNumberOfWorkers", new AtomicLong(), false, false, null),
				1));

		CountDownLatch latch = new CountDownLatch(1);

		Flux<String> flux = Flux
				.range(1, maxQueue)
				.map(v -> {
					try {
						Thread.sleep(100);
					}
					catch (InterruptedException e) {
						e.printStackTrace();
					}
					return "value" + v;
				})
				.subscribeOn(scheduler)
				.doOnNext(v -> System.out.println("published " + v));

		for (int i = 0; i < maxQueue * maxThreads; i++) {
			flux = flux.publishOn(scheduler, false, 1 + i % 2);
		}

		flux.doFinally(sig -> latch.countDown())
		    .subscribe();

		assertThat(scheduler.estimateSize()).as("all 3 threads created").isEqualTo(3);

		assertThat(latch.await(11, TimeUnit.SECONDS)).as("completed").isTrue();

		Awaitility.await().atMost(1500, TimeUnit.MILLISECONDS)
		          .pollInterval(50, TimeUnit.MILLISECONDS)
		          .pollDelay(1, TimeUnit.SECONDS)
		          .untilAsserted(() -> assertThat(scheduler.estimateSize()).as("post eviction").isZero());
	}

	@Test
	public void testSmallTaskCapacityReached() {
		BoundedElasticScheduler scheduler = afterTest.autoDispose(new BoundedElasticScheduler(1, 2,
				new ReactorThreadFactory("testSmallTaskCapacityReached", new AtomicLong(), false, false, null), 60));

		assertThatExceptionOfType(RejectedExecutionException.class)
				.isThrownBy(() ->
						Flux.interval(Duration.ofSeconds(1), scheduler)
						    .doOnNext(ignored -> System.out.println("emitted"))
						    .publishOn(scheduler)
						    .publishOn(scheduler)
						    .publishOn(scheduler)
						    .publishOn(scheduler)
						    .doOnNext(ignored -> System.out.println("published"))
						    .blockFirst(Duration.ofSeconds(2))
				)
				.withMessage("Task capacity of bounded elastic scheduler reached while scheduling 1 tasks (3/2)");
	}

	@Test
	public void testSmallTaskCapacityJustEnough() {
		BoundedElasticScheduler scheduler = afterTest.autoDispose(new BoundedElasticScheduler(1, 2,
				new ReactorThreadFactory("testSmallTaskCapacityJustEnough", new AtomicLong(), false, false, null), 60));

		assertThat(Flux.interval(Duration.ofSeconds(1), scheduler)
		               .doOnNext(ignored -> System.out.println("emitted"))
		               .publishOn(scheduler)
		               .doOnNext(ignored -> System.out.println("published"))
		               .blockFirst(Duration.ofSeconds(2))
		).isEqualTo(0);
	}

	@Test
	public void TODO_TEST_MUTUALLY_DELAYING_TASKS() {
//		AtomicInteger taskRun = new Atomic Integer();
//		worker1.schedule(() -> {});
//		worker2.schedule(() -> {});
//		worker3.schedule(() -> {});
//		worker4.schedule(() -> {});
//		Disposable periodicDeferredTask = worker5.schedulePeriodically(taskRun::incrementAndGet, 0L, 100, TimeUnit.MILLISECONDS);
//
//		Awaitility.with().pollDelay(100, TimeUnit.MILLISECONDS)
//		          .untilAsserted(() -> assertThat(taskRun).as("task held due to worker cap").hasValue(0));
//
//		worker1.dispose(); //should trigger work stealing of worker5
//
//		Awaitility.waitAtMost(250, TimeUnit.MILLISECONDS)
//		          .untilAsserted(() -> assertThat(taskRun).as("task running periodically").hasValue(3));
//
//		periodicDeferredTask.dispose();
//
//		int onceCancelled = taskRun.get();
//		Awaitility.with()
//		          .pollDelay(200, TimeUnit.MILLISECONDS)
//		          .untilAsserted(() -> assertThat(taskRun).as("task has stopped").hasValue(onceCancelled));
	}

	@Test
	public void whenCapReachedPicksLeastBusyExecutor() throws InterruptedException {
		BoundedElasticScheduler s = scheduler();
		//reach the cap of workers
		BoundedState state1 = afterTest.autoDispose(s.boundedServices.pick());
		BoundedState state2 = afterTest.autoDispose(s.boundedServices.pick());
		BoundedState state3 = afterTest.autoDispose(s.boundedServices.pick());
		BoundedState state4 = afterTest.autoDispose(s.boundedServices.pick());

		assertThat(new HashSet<>(Arrays.asList(state1, state2, state3, state4))).as("4 distinct").hasSize(4);
		//cheat to make some look like more busy
		s.boundedServices.busyQueue.remove(state1);
		s.boundedServices.busyQueue.remove(state2);
		s.boundedServices.busyQueue.remove(state3);
		state1.markPicked();
		state1.markPicked();
		state1.markPicked();
		state2.markPicked();
		state2.markPicked();
		state3.markPicked();
		s.boundedServices.busyQueue.addAll(Arrays.asList(state1, state2, state3));

		assertThat(s.boundedServices.pick()).as("picked least busy state4").isSameAs(state4);
		//at this point state4 and state3 both are backing 1
		assertThat(Arrays.asList(s.boundedServices.pick(), s.boundedServices.pick()))
				.as("next 2 picks picked state4 and state3")
				.containsExactlyInAnyOrder(state4, state3);
	}

	@Test
	public void startNoOpIfStarted() {
		BoundedElasticScheduler s = scheduler();
		BoundedServices servicesBefore = s.boundedServices;

		s.start();
		s.start();
		s.start();

		assertThat(s.boundedServices).isSameAs(servicesBefore);
	}

	@Test
	public void restartSupported() {
		BoundedElasticScheduler s = scheduler();
		s.dispose();
		BoundedServices servicesBefore = s.boundedServices;

		assertThat(servicesBefore).as("SHUTDOWN").isSameAs(BoundedElasticScheduler.SHUTDOWN);

		s.start();

		assertThat(s.boundedServices)
				.isNotSameAs(servicesBefore)
				.hasValue(0);
	}

	// below tests similar to ElasticScheduler
	@Test
	public void negativeTtl() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new BoundedElasticScheduler(1, Integer.MAX_VALUE,null, -1))
				.withMessage("TTL must be strictly positive, was -1000ms");
	}

	@Test
	public void zeroTtl() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new BoundedElasticScheduler(1, Integer.MAX_VALUE,null, 0))
				.withMessage("TTL must be strictly positive, was 0ms");
	}

	@Test
	public void negativeThreadCap() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new BoundedElasticScheduler(-1, Integer.MAX_VALUE, null, 1))
				.withMessage("maxThreads must be strictly positive, was -1");
	}

	@Test
	public void zeroThreadCap() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new BoundedElasticScheduler(0, Integer.MAX_VALUE, null, 1))
				.withMessage("maxThreads must be strictly positive, was 0");
	}

	@Test
	public void negativeTaskCap() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new BoundedElasticScheduler(1, -1, null, 1))
				.withMessage("maxTaskQueuedPerThread must be strictly positive, was -1");
	}

	@Test
	public void zeroTaskCap() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new BoundedElasticScheduler(1, 0, null, 1))
				.withMessage("maxTaskQueuedPerThread must be strictly positive, was 0");
	}

	@Test
	public void evictionForWorkerScheduling() {
		MockUtils.VirtualClock clock = new MockUtils.VirtualClock(Instant.ofEpochMilli(1_000_000), ZoneId.systemDefault());
		BoundedElasticScheduler s = afterTest.autoDispose(new BoundedElasticScheduler(2, Integer.MAX_VALUE, r -> new Thread(r, "eviction"),
				60*1000, clock));
		BoundedServices services = s.boundedServices;

		Scheduler.Worker worker1 = afterTest.autoDispose(s.createWorker());

		assertThat(services).as("count worker 1").hasValue(2);
		assertThat(s.estimateSize()).as("non null size before workers 2 and 3").isEqualTo(1);

		Scheduler.Worker worker2 = afterTest.autoDispose(s.createWorker());
		Scheduler.Worker worker3 = afterTest.autoDispose(s.createWorker());

		assertThat(services).as("count worker 1 2 3").hasValue(2);
		assertThat(s.estimateSize()).as("3 workers equals 2 executors").isEqualTo(2);

		services.eviction();
		assertThat(s.estimateIdle()).as("not idle yet").isZero();

		clock.advanceTimeBy(Duration.ofMillis(1));
		worker1.dispose();
		worker2.dispose();
		worker3.dispose();
		clock.advanceTimeBy(Duration.ofMillis(10));
		services.eviction();

		assertThat(s.estimateIdle()).as("idle for 10 milliseconds").isEqualTo(2);

		clock.advanceTimeBy(Duration.ofMinutes(1));
		services.eviction();

		assertThat(s.estimateIdle()).as("idle for 1 minute and 10ms")
		                            .isEqualTo(s.estimateBusy())
		                            .isEqualTo(s.estimateSize())
		                            .isZero();
	}

	@Test
	public void lifoEvictionNoThreadRegrowthLoop() throws InterruptedException {
		for (int i = 0; i < 1000; i++) {
			lifoEvictionNoThreadRegrowth();
		}
	}

	@Test
	public void lifoEvictionNoThreadRegrowth() throws InterruptedException {
		BoundedElasticScheduler scheduler = afterTest.autoDispose(new BoundedElasticScheduler(200, Integer.MAX_VALUE,
				r -> new Thread(r, "dequeueEviction"), 1));
		int otherThreads = Thread.activeCount();
		try {

			int cacheSleep = 100; //slow tasks last 100ms
			int cacheCount = 100; //100 of slow tasks
			int fastSleep = 10;   //interval between fastTask scheduling
			int fastCount = 200;  //will schedule fast tasks up to 2s later

			CountDownLatch latch = new CountDownLatch(cacheCount + fastCount);
			for (int i = 0; i < cacheCount; i++) {
				Mono.fromRunnable(ThrowingRunnable.unchecked(() -> Thread.sleep(cacheSleep)))
				    .subscribeOn(scheduler)
				    .doFinally(sig -> latch.countDown())
				    .subscribe();
			}

			int[] threadCountTrend = new int[fastCount + 1];
			int threadCountChange = 1;

			int oldActive = 0;
			int activeAtBeginning = 0;
			int activeAtEnd = Integer.MAX_VALUE;
			for (int i = 0; i < fastCount; i++) {
				Mono.just(i)
				    .subscribeOn(scheduler)
				    .doFinally(sig -> latch.countDown())
				    .subscribe();

				if (i == 0) {
					activeAtBeginning = Thread.activeCount() - otherThreads;
					threadCountTrend[0] = activeAtBeginning;
					oldActive = activeAtBeginning;
					LOGGER.debug("{} threads active in round 1/{}", activeAtBeginning, fastCount);
				}
				else if (i == fastCount - 1) {
					activeAtEnd = Thread.activeCount() - otherThreads;
					threadCountTrend[threadCountChange] = activeAtEnd;
					LOGGER.debug("{} threads active in round {}/{}", activeAtEnd, i + 1, fastCount);
				}
				else {
					int newActive = Thread.activeCount() - otherThreads;
					if (oldActive != newActive) {
						threadCountTrend[threadCountChange++] = newActive;
						oldActive = newActive;
						LOGGER.debug("{} threads active in round {}/{}", newActive, i + 1, fastCount);
					}
				}
				Thread.sleep(fastSleep);
			}

			assertThat(scheduler.estimateBusy()).as("busy at end of loop").isZero();
			assertThat(threadCountTrend).as("no thread regrowth").isSortedAccordingTo(Comparator.reverseOrder());
			assertThat(activeAtEnd).as("almost all evicted at end").isCloseTo(0, Offset.offset(5));

			System.out.println(Arrays.toString(Arrays.copyOf(threadCountTrend, threadCountChange)));
		}
		finally {
			scheduler.dispose();
			Thread.sleep(100);
			final int postShutdown = Thread.activeCount() - otherThreads;
			LOGGER.info("{} threads active post shutdown", postShutdown);
			if (postShutdown > 0) System.out.println(Thread.getAllStackTraces().keySet() + "\n");
			assertThat(postShutdown).as("post shutdown").isNotPositive();
		}
	}
//
//	@Test
//	public void userWorkerShutdownBySchedulerDisposal() throws InterruptedException {
//		Scheduler s = afterTest.autoDispose(Schedulers.newBoundedElastic(4, Integer.MAX_VALUE, "boundedElasticUserThread", 10, false));
//		Scheduler.Worker w = afterTest.autoDispose(s.createWorker());
//
//		CountDownLatch latch = new CountDownLatch(1);
//		AtomicReference<String> threadName = new AtomicReference<>();
//
//		w.schedule(() -> {
//			threadName.set(Thread.currentThread().getName());
//			latch.countDown();
//		});
//
//		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch 5s").isTrue();
//
//		s.dispose();
//
//		Awaitility.with().pollInterval(100, TimeUnit.MILLISECONDS)
//		          .await().atMost(500, TimeUnit.MILLISECONDS)
//		          .untilAsserted(() -> assertThat(dumpThreadNames()).doesNotContain(threadName.get()));
//	}
//
//	@Test
//	public void deferredWorkerDisposedEarly() {
//		BoundedElasticScheduler s = afterTest.autoDispose(new BoundedElasticScheduler(1, Integer.MAX_VALUE, Thread::new,10));
//		Scheduler.Worker firstWorker = afterTest.autoDispose(s.createWorker());
//		Scheduler.Worker worker = s.createWorker();
//
//		assertThat(s.deferredFacades).as("deferred workers before inverted dispose").hasSize(1);
//		assertThat(s.idleServicesWithExpiry).as("threads before inverted dispose").isEmpty();
//
//		worker.dispose();
//		firstWorker.dispose();
//
//		assertThat(s.deferredFacades).as("deferred workers after inverted dispose").isEmpty();
//		assertThat(s.idleServicesWithExpiry).as("threads after inverted dispose").hasSize(1);
//	}
//
//	@Test
//	public void regrowFromEviction() throws InterruptedException {
//		BoundedElasticScheduler
//				scheduler = afterTest.autoDispose((BoundedElasticScheduler) Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "regrowFromEviction", 1));
//		Scheduler.Worker worker = scheduler.createWorker();
//		worker.schedule(() -> {});
//
//		List<BoundedElasticScheduler.CachedService> beforeEviction = new ArrayList<>(scheduler.allServices);
//		assertThat(beforeEviction)
//				.as("before eviction")
//				.hasSize(1);
//
//		worker.dispose();
//		//simulate an eviction 1s in the future
//		long fakeNow = System.currentTimeMillis() + 1001;
//		scheduler.eviction(() -> fakeNow);
//
//		assertThat(scheduler.allServices)
//				.as("after eviction")
//				.isEmpty();
//
//		Scheduler.Worker regrowWorker = afterTest.autoDispose(scheduler.createWorker());
//		assertThat(regrowWorker).isInstanceOf(BoundedElasticScheduler.ActiveWorker.class);
//		regrowWorker.schedule(() -> {});
//
//		assertThat(scheduler.allServices)
//				.as("after regrowth")
//				.isNotEmpty()
//				.hasSize(1)
//				.doesNotContainAnyElementsOf(beforeEviction);
//	}
//
//	@Test
//	public void taskCapIsSharedBetweenDirectAndIndirectDeferredScheduling() {
//		BoundedElasticScheduler
//				boundedElasticScheduler = afterTest.autoDispose(new BoundedElasticScheduler(1, 9, Thread::new, 10));
//		Scheduler.Worker activeWorker = afterTest.autoDispose(boundedElasticScheduler.createWorker());
//		Scheduler.Worker deferredWorker1 = boundedElasticScheduler.createWorker();
//		Scheduler.Worker deferredWorker2 = boundedElasticScheduler.createWorker();
//
//		//enqueue tasks in first deferred worker
//		deferredWorker1.schedule(() -> {});
//		deferredWorker1.schedule(() -> {}, 100, TimeUnit.MILLISECONDS);
//		deferredWorker1.schedulePeriodically(() -> {}, 100, 100, TimeUnit.MILLISECONDS);
//
//		//enqueue tasks in second deferred worker
//		deferredWorker2.schedule(() -> {});
//		deferredWorker2.schedule(() -> {}, 100, TimeUnit.MILLISECONDS);
//		deferredWorker2.schedulePeriodically(() -> {}, 100, 100, TimeUnit.MILLISECONDS);
//
//		//enqueue tasks directly on scheduler
//		boundedElasticScheduler.schedule(() -> {});
//		boundedElasticScheduler.schedule(() -> {}, 100, TimeUnit.MILLISECONDS);
//		boundedElasticScheduler.schedulePeriodically(() -> {}, 100, 100, TimeUnit.MILLISECONDS);
//
//		//any attempt at scheduling more task should result in rejection
//		assertThat(boundedElasticScheduler.remainingDeferredTasks).isEqualTo(0);
//		assertThatExceptionOfType(RejectedExecutionException.class).as("worker1 immediate").isThrownBy(() -> deferredWorker1.schedule(() -> {}));
//		assertThatExceptionOfType(RejectedExecutionException.class).as("worker1 delayed").isThrownBy(() -> deferredWorker1.schedule(() -> {}, 100, TimeUnit.MILLISECONDS));
//		assertThatExceptionOfType(RejectedExecutionException.class).as("worker1 periodic").isThrownBy(() -> deferredWorker1.schedulePeriodically(() -> {}, 100, 100, TimeUnit.MILLISECONDS));
//		assertThatExceptionOfType(RejectedExecutionException.class).as("worker2 immediate").isThrownBy(() -> deferredWorker2.schedule(() -> {}));
//		assertThatExceptionOfType(RejectedExecutionException.class).as("worker2 delayed").isThrownBy(() -> deferredWorker2.schedule(() -> {}, 100, TimeUnit.MILLISECONDS));
//		assertThatExceptionOfType(RejectedExecutionException.class).as("worker2 periodic").isThrownBy(() -> deferredWorker2.schedulePeriodically(() -> {}, 100, 100, TimeUnit.MILLISECONDS));
//		assertThatExceptionOfType(RejectedExecutionException.class).as("scheduler immediate").isThrownBy(() -> boundedElasticScheduler.schedule(() -> {}));
//		assertThatExceptionOfType(RejectedExecutionException.class).as("scheduler delayed").isThrownBy(() -> boundedElasticScheduler.schedule(() -> {}, 100, TimeUnit.MILLISECONDS));
//		assertThatExceptionOfType(RejectedExecutionException.class).as("scheduler periodic").isThrownBy(() -> boundedElasticScheduler.schedulePeriodically(() -> {}, 100, 100, TimeUnit.MILLISECONDS));
//	}
//
//	@Test
//	public void taskCapResetWhenDirectDeferredTaskIsExecuted() {
//		BoundedElasticScheduler
//				boundedElasticScheduler = afterTest.autoDispose(new BoundedElasticScheduler(1, 1, Thread::new, 10));
//		Scheduler.Worker activeWorker = boundedElasticScheduler.createWorker();
//
//		//enqueue tasks directly on scheduler
//		boundedElasticScheduler.schedule(() -> {});
//		assertThat(boundedElasticScheduler.remainingDeferredTasks).isEqualTo(0);
//
//		activeWorker.dispose();
//		assertThat(boundedElasticScheduler.remainingDeferredTasks).isEqualTo(1);
//	}
//
//	@Test
//	public void taskCapResetWhenWorkerDeferredTaskIsExecuted() {
//		BoundedElasticScheduler
//				boundedElasticScheduler = afterTest.autoDispose(new BoundedElasticScheduler(1, 1, Thread::new, 10));
//		Scheduler.Worker activeWorker = boundedElasticScheduler.createWorker();
//		Scheduler.Worker deferredWorker = boundedElasticScheduler.createWorker();
//
//		//enqueue tasks on deferred worker
//		deferredWorker.schedule(() -> {});
//		assertThat(boundedElasticScheduler.remainingDeferredTasks).isEqualTo(0);
//
//		activeWorker.dispose();
//		assertThat(boundedElasticScheduler.remainingDeferredTasks).isEqualTo(1);
//	}
//
//	@Test
//	public void taskCapResetWhenDirectDeferredTaskIsDisposed() {
//		BoundedElasticScheduler
//				boundedElasticScheduler = afterTest.autoDispose(new BoundedElasticScheduler(1, 1, Thread::new, 10));
//		Scheduler.Worker activeWorker = boundedElasticScheduler.createWorker();
//
//		Disposable d = boundedElasticScheduler.schedule(() -> {});
//		assertThat(boundedElasticScheduler.remainingDeferredTasks).isEqualTo(0);
//
//		d.dispose();
//		assertThat(boundedElasticScheduler.remainingDeferredTasks).isEqualTo(1);
//	}
//
//	@Test
//	public void taskCapResetWhenWorkerDeferredTaskIsDisposed() {
//		BoundedElasticScheduler
//				boundedElasticScheduler = afterTest.autoDispose(new BoundedElasticScheduler(1, 1, Thread::new, 10));
//		Scheduler.Worker activeWorker = boundedElasticScheduler.createWorker();
//		Scheduler.Worker deferredWorker = boundedElasticScheduler.createWorker();
//
//		//enqueue tasks on deferred worker
//		Disposable d = deferredWorker.schedule(() -> {});
//		assertThat(boundedElasticScheduler.remainingDeferredTasks).isEqualTo(0);
//
//		d.dispose();
//		assertThat(boundedElasticScheduler.remainingDeferredTasks).isEqualTo(1);
//	}
//
//	@Test
//	public void deferredWorkerTasksEventuallyExecuted() {
//		BoundedElasticScheduler
//				scheduler = afterTest.autoDispose((BoundedElasticScheduler) Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "test"));
//		Scheduler.Worker activeWorker = afterTest.autoDispose(scheduler.createWorker());
//		Scheduler.Worker deferredWorker = afterTest.autoDispose(scheduler.createWorker());
//
//		AtomicInteger runCount = new AtomicInteger();
//		deferredWorker.schedule(runCount::incrementAndGet);
//		deferredWorker.schedule(runCount::incrementAndGet, 10, TimeUnit.MILLISECONDS);
//		deferredWorker.schedulePeriodically(runCount::incrementAndGet, 10, 100_000, TimeUnit.MILLISECONDS);
//
//		assertThat(runCount).hasValue(0);
//		activeWorker.dispose();
//
//		Awaitility.with().pollDelay(0, TimeUnit.MILLISECONDS).and().pollInterval(10, TimeUnit.MILLISECONDS)
//		          .await().atMost(100, TimeUnit.MILLISECONDS)
//		          .untilAsserted(() -> assertThat(runCount).hasValue(3));
//	}
//
//	@Test
//	public void deferredDirectTasksEventuallyExecuted() {
//		BoundedElasticScheduler
//				scheduler = afterTest.autoDispose((BoundedElasticScheduler) Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "test"));
//		Scheduler.Worker activeWorker = afterTest.autoDispose(scheduler.createWorker());
//
//		AtomicInteger runCount = new AtomicInteger();
//		scheduler.schedule(runCount::incrementAndGet);
//		scheduler.schedule(runCount::incrementAndGet, 10, TimeUnit.MILLISECONDS);
//		scheduler.schedulePeriodically(runCount::incrementAndGet, 10, 100_000, TimeUnit.MILLISECONDS);
//
//		assertThat(runCount).hasValue(0);
//		activeWorker.dispose();
//
//		Awaitility.with().pollDelay(0, TimeUnit.MILLISECONDS).and().pollInterval(10, TimeUnit.MILLISECONDS)
//		          .await().atMost(100, TimeUnit.MILLISECONDS)
//		          .untilAsserted(() -> assertThat(runCount).hasValue(3));
//	}
//
//	@Test
//	public void deferredWorkerDisposalRemovesFromFacadeQueue() {
//		BoundedElasticScheduler
//				scheduler = afterTest.autoDispose((BoundedElasticScheduler) Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "test"));
//		Scheduler.Worker activeWorker = afterTest.autoDispose(scheduler.createWorker());
//		Scheduler.Worker deferredWorker = afterTest.autoDispose(scheduler.createWorker());
//
//		assertThat(scheduler.deferredFacades).as("before dispose")
//		                                     .hasSize(1)
//		                                     .containsExactly((BoundedElasticScheduler.DeferredFacade) deferredWorker);
//
//		deferredWorker.dispose();
//		assertThat(scheduler.deferredFacades).as("after dispose").isEmpty();
//	}
//
//	@Test
//	public void deferredDirectDisposalRemovesFromFacadeQueue() {
//		BoundedElasticScheduler
//				scheduler = afterTest.autoDispose((BoundedElasticScheduler) Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "test"));
//		Scheduler.Worker activeWorker = afterTest.autoDispose(scheduler.createWorker());
//		Disposable deferredDirect = scheduler.schedule(() -> {});
//
//		assertThat(scheduler.deferredFacades).as("before dispose")
//		                                     .hasSize(1)
//		                                     .containsExactly((BoundedElasticScheduler.DeferredFacade) deferredDirect);
//
//		deferredDirect.dispose();
//		assertThat(scheduler.deferredFacades).as("after dispose").isEmpty();
//	}
//
//	@Test
//	public void deferredWorkerSetServiceIgnoredIfDisposed() {
//		BoundedElasticScheduler
//				scheduler = afterTest.autoDispose((BoundedElasticScheduler) Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "test"));
//		BoundedElasticScheduler.ActiveWorker activeWorker = (BoundedElasticScheduler.ActiveWorker) afterTest.autoDispose(scheduler.createWorker());
//		BoundedElasticScheduler.DeferredWorker deferredWorker = (BoundedElasticScheduler.DeferredWorker) afterTest.autoDispose(scheduler.createWorker());
//
//		deferredWorker.dispose();
//
//		assertThat(scheduler.idleServicesWithExpiry).isEmpty();
//		deferredWorker.setService(afterTest.autoDispose(new BoundedElasticScheduler.CachedService(scheduler)));
//
//		assertThat(scheduler.idleServicesWithExpiry).hasSize(1);
//	}
//
//	@Test
//	public void deferredDirectSetServiceIgnoredIfDisposed() {
//		BoundedElasticScheduler
//				scheduler = afterTest.autoDispose((BoundedElasticScheduler) Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "test"));
//		BoundedElasticScheduler.ActiveWorker activeWorker = (BoundedElasticScheduler.ActiveWorker) afterTest.autoDispose(scheduler.createWorker());
//		BoundedElasticScheduler.DeferredDirect deferredDirect = (BoundedElasticScheduler.DeferredDirect) afterTest.autoDispose(scheduler.schedule(() -> {}));
//
//		deferredDirect.dispose();
//
//		assertThat(scheduler.idleServicesWithExpiry).isEmpty();
//		deferredDirect.setService(afterTest.autoDispose(new BoundedElasticScheduler.CachedService(scheduler)));
//
//		assertThat(scheduler.idleServicesWithExpiry).hasSize(1);
//	}
//
//	@Test
//	public void deferredWorkerRejectsTasksAfterBeingDisposed() {
//		BoundedElasticScheduler
//				scheduler = afterTest.autoDispose((BoundedElasticScheduler) Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "test"));
//		BoundedElasticScheduler.DeferredWorker deferredWorker = new BoundedElasticScheduler.DeferredWorker(scheduler);
//		deferredWorker.dispose();
//
//		assertThatExceptionOfType(RejectedExecutionException.class)
//				.isThrownBy(() -> deferredWorker.schedule(() -> {}))
//				.as("immediate schedule after dispose")
//				.withMessage("Worker has been disposed");
//
//		assertThatExceptionOfType(RejectedExecutionException.class)
//				.isThrownBy(() -> deferredWorker.schedule(() -> {}, 10, TimeUnit.MILLISECONDS))
//				.as("delayed schedule after dispose")
//				.withMessage("Worker has been disposed");
//
//		assertThatExceptionOfType(RejectedExecutionException.class)
//				.isThrownBy(() -> deferredWorker.schedulePeriodically(() -> {}, 10, 10, TimeUnit.MILLISECONDS))
//				.as("periodic schedule after dispose")
//				.withMessage("Worker has been disposed");
//	}
//
//
//	@Test
//	public void mixOfDirectAndWorkerTasksWithRejectionAfter100kLimit() {
//		AtomicInteger taskDone = new AtomicInteger();
//		AtomicInteger taskRejected = new AtomicInteger();
//
//		int limit = 100_000;
//		int workerCount = 70_000;
//
//		Scheduler scheduler = afterTest.autoDispose(Schedulers.newBoundedElastic(
//				1, limit,
//				"tasksRejectionAfter100kLimit"
//		));
//		Scheduler.Worker activeWorker = afterTest.autoDispose(scheduler.createWorker());
//		Scheduler.Worker fakeWorker = afterTest.autoDispose(scheduler.createWorker());
//
//		for (int i = 0; i < limit + 10; i++) {
//			try {
//				if (i < workerCount) {
//					//larger subset of tasks are submitted to the worker
//					fakeWorker.schedule(taskDone::incrementAndGet);
//				}
//				else if (i < limit) {
//					//smaller subset of tasks are submitted directly to the scheduler
//					scheduler.schedule(taskDone::incrementAndGet);
//				}
//				else if (i % 2 == 0) {
//					//half of over-limit tasks are submitted directly to the scheduler, half to worker
//					scheduler.schedule(taskDone::incrementAndGet);
//				}
//				else {
//					//half of over-limit tasks are submitted directly to the scheduler, half to worker
//					fakeWorker.schedule(taskDone::incrementAndGet);
//				}
//			}
//			catch (RejectedExecutionException ree) {
//				taskRejected.incrementAndGet();
//			}
//		}
//
//		assertThat(taskDone).as("taskDone before releasing activeWorker").hasValue(0);
//		assertThat(taskRejected).as("task rejected").hasValue(10);
//
//		activeWorker.dispose();
//
//		Awaitility.await().atMost(500, TimeUnit.MILLISECONDS)
//		          .untilAsserted(() ->
//				          assertThat(taskDone).as("all fakeWorker tasks done")
//				                              .hasValue(workerCount)
//		          );
//
//		fakeWorker.dispose();
//
//		//TODO maybe investigate: large amount of direct deferred tasks takes more time to be executed
//		Awaitility.await().atMost(10, TimeUnit.SECONDS)
//		          .untilAsserted(() ->
//				          assertThat(taskDone).as("all deferred tasks done")
//				                              .hasValue(limit)
//		          );
//	}
//
//	@Test
//	public void subscribeOnDisposesWorkerWhenCancelled() {
//		AtomicInteger taskExecuted = new AtomicInteger();
//
//		BoundedElasticScheduler bounded = afterTest.autoDispose(
//				new BoundedElasticScheduler(1, 100, new ReactorThreadFactory("disposeMonoSubscribeOn", new AtomicLong(), false, false, null), 60));
//		afterTest.autoDispose(bounded.createWorker());
//		Disposable.Composite tasks = Disposables.composite();
//
//		tasks.add(
//				Mono.fromRunnable(ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .subscribeOn(bounded)
//				    .subscribe()
//		);
//		tasks.add(
//				Mono.fromRunnable(ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .hide()
//				    .subscribeOn(bounded)
//				    .subscribe()
//		);
//		tasks.add(
//				Flux.just("foo")
//				    .map(v -> ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .subscribeOn(bounded)
//				    .subscribe()
//		);
//		tasks.add(
//				Flux.just("foo")
//				    .hide()
//				    .map(v -> ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .subscribeOn(bounded)
//				    .subscribe()
//		);
//		tasks.add(
//				//test the FluxCallable (not ScalarCallable) case
//				Mono.fromRunnable(ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .flux()
//				    .subscribeOn(bounded)
//				    .subscribe()
//		);
//
//		tasks.dispose();
//
//		assertThat(bounded.deferredFacades).isEmpty();
//		assertThat(taskExecuted).hasValue(0);
//	}
//
//	@Test
//	public void publishOnDisposesWorkerWhenCancelled() {
//		AtomicInteger taskExecuted = new AtomicInteger();
//
//		BoundedElasticScheduler bounded = afterTest.autoDispose(
//				new BoundedElasticScheduler(1, 100, new ReactorThreadFactory("disposeMonoSubscribeOn", new AtomicLong(), false, false, null), 60));
//		afterTest.autoDispose(bounded.createWorker());
//		Disposable.Composite tasks = Disposables.composite();
//
//		tasks.add(
//				Mono.fromCallable(() -> "init")
//				    .publishOn(bounded)
//				    .map(v -> ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .subscribe()
//		);
//		tasks.add(
//				Mono.fromCallable(() -> "init")
//				    .hide()
//				    .publishOn(bounded)
//				    .map(v -> ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .subscribe()
//		);
//		tasks.add(
//				Flux.just("foo")
//				    .publishOn(bounded)
//				    .map(v -> ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .subscribe()
//		);
//		tasks.add(
//				Flux.just("foo")
//				    .hide()
//				    .publishOn(bounded)
//				    .map(v -> ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .subscribe()
//		);
//		tasks.add(
//				//test the FluxCallable (not ScalarCallable) case
//				Mono.fromCallable(() -> "init")
//				    .flux()
//				    .publishOn(bounded)
//				    .map(v -> ThrowingRunnable.sneaky(() -> { Thread.sleep(1000); taskExecuted.incrementAndGet(); }))
//				    .subscribe()
//		);
//
//		tasks.dispose();
//
//		assertThat(bounded.deferredFacades).isEmpty();
//		assertThat(taskExecuted).hasValue(0);
//	}
//
//	//gh-1992 smoke test
//	@Test
//	public void gh1992_1() {
//		final Scheduler scheduler = Schedulers.newSingle("test");
////		final Scheduler scheduler = Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "test");
//
//		final Mono<Integer> integerMono = Mono
//				.fromSupplier(ThrowingSupplier.sneaky(() -> {
//					Thread.sleep(2000);
//					return 1;
//				}))
//				.subscribeOn(scheduler)
//				.subscribeOn(scheduler);
//
//		integerMono.block();
//	}
//
//	@Test
//	public void defaultBoundedElasticConfigurationIsConsistentWithJavadoc() {
//		Schedulers.CachedScheduler cachedBoundedElastic = (Schedulers.CachedScheduler) Schedulers.boundedElastic();
//		BoundedElasticScheduler boundedElastic = (BoundedElasticScheduler) cachedBoundedElastic.cached;
//
//		//10 x number of CPUs
//		assertThat(boundedElastic.threadCap)
//				.as("default boundedElastic size")
//				.isEqualTo(Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE)
//				.isEqualTo(Runtime.getRuntime().availableProcessors() * 10);
//
//		//60s TTL
//		assertThat(boundedElastic.ttlSeconds)
//				.as("default TTL")
//				.isEqualTo(BoundedElasticScheduler.DEFAULT_TTL_SECONDS)
//				.isEqualTo(60);
//
//		//100K bounded task queueing
//		assertThat(boundedElastic.deferredTaskCap)
//				.as("default unbounded task queueing")
//				.isEqualTo(100_000)
//				.isEqualTo(Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE);
//	}
//
//	@Test
//	public void scanName() {
//		Scheduler withNamedFactory = afterTest.autoDispose(Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, "scanName", 3));
//		Scheduler withBasicFactory = afterTest.autoDispose(Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, Thread::new, 3));
//		Scheduler withTaskCap = afterTest.autoDispose(Schedulers.newBoundedElastic(1, 123, Thread::new, 3));
//		Scheduler cached = Schedulers.boundedElastic();
//
//		Scheduler.Worker workerWithNamedFactory = afterTest.autoDispose(withNamedFactory.createWorker());
//		Scheduler.Worker deferredWorkerWithNamedFactory = afterTest.autoDispose(withNamedFactory.createWorker());
//		Scheduler.Worker workerWithBasicFactory = afterTest.autoDispose(withBasicFactory.createWorker());
//		Scheduler.Worker deferredWorkerWithBasicFactory = afterTest.autoDispose(withBasicFactory.createWorker());
//
//		assertThat(Scannable.from(withNamedFactory).scan(Scannable.Attr.NAME))
//				.as("withNamedFactory")
//				.isEqualTo("boundedElastic(\"scanName\",maxThreads=1,maxTaskQueued=unbounded,ttl=3s)");
//
//		assertThat(Scannable.from(withBasicFactory).scan(Scannable.Attr.NAME))
//				.as("withBasicFactory")
//				.isEqualTo("boundedElastic(maxThreads=1,maxTaskQueued=unbounded,ttl=3s)");
//
//		assertThat(Scannable.from(withTaskCap).scan(Scannable.Attr.NAME))
//				.as("withTaskCap")
//				.isEqualTo("boundedElastic(maxThreads=1,maxTaskQueued=123,ttl=3s)");
//
//		assertThat(cached)
//				.as("boundedElastic() is cached")
//				.is(SchedulersTest.CACHED_SCHEDULER);
//		assertThat(Scannable.from(cached).scan(Scannable.Attr.NAME))
//				.as("default boundedElastic()")
//				.isEqualTo("Schedulers.boundedElastic()");
//
//		assertThat(Scannable.from(workerWithNamedFactory).scan(Scannable.Attr.NAME))
//				.as("workerWithNamedFactory")
//				.isEqualTo("boundedElastic(\"scanName\",maxThreads=1,maxTaskQueued=unbounded,ttl=3s).worker");
//
//		assertThat(Scannable.from(workerWithBasicFactory).scan(Scannable.Attr.NAME))
//				.as("workerWithBasicFactory")
//				.isEqualTo("boundedElastic(maxThreads=1,maxTaskQueued=unbounded,ttl=3s).worker");
//
//		assertThat(Scannable.from(deferredWorkerWithNamedFactory).scan(Scannable.Attr.NAME))
//				.as("deferredWorkerWithNamedFactory")
//				.isEqualTo("boundedElastic(\"scanName\",maxThreads=1,maxTaskQueued=unbounded,ttl=3s).deferredWorker");
//
//		assertThat(Scannable.from(deferredWorkerWithBasicFactory).scan(Scannable.Attr.NAME))
//				.as("deferredWorkerWithBasicFactory")
//				.isEqualTo("boundedElastic(maxThreads=1,maxTaskQueued=unbounded,ttl=3s).deferredWorker");
//	}
//
//	@Test
//	public void scanCapacity() {
//		Scheduler scheduler = afterTest.autoDispose(Schedulers.newBoundedElastic(1, Integer.MAX_VALUE, Thread::new, 2));
//		Scheduler.Worker activeWorker = afterTest.autoDispose(scheduler.createWorker());
//		Scheduler.Worker deferredWorker = afterTest.autoDispose(scheduler.createWorker());
//
//		//smoke test that second worker is a DeferredWorker
//		assertThat(deferredWorker).as("check second worker is deferred").isExactlyInstanceOf(
//				BoundedElasticScheduler.DeferredWorker.class);
//
//		assertThat(Scannable.from(scheduler).scan(Scannable.Attr.CAPACITY)).as("scheduler capacity").isEqualTo(1);
//		assertThat(Scannable.from(activeWorker).scan(Scannable.Attr.CAPACITY)).as("active worker capacity").isEqualTo(1);
//		assertThat(Scannable.from(deferredWorker).scan(Scannable.Attr.CAPACITY)).as("deferred worker capacity").isEqualTo(Integer.MAX_VALUE);
//	}
//
//	@Test
//	public void scanDeferredDirect() {
//		BoundedElasticScheduler parent = afterTest.autoDispose(new BoundedElasticScheduler(3, 10, Thread::new, 60));
//		BoundedElasticScheduler.REMAINING_DEFERRED_TASKS.decrementAndGet(parent);
//
//		BoundedElasticScheduler.DeferredDirect deferredDirect = new BoundedElasticScheduler.DeferredDirect(() -> {}, 10, 10, TimeUnit.MILLISECONDS, parent);
//
//		assertThat(deferredDirect.scan(Scannable.Attr.TERMINATED)).as("TERMINATED").isFalse();
//		assertThat(deferredDirect.scan(Scannable.Attr.CANCELLED)).as("CANCELLED").isFalse();
//
//		assertThat(deferredDirect.scan(Scannable.Attr.NAME)).as("NAME").isEqualTo(parent.toString() + ".deferredDirect");
//
//		assertThat(deferredDirect.scan(Scannable.Attr.CAPACITY)).as("CAPACITY").isOne();
//		assertThat(deferredDirect.scan(Scannable.Attr.PARENT)).as("PARENT").isSameAs(parent);
//
//		//BUFFERED 1 as long as not realized
//		assertThat(deferredDirect.scan(Scannable.Attr.BUFFERED)).as("BUFFERED").isOne();
//		BoundedElasticScheduler.CachedService cachedService = afterTest.autoDispose(new BoundedElasticScheduler.CachedService(parent));
//		deferredDirect.set(cachedService);
//		assertThat(deferredDirect.scan(Scannable.Attr.BUFFERED)).as("BUFFERED with CachedService").isZero();
//
//		deferredDirect.dispose();
//		assertThat(deferredDirect.scan(Scannable.Attr.TERMINATED)).as("TERMINATED once disposed").isTrue();
//		assertThat(deferredDirect.scan(Scannable.Attr.CANCELLED)).as("CANCELLED once disposed").isTrue();
//	}
//
//	@Test
//	public void scanDeferredWorker() {
//		BoundedElasticScheduler parent = afterTest.autoDispose(new BoundedElasticScheduler(3, 10, Thread::new, 60));
//		BoundedElasticScheduler.REMAINING_DEFERRED_TASKS.decrementAndGet(parent);
//
//		BoundedElasticScheduler.DeferredWorker deferredWorker = new BoundedElasticScheduler.DeferredWorker(parent);
//
//		assertThat(deferredWorker.scan(Scannable.Attr.TERMINATED)).as("TERMINATED").isFalse();
//		assertThat(deferredWorker.scan(Scannable.Attr.CANCELLED)).as("CANCELLED").isFalse();
//		assertThat(deferredWorker.scan(Scannable.Attr.PARENT)).as("PARENT").isSameAs(parent);
//		assertThat(deferredWorker.scan(Scannable.Attr.NAME)).as("NAME").isEqualTo(parent.toString() + ".deferredWorker");
//
//		//capacity depends on parent's remaining tasks
//		assertThat(deferredWorker.scan(Scannable.Attr.CAPACITY)).as("CAPACITY").isEqualTo(9);
//		BoundedElasticScheduler.REMAINING_DEFERRED_TASKS.decrementAndGet(parent);
//		assertThat(deferredWorker.scan(Scannable.Attr.CAPACITY)).as("CAPACITY after remaingTask decrement").isEqualTo(8);
//
//
//		//BUFFERED depends on tasks queue size
//		assertThat(deferredWorker.scan(Scannable.Attr.BUFFERED)).as("BUFFERED").isZero();
//		deferredWorker.schedule(() -> {});
//		deferredWorker.schedule(() -> {});
//		assertThat(deferredWorker.scan(Scannable.Attr.BUFFERED)).as("BUFFERED once tasks submitted").isEqualTo(2);
//
//		deferredWorker.dispose();
//		assertThat(deferredWorker.scan(Scannable.Attr.TERMINATED)).as("TERMINATED once disposed").isTrue();
//		assertThat(deferredWorker.scan(Scannable.Attr.CANCELLED)).as("CANCELLED once disposed").isTrue();
//	}

	@Test
	public void toStringOfTtlInSplitSeconds() {
		String toString = afterTest.autoDispose(new BoundedElasticScheduler(1, 1, null, 1659, Clock.systemDefaultZone())).toString();
		assertThat(toString).endsWith("ttl=1s)");
	}

	@Test
	public void toStringOfTtlUnderOneSecond() {
		String toString = afterTest.autoDispose(new BoundedElasticScheduler(1, 1, null, 523, Clock.systemDefaultZone())).toString();
		assertThat(toString).endsWith("ttl=523ms)");
	}
}