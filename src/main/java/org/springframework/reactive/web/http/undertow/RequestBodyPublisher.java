package org.springframework.reactive.web.http.undertow;

import io.undertow.server.HttpServerExchange;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.Pool;
import org.xnio.Pooled;
import org.xnio.channels.StreamSourceChannel;
import reactor.core.support.BackpressureUtils;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

import static java.util.Objects.requireNonNull;
import static org.xnio.IoUtils.safeClose;


public class RequestBodyPublisher implements Publisher<ByteBuffer> {

    private final HttpServerExchange exchange;

    private Subscriber<? super ByteBuffer> subscriber;
    private boolean cancelled;

    public RequestBodyPublisher(HttpServerExchange exchange) {
        requireNonNull(exchange);
        this.exchange = exchange;
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
        requireNonNull(exchange);
        if (this.subscriber != null) {
            subscriber.onError(new IllegalStateException("Only one subscriber allowed"));
        }

        this.subscriber = subscriber;
        this.subscriber.onSubscribe(new RequestBodySubscription());
    }

    private class RequestBodySubscription implements Subscription, Closeable, ChannelListener<StreamSourceChannel> {
        private Pooled<ByteBuffer> pooledBuffer;
        private ByteBuffer buffer;
        private StreamSourceChannel channel;
        private long demand = 0; //TODO demand is not respected now
        private long messagesCount = 0;

        public RequestBodySubscription() {
            this.pooledBuffer = exchange.getConnection().getBufferPool().allocate();
            this.buffer = pooledBuffer.getResource();
            this.channel = exchange.getRequestChannel();
        }

        @Override
        public void request(long n) {
            BackpressureUtils.checkRequest(n, subscriber);

            if (cancelled) {
                return;
            }
            demand += n;

            try {
                int r;
                do {
                    r = channel.read(buffer);
                    if (r == 0) {
                        channel.getReadSetter().set(this);
                        channel.resumeReads();
                    } else if (r == -1) {
                        closeChannel();
                        subscriber.onComplete();
                        safeClose(this);
                    } else {
                        if (buffer.remaining() == 0){
                            buffer.flip();
                            subscriber.onNext(buffer);
                            buffer.clear();
                        }
                    }
                } while (r > 0);
            } catch (IOException e) {
                subscriber.onError(e);
                safeClose(this);
            }

        }

        @Override
        public void handleEvent(StreamSourceChannel channel) {
            if (cancelled){
                return;
            }

            try {
                int r;
                do {
                    r = channel.read(buffer);
                    if (r == 0) {
                        return;
                    } else if (r == -1) {
                        closeChannel();
                        buffer.flip();
                        subscriber.onNext(buffer);
                        subscriber.onComplete();
                        safeClose(this);
                    } else {
                        if (buffer.remaining() == 0){
                            buffer.flip();
                            subscriber.onNext(buffer);
                            buffer.clear();
                        }
                    }
                } while (r > 0);
            } catch (IOException e) {
                subscriber.onError(e);
                safeClose(this);
            }
        }

        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            safeClose(this);
        }

        private void closeChannel(){
            if (channel != null) {
                safeClose(channel);
                channel = null;
            }
        }

        @Override
        public void close() throws IOException {
            closeChannel();
            if (pooledBuffer != null) {
                pooledBuffer.free();
                pooledBuffer = null;
                buffer = null;
            }
        }

    }
}
