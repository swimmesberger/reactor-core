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

package reactor.util.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.reactivestreams.Publisher;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry.RetrySignal;

class ExponentialBackoffFunction extends SimpleRetryFunction {

	final double    jitterFactor;
	final Duration  firstBackoff;
	final Duration  maxBackoff;
	final Scheduler backoffScheduler;

	ExponentialBackoffFunction(Retry.Builder builder) {
		super(builder);
		this.jitterFactor = builder.jitterFactor;
		if (jitterFactor < 0 || jitterFactor > 1) throw new IllegalArgumentException("jitterFactor must be between 0 and 1 (default 0.5)");
		this.firstBackoff = builder.minBackoff;
		this.maxBackoff = builder.maxBackoff;
		this.backoffScheduler = builder.backoffScheduler == null ? Schedulers.parallel() : builder.backoffScheduler;
	}

	@Override
	public Publisher<?> apply(Flux<RetrySignal> t) {
		return t.flatMap(retryWhenState -> {
			//capture the state immediately
			Throwable currentFailure = retryWhenState.failure();
			long iteration = isTransientErrors ? retryWhenState.failureSubsequentIndex() : retryWhenState.failureTotalIndex();

			if (currentFailure == null) {
				return Mono.error(new IllegalStateException("Retry.RetrySignal#failure() not expected to be null"));
			}

			if (!throwablePredicate.test(currentFailure)) {
				return Mono.error(currentFailure);
			}

			if (iteration >= maxAttempts) {
				return Mono.error(new IllegalStateException("Retries exhausted: " + iteration + "/" + maxAttempts, currentFailure));
			}

			Duration nextBackoff;
			try {
				nextBackoff = firstBackoff.multipliedBy((long) Math.pow(2, iteration));
				if (nextBackoff.compareTo(maxBackoff) > 0) {
					nextBackoff = maxBackoff;
				}
			}
			catch (ArithmeticException overflow) {
				nextBackoff = maxBackoff;
			}

			//short-circuit delay == 0 case
			if (nextBackoff.isZero()) {
				return Mono.just(iteration);
			}

			ThreadLocalRandom random = ThreadLocalRandom.current();

			long jitterOffset;
			try {
				jitterOffset = nextBackoff.multipliedBy((long) (100 * jitterFactor))
				                          .dividedBy(100)
				                          .toMillis();
			}
			catch (ArithmeticException ae) {
				jitterOffset = Math.round(Long.MAX_VALUE * jitterFactor);
			}
			long lowBound = Math.max(firstBackoff.minus(nextBackoff)
			                                     .toMillis(), -jitterOffset);
			long highBound = Math.min(maxBackoff.minus(nextBackoff)
			                                    .toMillis(), jitterOffset);

			long jitter;
			if (highBound == lowBound) {
				if (highBound == 0) jitter = 0;
				else jitter = random.nextLong(highBound);
			}
			else {
				jitter = random.nextLong(lowBound, highBound);
			}
			Duration effectiveBackoff = nextBackoff.plusMillis(jitter);
			return Mono.delay(effectiveBackoff, backoffScheduler);
		});
	}
}
