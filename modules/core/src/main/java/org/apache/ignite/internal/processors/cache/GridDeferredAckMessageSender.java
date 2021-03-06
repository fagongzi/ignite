/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.processors.closure.GridClosureProcessor;
import org.apache.ignite.internal.processors.timeout.GridTimeoutObject;
import org.apache.ignite.internal.processors.timeout.GridTimeoutProcessor;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteUuid;
import org.jsr166.ConcurrentHashMap8;
import org.jsr166.ConcurrentLinkedDeque8;

/**
 *
 */
public abstract class GridDeferredAckMessageSender {
    /** Deferred message buffers. */
    private ConcurrentMap<UUID, DeferredAckMessageBuffer> deferredAckMsgBuffers = new ConcurrentHashMap8<>();

    /** Timeout processor. */
    private GridTimeoutProcessor time;

    /** Closure processor. */
    public GridClosureProcessor closure;

    /**
     * @param time Time.
     * @param closure Closure.
     */
    public GridDeferredAckMessageSender(GridTimeoutProcessor time,
        GridClosureProcessor closure) {
        this.time = time;
        this.closure = closure;
    }

    /**
     *
     */
    public abstract int getTimeout();

    /**
     *
     */
    public abstract int getBufferSize();

    /**
     *
     */
    public abstract void finish(UUID nodeId, ConcurrentLinkedDeque8<GridCacheVersion> vers);

    /**
     *
     */
    public void stop() {
        for (DeferredAckMessageBuffer buf : deferredAckMsgBuffers.values())
            buf.finish0();
    }

    /**
     * @param nodeId Node ID to send message to.
     * @param ver Version to ack.
     */
    public void sendDeferredAckMessage(UUID nodeId, GridCacheVersion ver) {
        while (true) {
            DeferredAckMessageBuffer buf = deferredAckMsgBuffers.get(nodeId);

            if (buf == null) {
                buf = new DeferredAckMessageBuffer(nodeId);

                DeferredAckMessageBuffer old = deferredAckMsgBuffers.putIfAbsent(nodeId, buf);

                if (old == null) {
                    // We have successfully added buffer to map.
                    time.addTimeoutObject(buf);
                }
                else
                    buf = old;
            }

            if (!buf.add(ver))
                // Some thread is sending filled up buffer, we can remove it.
                deferredAckMsgBuffers.remove(nodeId, buf);
            else
                break;
        }
    }

    /**
     * Deferred message buffer.
     */
    private class DeferredAckMessageBuffer extends ReentrantReadWriteLock implements GridTimeoutObject {
        /** */
        private static final long serialVersionUID = 0L;

        /** Filled atomic flag. */
        private AtomicBoolean guard = new AtomicBoolean(false);

        /** Versions. */
        private ConcurrentLinkedDeque8<GridCacheVersion> vers = new ConcurrentLinkedDeque8<>();

        /** Node ID. */
        private final UUID nodeId;

        /** Timeout ID. */
        private final IgniteUuid timeoutId;

        /** End time. */
        private final long endTime;

        /**
         * @param nodeId Node ID to send message to.
         */
        private DeferredAckMessageBuffer(UUID nodeId) {
            this.nodeId = nodeId;

            timeoutId = IgniteUuid.fromUuid(nodeId);

            endTime = U.currentTimeMillis() + getTimeout();
        }

        /** {@inheritDoc} */
        @Override public IgniteUuid timeoutId() {
            return timeoutId;
        }

        /** {@inheritDoc} */
        @Override public long endTime() {
            return endTime;
        }

        /** {@inheritDoc} */
        @Override public void onTimeout() {
            if (guard.compareAndSet(false, true)) {
                closure.runLocalSafe(new Runnable() {
                    @Override public void run() {
                        writeLock().lock();

                        try {
                            finish0();
                        }
                        finally {
                            writeLock().unlock();
                        }
                    }
                });
            }
        }

        /**
         * Adds deferred request to buffer.
         *
         * @param ver Version to send.
         * @return {@code True} if request was handled, {@code false} if this buffer is filled and cannot be used.
         */
        public boolean add(GridCacheVersion ver) {
            readLock().lock();

            boolean snd = false;

            try {
                if (guard.get())
                    return false;

                vers.add(ver);

                if (vers.sizex() > getBufferSize() && guard.compareAndSet(false, true))
                    snd = true;
            }
            finally {
                readLock().unlock();
            }

            if (snd) {
                // Wait all threads in read lock to finish.
                writeLock().lock();

                try {
                    finish0();

                    time.removeTimeoutObject(this);
                }
                finally {
                    writeLock().unlock();
                }
            }

            return true;
        }

        /**
         * Sends deferred notification message and removes this buffer from pending responses map.
         */
        private void finish0() {
            finish(nodeId, vers);

            deferredAckMsgBuffers.remove(nodeId, this);
        }
    }
}
