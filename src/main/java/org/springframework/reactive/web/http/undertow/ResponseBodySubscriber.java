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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.Pooled;
import org.xnio.channels.StreamSinkChannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.xnio.ChannelListeners.closingChannelExceptionHandler;
import static org.xnio.ChannelListeners.flushingChannelListener;

/**
 * @author Marek Hawrylczak
 */
class ResponseBodySubscriber implements Subscriber<ByteBuffer>, ChannelListener<StreamSinkChannel> {

    private static final Log logger = LogFactory.getLog(ResponseBodySubscriber.class);

    private final HttpServerExchange exchange;
    private final Queue<Pooled<ByteBuffer>> buffers;

    private Subscription subscription;

    private StreamSinkChannel responseChannel;

    public ResponseBodySubscriber(HttpServerExchange exchange) {
        this.exchange = exchange;
        this.buffers = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        this.subscription.request(1);
    }

    @Override
    public void onNext(ByteBuffer buffer) {
        if (responseChannel == null) {
            responseChannel = exchange.getResponseChannel();
        }
        try {
            int c;
            do {
                c = responseChannel.write(buffer);
            } while (buffer.hasRemaining() && c > 0);
            if (buffer.hasRemaining()) {
                enqueue(buffer);
                responseChannel.getWriteSetter().set(this);
                responseChannel.resumeWrites();
            } else {
                this.subscription.request(1);
            }

        } catch (IOException ex) {
            onError(ex);
        }
    }

    private void enqueue(ByteBuffer src) {
        do {
            Pooled<ByteBuffer> pooledBuffer = exchange.getConnection().getBufferPool().allocate();
            ByteBuffer dst = pooledBuffer.getResource();
            copy(dst, src);
            dst.flip();
            buffers.add(pooledBuffer);
        } while (src.remaining() > 0);
    }

    private void copy(ByteBuffer dst, ByteBuffer src) {
        int n = Math.min(dst.capacity(), src.remaining());
        for (int i = 0; i < n; i++) {
            dst.put(src.get());
        }
    }

    @Override
    public void handleEvent(StreamSinkChannel channel) {
        try {
            int c;
            do {
                ByteBuffer buffer = buffers.peek().getResource();
                do {
                    c = channel.write(buffer);
                } while (buffer.hasRemaining() && c > 0);
                if (!buffer.hasRemaining()) {
                    buffers.remove().free();
                }
            } while (!buffers.isEmpty() && c > 0);
            if (!buffers.isEmpty()) {
                channel.resumeWrites();
            } else {
                this.subscription.request(1);
            }
        } catch (IOException ex) {
            onError(ex);
        }
    }

    @Override
    public void onError(Throwable t) {
        logger.error("ResponseBodySubscriber error", t);
    }

    @Override
    public void onComplete() {
        if (responseChannel != null) {
            writeDone(responseChannel);
        }
        logger.debug("onComplete");
    }

    protected void writeDone(final StreamSinkChannel channel) {
        try {
            channel.shutdownWrites();

            if (!channel.flush()) {
                channel.getWriteSetter().set(
                        flushingChannelListener(
                                o -> IoUtils.safeClose(channel),
                                closingChannelExceptionHandler()));
                channel.resumeWrites();

            }
        } catch (IOException ex) {
            onError(ex);
        }
    }
}
