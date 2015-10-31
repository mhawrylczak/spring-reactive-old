package org.springframework.reactive.web.http.undertow;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.SameThreadExecutor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.reactive.util.DemandCounter;
import org.xnio.ChannelListener;
import org.xnio.Pooled;
import org.xnio.channels.StreamSourceChannel;
import reactor.core.support.BackpressureUtils;

import java.io.IOException;
import java.nio.ByteBuffer;

import static java.util.Objects.requireNonNull;
import static org.xnio.IoUtils.safeClose;


public class RequestBodyPublisher implements Publisher<ByteBuffer> {

    private final HttpServerExchange exchange;
    private Subscriber<? super ByteBuffer> subscriber;

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

    private class RequestBodySubscription implements Subscription , Runnable, ChannelListener<StreamSourceChannel> {
        private Pooled<ByteBuffer> pooledBuffer;
        private StreamSourceChannel channel;
        private final DemandCounter demand = new DemandCounter();
        private boolean cancelled;
        private boolean signalInProgress;
        private boolean complete;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void request(long n) {
            BackpressureUtils.checkRequest(n, subscriber);

            if (cancelled) {
                return;
            }

            demand.increase(n);
            scheduleNextSignal();
        }

        private void scheduleNextSignal() {
            exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(), this);
        }

        public boolean isSignalInProgress() {
            return signalInProgress;
        }

        public boolean isComplete() {
            return complete;
        }

        private void doOnNext(ByteBuffer buffer){
            buffer.flip();
            subscriber.onNext(buffer);
            signalInProgress = false;
        }

        private void doOnComplete(){
            subscriber.onComplete();
            signalInProgress = false;
            complete = true;
            close();
        }

        private void doOnError(Throwable t){
            subscriber.onError(t);
            signalInProgress = false;
            complete = true;
            close();
        }

        private void close(){
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
            if (cancelled || signalInProgress){
                return;
            }
            if(!demand.hasDemand()){
                //TODO  suspned channel ?
                return;
            }

            demand.decrement();
            signalInProgress = true;

            if (channel == null){
                this.channel = exchange.getRequestChannel();
            }
            if (pooledBuffer == null){
                this.pooledBuffer = exchange.getConnection().getBufferPool().allocate();
            }

            ByteBuffer buffer = pooledBuffer.getResource();
            buffer.clear();


            try {
                int count;
                do {
                    count = channel.read(buffer);
                    if (count == 0) {
                        channel.getReadSetter().set(this);
                        channel.resumeReads();
                    } else if (count == -1) {
                        if (buffer.position() > 0){
                           doOnNext(buffer);
                        }
                        doOnComplete();
                    } else {
                        if (buffer.remaining() == 0){
                            doOnNext(buffer);
                            scheduleNextSignal();
                            break;
                        }
                    }
                } while (count > 0 && isSignalInProgress());
            } catch (IOException e) {
                doOnError(e);
            }
        }

        @Override
        public void handleEvent(StreamSourceChannel channel) {
            if (cancelled){
                return;
            }

            ByteBuffer buffer = pooledBuffer.getResource();

            try {
                int count;
                do {
                    count = channel.read(buffer);
                    if (count == 0) {
                        return;
                    } else if (count == -1) {
                        doOnNext(buffer);
                        doOnComplete();
                    } else {
                        if (buffer.remaining() == 0){
                            doOnNext(buffer);
                            scheduleNextSignal();
                        }
                    }
                } while (count > 0 && isSignalInProgress());
            } catch (IOException e) {
                doOnError(e);
            }
        }
    }
}
