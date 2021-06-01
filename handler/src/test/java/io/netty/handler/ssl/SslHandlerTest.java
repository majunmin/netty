/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.handler.ssl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CodecException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.ImmediateExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.EmptyArrays;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Test;
import org.junit.jupiter.api.Disabled;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.security.NoSuchAlgorithmException;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.X509ExtendedTrustManager;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

public class SslHandlerTest {

    @Test(timeout = 5000)
    public void testNonApplicationDataFailureFailsQueuedWrites() throws NoSuchAlgorithmException, InterruptedException {
        final CountDownLatch writeLatch = new CountDownLatch(1);
        final Queue<ChannelPromise> writesToFail = new ConcurrentLinkedQueue<ChannelPromise>();
        SSLEngine engine = newClientModeSSLEngine();
        SslHandler handler = new SslHandler(engine) {
            @Override
            public ChannelFuture write(final ChannelHandlerContext ctx, Object msg) {
                ChannelFuture future =  super.write(ctx, msg);
                writeLatch.countDown();
                return future;
            }
        };
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelHandler() {
            @Override
            public ChannelFuture write(ChannelHandlerContext ctx, Object msg) {
                 try {
                     if (msg instanceof ByteBuf) {
                         if (((ByteBuf) msg).isReadable()) {
                             ChannelPromise promise = ctx.newPromise();
                             writesToFail.add(promise);
                             return promise;
                         }
                     }
                     return ctx.newSucceededFuture();
                 } finally {
                     ReferenceCountUtil.release(msg);
                 }
            }
        }, handler);

