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

import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SimpleRetryFunction implements Function<Flux<Retry.RetrySignal>, Publisher<?>> {

	final long                         maxAttempts;
	final Predicate<? super Throwable> throwablePredicate;
	final boolean                      isTransientErrors;

	SimpleRetryFunction(Retry.Builder builder) {
		this.maxAttempts = builder.maxAttempts;
		this.throwablePredicate = builder.throwablePredicate;
		this.isTransientErrors = builder.isTransientErrors;
	}

	@Override
	public Publisher<?> apply(Flux<Retry.RetrySignal> flux) {
		return flux.flatMap(retryWhenState -> {
			//capture the state immediately
			Throwable currentFailure = retryWhenState.failure();
			long iteration = isTransientErrors ? retryWhenState.failureSubsequentIndex() : retryWhenState.failureTotalIndex();

			if (currentFailure == null) {
				return Mono.error(new IllegalStateException("RetryWhenState#failure() not expected to be null"));
			}

			if (!throwablePredicate.test(currentFailure)) {
				return Mono.error(currentFailure);
			}

			if (iteration >= maxAttempts) {
				return Mono.error(new IllegalStateException("Retries exhausted: " + iteration + "/" + maxAttempts, currentFailure));
			}

			return Mono.just(iteration);
		});
	}
}
