/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.reactive.web.http.undertow;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.SameThreadExecutor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.xnio.ChannelListener;
import org.xnio.Pooled;
import org.xnio.channels.StreamSourceChannel;
import reactor.core.support.BackpressureUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static java.util.Objects.requireNonNull;
import static org.xnio.IoUtils.safeClose;

/**
 * @author Marek Hawrylczak
 */
class RequestBodyPublisher implements Publisher<ByteBuffer> {

    private final HttpServerExchange exchange;
    private Subscriber<? super ByteBuffer> subscriber;

    static final AtomicLongFieldUpdater<RequestBodySubscription> DEMAND =
            AtomicLongFieldUpdater.newUpdater(RequestBodySubscription.class, "demand");

    public RequestBodyPublisher(HttpServerExchange exchange) {
        requireNonNull(exchange);
        this.exchange = exchange;
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
        requireNonNull(subscriber);
        if (this.subscriber != null) {
            subscriber.onError(new IllegalStateException("Only one subscriber allowed"));
        }

        this.subscriber = subscriber;
        subscriber.onSubscribe(new RequestBodySubscription());
    }

    private class RequestBodySubscription implements Subscription, Runnable, ChannelListener<StreamSourceChannel> {
        private Pooled<ByteBuffer> pooledBuffer;
        private StreamSourceChannel channel;
        volatile long demand;


        private boolean subscriptionClosed;
        private boolean signalInProgress;

        @Override
        public void cancel() {
            subscriptionClosed = true;
            close();
        }

        @Override
        public void request(long n) {
            BackpressureUtils.checkRequest(n, subscriber);

            if (subscriptionClosed) {
                return;
            }

            BackpressureUtils.getAndAdd(DEMAND, this, n);
            scheduleNextSignal();
        }

        private void scheduleNextSignal() {
            exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(), this);
        }

        private void doOnNext(ByteBuffer buffer) {
            signalInProgress = false;
            buffer.flip();
            subscriber.onNext(buffer);
        }

        private void doOnComplete() {
            subscriptionClosed = true;
            try {
                subscriber.onComplete();
            } finally {
                close();
            }
        }

        private void doOnError(Throwable t) {
            subscriptionClosed = true;
            try {
                subscriber.onError(t);
            } finally {
                close();
            }
        }

        private void close() {
            if (pooledBuffer != null) {
                pooledBuffer.free();
                pooledBuffer = null;
            }
            if (channel != null) {
                safeClose(channel);
                channel = null;
            }
        }

        @Override
        public void run() {
            if (subscriptionClosed || signalInProgress) {
                return;
            }

            if (0 == BackpressureUtils.getAndSub(DEMAND, this, 1)) {
                return;
            }

            signalInProgress = true;

            if (channel == null) {
                channel = exchange.getRequestChannel();
            }
            if (pooledBuffer == null) {
                pooledBuffer = exchange.getConnection().getBufferPool().allocate();
            } else {
                pooledBuffer.getResource().clear();
            }

            try {
                ByteBuffer buffer = pooledBuffer.getResource();
                int count;
                do {
                    count = channel.read(buffer);
                    if (count == 0) {
                        channel.getReadSetter().set(this);
                        channel.resumeReads();
                    } else if (count == -1) {
                        if (buffer.position() > 0) {
                            doOnNext(buffer);
                        }
                        doOnComplete();
                    } else {
                        if (buffer.remaining() == 0) {
                            if (demand == 0) {
                                channel.suspendReads();
                            }
                            doOnNext(buffer);
                            if (demand > 0) {
                                scheduleNextSignal();
                            }
                            break;
                        }
                    }
                } while (count > 0);
            } catch (IOException e) {
                doOnError(e);
            }
        }

        @Override
        public void handleEvent(StreamSourceChannel channel) {
            if (subscriptionClosed) {
                return;
            }

            try {
                ByteBuffer buffer = pooledBuffer.getResource();
                int count;
                do {
                    count = channel.read(buffer);
                    if (count == 0) {
                        return;
                    } else if (count == -1) {
                        if (buffer.position() > 0) {
                            doOnNext(buffer);
                        }
                        doOnComplete();
                    } else {
                        if (buffer.remaining() == 0) {
                            if (demand == 0) {
                                channel.suspendReads();
                            }
                            doOnNext(buffer);
                            if (demand > 0) {
                                scheduleNextSignal();
                            }
                            break;
                        }
                    }
                } while (count > 0);
            } catch (IOException e) {
                doOnError(e);
            }
        }
    }
}