        try {
            final CountDownLatch writeCauseLatch = new CountDownLatch(1);
            final AtomicReference<Throwable> failureRef = new AtomicReference<Throwable>();
            ch.write(Unpooled.wrappedBuffer(new byte[]{1})).addListener((ChannelFutureListener) future -> {
                failureRef.compareAndSet(null, future.cause());
                writeCauseLatch.countDown();
            });
            writeLatch.await();

            // Simulate failing the SslHandler non-application writes after there are applications writes queued.
            ChannelPromise promiseToFail;
            while ((promiseToFail = writesToFail.poll()) != null) {
                promiseToFail.setFailure(new RuntimeException("fake exception"));
            }

            writeCauseLatch.await();
            Throwable writeCause = failureRef.get();
            assertNotNull(writeCause);
            assertThat(writeCause, is(CoreMatchers.<Throwable>instanceOf(SSLException.class)));
            Throwable cause = handler.handshakeFuture().cause();
            assertNotNull(cause);
            assertThat(cause, is(CoreMatchers.<Throwable>instanceOf(SSLException.class)));
        } finally {
            assertFalse(ch.finishAndReleaseAll());
        }
    }

    @Test
    public void testNoSslHandshakeEventWhenNoHandshake() throws Exception {
        final AtomicBoolean inActive = new AtomicBoolean(false);

        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        EmbeddedChannel ch = new EmbeddedChannel(
                DefaultChannelId.newInstance(), false, false, new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                // Not forward the event to the SslHandler but just close the Channel.
                ctx.close();
            }
        }, new SslHandler(engine) {
            @Override
            public void handlerAdded0(ChannelHandlerContext ctx) throws Exception {
                // We want to override what Channel.isActive() will return as otherwise it will
                // return true and so trigger an handshake.
                inActive.set(true);
                super.handlerAdded0(ctx);
                inActive.set(false);
            }
        }, new ChannelHandler() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof SslHandshakeCompletionEvent) {
                    throw (Exception) ((SslHandshakeCompletionEvent) evt).cause();
                }
            }
        }) {
            @Override
            public boolean isActive() {
                return !inActive.get() && super.isActive();
            }
        };

        ch.register();
        assertFalse(ch.finishAndReleaseAll());
    }

    @Test(expected = SslHandshakeTimeoutException.class, timeout = 3000)
    public void testClientHandshakeTimeout() throws Throwable {
        testHandshakeTimeout(true);
    }

    @Test(expected = SslHandshakeTimeoutException.class, timeout = 3000)
    public void testServerHandshakeTimeout() throws Throwable {
        testHandshakeTimeout(false);
    }

    private static SSLEngine newServerModeSSLEngine() throws NoSuchAlgorithmException {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        // Set the mode before we try to do the handshake as otherwise it may throw an IllegalStateException.
        // See:
        //  - https://docs.oracle.com/javase/10/docs/api/javax/net/ssl/SSLEngine.html#beginHandshake()
        //  - https://mail.openjdk.java.net/pipermail/security-dev/2018-July/017715.html
        engine.setUseClientMode(false);
        return engine;
    }

    private static SSLEngine newClientModeSSLEngine() throws NoSuchAlgorithmException {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        // Set the mode before we try to do the handshake as otherwise it may throw an IllegalStateException.
        // See:
        //  - https://docs.oracle.com/javase/10/docs/api/javax/net/ssl/SSLEngine.html#beginHandshake()
        //  - https://mail.openjdk.java.net/pipermail/security-dev/2018-July/017715.html
        engine.setUseClientMode(true);
        return engine;
    }

    private static void testHandshakeTimeout(boolean client) throws Throwable {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        engine.setUseClientMode(client);
        SslHandler handler = new SslHandler(engine);
        handler.setHandshakeTimeoutMillis(1);

        EmbeddedChannel ch = new EmbeddedChannel(handler);
        try {
            while (!handler.handshakeFuture().isDone()) {
                Thread.sleep(10);
                // We need to run all pending tasks as the handshake timeout is scheduled on the EventLoop.
                ch.runPendingTasks();
            }

            handler.handshakeFuture().syncUninterruptibly();
        } catch (CompletionException e) {
            throw e.getCause();
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test(timeout = 5000L)
    public void testHandshakeAndClosePromiseFailedOnRemoval() throws Exception {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        engine.setUseClientMode(true);
        SslHandler handler = new SslHandler(engine);
        final AtomicReference<Throwable> handshakeRef = new AtomicReference<>();
        final AtomicReference<Throwable> closeRef = new AtomicReference<>();
        EmbeddedChannel ch = new EmbeddedChannel(handler, new ChannelHandler() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof SslHandshakeCompletionEvent) {
                    handshakeRef.set(((SslHandshakeCompletionEvent) evt).cause());
                } else if (evt instanceof SslCloseCompletionEvent) {
                    closeRef.set(((SslCloseCompletionEvent) evt).cause());
                }
            }
        });
        assertFalse(handler.handshakeFuture().isDone());
        assertFalse(handler.sslCloseFuture().isDone());

        ch.pipeline().remove(handler);

        try {
            while (!handler.handshakeFuture().isDone() || handshakeRef.get() == null
                    || !handler.sslCloseFuture().isDone() || closeRef.get() == null) {
                Thread.sleep(10);
                // Continue running all pending tasks until we notified for everything.
                ch.runPendingTasks();
            }

            assertSame(handler.handshakeFuture().cause(), handshakeRef.get());
            assertSame(handler.sslCloseFuture().cause(), closeRef.get());
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    public void testTruncatedPacket() throws Exception {
        SSLEngine engine = newServerModeSSLEngine();
        EmbeddedChannel ch = new EmbeddedChannel(new SslHandler(engine));

        // Push the first part of a 5-byte handshake message.
        ch.writeInbound(wrappedBuffer(new byte[]{22, 3, 1, 0, 5}));

        // Should decode nothing yet.
        assertThat(ch.readInbound(), is(nullValue()));

        try {
            // Push the second part of the 5-byte handshake message.
            ch.writeInbound(wrappedBuffer(new byte[]{2, 0, 0, 1, 0}));
            fail();
        } catch (DecoderException e) {
            // Be sure we cleanup the channel and release any pending messages that may have been generated because
            // of an alert.
            // See https://github.com/netty/netty/issues/6057.
            ch.finishAndReleaseAll();

            // The pushed message is invalid, so it should raise an exception if it decoded the message correctly.
            assertThat(e.getCause(), is(instanceOf(SSLProtocolException.class)));
        }
    }

    @Test
    public void testNonByteBufWriteIsReleased() throws Exception {
        SSLEngine engine = newServerModeSSLEngine();
        EmbeddedChannel ch = new EmbeddedChannel(new SslHandler(engine));

        AbstractReferenceCounted referenceCounted = new AbstractReferenceCounted() {
            @Override
            public ReferenceCounted touch(Object hint) {
                return this;
            }

            @Override
            protected void deallocate() {
            }
        };
        try {
            ch.write(referenceCounted).get();
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), is(instanceOf(UnsupportedMessageTypeException.class)));
        }
        assertEquals(0, referenceCounted.refCnt());
        assertTrue(ch.finishAndReleaseAll());
    }

    @Test(expected = UnsupportedMessageTypeException.class)
    public void testNonByteBufNotPassThrough() throws Exception {
        SSLEngine engine = newServerModeSSLEngine();
        EmbeddedChannel ch = new EmbeddedChannel(new SslHandler(engine));

        try {
            ch.writeOutbound(new Object());
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    public void testIncompleteWriteDoesNotCompletePromisePrematurely() throws NoSuchAlgorithmException {
        SSLEngine engine = newServerModeSSLEngine();
        EmbeddedChannel ch = new EmbeddedChannel(new SslHandler(engine));

        ByteBuf buf = Unpooled.buffer(10).writeZero(10);
        ChannelFuture future = ch.writeAndFlush(buf);
        assertFalse(future.isDone());
        assertTrue(ch.finishAndReleaseAll());
        assertTrue(future.isDone());
        assertThat(future.cause(), is(instanceOf(SSLException.class)));
    }

    @Test
    public void testReleaseSslEngine() throws Exception {
        OpenSsl.ensureAvailability();

        SelfSignedCertificate cert = new SelfSignedCertificate();
        try {
            SslContext sslContext = SslContextBuilder.forServer(cert.certificate(), cert.privateKey())
                .sslProvider(SslProvider.OPENSSL)
                .build();
            try {
                assertEquals(1, ((ReferenceCounted) sslContext).refCnt());
                SSLEngine sslEngine = sslContext.newEngine(ByteBufAllocator.DEFAULT);
                EmbeddedChannel ch = new EmbeddedChannel(new SslHandler(sslEngine));

                assertEquals(2, ((ReferenceCounted) sslContext).refCnt());
                assertEquals(1, ((ReferenceCounted) sslEngine).refCnt());

                assertTrue(ch.finishAndReleaseAll());
                ch.close().syncUninterruptibly();

                assertEquals(1, ((ReferenceCounted) sslContext).refCnt());
                assertEquals(0, ((ReferenceCounted) sslEngine).refCnt());
            } finally {
                ReferenceCountUtil.release(sslContext);
            }
        } finally {
            cert.delete();
        }
    }

    private static final class TlsReadTest implements ChannelHandler {
        private volatile boolean readIssued;

        @Override
        public void read(ChannelHandlerContext ctx) {
            readIssued = true;
            ctx.read();
        }

        public void test(final boolean dropChannelActive) throws Exception {
            SSLEngine engine = SSLContext.getDefault().createSSLEngine();
            engine.setUseClientMode(true);

            EmbeddedChannel ch = new EmbeddedChannel(false, false,
                    this,
                    new SslHandler(engine),
                    new ChannelHandler() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) throws Exception {
                            if (!dropChannelActive) {
                                ctx.fireChannelActive();
                            }
                        }
                    }
            );
            ch.config().setAutoRead(false);
            assertFalse(ch.config().isAutoRead());

            ch.register();

            assertTrue(readIssued);
            readIssued = false;

            assertTrue(ch.writeOutbound(Unpooled.EMPTY_BUFFER));
            assertTrue(readIssued);
            assertTrue(ch.finishAndReleaseAll());
       }
    }

    @Test
    public void testIssueReadAfterActiveWriteFlush() throws Exception {
        // the handshake is initiated by channelActive
        new TlsReadTest().test(false);
    }

    @Test
    public void testIssueReadAfterWriteFlushActive() throws Exception {
        // the handshake is initiated by flush
        new TlsReadTest().test(true);
    }

    @Test(timeout = 30000)
    public void testRemoval() throws Exception {
        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        Channel sc = null;
        Channel cc = null;
        try {
            final Promise<Void> clientPromise = group.next().newPromise();
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(newHandler(SslContextBuilder.forClient().trustManager(
                            InsecureTrustManagerFactory.INSTANCE).build(), clientPromise));

            SelfSignedCertificate ssc = new SelfSignedCertificate();
            final Promise<Void> serverPromise = group.next().newPromise();
            ServerBootstrap serverBootstrap = new ServerBootstrap()
                    .group(group, group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(newHandler(SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build(),
                            serverPromise));
            sc = serverBootstrap.bind(new InetSocketAddress(0)).syncUninterruptibly().channel();
            cc = bootstrap.connect(sc.localAddress()).syncUninterruptibly().channel();

            serverPromise.syncUninterruptibly();
            clientPromise.syncUninterruptibly();
        } finally {
            if (cc != null) {
                cc.close().syncUninterruptibly();
            }
            if (sc != null) {
                sc.close().syncUninterruptibly();
            }
            group.shutdownGracefully();
        }
    }

    private static ChannelHandler newHandler(final SslContext sslCtx, final Promise<Void> promise) {
        return new ChannelInitializer() {
            @Override
            protected void initChannel(final Channel ch) {
                final SslHandler sslHandler = sslCtx.newHandler(ch.alloc());
                sslHandler.setHandshakeTimeoutMillis(1000);
                ch.pipeline().addFirst(sslHandler);
                sslHandler.handshakeFuture().addListener((FutureListener<Channel>) future -> {
                    ch.eventLoop().execute(() -> {
                        ch.pipeline().remove(sslHandler);

                        // Schedule the close so removal has time to propagate exception if any.
                        ch.eventLoop().execute(ch::close);
                    });
                });

                ch.pipeline().addLast(new ChannelHandler() {
                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        if (cause instanceof CodecException) {
                            cause = cause.getCause();
                        }
                        if (cause instanceof IllegalReferenceCountException) {
                            promise.setFailure(cause);
                        }
                        cause.printStackTrace();
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        promise.trySuccess(null);
                    }
                });
            }
        };
    }

    @Test
    public void testCloseFutureNotified() throws Exception {
        SSLEngine engine = newServerModeSSLEngine();
        SslHandler handler = new SslHandler(engine);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.close();

        // When the channel is closed the SslHandler will write an empty buffer to the channel.
        ByteBuf buf = ch.readOutbound();
        assertFalse(buf.isReadable());
        buf.release();

        assertFalse(ch.finishAndReleaseAll());

        assertTrue(handler.handshakeFuture().cause() instanceof ClosedChannelException);
        assertTrue(handler.sslCloseFuture().cause() instanceof ClosedChannelException);
    }

    @Test(timeout = 5000)
    public void testEventsFired() throws Exception {
        SSLEngine engine = newServerModeSSLEngine();
        final BlockingQueue<SslCompletionEvent> events = new LinkedBlockingQueue<>();
        EmbeddedChannel channel = new EmbeddedChannel(new SslHandler(engine), new ChannelHandler() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof SslCompletionEvent) {
                    events.add((SslCompletionEvent) evt);
                }
            }
        });
        assertTrue(events.isEmpty());
        assertTrue(channel.finishAndReleaseAll());

        SslCompletionEvent evt = events.take();
        assertTrue(evt instanceof SslHandshakeCompletionEvent);
        assertTrue(evt.cause() instanceof ClosedChannelException);

        evt = events.take();
        assertTrue(evt instanceof SslCloseCompletionEvent);
        assertTrue(evt.cause() instanceof ClosedChannelException);
        assertTrue(events.isEmpty());
    }

    @Test(timeout = 5000)
    public void testHandshakeFailBeforeWritePromise() throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        final SslContext sslServerCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch latch2 = new CountDownLatch(2);
        final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        Channel serverChannel = null;
        Channel clientChannel = null;
        EventLoopGroup group = new MultithreadEventLoopGroup(LocalHandler.newFactory());
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sb.group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                      ch.pipeline().addLast(sslServerCtx.newHandler(ch.alloc()));
                      ch.pipeline().addLast(new ChannelHandler() {
                          @Override
                          public void channelActive(ChannelHandlerContext ctx) {
                              ByteBuf buf = ctx.alloc().buffer(10);
                              buf.writeZero(buf.capacity());
                              ctx.writeAndFlush(buf).addListener((ChannelFutureListener) future -> {
                                  events.add(future);
                                  latch.countDown();
                              });
                          }

                          @Override
                          public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                              if (evt instanceof SslCompletionEvent) {
                                  events.add(evt);
                                  latch.countDown();
                                  latch2.countDown();
                              }
                          }
                      });
                  }
                });

            Bootstrap cb = new Bootstrap();
            cb.group(group)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addFirst(new ChannelHandler() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ByteBuf buf = ctx.alloc().buffer(1000);
                                buf.writeZero(buf.capacity());
                                ctx.writeAndFlush(buf);
                            }
                        });
                    }
                });

            serverChannel = sb.bind(new LocalAddress("SslHandlerTest")).sync().channel();
            clientChannel = cb.connect(serverChannel.localAddress()).sync().channel();
            latch.await();

            SslCompletionEvent evt = (SslCompletionEvent) events.take();
            assertTrue(evt instanceof SslHandshakeCompletionEvent);
            assertThat(evt.cause(), is(instanceOf(SSLException.class)));

            evt = (SslCompletionEvent) events.take();
            assertTrue(evt instanceof SslCloseCompletionEvent);
            assertThat(evt.cause(), is(instanceOf(ClosedChannelException.class)));

            ChannelFuture future = (ChannelFuture) events.take();
            assertThat(future.cause(), is(instanceOf(SSLException.class)));

            serverChannel.close().sync();
            serverChannel = null;
            clientChannel.close().sync();
            clientChannel = null;

            latch2.await();
            assertTrue(events.isEmpty());
        } finally {
            if (serverChannel != null) {
                serverChannel.close();
            }
            if (clientChannel != null) {
                clientChannel.close();
            }
            group.shutdownGracefully();
        }
    }

    @Test
    public void writingReadOnlyBufferDoesNotBreakAggregation() throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();

        final SslContext sslServerCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();

        final SslContext sslClientCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE).build();

        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        Channel sc = null;
        Channel cc = null;
        final CountDownLatch serverReceiveLatch = new CountDownLatch(1);
        try {
            final int expectedBytes = 11;
            sc = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(sslServerCtx.newHandler(ch.alloc()));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                private int readBytes;
                                @Override
                                protected void messageReceived(ChannelHandlerContext ctx,
                                        ByteBuf msg) throws Exception {
                                    readBytes += msg.readableBytes();
                                    if (readBytes >= expectedBytes) {
                                        serverReceiveLatch.countDown();
                                    }
                                }
                            });
                        }
                    }).bind(new InetSocketAddress(0)).syncUninterruptibly().channel();

            cc = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(sslClientCtx.newHandler(ch.alloc()));
                        }
                    }).connect(sc.localAddress()).syncUninterruptibly().channel();

            // We first write a ReadOnlyBuffer because SslHandler will attempt to take the first buffer and append to it
            // until there is no room, or the aggregation size threshold is exceeded. We want to verify that we don't
            // throw when a ReadOnlyBuffer is used and just verify that we don't aggregate in this case.
            ByteBuf firstBuffer = Unpooled.buffer(10);
            firstBuffer.writeByte(0);
            firstBuffer = firstBuffer.asReadOnly();
            ByteBuf secondBuffer = Unpooled.buffer(10);
            secondBuffer.writeZero(secondBuffer.capacity());
            cc.write(firstBuffer);
            cc.writeAndFlush(secondBuffer).syncUninterruptibly();
            serverReceiveLatch.countDown();
        } finally {
            if (cc != null) {
                cc.close().syncUninterruptibly();
            }
            if (sc != null) {
                sc.close().syncUninterruptibly();
            }
            group.shutdownGracefully();

            ReferenceCountUtil.release(sslServerCtx);
            ReferenceCountUtil.release(sslClientCtx);
        }
    }

    @Test(timeout = 10000)
    public void testCloseOnHandshakeFailure() throws Exception {
        final SelfSignedCertificate ssc = new SelfSignedCertificate();

        final SslContext sslServerCtx = SslContextBuilder.forServer(ssc.key(), ssc.cert()).build();
        final SslContext sslClientCtx = SslContextBuilder.forClient()
                .trustManager(new SelfSignedCertificate().cert())
                .build();

        EventLoopGroup group = new MultithreadEventLoopGroup(1, LocalHandler.newFactory());
        Channel sc = null;
        Channel cc = null;
        try {
            LocalAddress address = new LocalAddress(getClass().getSimpleName() + ".testCloseOnHandshakeFailure");
            ServerBootstrap sb = new ServerBootstrap()
                    .group(group)
                    .channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(sslServerCtx.newHandler(ch.alloc()));
                        }
                    });
            sc = sb.bind(address).syncUninterruptibly().channel();

            final AtomicReference<SslHandler> sslHandlerRef = new AtomicReference<>();
            Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(LocalChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            SslHandler handler = sslClientCtx.newHandler(ch.alloc());

                            // We propagate the SslHandler via an AtomicReference to the outer-scope as using
                            // pipeline.get(...) may return null if the pipeline was teared down by the time we call it.
                            // This will happen if the channel was closed in the meantime.
                            sslHandlerRef.set(handler);
                            ch.pipeline().addLast(handler);
                        }
                    });
            cc = b.connect(sc.localAddress()).syncUninterruptibly().channel();
            SslHandler handler = sslHandlerRef.get();
            handler.handshakeFuture().awaitUninterruptibly();
            assertFalse(handler.handshakeFuture().isSuccess());

            cc.closeFuture().syncUninterruptibly();
        } finally {
            if (cc != null) {
                cc.close().syncUninterruptibly();
            }
            if (sc != null) {
                sc.close().syncUninterruptibly();
            }
            group.shutdownGracefully();

            ReferenceCountUtil.release(sslServerCtx);
            ReferenceCountUtil.release(sslClientCtx);
        }
    }

    @Test
    public void testOutboundClosedAfterChannelInactive() throws Exception {
        SslContext context = SslContextBuilder.forClient().build();
        SSLEngine engine = context.newEngine(UnpooledByteBufAllocator.DEFAULT);

        EmbeddedChannel channel = new EmbeddedChannel();
        assertFalse(channel.finish());
        channel.pipeline().addLast(new SslHandler(engine));
        assertFalse(engine.isOutboundDone());
        channel.close().syncUninterruptibly();

        assertTrue(engine.isOutboundDone());
    }

    @Test(timeout = 10000)
    public void testHandshakeFailedByWriteBeforeChannelActive() throws Exception {
        final SslContext sslClientCtx = SslContextBuilder.forClient()
                                                         .protocols(SslUtils.PROTOCOL_SSL_V3)
                                                         .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                                         .sslProvider(SslProvider.JDK).build();

        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        Channel sc = null;
        Channel cc = null;
        final CountDownLatch activeLatch = new CountDownLatch(1);
        final AtomicReference<AssertionError> errorRef = new AtomicReference<>();
        final SslHandler sslHandler = sslClientCtx.newHandler(UnpooledByteBufAllocator.DEFAULT);
        try {
            sc = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelHandler() { })
                    .bind(new InetSocketAddress(0)).syncUninterruptibly().channel();

            cc = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(sslHandler);
                            ch.pipeline().addLast(new ChannelHandler() {
                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                                        throws Exception {
                                    if (cause instanceof AssertionError) {
                                        errorRef.set((AssertionError) cause);
                                    }
                                }

                                @Override
                                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                    activeLatch.countDown();
                                }
                            });
                        }
                    }).connect(sc.localAddress()).addListener((ChannelFutureListener) future -> {
                        // Write something to trigger the handshake before fireChannelActive is called.
                        future.channel().writeAndFlush(wrappedBuffer(new byte [] { 1, 2, 3, 4 }));
                    }).syncUninterruptibly().channel();

            // Ensure there is no AssertionError thrown by having the handshake failed by the writeAndFlush(...) before
            // channelActive(...) was called. Let's first wait for the activeLatch countdown to happen and after this
            // check if we saw and AssertionError (even if we timed out waiting).
            activeLatch.await(5, TimeUnit.SECONDS);
            AssertionError error = errorRef.get();
            if (error != null) {
                throw error;
            }
            assertThat(sslHandler.handshakeFuture().await().cause(),
                       CoreMatchers.instanceOf(SSLException.class));
        } finally {
            if (cc != null) {
                cc.close().syncUninterruptibly();
            }
            if (sc != null) {
                sc.close().syncUninterruptibly();
            }
            group.shutdownGracefully();

            ReferenceCountUtil.release(sslClientCtx);
        }
    }

    @Test(timeout = 10000)
    public void testHandshakeTimeoutFlushStartsHandshake() throws Exception {
        testHandshakeTimeout0(false);
    }

    @Test(timeout = 10000)
    public void testHandshakeTimeoutStartTLS() throws Exception {
        testHandshakeTimeout0(true);
    }

    private static void testHandshakeTimeout0(final boolean startTls) throws Exception {
        final SslContext sslClientCtx = SslContextBuilder.forClient()
                                                         .startTls(true)
                                                         .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                                         .sslProvider(SslProvider.JDK).build();

        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        Channel sc = null;
        Channel cc = null;
        final SslHandler sslHandler = sslClientCtx.newHandler(UnpooledByteBufAllocator.DEFAULT);
        sslHandler.setHandshakeTimeout(500, TimeUnit.MILLISECONDS);

        try {
            sc = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelHandler() { })
                    .bind(new InetSocketAddress(0)).syncUninterruptibly().channel();

            ChannelFuture future = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(sslHandler);
                            if (startTls) {
                                ch.pipeline().addLast(new ChannelHandler() {
                                    @Override
                                    public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                        ctx.writeAndFlush(wrappedBuffer(new byte[] { 1, 2, 3, 4 }));
                                    }
                                });
                            }
                        }
                    }).connect(sc.localAddress());
            if (!startTls) {
                future.addListener((ChannelFutureListener) future1 -> {
                    // Write something to trigger the handshake before fireChannelActive is called.
                    future1.channel().writeAndFlush(wrappedBuffer(new byte [] { 1, 2, 3, 4 }));
                });
            }
            cc = future.syncUninterruptibly().channel();

            Throwable cause = sslHandler.handshakeFuture().await().cause();
            assertThat(cause, CoreMatchers.instanceOf(SSLException.class));
            assertThat(cause.getMessage(), containsString("timed out"));
        } finally {
            if (cc != null) {
                cc.close().syncUninterruptibly();
            }
            if (sc != null) {
                sc.close().syncUninterruptibly();
            }
            group.shutdownGracefully();
            ReferenceCountUtil.release(sslClientCtx);
        }
    }

    @Test
    public void testHandshakeWithExecutorThatExecuteDirecty() throws Exception {
        testHandshakeWithExecutor(command -> command.run());
    }

    @Test
    public void testHandshakeWithImmediateExecutor() throws Exception {
        testHandshakeWithExecutor(ImmediateExecutor.INSTANCE);
    }

    @Test
    public void testHandshakeWithImmediateEventExecutor() throws Exception {
        testHandshakeWithExecutor(ImmediateEventExecutor.INSTANCE);
    }

    @Test
    public void testHandshakeWithExecutor() throws Exception {
        ExecutorService executorService = Executors.newCachedThreadPool();
        try {
            testHandshakeWithExecutor(executorService);
        } finally {
            executorService.shutdown();
        }
    }

    private static void testHandshakeWithExecutor(Executor executor) throws Exception {
        final SslContext sslClientCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .sslProvider(SslProvider.JDK).build();

        final SelfSignedCertificate cert = new SelfSignedCertificate();
        final SslContext sslServerCtx = SslContextBuilder.forServer(cert.key(), cert.cert())
                .sslProvider(SslProvider.JDK).build();

        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        Channel sc = null;
        Channel cc = null;
        final SslHandler clientSslHandler = sslClientCtx.newHandler(UnpooledByteBufAllocator.DEFAULT, executor);
        final SslHandler serverSslHandler = sslServerCtx.newHandler(UnpooledByteBufAllocator.DEFAULT, executor);

        try {
            sc = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(serverSslHandler)
                    .bind(new InetSocketAddress(0)).syncUninterruptibly().channel();

            ChannelFuture future = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(clientSslHandler);
                        }
                    }).connect(sc.localAddress());
            cc = future.syncUninterruptibly().channel();

            assertTrue(clientSslHandler.handshakeFuture().await().isSuccess());
            assertTrue(serverSslHandler.handshakeFuture().await().isSuccess());
        } finally {
            if (cc != null) {
                cc.close().syncUninterruptibly();
            }
            if (sc != null) {
                sc.close().syncUninterruptibly();
            }
            group.shutdownGracefully();
            ReferenceCountUtil.release(sslClientCtx);
        }
    }

    @Test
    public void testClientHandshakeTimeoutBecauseExecutorNotExecute() throws Exception {
        testHandshakeTimeoutBecauseExecutorNotExecute(true);
    }

    @Test
    public void testServerHandshakeTimeoutBecauseExecutorNotExecute() throws Exception {
        testHandshakeTimeoutBecauseExecutorNotExecute(false);
    }

    private static void testHandshakeTimeoutBecauseExecutorNotExecute(final boolean client) throws Exception {
        final SslContext sslClientCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .sslProvider(SslProvider.JDK).build();

        final SelfSignedCertificate cert = new SelfSignedCertificate();
        final SslContext sslServerCtx = SslContextBuilder.forServer(cert.key(), cert.cert())
                .sslProvider(SslProvider.JDK).build();

        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        Channel sc = null;
        Channel cc = null;
        final SslHandler clientSslHandler = sslClientCtx.newHandler(UnpooledByteBufAllocator.DEFAULT, command -> {
            if (!client) {
                command.run();
            }
            // Do nothing to simulate slow execution.
        });
        if (client) {
            clientSslHandler.setHandshakeTimeout(100, TimeUnit.MILLISECONDS);
        }
        final SslHandler serverSslHandler = sslServerCtx.newHandler(UnpooledByteBufAllocator.DEFAULT, command -> {
            if (client) {
                command.run();
            }
            // Do nothing to simulate slow execution.
        });
        if (!client) {
            serverSslHandler.setHandshakeTimeout(100, TimeUnit.MILLISECONDS);
        }
        try {
            sc = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(serverSslHandler)
                    .bind(new InetSocketAddress(0)).syncUninterruptibly().channel();

            ChannelFuture future = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(clientSslHandler);
                        }
                    }).connect(sc.localAddress());
            cc = future.syncUninterruptibly().channel();

            if (client) {
                Throwable cause = clientSslHandler.handshakeFuture().await().cause();
                assertThat(cause, CoreMatchers.<Throwable>instanceOf(SslHandshakeTimeoutException.class));
                assertFalse(serverSslHandler.handshakeFuture().await().isSuccess());
            } else {
                Throwable cause = serverSslHandler.handshakeFuture().await().cause();
                assertThat(cause, CoreMatchers.<Throwable>instanceOf(SslHandshakeTimeoutException.class));
                assertFalse(clientSslHandler.handshakeFuture().await().isSuccess());
            }
        } finally {
            if (cc != null) {
                cc.close().syncUninterruptibly();
            }
            if (sc != null) {
                sc.close().syncUninterruptibly();
            }
            group.shutdownGracefully();
            ReferenceCountUtil.release(sslClientCtx);
        }
    }

    @Test(timeout = 5000L)
    public void testSessionTicketsWithTLSv12() throws Throwable {
        testSessionTickets(SslProvider.OPENSSL, SslUtils.PROTOCOL_TLS_V1_2, true);
    }

    @Test(timeout = 5000L)
    public void testSessionTicketsWithTLSv13() throws Throwable {
        assumeTrue(SslProvider.isTlsv13Supported(SslProvider.OPENSSL));
        testSessionTickets(SslProvider.OPENSSL, SslUtils.PROTOCOL_TLS_V1_3, true);
    }

    @Test(timeout = 5000L)
    public void testSessionTicketsWithTLSv12AndNoKey() throws Throwable {
        testSessionTickets(SslProvider.OPENSSL, SslUtils.PROTOCOL_TLS_V1_2, false);
    }

    @Test(timeout = 5000L)
    public void testSessionTicketsWithTLSv13AndNoKey() throws Throwable {
        assumeTrue(SslProvider.isTlsv13Supported(SslProvider.OPENSSL));
        testSessionTickets(SslProvider.OPENSSL, SslUtils.PROTOCOL_TLS_V1_3, false);
    }

    private static void testSessionTickets(SslProvider provider, String protocol, boolean withKey) throws Throwable {
        OpenSsl.ensureAvailability();
        final SslContext sslClientCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .sslProvider(provider)
                .protocols(protocol)
                .build();

        // Explicit enable session cache as it's disabled by default atm.
        ((OpenSslContext) sslClientCtx).sessionContext()
                .setSessionCacheEnabled(true);

        final SelfSignedCertificate cert = new SelfSignedCertificate();
        final SslContext sslServerCtx = SslContextBuilder.forServer(cert.key(), cert.cert())
                .sslProvider(provider)
                .protocols(protocol)
                .build();

        if (withKey) {
            OpenSslSessionTicketKey key = new OpenSslSessionTicketKey(new byte[OpenSslSessionTicketKey.NAME_SIZE],
                    new byte[OpenSslSessionTicketKey.HMAC_KEY_SIZE], new byte[OpenSslSessionTicketKey.AES_KEY_SIZE]);
            ((OpenSslSessionContext) sslClientCtx.sessionContext()).setTicketKeys(key);
            ((OpenSslSessionContext) sslServerCtx.sessionContext()).setTicketKeys(key);
        } else {
            ((OpenSslSessionContext) sslClientCtx.sessionContext()).setTicketKeys();
            ((OpenSslSessionContext) sslServerCtx.sessionContext()).setTicketKeys();
        }

        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        Channel sc = null;
        final byte[] bytes = new byte[96];
        ThreadLocalRandom.current().nextBytes(bytes);
        try {
            final AtomicReference<AssertionError> assertErrorRef = new AtomicReference<AssertionError>();
            sc = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            final SslHandler sslHandler = sslServerCtx.newHandler(ch.alloc());
                            ch.pipeline().addLast(sslServerCtx.newHandler(UnpooledByteBufAllocator.DEFAULT));
                            ch.pipeline().addLast(new ChannelHandler() {

                                private int handshakeCount;

                                @Override
                                public void userEventTriggered(ChannelHandlerContext ctx, Object evt)  {
                                    if (evt instanceof SslHandshakeCompletionEvent) {
                                        handshakeCount++;
                                        ReferenceCountedOpenSslEngine engine =
                                                (ReferenceCountedOpenSslEngine) sslHandler.engine();
                                        // This test only works for non TLSv1.3 as TLSv1.3 will establish sessions after
                                        // the handshake is done.
                                        // See https://www.openssl.org/docs/man1.1.1/man3/SSL_CTX_sess_set_get_cb.html
                                        if (!SslUtils.PROTOCOL_TLS_V1_3.equals(engine.getSession().getProtocol())) {
                                            // First should not re-use the session
                                            try {
                                                assertEquals(handshakeCount > 1, engine.isSessionReused());
                                            } catch (AssertionError error) {
                                                assertErrorRef.set(error);
                                                return;
                                            }
                                        }

                                        ctx.writeAndFlush(Unpooled.wrappedBuffer(bytes));
                                    }
                                }
                            });
                        }
                    })
                    .bind(new InetSocketAddress(0)).syncUninterruptibly().channel();

            InetSocketAddress serverAddr = (InetSocketAddress) sc.localAddress();
            testSessionTickets(serverAddr, group, sslClientCtx, bytes, false);
            testSessionTickets(serverAddr, group, sslClientCtx, bytes, true);
            AssertionError error = assertErrorRef.get();
            if (error != null) {
                throw error;
            }
        } finally {
            if (sc != null) {
                sc.close().syncUninterruptibly();
            }
            group.shutdownGracefully();
            ReferenceCountUtil.release(sslClientCtx);
        }
    }

    private static void testSessionTickets(InetSocketAddress serverAddress, EventLoopGroup group,
                                           SslContext sslClientCtx, final byte[] bytes, boolean isReused)
            throws Throwable {
        Channel cc = null;
        final BlockingQueue<Object> queue = new LinkedBlockingQueue<Object>();
        try {
            final SslHandler clientSslHandler = sslClientCtx.newHandler(UnpooledByteBufAllocator.DEFAULT,
                    serverAddress.getAddress().getHostAddress(), serverAddress.getPort());

            ChannelFuture future = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(clientSslHandler);
                            ch.pipeline().addLast(new ByteToMessageDecoder() {

                                @Override
                                protected void decode(ChannelHandlerContext ctx, ByteBuf in) {
                                    if (in.readableBytes() == bytes.length) {
                                        queue.add(in.readBytes(bytes.length));
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    queue.add(cause);
                                }
                            });
                        }
                    }).connect(serverAddress);
            cc = future.syncUninterruptibly().channel();

            assertTrue(clientSslHandler.handshakeFuture().sync().isSuccess());

            ReferenceCountedOpenSslEngine engine = (ReferenceCountedOpenSslEngine) clientSslHandler.engine();
            // This test only works for non TLSv1.3 as TLSv1.3 will establish sessions after
            // the handshake is done.
            // See https://www.openssl.org/docs/man1.1.1/man3/SSL_CTX_sess_set_get_cb.html
            if (!SslUtils.PROTOCOL_TLS_V1_3.equals(engine.getSession().getProtocol())) {
                assertEquals(isReused, engine.isSessionReused());
            }
            Object obj = queue.take();
            if (obj instanceof ByteBuf) {
                ByteBuf buffer = (ByteBuf) obj;
                ByteBuf expected = Unpooled.wrappedBuffer(bytes);
                try {
                    assertEquals(expected, buffer);
                } finally {
                    expected.release();
                    buffer.release();
                }
            } else {
                throw (Throwable) obj;
            }
        } finally {
            if (cc != null) {
                cc.close().syncUninterruptibly();
            }
        }
    }

    @Test(timeout = 10000L)
    public void testHandshakeFailureOnlyFireExceptionOnce() throws Exception {
        final SslContext sslClientCtx = SslContextBuilder.forClient()
                .trustManager(new X509ExtendedTrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                            throws CertificateException {
                        failVerification();
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                            throws CertificateException {
                        failVerification();
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                            throws CertificateException {
                        failVerification();
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                            throws CertificateException {
                        failVerification();
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                        failVerification();
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                        failVerification();
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return EmptyArrays.EMPTY_X509_CERTIFICATES;
                    }

                    private void failVerification() throws CertificateException {
                        throw new CertificateException();
                    }
                })
                .sslProvider(SslProvider.JDK).build();

        final SelfSignedCertificate cert = new SelfSignedCertificate();
        final SslContext sslServerCtx = SslContextBuilder.forServer(cert.key(), cert.cert())
                .sslProvider(SslProvider.JDK).build();

        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        Channel sc = null;
        final SslHandler clientSslHandler = sslClientCtx.newHandler(UnpooledByteBufAllocator.DEFAULT);
        final SslHandler serverSslHandler = sslServerCtx.newHandler(UnpooledByteBufAllocator.DEFAULT);

        try {
            final Object terminalEvent = new Object();
            final BlockingQueue<Object> errorQueue = new LinkedBlockingQueue<Object>();
            sc = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(serverSslHandler);
                            ch.pipeline().addLast(new ChannelHandler() {
                                @Override
                                public void exceptionCaught(final ChannelHandlerContext ctx, Throwable cause) {
                                    errorQueue.add(cause);
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) {
                                    errorQueue.add(terminalEvent);
                                }
                            });
                        }
                    })
                    .bind(new InetSocketAddress(0)).syncUninterruptibly().channel();
            final ChannelFuture future = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(clientSslHandler);
                        }
                    }).connect(sc.localAddress());
            future.syncUninterruptibly();
            clientSslHandler.handshakeFuture().addListener((FutureListener<Channel>) f -> {
                future.channel().close();
            });
            assertFalse(clientSslHandler.handshakeFuture().await().isSuccess());
            assertFalse(serverSslHandler.handshakeFuture().await().isSuccess());

            Object error = errorQueue.take();
            assertThat(error, Matchers.instanceOf(DecoderException.class));
            assertThat(((Throwable) error).getCause(), Matchers.<Throwable>instanceOf(SSLException.class));
            Object terminal = errorQueue.take();
            assertSame(terminalEvent, terminal);

            assertNull(errorQueue.poll(1, TimeUnit.MILLISECONDS));
        } finally {
            if (sc != null) {
                sc.close().syncUninterruptibly();
            }
            group.shutdownGracefully();
        }
    }

    @Test
    public void testHandshakeFailureCipherMissmatchTLSv12Jdk() throws Exception {
        testHandshakeFailureCipherMissmatch(SslProvider.JDK, false);
    }

    @Test
    public void testHandshakeFailureCipherMissmatchTLSv13Jdk() throws Exception {
        Assume.assumeTrue(SslProvider.isTlsv13Supported(SslProvider.JDK));
        testHandshakeFailureCipherMissmatch(SslProvider.JDK, true);
    }

    @Disabled("This fails atm... needs investigation")
    @Test
    public void testHandshakeFailureCipherMissmatchTLSv12OpenSsl() throws Exception {
        OpenSsl.ensureAvailability();
        testHandshakeFailureCipherMissmatch(SslProvider.OPENSSL, false);
    }

    @Test
    public void testHandshakeFailureCipherMissmatchTLSv13OpenSsl() throws Exception {
        OpenSsl.ensureAvailability();
        Assume.assumeTrue(SslProvider.isTlsv13Supported(SslProvider.OPENSSL));
        Assume.assumeFalse("BoringSSL does not support setting ciphers for TLSv1.3 explicit", OpenSsl.isBoringSSL());
        testHandshakeFailureCipherMissmatch(SslProvider.OPENSSL, true);
    }

    private static void testHandshakeFailureCipherMissmatch(SslProvider provider, boolean tls13) throws Exception {
        final String clientCipher;
        final String serverCipher;
        final String protocol;

        if (tls13) {
            clientCipher = "TLS_AES_128_GCM_SHA256";
            serverCipher = "TLS_AES_256_GCM_SHA384";
            protocol = SslUtils.PROTOCOL_TLS_V1_3;
        } else {
            clientCipher = "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256";
            serverCipher = "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384";
            protocol = SslUtils.PROTOCOL_TLS_V1_2;
        }
        final SslContext sslClientCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .protocols(protocol)
                .ciphers(Collections.singleton(clientCipher))
                .sslProvider(provider).build();

        final SelfSignedCertificate cert = new SelfSignedCertificate();
        final SslContext sslServerCtx = SslContextBuilder.forServer(cert.key(), cert.cert())
                .protocols(protocol)
                .ciphers(Collections.singleton(serverCipher))
                .sslProvider(provider).build();

        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        Channel sc = null;
        Channel cc = null;
        final SslHandler clientSslHandler = sslClientCtx.newHandler(UnpooledByteBufAllocator.DEFAULT);
        final SslHandler serverSslHandler = sslServerCtx.newHandler(UnpooledByteBufAllocator.DEFAULT);

        class SslEventHandler implements ChannelHandler {
            private final AtomicReference<SslHandshakeCompletionEvent> ref;

            SslEventHandler(AtomicReference<SslHandshakeCompletionEvent> ref) {
                this.ref = ref;
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof SslHandshakeCompletionEvent) {
                    ref.set((SslHandshakeCompletionEvent) evt);
                }
                ctx.fireUserEventTriggered(evt);
            }
        }
        final AtomicReference<SslHandshakeCompletionEvent> clientEvent =
                new AtomicReference<SslHandshakeCompletionEvent>();
        final AtomicReference<SslHandshakeCompletionEvent> serverEvent =
                new AtomicReference<SslHandshakeCompletionEvent>();
        try {
            sc = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(serverSslHandler);
                            ch.pipeline().addLast(new SslEventHandler(serverEvent));
                        }
                    })
                    .bind(new InetSocketAddress(0)).syncUninterruptibly().channel();

            ChannelFuture future = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(clientSslHandler);
                            ch.pipeline().addLast(new SslEventHandler(clientEvent));
                        }
                    }).connect(sc.localAddress());
            cc = future.syncUninterruptibly().channel();

            Throwable clientCause = clientSslHandler.handshakeFuture().await().cause();
            assertThat(clientCause, CoreMatchers.<Throwable>instanceOf(SSLException.class));
            assertThat(clientCause.getCause(), not(CoreMatchers.<Throwable>instanceOf(ClosedChannelException.class)));
            Throwable serverCause = serverSslHandler.handshakeFuture().await().cause();
            assertThat(serverCause, CoreMatchers.<Throwable>instanceOf(SSLException.class));
            assertThat(serverCause.getCause(), not(CoreMatchers.<Throwable>instanceOf(ClosedChannelException.class)));
            cc.close().syncUninterruptibly();
            sc.close().syncUninterruptibly();

            Throwable eventClientCause = clientEvent.get().cause();
            assertThat(eventClientCause, CoreMatchers.<Throwable>instanceOf(SSLException.class));
            assertThat(eventClientCause.getCause(),
                    not(CoreMatchers.<Throwable>instanceOf(ClosedChannelException.class)));
            Throwable serverEventCause = serverEvent.get().cause();

            assertThat(serverEventCause, CoreMatchers.<Throwable>instanceOf(SSLException.class));
            assertThat(serverEventCause.getCause(),
                    not(CoreMatchers.<Throwable>instanceOf(ClosedChannelException.class)));
        } finally {
            group.shutdownGracefully();
            ReferenceCountUtil.release(sslClientCtx);
        }
    }

    @Test
    public void testHandshakeEventsTls12JDK() throws Exception {
        testHandshakeEvents(SslProvider.JDK, SslUtils.PROTOCOL_TLS_V1_2);
    }

    @Test
    public void testHandshakeEventsTls12Openssl() throws Exception {
        OpenSsl.ensureAvailability();
        testHandshakeEvents(SslProvider.OPENSSL, SslUtils.PROTOCOL_TLS_V1_2);
    }

    @Test
    public void testHandshakeEventsTls13JDK() throws Exception {
        assumeTrue(SslProvider.isTlsv13Supported(SslProvider.JDK));
        testHandshakeEvents(SslProvider.JDK, SslUtils.PROTOCOL_TLS_V1_3);
    }

    @Test
    public void testHandshakeEventsTls13Openssl() throws Exception {
        OpenSsl.ensureAvailability();
        assumeTrue(SslProvider.isTlsv13Supported(SslProvider.OPENSSL));
        testHandshakeEvents(SslProvider.OPENSSL, SslUtils.PROTOCOL_TLS_V1_3);
    }

    private void testHandshakeEvents(SslProvider provider, String protocol) throws Exception {
        final SslContext sslClientCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .protocols(protocol)
                .sslProvider(provider).build();

        final SelfSignedCertificate cert = new SelfSignedCertificate();
        final SslContext sslServerCtx = SslContextBuilder.forServer(cert.key(), cert.cert())
                .protocols(protocol)
                .sslProvider(provider).build();

        EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());

        final LinkedBlockingQueue<SslHandshakeCompletionEvent> serverCompletionEvents =
                new LinkedBlockingQueue<SslHandshakeCompletionEvent>();

        final LinkedBlockingQueue<SslHandshakeCompletionEvent> clientCompletionEvents =
                new LinkedBlockingQueue<SslHandshakeCompletionEvent>();
        try {
            Channel sc = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(sslServerCtx.newHandler(UnpooledByteBufAllocator.DEFAULT));
                            ch.pipeline().addLast(new SslHandshakeCompletionEventHandler(serverCompletionEvents));
                        }
                    })
                    .bind(new InetSocketAddress(0)).syncUninterruptibly().channel();

            Bootstrap bs = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(sslClientCtx.newHandler(
                                    UnpooledByteBufAllocator.DEFAULT, "netty.io", 9999));
                            ch.pipeline().addLast(new SslHandshakeCompletionEventHandler(clientCompletionEvents));
                        }
                    })
                    .remoteAddress(sc.localAddress());

            Channel cc1 = bs.connect().sync().channel();
            Channel cc2 = bs.connect().sync().channel();

            // We expect 4 events as we have 2 connections and for each connection there should be one event
            // on the server-side and one on the client-side.
            for (int i = 0; i < 2; i++) {
                SslHandshakeCompletionEvent event = clientCompletionEvents.take();
                assertTrue(event.isSuccess());
            }
            for (int i = 0; i < 2; i++) {
                SslHandshakeCompletionEvent event = serverCompletionEvents.take();
                assertTrue(event.isSuccess());
            }

            cc1.close().sync();
            cc2.close().sync();
            sc.close().sync();
            assertEquals(0, clientCompletionEvents.size());
            assertEquals(0, serverCompletionEvents.size());
        } finally {
            group.shutdownGracefully();
            ReferenceCountUtil.release(sslClientCtx);
            ReferenceCountUtil.release(sslServerCtx);
        }
    }

    private static class SslHandshakeCompletionEventHandler implements ChannelHandler {
        private final Queue<SslHandshakeCompletionEvent> completionEvents;

        SslHandshakeCompletionEventHandler(Queue<SslHandshakeCompletionEvent> completionEvents) {
            this.completionEvents = completionEvents;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof SslHandshakeCompletionEvent) {
                completionEvents.add((SslHandshakeCompletionEvent) evt);
            }
        }
    };
}
