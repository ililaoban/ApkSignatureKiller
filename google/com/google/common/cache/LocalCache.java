

package com.google.common.cache;

import android.support.annotation.Nullable;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.cache.AbstractCache.SimpleStatsCounter;
import com.google.common.cache.AbstractCache.StatsCounter;
import com.google.common.cache.CacheBuilder.NullListener;
import com.google.common.cache.CacheBuilder.OneWeigher;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.CacheLoader.UnsupportedLoadingOperationException;
import com.google.common.collect.AbstractSequentialIterator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.common.util.concurrent.Uninterruptibles;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractQueue;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.cache.CacheBuilder.NULL_TICKER;
import static com.google.common.cache.CacheBuilder.UNSET_INT;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly;
import static java.util.concurrent.TimeUnit.NANOSECONDS;


@GwtCompatible(emulated = true)
class LocalCache<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {


    // Constants


    static final int MAXIMUM_CAPACITY = 1 << 30;


    static final int MAX_SEGMENTS = 1 << 16; // slightly conservative


    static final int CONTAINS_VALUE_RETRIES = 3;


    static final int DRAIN_THRESHOLD = 0x3F;


    // TODO(fry): empirically optimize this
    static final int DRAIN_MAX = 16;

    // Fields

    static final Logger logger = Logger.getLogger(LocalCache.class.getName());


    final int segmentMask;


    final int segmentShift;


    final Segment<K, V>[] segments;


    final int concurrencyLevel;


    final Equivalence<Object> keyEquivalence;


    final Equivalence<Object> valueEquivalence;


    final Strength keyStrength;


    final Strength valueStrength;


    final long maxWeight;


    final Weigher<K, V> weigher;


    final long expireAfterAccessNanos;


    final long expireAfterWriteNanos;


    final long refreshNanos;


    // TODO(fry): define a new type which creates event objects and automates the clear logic
    final Queue<RemovalNotification<K, V>> removalNotificationQueue;


    final RemovalListener<K, V> removalListener;


    final Ticker ticker;


    final EntryFactory entryFactory;


    final StatsCounter globalStatsCounter;


    @Nullable
    final CacheLoader<? super K, V> defaultLoader;


    LocalCache(
            CacheBuilder<? super K, ? super V> builder, @Nullable CacheLoader<? super K, V> loader) {
        concurrencyLevel = Math.min(builder.getConcurrencyLevel(), MAX_SEGMENTS);

        keyStrength = builder.getKeyStrength();
        valueStrength = builder.getValueStrength();

        keyEquivalence = builder.getKeyEquivalence();
        valueEquivalence = builder.getValueEquivalence();

        maxWeight = builder.getMaximumWeight();
        weigher = builder.getWeigher();
        expireAfterAccessNanos = builder.getExpireAfterAccessNanos();
        expireAfterWriteNanos = builder.getExpireAfterWriteNanos();
        refreshNanos = builder.getRefreshNanos();

        removalListener = builder.getRemovalListener();
        removalNotificationQueue = (removalListener == NullListener.INSTANCE)
                ? LocalCache.<RemovalNotification<K, V>>discardingQueue()
                : new ConcurrentLinkedQueue<RemovalNotification<K, V>>();

        ticker = builder.getTicker(recordsTime());
        entryFactory = EntryFactory.getFactory(keyStrength, usesAccessEntries(), usesWriteEntries());
        globalStatsCounter = builder.getStatsCounterSupplier().get();
        defaultLoader = loader;

        int initialCapacity = Math.min(builder.getInitialCapacity(), MAXIMUM_CAPACITY);
        if (evictsBySize() && !customWeigher()) {
            initialCapacity = Math.min(initialCapacity, (int) maxWeight);
        }

        // Find the lowest power-of-two segmentCount that exceeds concurrencyLevel, unless
        // maximumSize/Weight is specified in which case ensure that each segment gets at least 10
        // entries. The special casing for size-based eviction is only necessary because that eviction
        // happens per segment instead of globally, so too many segments compared to the maximum size
        // will result in random eviction behavior.
        int segmentShift = 0;
        int segmentCount = 1;
        while (segmentCount < concurrencyLevel
                && (!evictsBySize() || segmentCount * 20 <= maxWeight)) {
            ++segmentShift;
            segmentCount <<= 1;
        }
        this.segmentShift = 32 - segmentShift;
        segmentMask = segmentCount - 1;

        this.segments = newSegmentArray(segmentCount);

        int segmentCapacity = initialCapacity / segmentCount;
        if (segmentCapacity * segmentCount < initialCapacity) {
            ++segmentCapacity;
        }

        int segmentSize = 1;
        while (segmentSize < segmentCapacity) {
            segmentSize <<= 1;
        }

        if (evictsBySize()) {
            // Ensure sum of segment max weights = overall max weights
            long maxSegmentWeight = maxWeight / segmentCount + 1;
            long remainder = maxWeight % segmentCount;
            for (int i = 0; i < this.segments.length; ++i) {
                if (i == remainder) {
                    maxSegmentWeight--;
                }
                this.segments[i] =
                        createSegment(segmentSize, maxSegmentWeight, builder.getStatsCounterSupplier().get());
            }
        } else {
            for (int i = 0; i < this.segments.length; ++i) {
                this.segments[i] =
                        createSegment(segmentSize, UNSET_INT, builder.getStatsCounterSupplier().get());
            }
        }
    }

    boolean evictsBySize() {
        return maxWeight >= 0;
    }

    boolean customWeigher() {
        return weigher != OneWeigher.INSTANCE;
    }

    boolean expires() {
        return expiresAfterWrite() || expiresAfterAccess();
    }

    boolean expiresAfterWrite() {
        return expireAfterWriteNanos > 0;
    }

    boolean expiresAfterAccess() {
        return expireAfterAccessNanos > 0;
    }

    boolean refreshes() {
        return refreshNanos > 0;
    }

    boolean usesAccessQueue() {
        return expiresAfterAccess() || evictsBySize();
    }

    boolean usesWriteQueue() {
        return expiresAfterWrite();
    }

    boolean recordsWrite() {
        return expiresAfterWrite() || refreshes();
    }

    boolean recordsAccess() {
        return expiresAfterAccess();
    }

    boolean recordsTime() {
        return recordsWrite() || recordsAccess();
    }

    boolean usesWriteEntries() {
        return usesWriteQueue() || recordsWrite();
    }

    boolean usesAccessEntries() {
        return usesAccessQueue() || recordsAccess();
    }

    boolean usesKeyReferences() {
        return keyStrength != Strength.STRONG;
    }

    boolean usesValueReferences() {
        return valueStrength != Strength.STRONG;
    }

    enum Strength {


        STRONG {
            @Override
            <K, V> ValueReference<K, V> referenceValue(
                    Segment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight) {
                return (weight == 1)
                        ? new StrongValueReference<K, V>(value)
                        : new WeightedStrongValueReference<K, V>(value, weight);
            }

            @Override
            Equivalence<Object> defaultEquivalence() {
                return Equivalence.equals();
            }
        },

        SOFT {
            @Override
            <K, V> ValueReference<K, V> referenceValue(
                    Segment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight) {
                return (weight == 1)
                        ? new SoftValueReference<K, V>(segment.valueReferenceQueue, value, entry)
                        : new WeightedSoftValueReference<K, V>(
                        segment.valueReferenceQueue, value, entry, weight);
            }

            @Override
            Equivalence<Object> defaultEquivalence() {
                return Equivalence.identity();
            }
        },

        WEAK {
            @Override
            <K, V> ValueReference<K, V> referenceValue(
                    Segment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight) {
                return (weight == 1)
                        ? new WeakValueReference<K, V>(segment.valueReferenceQueue, value, entry)
                        : new WeightedWeakValueReference<K, V>(
                        segment.valueReferenceQueue, value, entry, weight);
            }

            @Override
            Equivalence<Object> defaultEquivalence() {
                return Equivalence.identity();
            }
        };


        abstract <K, V> ValueReference<K, V> referenceValue(
                Segment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight);


        abstract Equivalence<Object> defaultEquivalence();
    }


    enum EntryFactory {
        STRONG {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(
                    Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
                return new StrongEntry<>(key, hash, next);
            }
        },
        STRONG_ACCESS {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(
                    Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
                return new StrongAccessEntry<>(key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(
                    Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyAccessEntry(original, newEntry);
                return newEntry;
            }
        },
        STRONG_WRITE {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(
                    Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
                return new StrongWriteEntry<>(key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(
                    Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyWriteEntry(original, newEntry);
                return newEntry;
            }
        },
        STRONG_ACCESS_WRITE {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(
                    Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
                return new StrongAccessWriteEntry<>(key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(
                    Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyAccessEntry(original, newEntry);
                copyWriteEntry(original, newEntry);
                return newEntry;
            }
        },

        WEAK {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(
                    Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
                return new WeakEntry<>(segment.keyReferenceQueue, key, hash, next);
            }
        },
        WEAK_ACCESS {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(
                    Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
                return new WeakAccessEntry<>(segment.keyReferenceQueue, key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(
                    Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyAccessEntry(original, newEntry);
                return newEntry;
            }
        },
        WEAK_WRITE {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(
                    Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
                return new WeakWriteEntry<>(segment.keyReferenceQueue, key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(
                    Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyWriteEntry(original, newEntry);
                return newEntry;
            }
        },
        WEAK_ACCESS_WRITE {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(
                    Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
                return new WeakAccessWriteEntry<>(segment.keyReferenceQueue, key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(
                    Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyAccessEntry(original, newEntry);
                copyWriteEntry(original, newEntry);
                return newEntry;
            }
        };


        static final int ACCESS_MASK = 1;
        static final int WRITE_MASK = 2;
        static final int WEAK_MASK = 4;


        static final EntryFactory[] factories = {
                STRONG, STRONG_ACCESS, STRONG_WRITE, STRONG_ACCESS_WRITE,
                WEAK, WEAK_ACCESS, WEAK_WRITE, WEAK_ACCESS_WRITE,
        };

        static EntryFactory getFactory(Strength keyStrength, boolean usesAccessQueue,
                                       boolean usesWriteQueue) {
            int flags = ((keyStrength == Strength.WEAK) ? WEAK_MASK : 0)
                    | (usesAccessQueue ? ACCESS_MASK : 0)
                    | (usesWriteQueue ? WRITE_MASK : 0);
            return factories[flags];
        }


        abstract <K, V> ReferenceEntry<K, V> newEntry(
                Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next);


        // Guarded By Segment.this
        <K, V> ReferenceEntry<K, V> copyEntry(
                Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
            return newEntry(segment, original.getKey(), original.getHash(), newNext);
        }

        // Guarded By Segment.this
        <K, V> void copyAccessEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newEntry) {
            // TODO(fry): when we link values instead of entries this method can go
            // away, as can connectAccessOrder, nullifyAccessOrder.
            newEntry.setAccessTime(original.getAccessTime());

            connectAccessOrder(original.getPreviousInAccessQueue(), newEntry);
            connectAccessOrder(newEntry, original.getNextInAccessQueue());

            nullifyAccessOrder(original);
        }

        // Guarded By Segment.this
        <K, V> void copyWriteEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newEntry) {
            // TODO(fry): when we link values instead of entries this method can go
            // away, as can connectWriteOrder, nullifyWriteOrder.
            newEntry.setWriteTime(original.getWriteTime());

            connectWriteOrder(original.getPreviousInWriteQueue(), newEntry);
            connectWriteOrder(newEntry, original.getNextInWriteQueue());

            nullifyWriteOrder(original);
        }
    }


    interface ValueReference<K, V> {

        @Nullable
        V get();


        V waitForValue() throws ExecutionException;


        int getWeight();


        @Nullable
        ReferenceEntry<K, V> getEntry();


        ValueReference<K, V> copyFor(
                ReferenceQueue<V> queue, @Nullable V value, ReferenceEntry<K, V> entry);


        void notifyNewValue(@Nullable V newValue);


        boolean isLoading();


        boolean isActive();
    }


    static final ValueReference<Object, Object> UNSET = new ValueReference<Object, Object>() {
        @Override
        public Object get() {
            return null;
        }

        @Override
        public int getWeight() {
            return 0;
        }

        @Override
        public ReferenceEntry<Object, Object> getEntry() {
            return null;
        }

        @Override
        public ValueReference<Object, Object> copyFor(ReferenceQueue<Object> queue,
                                                      @Nullable Object value, ReferenceEntry<Object, Object> entry) {
            return this;
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public Object waitForValue() {
            return null;
        }

        @Override
        public void notifyNewValue(Object newValue) {
        }
    };


    @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
    static <K, V> ValueReference<K, V> unset() {
        return (ValueReference<K, V>) UNSET;
    }


    interface ReferenceEntry<K, V> {

        ValueReference<K, V> getValueReference();


        void setValueReference(ValueReference<K, V> valueReference);


        @Nullable
        ReferenceEntry<K, V> getNext();


        int getHash();


        @Nullable
        K getKey();


        long getAccessTime();


        void setAccessTime(long time);


        ReferenceEntry<K, V> getNextInAccessQueue();


        void setNextInAccessQueue(ReferenceEntry<K, V> next);


        ReferenceEntry<K, V> getPreviousInAccessQueue();


        void setPreviousInAccessQueue(ReferenceEntry<K, V> previous);


        long getWriteTime();


        void setWriteTime(long time);


        ReferenceEntry<K, V> getNextInWriteQueue();


        void setNextInWriteQueue(ReferenceEntry<K, V> next);


        ReferenceEntry<K, V> getPreviousInWriteQueue();


        void setPreviousInWriteQueue(ReferenceEntry<K, V> previous);
    }

    private enum NullEntry implements ReferenceEntry<Object, Object> {
        INSTANCE;

        @Override
        public ValueReference<Object, Object> getValueReference() {
            return null;
        }

        @Override
        public void setValueReference(ValueReference<Object, Object> valueReference) {
        }

        @Override
        public ReferenceEntry<Object, Object> getNext() {
            return null;
        }

        @Override
        public int getHash() {
            return 0;
        }

        @Override
        public Object getKey() {
            return null;
        }

        @Override
        public long getAccessTime() {
            return 0;
        }

        @Override
        public void setAccessTime(long time) {
        }

        @Override
        public ReferenceEntry<Object, Object> getNextInAccessQueue() {
            return this;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<Object, Object> next) {
        }

        @Override
        public ReferenceEntry<Object, Object> getPreviousInAccessQueue() {
            return this;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<Object, Object> previous) {
        }

        @Override
        public long getWriteTime() {
            return 0;
        }

        @Override
        public void setWriteTime(long time) {
        }

        @Override
        public ReferenceEntry<Object, Object> getNextInWriteQueue() {
            return this;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<Object, Object> next) {
        }

        @Override
        public ReferenceEntry<Object, Object> getPreviousInWriteQueue() {
            return this;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<Object, Object> previous) {
        }
    }

    abstract static class AbstractReferenceEntry<K, V> implements ReferenceEntry<K, V> {
        @Override
        public ValueReference<K, V> getValueReference() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setValueReference(ValueReference<K, V> valueReference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getHash() {
            throw new UnsupportedOperationException();
        }

        @Override
        public K getKey() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getAccessTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAccessTime(long time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getWriteTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setWriteTime(long time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
    static <K, V> ReferenceEntry<K, V> nullEntry() {
        return (ReferenceEntry<K, V>) NullEntry.INSTANCE;
    }

    static final Queue<? extends Object> DISCARDING_QUEUE = new AbstractQueue<Object>() {
        @Override
        public boolean offer(Object o) {
            return true;
        }

        @Override
        public Object peek() {
            return null;
        }

        @Override
        public Object poll() {
            return null;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public Iterator<Object> iterator() {
            return ImmutableSet.of().iterator();
        }
    };


    @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
    static <E> Queue<E> discardingQueue() {
        return (Queue) DISCARDING_QUEUE;
    }


    static class StrongEntry<K, V> extends AbstractReferenceEntry<K, V> {
        final K key;

        StrongEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.next = next;
        }

        @Override
        public K getKey() {
            return this.key;
        }

        // The code below is exactly the same for each entry type.

        final int hash;
        final ReferenceEntry<K, V> next;
        volatile ValueReference<K, V> valueReference = unset();

        @Override
        public ValueReference<K, V> getValueReference() {
            return valueReference;
        }

        @Override
        public void setValueReference(ValueReference<K, V> valueReference) {
            this.valueReference = valueReference;
        }

        @Override
        public int getHash() {
            return hash;
        }

        @Override
        public ReferenceEntry<K, V> getNext() {
            return next;
        }
    }

    static final class StrongAccessEntry<K, V> extends StrongEntry<K, V> {
        StrongAccessEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(key, hash, next);
        }

        // The code below is exactly the same for each access entry type.

        volatile long accessTime = Long.MAX_VALUE;

        @Override
        public long getAccessTime() {
            return accessTime;
        }

        @Override
        public void setAccessTime(long time) {
            this.accessTime = time;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> nextAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return nextAccess;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            this.nextAccess = next;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> previousAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return previousAccess;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            this.previousAccess = previous;
        }
    }

    static final class StrongWriteEntry<K, V> extends StrongEntry<K, V> {
        StrongWriteEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(key, hash, next);
        }

        // The code below is exactly the same for each write entry type.

        volatile long writeTime = Long.MAX_VALUE;

        @Override
        public long getWriteTime() {
            return writeTime;
        }

        @Override
        public void setWriteTime(long time) {
            this.writeTime = time;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> nextWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return nextWrite;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            this.nextWrite = next;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> previousWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return previousWrite;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            this.previousWrite = previous;
        }
    }

    static final class StrongAccessWriteEntry<K, V> extends StrongEntry<K, V> {
        StrongAccessWriteEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(key, hash, next);
        }

        // The code below is exactly the same for each access entry type.

        volatile long accessTime = Long.MAX_VALUE;

        @Override
        public long getAccessTime() {
            return accessTime;
        }

        @Override
        public void setAccessTime(long time) {
            this.accessTime = time;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> nextAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return nextAccess;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            this.nextAccess = next;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> previousAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return previousAccess;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            this.previousAccess = previous;
        }

        // The code below is exactly the same for each write entry type.

        volatile long writeTime = Long.MAX_VALUE;

        @Override
        public long getWriteTime() {
            return writeTime;
        }

        @Override
        public void setWriteTime(long time) {
            this.writeTime = time;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> nextWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return nextWrite;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            this.nextWrite = next;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> previousWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return previousWrite;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            this.previousWrite = previous;
        }
    }


    static class WeakEntry<K, V> extends WeakReference<K> implements ReferenceEntry<K, V> {
        WeakEntry(ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(key, queue);
            this.hash = hash;
            this.next = next;
        }

        @Override
        public K getKey() {
            return get();
        }


        // null access

        @Override
        public long getAccessTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAccessTime(long time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            throw new UnsupportedOperationException();
        }

        // null write

        @Override
        public long getWriteTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setWriteTime(long time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            throw new UnsupportedOperationException();
        }

        // The code below is exactly the same for each entry type.

        final int hash;
        final ReferenceEntry<K, V> next;
        volatile ValueReference<K, V> valueReference = unset();

        @Override
        public ValueReference<K, V> getValueReference() {
            return valueReference;
        }

        @Override
        public void setValueReference(ValueReference<K, V> valueReference) {
            this.valueReference = valueReference;
        }

        @Override
        public int getHash() {
            return hash;
        }

        @Override
        public ReferenceEntry<K, V> getNext() {
            return next;
        }
    }

    static final class WeakAccessEntry<K, V> extends WeakEntry<K, V> {
        WeakAccessEntry(
                ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(queue, key, hash, next);
        }

        // The code below is exactly the same for each access entry type.

        volatile long accessTime = Long.MAX_VALUE;

        @Override
        public long getAccessTime() {
            return accessTime;
        }

        @Override
        public void setAccessTime(long time) {
            this.accessTime = time;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> nextAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return nextAccess;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            this.nextAccess = next;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> previousAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return previousAccess;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            this.previousAccess = previous;
        }
    }

    static final class WeakWriteEntry<K, V> extends WeakEntry<K, V> {
        WeakWriteEntry(
                ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(queue, key, hash, next);
        }

        // The code below is exactly the same for each write entry type.

        volatile long writeTime = Long.MAX_VALUE;

        @Override
        public long getWriteTime() {
            return writeTime;
        }

        @Override
        public void setWriteTime(long time) {
            this.writeTime = time;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> nextWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return nextWrite;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            this.nextWrite = next;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> previousWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return previousWrite;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            this.previousWrite = previous;
        }
    }

    static final class WeakAccessWriteEntry<K, V> extends WeakEntry<K, V> {
        WeakAccessWriteEntry(
                ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(queue, key, hash, next);
        }

        // The code below is exactly the same for each access entry type.

        volatile long accessTime = Long.MAX_VALUE;

        @Override
        public long getAccessTime() {
            return accessTime;
        }

        @Override
        public void setAccessTime(long time) {
            this.accessTime = time;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> nextAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return nextAccess;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            this.nextAccess = next;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> previousAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return previousAccess;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            this.previousAccess = previous;
        }

        // The code below is exactly the same for each write entry type.

        volatile long writeTime = Long.MAX_VALUE;

        @Override
        public long getWriteTime() {
            return writeTime;
        }

        @Override
        public void setWriteTime(long time) {
            this.writeTime = time;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> nextWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return nextWrite;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            this.nextWrite = next;
        }

        // Guarded By Segment.this
        ReferenceEntry<K, V> previousWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return previousWrite;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            this.previousWrite = previous;
        }
    }


    static class WeakValueReference<K, V>
            extends WeakReference<V> implements ValueReference<K, V> {
        final ReferenceEntry<K, V> entry;

        WeakValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry) {
            super(referent, queue);
            this.entry = entry;
        }

        @Override
        public int getWeight() {
            return 1;
        }

        @Override
        public ReferenceEntry<K, V> getEntry() {
            return entry;
        }

        @Override
        public void notifyNewValue(V newValue) {
        }

        @Override
        public ValueReference<K, V> copyFor(
                ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return new WeakValueReference<>(queue, value, entry);
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public V waitForValue() {
            return get();
        }
    }


    static class SoftValueReference<K, V>
            extends SoftReference<V> implements ValueReference<K, V> {
        final ReferenceEntry<K, V> entry;

        SoftValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry) {
            super(referent, queue);
            this.entry = entry;
        }

        @Override
        public int getWeight() {
            return 1;
        }

        @Override
        public ReferenceEntry<K, V> getEntry() {
            return entry;
        }

        @Override
        public void notifyNewValue(V newValue) {
        }

        @Override
        public ValueReference<K, V> copyFor(
                ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return new SoftValueReference<>(queue, value, entry);
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public V waitForValue() {
            return get();
        }
    }


    static class StrongValueReference<K, V> implements ValueReference<K, V> {
        final V referent;

        StrongValueReference(V referent) {
            this.referent = referent;
        }

        @Override
        public V get() {
            return referent;
        }

        @Override
        public int getWeight() {
            return 1;
        }

        @Override
        public ReferenceEntry<K, V> getEntry() {
            return null;
        }

        @Override
        public ValueReference<K, V> copyFor(
                ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return this;
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public V waitForValue() {
            return get();
        }

        @Override
        public void notifyNewValue(V newValue) {
        }
    }


    static final class WeightedWeakValueReference<K, V> extends WeakValueReference<K, V> {
        final int weight;

        WeightedWeakValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry,
                                   int weight) {
            super(queue, referent, entry);
            this.weight = weight;
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public ValueReference<K, V> copyFor(
                ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return new WeightedWeakValueReference<>(queue, value, entry, weight);
        }
    }


    static final class WeightedSoftValueReference<K, V> extends SoftValueReference<K, V> {
        final int weight;

        WeightedSoftValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry,
                                   int weight) {
            super(queue, referent, entry);
            this.weight = weight;
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public ValueReference<K, V> copyFor(
                ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return new WeightedSoftValueReference<>(queue, value, entry, weight);
        }

    }


    static final class WeightedStrongValueReference<K, V> extends StrongValueReference<K, V> {
        final int weight;

        WeightedStrongValueReference(V referent, int weight) {
            super(referent);
            this.weight = weight;
        }

        @Override
        public int getWeight() {
            return weight;
        }
    }


    static int rehash(int h) {
        // Spread bits to regularize both segment and index locations,
        // using variant of single-word Wang/Jenkins hash.
        // TODO(kevinb): use Hashing/move this to Hashing?
        h += (h << 15) ^ 0xffffcd7d;
        h ^= (h >>> 10);
        h += (h << 3);
        h ^= (h >>> 6);
        h += (h << 2) + (h << 14);
        return h ^ (h >>> 16);
    }


    @VisibleForTesting
    ReferenceEntry<K, V> newEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        Segment<K, V> segment = segmentFor(hash);
        segment.lock();
        try {
            return segment.newEntry(key, hash, next);
        } finally {
            segment.unlock();
        }
    }


    // Guarded By Segment.this
    @VisibleForTesting
    ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
        int hash = original.getHash();
        return segmentFor(hash).copyEntry(original, newNext);
    }


    // Guarded By Segment.this
    @VisibleForTesting
    ValueReference<K, V> newValueReference(ReferenceEntry<K, V> entry, V value, int weight) {
        int hash = entry.getHash();
        return valueStrength.referenceValue(segmentFor(hash), entry, checkNotNull(value), weight);
    }

    int hash(@Nullable Object key) {
        int h = keyEquivalence.hash(key);
        return rehash(h);
    }

    void reclaimValue(ValueReference<K, V> valueReference) {
        ReferenceEntry<K, V> entry = valueReference.getEntry();
        int hash = entry.getHash();
        segmentFor(hash).reclaimValue(entry.getKey(), hash, valueReference);
    }

    void reclaimKey(ReferenceEntry<K, V> entry) {
        int hash = entry.getHash();
        segmentFor(hash).reclaimKey(entry, hash);
    }


    @VisibleForTesting
    boolean isLive(ReferenceEntry<K, V> entry, long now) {
        return segmentFor(entry.getHash()).getLiveValue(entry, now) != null;
    }


    Segment<K, V> segmentFor(int hash) {
        // TODO(fry): Lazily create segments?
        return segments[(hash >>> segmentShift) & segmentMask];
    }

    Segment<K, V> createSegment(
            int initialCapacity, long maxSegmentWeight, StatsCounter statsCounter) {
        return new Segment<>(this, initialCapacity, maxSegmentWeight, statsCounter);
    }


    @Nullable
    V getLiveValue(ReferenceEntry<K, V> entry, long now) {
        if (entry.getKey() == null) {
            return null;
        }
        V value = entry.getValueReference().get();
        if (value == null) {
            return null;
        }

        if (isExpired(entry, now)) {
            return null;
        }
        return value;
    }

    // expiration


    boolean isExpired(ReferenceEntry<K, V> entry, long now) {
        checkNotNull(entry);
        if (expiresAfterAccess()
                && (now - entry.getAccessTime() >= expireAfterAccessNanos)) {
            return true;
        }
        if (expiresAfterWrite()
                && (now - entry.getWriteTime() >= expireAfterWriteNanos)) {
            return true;
        }
        return false;
    }

    // queues

    // Guarded By Segment.this
    static <K, V> void connectAccessOrder(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
        previous.setNextInAccessQueue(next);
        next.setPreviousInAccessQueue(previous);
    }

    // Guarded By Segment.this
    static <K, V> void nullifyAccessOrder(ReferenceEntry<K, V> nulled) {
        ReferenceEntry<K, V> nullEntry = nullEntry();
        nulled.setNextInAccessQueue(nullEntry);
        nulled.setPreviousInAccessQueue(nullEntry);
    }

    // Guarded By Segment.this
    static <K, V> void connectWriteOrder(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
        previous.setNextInWriteQueue(next);
        next.setPreviousInWriteQueue(previous);
    }

    // Guarded By Segment.this
    static <K, V> void nullifyWriteOrder(ReferenceEntry<K, V> nulled) {
        ReferenceEntry<K, V> nullEntry = nullEntry();
        nulled.setNextInWriteQueue(nullEntry);
        nulled.setPreviousInWriteQueue(nullEntry);
    }


    void processPendingNotifications() {
        RemovalNotification<K, V> notification;
        while ((notification = removalNotificationQueue.poll()) != null) {
            try {
                removalListener.onRemoval(notification);
            } catch (Throwable e) {
                logger.log(Level.WARNING, "Exception thrown by removal listener", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    final Segment<K, V>[] newSegmentArray(int ssize) {
        return new Segment[ssize];
    }

    // Inner Classes


    @SuppressWarnings("serial") // This class is never serialized.
    static class Segment<K, V> extends ReentrantLock {


        final LocalCache<K, V> map;


        volatile int count;


        long totalWeight;


        int modCount;


        int threshold;


        volatile AtomicReferenceArray<ReferenceEntry<K, V>> table;


        final long maxSegmentWeight;


        final ReferenceQueue<K> keyReferenceQueue;


        final ReferenceQueue<V> valueReferenceQueue;


        final Queue<ReferenceEntry<K, V>> recencyQueue;


        final AtomicInteger readCount = new AtomicInteger();


        final Queue<ReferenceEntry<K, V>> writeQueue;


        final Queue<ReferenceEntry<K, V>> accessQueue;


        final StatsCounter statsCounter;

        Segment(LocalCache<K, V> map, int initialCapacity, long maxSegmentWeight,
                StatsCounter statsCounter) {
            this.map = map;
            this.maxSegmentWeight = maxSegmentWeight;
            this.statsCounter = checkNotNull(statsCounter);
            initTable(newEntryArray(initialCapacity));

            keyReferenceQueue = map.usesKeyReferences()
                    ? new ReferenceQueue<K>() : null;

            valueReferenceQueue = map.usesValueReferences()
                    ? new ReferenceQueue<V>() : null;

            recencyQueue = map.usesAccessQueue()
                    ? new ConcurrentLinkedQueue<ReferenceEntry<K, V>>()
                    : LocalCache.<ReferenceEntry<K, V>>discardingQueue();

            writeQueue = map.usesWriteQueue()
                    ? new WriteQueue<K, V>()
                    : LocalCache.<ReferenceEntry<K, V>>discardingQueue();

            accessQueue = map.usesAccessQueue()
                    ? new AccessQueue<K, V>()
                    : LocalCache.<ReferenceEntry<K, V>>discardingQueue();
        }

        AtomicReferenceArray<ReferenceEntry<K, V>> newEntryArray(int size) {
            return new AtomicReferenceArray<>(size);
        }

        void initTable(AtomicReferenceArray<ReferenceEntry<K, V>> newTable) {
            this.threshold = newTable.length() * 3 / 4; // 0.75
            if (!map.customWeigher() && this.threshold == maxSegmentWeight) {
                // prevent spurious expansion before eviction
                this.threshold++;
            }
            this.table = newTable;
        }


        ReferenceEntry<K, V> newEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            return map.entryFactory.newEntry(this, checkNotNull(key), hash, next);
        }


        ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
            if (original.getKey() == null) {
                // key collected
                return null;
            }

            ValueReference<K, V> valueReference = original.getValueReference();
            V value = valueReference.get();
            if ((value == null) && valueReference.isActive()) {
                // value collected
                return null;
            }

            ReferenceEntry<K, V> newEntry = map.entryFactory.copyEntry(this, original, newNext);
            newEntry.setValueReference(valueReference.copyFor(this.valueReferenceQueue, value, newEntry));
            return newEntry;
        }


        void setValue(ReferenceEntry<K, V> entry, K key, V value, long now) {
            ValueReference<K, V> previous = entry.getValueReference();
            int weight = map.weigher.weigh(key, value);
            checkState(weight >= 0, "Weights must be non-negative");

            ValueReference<K, V> valueReference =
                    map.valueStrength.referenceValue(this, entry, value, weight);
            entry.setValueReference(valueReference);
            recordWrite(entry, weight, now);
            previous.notifyNewValue(value);
        }

        // loading

        V get(K key, int hash, CacheLoader<? super K, V> loader) throws ExecutionException {
            checkNotNull(key);
            checkNotNull(loader);
            try {
                if (count != 0) { // read-volatile
                    // don't call getLiveEntry, which would ignore loading values
                    ReferenceEntry<K, V> e = getEntry(key, hash);
                    if (e != null) {
                        long now = map.ticker.read();
                        V value = getLiveValue(e, now);
                        if (value != null) {
                            recordRead(e, now);
                            statsCounter.recordHits(1);
                            return scheduleRefresh(e, key, hash, value, now, loader);
                        }
                        ValueReference<K, V> valueReference = e.getValueReference();
                        if (valueReference.isLoading()) {
                            return waitForLoadingValue(e, key, valueReference);
                        }
                    }
                }

                // at this point e is either null or expired;
                return lockedGetOrLoad(key, hash, loader);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof Error) {
                    throw new ExecutionError((Error) cause);
                } else if (cause instanceof RuntimeException) {
                    throw new UncheckedExecutionException(cause);
                }
                throw ee;
            } finally {
                postReadCleanup();
            }
        }

        V lockedGetOrLoad(K key, int hash, CacheLoader<? super K, V> loader)
                throws ExecutionException {
            ReferenceEntry<K, V> e;
            ValueReference<K, V> valueReference = null;
            LoadingValueReference<K, V> loadingValueReference = null;
            boolean createNewEntry = true;

            lock();
            try {
                // re-read ticker once inside the lock
                long now = map.ticker.read();
                preWriteCleanup(now);

                int newCount = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        valueReference = e.getValueReference();
                        if (valueReference.isLoading()) {
                            createNewEntry = false;
                        } else {
                            V value = valueReference.get();
                            if (value == null) {
                                enqueueNotification(entryKey, hash, valueReference, RemovalCause.COLLECTED);
                            } else if (map.isExpired(e, now)) {
                                // This is a duplicate check, as preWriteCleanup already purged expired
                                // entries, but let's accomodate an incorrect expiration queue.
                                enqueueNotification(entryKey, hash, valueReference, RemovalCause.EXPIRED);
                            } else {
                                recordLockedRead(e, now);
                                statsCounter.recordHits(1);
                                // we were concurrent with loading; don't consider refresh
                                return value;
                            }

                            // immediately reuse invalid entries
                            writeQueue.remove(e);
                            accessQueue.remove(e);
                            this.count = newCount; // write-volatile
                        }
                        break;
                    }
                }

                if (createNewEntry) {
                    loadingValueReference = new LoadingValueReference<>();

                    if (e == null) {
                        e = newEntry(key, hash, first);
                        e.setValueReference(loadingValueReference);
                        table.set(index, e);
                    } else {
                        e.setValueReference(loadingValueReference);
                    }
                }
            } finally {
                unlock();
                postWriteCleanup();
            }

            if (createNewEntry) {
                try {
                    // Synchronizes on the entry to allow failing fast when a recursive load is
                    // detected. This may be circumvented when an entry is copied, but will fail fast most
                    // of the time.
                    synchronized (e) {
                        return loadSync(key, hash, loadingValueReference, loader);
                    }
                } finally {
                    statsCounter.recordMisses(1);
                }
            } else {
                // The entry already exists. Wait for loading.
                return waitForLoadingValue(e, key, valueReference);
            }
        }

        V waitForLoadingValue(ReferenceEntry<K, V> e, K key, ValueReference<K, V> valueReference)
                throws ExecutionException {
            if (!valueReference.isLoading()) {
                throw new AssertionError();
            }

            checkState(!Thread.holdsLock(e), "Recursive load of: %s", key);
            // don't consider expiration as we're concurrent with loading
            try {
                V value = valueReference.waitForValue();
                if (value == null) {
                    throw new InvalidCacheLoadException("CacheLoader returned null for key " + key + ".");
                }
                // re-read ticker now that loading has completed
                long now = map.ticker.read();
                recordRead(e, now);
                return value;
            } finally {
                statsCounter.recordMisses(1);
            }
        }

        // at most one of loadSync/loadAsync may be called for any given LoadingValueReference

        V loadSync(K key, int hash, LoadingValueReference<K, V> loadingValueReference,
                   CacheLoader<? super K, V> loader) throws ExecutionException {
            ListenableFuture<V> loadingFuture = loadingValueReference.loadFuture(key, loader);
            return getAndRecordStats(key, hash, loadingValueReference, loadingFuture);
        }

        ListenableFuture<V> loadAsync(final K key, final int hash,
                                      final LoadingValueReference<K, V> loadingValueReference, CacheLoader<? super K, V> loader) {
            final ListenableFuture<V> loadingFuture = loadingValueReference.loadFuture(key, loader);
            loadingFuture.addListener(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                V newValue = getAndRecordStats(key, hash, loadingValueReference, loadingFuture);
                            } catch (Throwable t) {
                                logger.log(Level.WARNING, "Exception thrown during refresh", t);
                                loadingValueReference.setException(t);
                            }
                        }
                    }, directExecutor());
            return loadingFuture;
        }


        V getAndRecordStats(K key, int hash, LoadingValueReference<K, V> loadingValueReference,
                            ListenableFuture<V> newValue) throws ExecutionException {
            V value = null;
            try {
                value = getUninterruptibly(newValue);
                if (value == null) {
                    throw new InvalidCacheLoadException("CacheLoader returned null for key " + key + ".");
                }
                statsCounter.recordLoadSuccess(loadingValueReference.elapsedNanos());
                storeLoadedValue(key, hash, loadingValueReference, value);
                return value;
            } finally {
                if (value == null) {
                    statsCounter.recordLoadException(loadingValueReference.elapsedNanos());
                    removeLoadingValue(key, hash, loadingValueReference);
                }
            }
        }

        V scheduleRefresh(ReferenceEntry<K, V> entry, K key, int hash, V oldValue, long now,
                          CacheLoader<? super K, V> loader) {
            if (map.refreshes() && (now - entry.getWriteTime() > map.refreshNanos)
                    && !entry.getValueReference().isLoading()) {
                V newValue = refresh(key, hash, loader, true);
                if (newValue != null) {
                    return newValue;
                }
            }
            return oldValue;
        }


        @Nullable
        V refresh(K key, int hash, CacheLoader<? super K, V> loader, boolean checkTime) {
            final LoadingValueReference<K, V> loadingValueReference =
                    insertLoadingValueReference(key, hash, checkTime);
            if (loadingValueReference == null) {
                return null;
            }

            ListenableFuture<V> result = loadAsync(key, hash, loadingValueReference, loader);
            if (result.isDone()) {
                try {
                    return Uninterruptibles.getUninterruptibly(result);
                } catch (Throwable t) {
                    // don't let refresh exceptions propagate; error was already logged
                }
            }
            return null;
        }


        @Nullable
        LoadingValueReference<K, V> insertLoadingValueReference(final K key, final int hash,
                                                                boolean checkTime) {
            ReferenceEntry<K, V> e = null;
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                // Look for an existing entry.
                for (e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        // We found an existing entry.

                        ValueReference<K, V> valueReference = e.getValueReference();
                        if (valueReference.isLoading()
                                || (checkTime && (now - e.getWriteTime() < map.refreshNanos))) {
                            // refresh is a no-op if loading is pending
                            // if checkTime, we want to check *after* acquiring the lock if refresh still needs
                            // to be scheduled
                            return null;
                        }

                        // continue returning old value while loading
                        ++modCount;
                        LoadingValueReference<K, V> loadingValueReference =
                                new LoadingValueReference<>(valueReference);
                        e.setValueReference(loadingValueReference);
                        return loadingValueReference;
                    }
                }

                ++modCount;
                LoadingValueReference<K, V> loadingValueReference = new LoadingValueReference<>();
                e = newEntry(key, hash, first);
                e.setValueReference(loadingValueReference);
                table.set(index, e);
                return loadingValueReference;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        // reference queues, for garbage collection cleanup


        void tryDrainReferenceQueues() {
            if (tryLock()) {
                try {
                    drainReferenceQueues();
                } finally {
                    unlock();
                }
            }
        }


        void drainReferenceQueues() {
            if (map.usesKeyReferences()) {
                drainKeyReferenceQueue();
            }
            if (map.usesValueReferences()) {
                drainValueReferenceQueue();
            }
        }


        void drainKeyReferenceQueue() {
            Reference<? extends K> ref;
            int i = 0;
            while ((ref = keyReferenceQueue.poll()) != null) {
                @SuppressWarnings("unchecked")
                ReferenceEntry<K, V> entry = (ReferenceEntry<K, V>) ref;
                map.reclaimKey(entry);
                if (++i == DRAIN_MAX) {
                    break;
                }
            }
        }


        void drainValueReferenceQueue() {
            Reference<? extends V> ref;
            int i = 0;
            while ((ref = valueReferenceQueue.poll()) != null) {
                @SuppressWarnings("unchecked")
                ValueReference<K, V> valueReference = (ValueReference<K, V>) ref;
                map.reclaimValue(valueReference);
                if (++i == DRAIN_MAX) {
                    break;
                }
            }
        }


        void clearReferenceQueues() {
            if (map.usesKeyReferences()) {
                clearKeyReferenceQueue();
            }
            if (map.usesValueReferences()) {
                clearValueReferenceQueue();
            }
        }

        void clearKeyReferenceQueue() {
            while (keyReferenceQueue.poll() != null) {
            }
        }

        void clearValueReferenceQueue() {
            while (valueReferenceQueue.poll() != null) {
            }
        }

        // recency queue, shared by expiration and eviction


        void recordRead(ReferenceEntry<K, V> entry, long now) {
            if (map.recordsAccess()) {
                entry.setAccessTime(now);
            }
            recencyQueue.add(entry);
        }


        void recordLockedRead(ReferenceEntry<K, V> entry, long now) {
            if (map.recordsAccess()) {
                entry.setAccessTime(now);
            }
            accessQueue.add(entry);
        }


        void recordWrite(ReferenceEntry<K, V> entry, int weight, long now) {
            // we are already under lock, so drain the recency queue immediately
            drainRecencyQueue();
            totalWeight += weight;

            if (map.recordsAccess()) {
                entry.setAccessTime(now);
            }
            if (map.recordsWrite()) {
                entry.setWriteTime(now);
            }
            accessQueue.add(entry);
            writeQueue.add(entry);
        }


        void drainRecencyQueue() {
            ReferenceEntry<K, V> e;
            while ((e = recencyQueue.poll()) != null) {
                // An entry may be in the recency queue despite it being removed from
                // the map . This can occur when the entry was concurrently read while a
                // writer is removing it from the segment or after a clear has removed
                // all of the segment's entries.
                if (accessQueue.contains(e)) {
                    accessQueue.add(e);
                }
            }
        }

        // expiration


        void tryExpireEntries(long now) {
            if (tryLock()) {
                try {
                    expireEntries(now);
                } finally {
                    unlock();
                    // don't call postWriteCleanup as we're in a read
                }
            }
        }


        void expireEntries(long now) {
            drainRecencyQueue();

            ReferenceEntry<K, V> e;
            while ((e = writeQueue.peek()) != null && map.isExpired(e, now)) {
                if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
                    throw new AssertionError();
                }
            }
            while ((e = accessQueue.peek()) != null && map.isExpired(e, now)) {
                if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
                    throw new AssertionError();
                }
            }
        }

        // eviction


        void enqueueNotification(ReferenceEntry<K, V> entry, RemovalCause cause) {
            enqueueNotification(entry.getKey(), entry.getHash(), entry.getValueReference(), cause);
        }


        void enqueueNotification(@Nullable K key, int hash, ValueReference<K, V> valueReference,
                                 RemovalCause cause) {
            totalWeight -= valueReference.getWeight();
            if (cause.wasEvicted()) {
                statsCounter.recordEviction();
            }
            if (map.removalNotificationQueue != DISCARDING_QUEUE) {
                V value = valueReference.get();
                RemovalNotification<K, V> notification = RemovalNotification.create(key, value, cause);
                map.removalNotificationQueue.offer(notification);
            }
        }


        void evictEntries(ReferenceEntry<K, V> newest) {
            if (!map.evictsBySize()) {
                return;
            }

            drainRecencyQueue();

            // If the newest entry by itself is too heavy for the segment, don't bother evicting
            // anything else, just that
            if (newest.getValueReference().getWeight() > maxSegmentWeight) {
                if (!removeEntry(newest, newest.getHash(), RemovalCause.SIZE)) {
                    throw new AssertionError();
                }
            }

            while (totalWeight > maxSegmentWeight) {
                ReferenceEntry<K, V> e = getNextEvictable();
                if (!removeEntry(e, e.getHash(), RemovalCause.SIZE)) {
                    throw new AssertionError();
                }
            }
        }

        // TODO(fry): instead implement this with an eviction head

        ReferenceEntry<K, V> getNextEvictable() {
            for (ReferenceEntry<K, V> e : accessQueue) {
                int weight = e.getValueReference().getWeight();
                if (weight > 0) {
                    return e;
                }
            }
            throw new AssertionError();
        }


        ReferenceEntry<K, V> getFirst(int hash) {
            // read this volatile field only once
            AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
            return table.get(hash & (table.length() - 1));
        }

        // Specialized implementations of map methods

        @Nullable
        ReferenceEntry<K, V> getEntry(Object key, int hash) {
            for (ReferenceEntry<K, V> e = getFirst(hash); e != null; e = e.getNext()) {
                if (e.getHash() != hash) {
                    continue;
                }

                K entryKey = e.getKey();
                if (entryKey == null) {
                    tryDrainReferenceQueues();
                    continue;
                }

                if (map.keyEquivalence.equivalent(key, entryKey)) {
                    return e;
                }
            }

            return null;
        }

        @Nullable
        ReferenceEntry<K, V> getLiveEntry(Object key, int hash, long now) {
            ReferenceEntry<K, V> e = getEntry(key, hash);
            if (e == null) {
                return null;
            } else if (map.isExpired(e, now)) {
                tryExpireEntries(now);
                return null;
            }
            return e;
        }


        V getLiveValue(ReferenceEntry<K, V> entry, long now) {
            if (entry.getKey() == null) {
                tryDrainReferenceQueues();
                return null;
            }
            V value = entry.getValueReference().get();
            if (value == null) {
                tryDrainReferenceQueues();
                return null;
            }

            if (map.isExpired(entry, now)) {
                tryExpireEntries(now);
                return null;
            }
            return value;
        }

        @Nullable
        V get(Object key, int hash) {
            try {
                if (count != 0) { // read-volatile
                    long now = map.ticker.read();
                    ReferenceEntry<K, V> e = getLiveEntry(key, hash, now);
                    if (e == null) {
                        return null;
                    }

                    V value = e.getValueReference().get();
                    if (value != null) {
                        recordRead(e, now);
                        return scheduleRefresh(e, e.getKey(), hash, value, now, map.defaultLoader);
                    }
                    tryDrainReferenceQueues();
                }
                return null;
            } finally {
                postReadCleanup();
            }
        }

        boolean containsKey(Object key, int hash) {
            try {
                if (count != 0) { // read-volatile
                    long now = map.ticker.read();
                    ReferenceEntry<K, V> e = getLiveEntry(key, hash, now);
                    if (e == null) {
                        return false;
                    }
                    return e.getValueReference().get() != null;
                }

                return false;
            } finally {
                postReadCleanup();
            }
        }


        @VisibleForTesting
        boolean containsValue(Object value) {
            try {
                if (count != 0) { // read-volatile
                    long now = map.ticker.read();
                    AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                    int length = table.length();
                    for (int i = 0; i < length; ++i) {
                        for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
                            V entryValue = getLiveValue(e, now);
                            if (entryValue == null) {
                                continue;
                            }
                            if (map.valueEquivalence.equivalent(value, entryValue)) {
                                return true;
                            }
                        }
                    }
                }

                return false;
            } finally {
                postReadCleanup();
            }
        }

        @Nullable
        V put(K key, int hash, V value, boolean onlyIfAbsent) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                int newCount = this.count + 1;
                if (newCount > this.threshold) { // ensure capacity
                    expand();
                    newCount = this.count + 1;
                }

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                // Look for an existing entry.
                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        // We found an existing entry.

                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();

                        if (entryValue == null) {
                            ++modCount;
                            if (valueReference.isActive()) {
                                enqueueNotification(key, hash, valueReference, RemovalCause.COLLECTED);
                                setValue(e, key, value, now);
                                newCount = this.count; // count remains unchanged
                            } else {
                                setValue(e, key, value, now);
                                newCount = this.count + 1;
                            }
                            this.count = newCount; // write-volatile
                            evictEntries(e);
                            return null;
                        } else if (onlyIfAbsent) {
                            // Mimic
                            // "if (!map.containsKey(key)) ...
                            // else return map.get(key);
                            recordLockedRead(e, now);
                            return entryValue;
                        } else {
                            // clobber existing entry, count remains unchanged
                            ++modCount;
                            enqueueNotification(key, hash, valueReference, RemovalCause.REPLACED);
                            setValue(e, key, value, now);
                            evictEntries(e);
                            return entryValue;
                        }
                    }
                }

                // Create a new entry.
                ++modCount;
                ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
                setValue(newEntry, key, value, now);
                table.set(index, newEntry);
                newCount = this.count + 1;
                this.count = newCount; // write-volatile
                evictEntries(newEntry);
                return null;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }


        void expand() {
            AtomicReferenceArray<ReferenceEntry<K, V>> oldTable = table;
            int oldCapacity = oldTable.length();
            if (oldCapacity >= MAXIMUM_CAPACITY) {
                return;
            }


            int newCount = count;
            AtomicReferenceArray<ReferenceEntry<K, V>> newTable = newEntryArray(oldCapacity << 1);
            threshold = newTable.length() * 3 / 4;
            int newMask = newTable.length() - 1;
            for (int oldIndex = 0; oldIndex < oldCapacity; ++oldIndex) {
                // We need to guarantee that any existing reads of old Map can
                // proceed. So we cannot yet null out each bin.
                ReferenceEntry<K, V> head = oldTable.get(oldIndex);

                if (head != null) {
                    ReferenceEntry<K, V> next = head.getNext();
                    int headIndex = head.getHash() & newMask;

                    // Single node on list
                    if (next == null) {
                        newTable.set(headIndex, head);
                    } else {
                        // Reuse the consecutive sequence of nodes with the same target
                        // index from the end of the list. tail points to the first
                        // entry in the reusable list.
                        ReferenceEntry<K, V> tail = head;
                        int tailIndex = headIndex;
                        for (ReferenceEntry<K, V> e = next; e != null; e = e.getNext()) {
                            int newIndex = e.getHash() & newMask;
                            if (newIndex != tailIndex) {
                                // The index changed. We'll need to copy the previous entry.
                                tailIndex = newIndex;
                                tail = e;
                            }
                        }
                        newTable.set(tailIndex, tail);

                        // Clone nodes leading up to the tail.
                        for (ReferenceEntry<K, V> e = head; e != tail; e = e.getNext()) {
                            int newIndex = e.getHash() & newMask;
                            ReferenceEntry<K, V> newNext = newTable.get(newIndex);
                            ReferenceEntry<K, V> newFirst = copyEntry(e, newNext);
                            if (newFirst != null) {
                                newTable.set(newIndex, newFirst);
                            } else {
                                removeCollectedEntry(e);
                                newCount--;
                            }
                        }
                    }
                }
            }
            table = newTable;
            this.count = newCount;
        }

        boolean replace(K key, int hash, V oldValue, V newValue) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();
                        if (entryValue == null) {
                            if (valueReference.isActive()) {
                                // If the value disappeared, this entry is partially collected.
                                int newCount = this.count - 1;
                                ++modCount;
                                ReferenceEntry<K, V> newFirst = removeValueFromChain(
                                        first, e, entryKey, hash, valueReference, RemovalCause.COLLECTED);
                                newCount = this.count - 1;
                                table.set(index, newFirst);
                                this.count = newCount; // write-volatile
                            }
                            return false;
                        }

                        if (map.valueEquivalence.equivalent(oldValue, entryValue)) {
                            ++modCount;
                            enqueueNotification(key, hash, valueReference, RemovalCause.REPLACED);
                            setValue(e, key, newValue, now);
                            evictEntries(e);
                            return true;
                        } else {
                            // Mimic
                            // "if (map.containsKey(key) && map.get(key).equals(oldValue))..."
                            recordLockedRead(e, now);
                            return false;
                        }
                    }
                }

                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        @Nullable
        V replace(K key, int hash, V newValue) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();
                        if (entryValue == null) {
                            if (valueReference.isActive()) {
                                // If the value disappeared, this entry is partially collected.
                                int newCount = this.count - 1;
                                ++modCount;
                                ReferenceEntry<K, V> newFirst = removeValueFromChain(
                                        first, e, entryKey, hash, valueReference, RemovalCause.COLLECTED);
                                newCount = this.count - 1;
                                table.set(index, newFirst);
                                this.count = newCount; // write-volatile
                            }
                            return null;
                        }

                        ++modCount;
                        enqueueNotification(key, hash, valueReference, RemovalCause.REPLACED);
                        setValue(e, key, newValue, now);
                        evictEntries(e);
                        return entryValue;
                    }
                }

                return null;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        @Nullable
        V remove(Object key, int hash) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                int newCount = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();

                        RemovalCause cause;
                        if (entryValue != null) {
                            cause = RemovalCause.EXPLICIT;
                        } else if (valueReference.isActive()) {
                            cause = RemovalCause.COLLECTED;
                        } else {
                            // currently loading
                            return null;
                        }

                        ++modCount;
                        ReferenceEntry<K, V> newFirst = removeValueFromChain(
                                first, e, entryKey, hash, valueReference, cause);
                        newCount = this.count - 1;
                        table.set(index, newFirst);
                        this.count = newCount; // write-volatile
                        return entryValue;
                    }
                }

                return null;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        boolean storeLoadedValue(K key, int hash, LoadingValueReference<K, V> oldValueReference,
                                 V newValue) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                int newCount = this.count + 1;
                if (newCount > this.threshold) { // ensure capacity
                    expand();
                    newCount = this.count + 1;
                }

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();
                        // replace the old LoadingValueReference if it's live, otherwise
                        // perform a putIfAbsent
                        if (oldValueReference == valueReference
                                || (entryValue == null && valueReference != UNSET)) {
                            ++modCount;
                            if (oldValueReference.isActive()) {
                                RemovalCause cause =
                                        (entryValue == null) ? RemovalCause.COLLECTED : RemovalCause.REPLACED;
                                enqueueNotification(key, hash, oldValueReference, cause);
                                newCount--;
                            }
                            setValue(e, key, newValue, now);
                            this.count = newCount; // write-volatile
                            evictEntries(e);
                            return true;
                        }

                        // the loaded value was already clobbered
                        valueReference = new WeightedStrongValueReference<>(newValue, 0);
                        enqueueNotification(key, hash, valueReference, RemovalCause.REPLACED);
                        return false;
                    }
                }

                ++modCount;
                ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
                setValue(newEntry, key, newValue, now);
                table.set(index, newEntry);
                this.count = newCount; // write-volatile
                evictEntries(newEntry);
                return true;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        boolean remove(Object key, int hash, Object value) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                int newCount = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();

                        RemovalCause cause;
                        if (map.valueEquivalence.equivalent(value, entryValue)) {
                            cause = RemovalCause.EXPLICIT;
                        } else if (entryValue == null && valueReference.isActive()) {
                            cause = RemovalCause.COLLECTED;
                        } else {
                            // currently loading
                            return false;
                        }

                        ++modCount;
                        ReferenceEntry<K, V> newFirst = removeValueFromChain(
                                first, e, entryKey, hash, valueReference, cause);
                        newCount = this.count - 1;
                        table.set(index, newFirst);
                        this.count = newCount; // write-volatile
                        return (cause == RemovalCause.EXPLICIT);
                    }
                }

                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        void clear() {
            if (count != 0) { // read-volatile
                lock();
                try {
                    AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                    for (int i = 0; i < table.length(); ++i) {
                        for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
                            // Loading references aren't actually in the map yet.
                            if (e.getValueReference().isActive()) {
                                enqueueNotification(e, RemovalCause.EXPLICIT);
                            }
                        }
                    }
                    for (int i = 0; i < table.length(); ++i) {
                        table.set(i, null);
                    }
                    clearReferenceQueues();
                    writeQueue.clear();
                    accessQueue.clear();
                    readCount.set(0);

                    ++modCount;
                    count = 0; // write-volatile
                } finally {
                    unlock();
                    postWriteCleanup();
                }
            }
        }


        @Nullable
        ReferenceEntry<K, V> removeValueFromChain(ReferenceEntry<K, V> first,
                                                  ReferenceEntry<K, V> entry, @Nullable K key, int hash, ValueReference<K, V> valueReference,
                                                  RemovalCause cause) {
            enqueueNotification(key, hash, valueReference, cause);
            writeQueue.remove(entry);
            accessQueue.remove(entry);

            if (valueReference.isLoading()) {
                valueReference.notifyNewValue(null);
                return first;
            } else {
                return removeEntryFromChain(first, entry);
            }
        }


        @Nullable
        ReferenceEntry<K, V> removeEntryFromChain(ReferenceEntry<K, V> first,
                                                  ReferenceEntry<K, V> entry) {
            int newCount = count;
            ReferenceEntry<K, V> newFirst = entry.getNext();
            for (ReferenceEntry<K, V> e = first; e != entry; e = e.getNext()) {
                ReferenceEntry<K, V> next = copyEntry(e, newFirst);
                if (next != null) {
                    newFirst = next;
                } else {
                    removeCollectedEntry(e);
                    newCount--;
                }
            }
            this.count = newCount;
            return newFirst;
        }


        void removeCollectedEntry(ReferenceEntry<K, V> entry) {
            enqueueNotification(entry, RemovalCause.COLLECTED);
            writeQueue.remove(entry);
            accessQueue.remove(entry);
        }


        boolean reclaimKey(ReferenceEntry<K, V> entry, int hash) {
            lock();
            try {
                int newCount = count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    if (e == entry) {
                        ++modCount;
                        ReferenceEntry<K, V> newFirst = removeValueFromChain(
                                first, e, e.getKey(), hash, e.getValueReference(), RemovalCause.COLLECTED);
                        newCount = this.count - 1;
                        table.set(index, newFirst);
                        this.count = newCount; // write-volatile
                        return true;
                    }
                }

                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }


        boolean reclaimValue(K key, int hash, ValueReference<K, V> valueReference) {
            lock();
            try {
                int newCount = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> v = e.getValueReference();
                        if (v == valueReference) {
                            ++modCount;
                            ReferenceEntry<K, V> newFirst = removeValueFromChain(
                                    first, e, entryKey, hash, valueReference, RemovalCause.COLLECTED);
                            newCount = this.count - 1;
                            table.set(index, newFirst);
                            this.count = newCount; // write-volatile
                            return true;
                        }
                        return false;
                    }
                }

                return false;
            } finally {
                unlock();
                if (!isHeldByCurrentThread()) { // don't cleanup inside of put
                    postWriteCleanup();
                }
            }
        }

        boolean removeLoadingValue(K key, int hash, LoadingValueReference<K, V> valueReference) {
            lock();
            try {
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> v = e.getValueReference();
                        if (v == valueReference) {
                            if (valueReference.isActive()) {
                                e.setValueReference(valueReference.getOldValue());
                            } else {
                                ReferenceEntry<K, V> newFirst = removeEntryFromChain(first, e);
                                table.set(index, newFirst);
                            }
                            return true;
                        }
                        return false;
                    }
                }

                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }


        boolean removeEntry(ReferenceEntry<K, V> entry, int hash, RemovalCause cause) {
            int newCount = this.count - 1;
            AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
            int index = hash & (table.length() - 1);
            ReferenceEntry<K, V> first = table.get(index);

            for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                if (e == entry) {
                    ++modCount;
                    ReferenceEntry<K, V> newFirst = removeValueFromChain(
                            first, e, e.getKey(), hash, e.getValueReference(), cause);
                    newCount = this.count - 1;
                    table.set(index, newFirst);
                    this.count = newCount; // write-volatile
                    return true;
                }
            }

            return false;
        }


        void postReadCleanup() {
            if ((readCount.incrementAndGet() & DRAIN_THRESHOLD) == 0) {
                cleanUp();
            }
        }


        void preWriteCleanup(long now) {
            runLockedCleanup(now);
        }


        void postWriteCleanup() {
            runUnlockedCleanup();
        }

        void cleanUp() {
            long now = map.ticker.read();
            runLockedCleanup(now);
            runUnlockedCleanup();
        }

        void runLockedCleanup(long now) {
            if (tryLock()) {
                try {
                    drainReferenceQueues();
                    expireEntries(now); // calls drainRecencyQueue
                    readCount.set(0);
                } finally {
                    unlock();
                }
            }
        }

        void runUnlockedCleanup() {
            // locked cleanup may generate notifications we can send unlocked
            if (!isHeldByCurrentThread()) {
                map.processPendingNotifications();
            }
        }

    }

    static class LoadingValueReference<K, V> implements ValueReference<K, V> {
        volatile ValueReference<K, V> oldValue;

        // TODO(fry): rename get, then extend AbstractFuture instead of containing SettableFuture
        final SettableFuture<V> futureValue = SettableFuture.create();
        final Stopwatch stopwatch = Stopwatch.createUnstarted();

        public LoadingValueReference() {
            this(LocalCache.<K, V>unset());
        }

        public LoadingValueReference(ValueReference<K, V> oldValue) {
            this.oldValue = oldValue;
        }

        @Override
        public boolean isLoading() {
            return true;
        }

        @Override
        public boolean isActive() {
            return oldValue.isActive();
        }

        @Override
        public int getWeight() {
            return oldValue.getWeight();
        }

        public boolean set(@Nullable V newValue) {
            return futureValue.set(newValue);
        }

        public boolean setException(Throwable t) {
            return futureValue.setException(t);
        }

        private ListenableFuture<V> fullyFailedFuture(Throwable t) {
            return Futures.immediateFailedFuture(t);
        }

        @Override
        public void notifyNewValue(@Nullable V newValue) {
            if (newValue != null) {
                // The pending load was clobbered by a manual write.
                // Unblock all pending gets, and have them return the new value.
                set(newValue);
            } else {
                // The pending load was removed. Delay notifications until loading completes.
                oldValue = unset();
            }

            // TODO(fry): could also cancel loading if we had a handle on its future
        }

        public ListenableFuture<V> loadFuture(K key, CacheLoader<? super K, V> loader) {
            try {
                stopwatch.start();
                V previousValue = oldValue.get();
                if (previousValue == null) {
                    V newValue = loader.load(key);
                    return set(newValue) ? futureValue : Futures.immediateFuture(newValue);
                }
                ListenableFuture<V> newValue = loader.reload(key, previousValue);
                if (newValue == null) {
                    return Futures.immediateFuture(null);
                }
                // To avoid a race, make sure the refreshed value is set into loadingValueReference
                // *before* returning newValue from the cache query.
                return Futures.transform(newValue, new Function<V, V>() {
                    @Override
                    public V apply(V newValue) {
                        LoadingValueReference.this.set(newValue);
                        return newValue;
                    }
                });
            } catch (Throwable t) {
                ListenableFuture<V> result = setException(t) ? futureValue : fullyFailedFuture(t);
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return result;
            }
        }

        public long elapsedNanos() {
            return stopwatch.elapsed(NANOSECONDS);
        }

        @Override
        public V waitForValue() throws ExecutionException {
            return getUninterruptibly(futureValue);
        }

        @Override
        public V get() {
            return oldValue.get();
        }

        public ValueReference<K, V> getOldValue() {
            return oldValue;
        }

        @Override
        public ReferenceEntry<K, V> getEntry() {
            return null;
        }

        @Override
        public ValueReference<K, V> copyFor(
                ReferenceQueue<V> queue, @Nullable V value, ReferenceEntry<K, V> entry) {
            return this;
        }
    }

    // Queues


    static final class WriteQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
        final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {

            @Override
            public long getWriteTime() {
                return Long.MAX_VALUE;
            }

            @Override
            public void setWriteTime(long time) {
            }

            ReferenceEntry<K, V> nextWrite = this;

            @Override
            public ReferenceEntry<K, V> getNextInWriteQueue() {
                return nextWrite;
            }

            @Override
            public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
                this.nextWrite = next;
            }

            ReferenceEntry<K, V> previousWrite = this;

            @Override
            public ReferenceEntry<K, V> getPreviousInWriteQueue() {
                return previousWrite;
            }

            @Override
            public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
                this.previousWrite = previous;
            }
        };

        // implements Queue

        @Override
        public boolean offer(ReferenceEntry<K, V> entry) {
            // unlink
            connectWriteOrder(entry.getPreviousInWriteQueue(), entry.getNextInWriteQueue());

            // add to tail
            connectWriteOrder(head.getPreviousInWriteQueue(), entry);
            connectWriteOrder(entry, head);

            return true;
        }

        @Override
        public ReferenceEntry<K, V> peek() {
            ReferenceEntry<K, V> next = head.getNextInWriteQueue();
            return (next == head) ? null : next;
        }

        @Override
        public ReferenceEntry<K, V> poll() {
            ReferenceEntry<K, V> next = head.getNextInWriteQueue();
            if (next == head) {
                return null;
            }

            remove(next);
            return next;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean remove(Object o) {
            ReferenceEntry<K, V> e = (ReferenceEntry) o;
            ReferenceEntry<K, V> previous = e.getPreviousInWriteQueue();
            ReferenceEntry<K, V> next = e.getNextInWriteQueue();
            connectWriteOrder(previous, next);
            nullifyWriteOrder(e);

            return next != NullEntry.INSTANCE;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean contains(Object o) {
            ReferenceEntry<K, V> e = (ReferenceEntry) o;
            return e.getNextInWriteQueue() != NullEntry.INSTANCE;
        }

        @Override
        public boolean isEmpty() {
            return head.getNextInWriteQueue() == head;
        }

        @Override
        public int size() {
            int size = 0;
            for (ReferenceEntry<K, V> e = head.getNextInWriteQueue(); e != head;
                 e = e.getNextInWriteQueue()) {
                size++;
            }
            return size;
        }

        @Override
        public void clear() {
            ReferenceEntry<K, V> e = head.getNextInWriteQueue();
            while (e != head) {
                ReferenceEntry<K, V> next = e.getNextInWriteQueue();
                nullifyWriteOrder(e);
                e = next;
            }

            head.setNextInWriteQueue(head);
            head.setPreviousInWriteQueue(head);
        }

        @Override
        public Iterator<ReferenceEntry<K, V>> iterator() {
            return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
                @Override
                protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
                    ReferenceEntry<K, V> next = previous.getNextInWriteQueue();
                    return (next == head) ? null : next;
                }
            };
        }
    }


    static final class AccessQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
        final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {

            @Override
            public long getAccessTime() {
                return Long.MAX_VALUE;
            }

            @Override
            public void setAccessTime(long time) {
            }

            ReferenceEntry<K, V> nextAccess = this;

            @Override
            public ReferenceEntry<K, V> getNextInAccessQueue() {
                return nextAccess;
            }

            @Override
            public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
                this.nextAccess = next;
            }

            ReferenceEntry<K, V> previousAccess = this;

            @Override
            public ReferenceEntry<K, V> getPreviousInAccessQueue() {
                return previousAccess;
            }

            @Override
            public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
                this.previousAccess = previous;
            }
        };

        // implements Queue

        @Override
        public boolean offer(ReferenceEntry<K, V> entry) {
            // unlink
            connectAccessOrder(entry.getPreviousInAccessQueue(), entry.getNextInAccessQueue());

            // add to tail
            connectAccessOrder(head.getPreviousInAccessQueue(), entry);
            connectAccessOrder(entry, head);

            return true;
        }

        @Override
        public ReferenceEntry<K, V> peek() {
            ReferenceEntry<K, V> next = head.getNextInAccessQueue();
            return (next == head) ? null : next;
        }

        @Override
        public ReferenceEntry<K, V> poll() {
            ReferenceEntry<K, V> next = head.getNextInAccessQueue();
            if (next == head) {
                return null;
            }

            remove(next);
            return next;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean remove(Object o) {
            ReferenceEntry<K, V> e = (ReferenceEntry) o;
            ReferenceEntry<K, V> previous = e.getPreviousInAccessQueue();
            ReferenceEntry<K, V> next = e.getNextInAccessQueue();
            connectAccessOrder(previous, next);
            nullifyAccessOrder(e);

            return next != NullEntry.INSTANCE;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean contains(Object o) {
            ReferenceEntry<K, V> e = (ReferenceEntry) o;
            return e.getNextInAccessQueue() != NullEntry.INSTANCE;
        }

        @Override
        public boolean isEmpty() {
            return head.getNextInAccessQueue() == head;
        }

        @Override
        public int size() {
            int size = 0;
            for (ReferenceEntry<K, V> e = head.getNextInAccessQueue(); e != head;
                 e = e.getNextInAccessQueue()) {
                size++;
            }
            return size;
        }

        @Override
        public void clear() {
            ReferenceEntry<K, V> e = head.getNextInAccessQueue();
            while (e != head) {
                ReferenceEntry<K, V> next = e.getNextInAccessQueue();
                nullifyAccessOrder(e);
                e = next;
            }

            head.setNextInAccessQueue(head);
            head.setPreviousInAccessQueue(head);
        }

        @Override
        public Iterator<ReferenceEntry<K, V>> iterator() {
            return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
                @Override
                protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
                    ReferenceEntry<K, V> next = previous.getNextInAccessQueue();
                    return (next == head) ? null : next;
                }
            };
        }
    }

    // Cache support

    public void cleanUp() {
        for (Segment<?, ?> segment : segments) {
            segment.cleanUp();
        }
    }

    // ConcurrentMap methods

    @Override
    public boolean isEmpty() {

        long sum = 0L;
        Segment<K, V>[] segments = this.segments;
        for (int i = 0; i < segments.length; ++i) {
            if (segments[i].count != 0) {
                return false;
            }
            sum += segments[i].modCount;
        }

        if (sum != 0L) { // recheck unless no modifications
            for (int i = 0; i < segments.length; ++i) {
                if (segments[i].count != 0) {
                    return false;
                }
                sum -= segments[i].modCount;
            }
            if (sum != 0L) {
                return false;
            }
        }
        return true;
    }

    long longSize() {
        Segment<K, V>[] segments = this.segments;
        long sum = 0;
        for (int i = 0; i < segments.length; ++i) {
            sum += segments[i].count;
        }
        return sum;
    }

    @Override
    public int size() {
        return Ints.saturatedCast(longSize());
    }

    @Override
    @Nullable
    public V get(@Nullable Object key) {
        if (key == null) {
            return null;
        }
        int hash = hash(key);
        return segmentFor(hash).get(key, hash);
    }

    @Nullable
    public V getIfPresent(Object key) {
        int hash = hash(checkNotNull(key));
        V value = segmentFor(hash).get(key, hash);
        if (value == null) {
            globalStatsCounter.recordMisses(1);
        } else {
            globalStatsCounter.recordHits(1);
        }
        return value;
    }

    V get(K key, CacheLoader<? super K, V> loader) throws ExecutionException {
        int hash = hash(checkNotNull(key));
        return segmentFor(hash).get(key, hash, loader);
    }

    V getOrLoad(K key) throws ExecutionException {
        return get(key, defaultLoader);
    }

    ImmutableMap<K, V> getAllPresent(Iterable<?> keys) {
        int hits = 0;
        int misses = 0;

        Map<K, V> result = Maps.newLinkedHashMap();
        for (Object key : keys) {
            V value = get(key);
            if (value == null) {
                misses++;
            } else {
                // TODO(fry): store entry key instead of query key
                @SuppressWarnings("unchecked")
                K castKey = (K) key;
                result.put(castKey, value);
                hits++;
            }
        }
        globalStatsCounter.recordHits(hits);
        globalStatsCounter.recordMisses(misses);
        return ImmutableMap.copyOf(result);
    }

    ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
        int hits = 0;
        int misses = 0;

        Map<K, V> result = Maps.newLinkedHashMap();
        Set<K> keysToLoad = Sets.newLinkedHashSet();
        for (K key : keys) {
            V value = get(key);
            if (!result.containsKey(key)) {
                result.put(key, value);
                if (value == null) {
                    misses++;
                    keysToLoad.add(key);
                } else {
                    hits++;
                }
            }
        }

        try {
            if (!keysToLoad.isEmpty()) {
                try {
                    Map<K, V> newEntries = loadAll(keysToLoad, defaultLoader);
                    for (K key : keysToLoad) {
                        V value = newEntries.get(key);
                        if (value == null) {
                            throw new InvalidCacheLoadException("loadAll failed to return a value for " + key);
                        }
                        result.put(key, value);
                    }
                } catch (UnsupportedLoadingOperationException e) {
                    // loadAll not implemented, fallback to load
                    for (K key : keysToLoad) {
                        misses--; // get will count this miss
                        result.put(key, get(key, defaultLoader));
                    }
                }
            }
            return ImmutableMap.copyOf(result);
        } finally {
            globalStatsCounter.recordHits(hits);
            globalStatsCounter.recordMisses(misses);
        }
    }


    @Nullable
    Map<K, V> loadAll(Set<? extends K> keys, CacheLoader<? super K, V> loader)
            throws ExecutionException {
        checkNotNull(loader);
        checkNotNull(keys);
        Stopwatch stopwatch = Stopwatch.createStarted();
        Map<K, V> result;
        boolean success = false;
        try {
            @SuppressWarnings("unchecked") // safe since all keys extend K
                    Map<K, V> map = (Map<K, V>) loader.loadAll(keys);
            result = map;
            success = true;
        } catch (UnsupportedLoadingOperationException e) {
            success = true;
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionException(e);
        } catch (RuntimeException e) {
            throw new UncheckedExecutionException(e);
        } catch (Exception e) {
            throw new ExecutionException(e);
        } catch (Error e) {
            throw new ExecutionError(e);
        } finally {
            if (!success) {
                globalStatsCounter.recordLoadException(stopwatch.elapsed(NANOSECONDS));
            }
        }

        if (result == null) {
            globalStatsCounter.recordLoadException(stopwatch.elapsed(NANOSECONDS));
            throw new InvalidCacheLoadException(loader + " returned null map from loadAll");
        }

        stopwatch.stop();
        // TODO(fry): batch by segment
        boolean nullsPresent = false;
        for (Map.Entry<K, V> entry : result.entrySet()) {
            K key = entry.getKey();
            V value = entry.getValue();
            if (key == null || value == null) {
                // delay failure until non-null entries are stored
                nullsPresent = true;
            } else {
                put(key, value);
            }
        }

        if (nullsPresent) {
            globalStatsCounter.recordLoadException(stopwatch.elapsed(NANOSECONDS));
            throw new InvalidCacheLoadException(loader + " returned null keys or values from loadAll");
        }

        // TODO(fry): record count of loaded entries
        globalStatsCounter.recordLoadSuccess(stopwatch.elapsed(NANOSECONDS));
        return result;
    }


    ReferenceEntry<K, V> getEntry(@Nullable Object key) {
        // does not impact recency ordering
        if (key == null) {
            return null;
        }
        int hash = hash(key);
        return segmentFor(hash).getEntry(key, hash);
    }

    void refresh(K key) {
        int hash = hash(checkNotNull(key));
        segmentFor(hash).refresh(key, hash, defaultLoader, false);
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
        // does not impact recency ordering
        if (key == null) {
            return false;
        }
        int hash = hash(key);
        return segmentFor(hash).containsKey(key, hash);
    }

    @Override
    public boolean containsValue(@Nullable Object value) {
        // does not impact recency ordering
        if (value == null) {
            return false;
        }

        // This implementation is patterned after ConcurrentHashMap, but without the locking. The only
        // way for it to return a false negative would be for the target value to jump around in the map
        // such that none of the subsequent iterations observed it, despite the fact that at every point
        // in time it was present somewhere int the map. This becomes increasingly unlikely as
        // CONTAINS_VALUE_RETRIES increases, though without locking it is theoretically possible.
        long now = ticker.read();
        final Segment<K, V>[] segments = this.segments;
        long last = -1L;
        for (int i = 0; i < CONTAINS_VALUE_RETRIES; i++) {
            long sum = 0L;
            for (Segment<K, V> segment : segments) {
                // ensure visibility of most recent completed write
                @SuppressWarnings({"UnusedDeclaration", "unused"})
                int c = segment.count; // read-volatile

                AtomicReferenceArray<ReferenceEntry<K, V>> table = segment.table;
                for (int j = 0; j < table.length(); j++) {
                    for (ReferenceEntry<K, V> e = table.get(j); e != null; e = e.getNext()) {
                        V v = segment.getLiveValue(e, now);
                        if (v != null && valueEquivalence.equivalent(value, v)) {
                            return true;
                        }
                    }
                }
                sum += segment.modCount;
            }
            if (sum == last) {
                break;
            }
            last = sum;
        }
        return false;
    }

    @Override
    public V put(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        int hash = hash(key);
        return segmentFor(hash).put(key, hash, value, false);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        int hash = hash(key);
        return segmentFor(hash).put(key, hash, value, true);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        for (Entry<? extends K, ? extends V> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public V remove(@Nullable Object key) {
        if (key == null) {
            return null;
        }
        int hash = hash(key);
        return segmentFor(hash).remove(key, hash);
    }

    @Override
    public boolean remove(@Nullable Object key, @Nullable Object value) {
        if (key == null || value == null) {
            return false;
        }
        int hash = hash(key);
        return segmentFor(hash).remove(key, hash, value);
    }

    @Override
    public boolean replace(K key, @Nullable V oldValue, V newValue) {
        checkNotNull(key);
        checkNotNull(newValue);
        if (oldValue == null) {
            return false;
        }
        int hash = hash(key);
        return segmentFor(hash).replace(key, hash, oldValue, newValue);
    }

    @Override
    public V replace(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        int hash = hash(key);
        return segmentFor(hash).replace(key, hash, value);
    }

    @Override
    public void clear() {
        for (Segment<K, V> segment : segments) {
            segment.clear();
        }
    }

    void invalidateAll(Iterable<?> keys) {
        // TODO(fry): batch by segment
        for (Object key : keys) {
            remove(key);
        }
    }

    Set<K> keySet;

    @Override
    public Set<K> keySet() {
        // does not impact recency ordering
        Set<K> ks = keySet;
        return (ks != null) ? ks : (keySet = new KeySet(this));
    }

    Collection<V> values;

    @Override
    public Collection<V> values() {
        // does not impact recency ordering
        Collection<V> vs = values;
        return (vs != null) ? vs : (values = new Values(this));
    }

    Set<Entry<K, V>> entrySet;

    @Override
    @GwtIncompatible("Not supported.")
    public Set<Entry<K, V>> entrySet() {
        // does not impact recency ordering
        Set<Entry<K, V>> es = entrySet;
        return (es != null) ? es : (entrySet = new EntrySet(this));
    }

    // Iterator Support

    abstract class HashIterator<T> implements Iterator<T> {

        int nextSegmentIndex;
        int nextTableIndex;
        Segment<K, V> currentSegment;
        AtomicReferenceArray<ReferenceEntry<K, V>> currentTable;
        ReferenceEntry<K, V> nextEntry;
        WriteThroughEntry nextExternal;
        WriteThroughEntry lastReturned;

        HashIterator() {
            nextSegmentIndex = segments.length - 1;
            nextTableIndex = -1;
            advance();
        }

        @Override
        public abstract T next();

        final void advance() {
            nextExternal = null;

            if (nextInChain()) {
                return;
            }

            if (nextInTable()) {
                return;
            }

            while (nextSegmentIndex >= 0) {
                currentSegment = segments[nextSegmentIndex--];
                if (currentSegment.count != 0) {
                    currentTable = currentSegment.table;
                    nextTableIndex = currentTable.length() - 1;
                    if (nextInTable()) {
                        return;
                    }
                }
            }
        }


        boolean nextInChain() {
            if (nextEntry != null) {
                for (nextEntry = nextEntry.getNext(); nextEntry != null; nextEntry = nextEntry.getNext()) {
                    if (advanceTo(nextEntry)) {
                        return true;
                    }
                }
            }
            return false;
        }


        boolean nextInTable() {
            while (nextTableIndex >= 0) {
                if ((nextEntry = currentTable.get(nextTableIndex--)) != null) {
                    if (advanceTo(nextEntry) || nextInChain()) {
                        return true;
                    }
                }
            }
            return false;
        }


        boolean advanceTo(ReferenceEntry<K, V> entry) {
            try {
                long now = ticker.read();
                K key = entry.getKey();
                V value = getLiveValue(entry, now);
                if (value != null) {
                    nextExternal = new WriteThroughEntry(key, value);
                    return true;
                } else {
                    // Skip stale entry.
                    return false;
                }
            } finally {
                currentSegment.postReadCleanup();
            }
        }

        @Override
        public boolean hasNext() {
            return nextExternal != null;
        }

        WriteThroughEntry nextEntry() {
            if (nextExternal == null) {
                throw new NoSuchElementException();
            }
            lastReturned = nextExternal;
            advance();
            return lastReturned;
        }

        @Override
        public void remove() {
            checkState(lastReturned != null);
            LocalCache.this.remove(lastReturned.getKey());
            lastReturned = null;
        }
    }

    final class KeyIterator extends HashIterator<K> {

        @Override
        public K next() {
            return nextEntry().getKey();
        }
    }

    final class ValueIterator extends HashIterator<V> {

        @Override
        public V next() {
            return nextEntry().getValue();
        }
    }


    final class WriteThroughEntry implements Entry<K, V> {
        final K key; // non-null
        V value; // non-null

        WriteThroughEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public boolean equals(@Nullable Object object) {
            // Cannot use key and value equivalence
            if (object instanceof Entry) {
                Entry<?, ?> that = (Entry<?, ?>) object;
                return key.equals(that.getKey()) && value.equals(that.getValue());
            }
            return false;
        }

        @Override
        public int hashCode() {
            // Cannot use key and value equivalence
            return key.hashCode() ^ value.hashCode();
        }

        @Override
        public V setValue(V newValue) {
            throw new UnsupportedOperationException();
        }


        @Override
        public String toString() {
            return getKey() + "=" + getValue();
        }
    }

    final class EntryIterator extends HashIterator<Entry<K, V>> {

        @Override
        public Entry<K, V> next() {
            return nextEntry();
        }
    }

    abstract class AbstractCacheSet<T> extends AbstractSet<T> {
        final ConcurrentMap<?, ?> map;

        AbstractCacheSet(ConcurrentMap<?, ?> map) {
            this.map = map;
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public void clear() {
            map.clear();
        }
    }

    final class KeySet extends AbstractCacheSet<K> {

        KeySet(ConcurrentMap<?, ?> map) {
            super(map);
        }

        @Override
        public Iterator<K> iterator() {
            return new KeyIterator();
        }

        @Override
        public boolean contains(Object o) {
            return map.containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            return map.remove(o) != null;
        }
    }

    final class Values extends AbstractCollection<V> {
        private final ConcurrentMap<?, ?> map;

        Values(ConcurrentMap<?, ?> map) {
            this.map = map;
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(Object o) {
            return map.containsValue(o);
        }
    }

    final class EntrySet extends AbstractCacheSet<Entry<K, V>> {

        EntrySet(ConcurrentMap<?, ?> map) {
            super(map);
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry<?, ?> e = (Entry<?, ?>) o;
            Object key = e.getKey();
            if (key == null) {
                return false;
            }
            V v = LocalCache.this.get(key);

            return v != null && valueEquivalence.equivalent(e.getValue(), v);
        }

        @Override
        public boolean remove(Object o) {
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry<?, ?> e = (Entry<?, ?>) o;
            Object key = e.getKey();
            return key != null && LocalCache.this.remove(key, e.getValue());
        }
    }

    // Serialization Support


    static class ManualSerializationProxy<K, V>
            extends ForwardingCache<K, V> implements Serializable {
        private static final long serialVersionUID = 1;

        final Strength keyStrength;
        final Strength valueStrength;
        final Equivalence<Object> keyEquivalence;
        final Equivalence<Object> valueEquivalence;
        final long expireAfterWriteNanos;
        final long expireAfterAccessNanos;
        final long maxWeight;
        final Weigher<K, V> weigher;
        final int concurrencyLevel;
        final RemovalListener<? super K, ? super V> removalListener;
        final Ticker ticker;
        final CacheLoader<? super K, V> loader;

        transient Cache<K, V> delegate;

        ManualSerializationProxy(LocalCache<K, V> cache) {
            this(
                    cache.keyStrength,
                    cache.valueStrength,
                    cache.keyEquivalence,
                    cache.valueEquivalence,
                    cache.expireAfterWriteNanos,
                    cache.expireAfterAccessNanos,
                    cache.maxWeight,
                    cache.weigher,
                    cache.concurrencyLevel,
                    cache.removalListener,
                    cache.ticker,
                    cache.defaultLoader);
        }

        private ManualSerializationProxy(
                Strength keyStrength, Strength valueStrength,
                Equivalence<Object> keyEquivalence, Equivalence<Object> valueEquivalence,
                long expireAfterWriteNanos, long expireAfterAccessNanos, long maxWeight,
                Weigher<K, V> weigher, int concurrencyLevel,
                RemovalListener<? super K, ? super V> removalListener,
                Ticker ticker, CacheLoader<? super K, V> loader) {
            this.keyStrength = keyStrength;
            this.valueStrength = valueStrength;
            this.keyEquivalence = keyEquivalence;
            this.valueEquivalence = valueEquivalence;
            this.expireAfterWriteNanos = expireAfterWriteNanos;
            this.expireAfterAccessNanos = expireAfterAccessNanos;
            this.maxWeight = maxWeight;
            this.weigher = weigher;
            this.concurrencyLevel = concurrencyLevel;
            this.removalListener = removalListener;
            this.ticker = (ticker == Ticker.systemTicker() || ticker == NULL_TICKER)
                    ? null : ticker;
            this.loader = loader;
        }

        CacheBuilder<K, V> recreateCacheBuilder() {
            CacheBuilder<K, V> builder = CacheBuilder.newBuilder()
                    .setKeyStrength(keyStrength)
                    .setValueStrength(valueStrength)
                    .keyEquivalence(keyEquivalence)
                    .valueEquivalence(valueEquivalence)
                    .concurrencyLevel(concurrencyLevel)
                    .removalListener(removalListener);
            builder.strictParsing = false;
            if (expireAfterWriteNanos > 0) {
                builder.expireAfterWrite(expireAfterWriteNanos, TimeUnit.NANOSECONDS);
            }
            if (expireAfterAccessNanos > 0) {
                builder.expireAfterAccess(expireAfterAccessNanos, TimeUnit.NANOSECONDS);
            }
            if (weigher != OneWeigher.INSTANCE) {
                builder.weigher(weigher);
                if (maxWeight != UNSET_INT) {
                    builder.maximumWeight(maxWeight);
                }
            } else {
                if (maxWeight != UNSET_INT) {
                    builder.maximumSize(maxWeight);
                }
            }
            if (ticker != null) {
                builder.ticker(ticker);
            }
            return builder;
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            CacheBuilder<K, V> builder = recreateCacheBuilder();
            this.delegate = builder.build();
        }

        private Object readResolve() {
            return delegate;
        }

        @Override
        protected Cache<K, V> delegate() {
            return delegate;
        }
    }


    static final class LoadingSerializationProxy<K, V>
            extends ManualSerializationProxy<K, V> implements LoadingCache<K, V>, Serializable {
        private static final long serialVersionUID = 1;

        transient LoadingCache<K, V> autoDelegate;

        LoadingSerializationProxy(LocalCache<K, V> cache) {
            super(cache);
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            CacheBuilder<K, V> builder = recreateCacheBuilder();
            this.autoDelegate = builder.build(loader);
        }

        @Override
        public V get(K key) throws ExecutionException {
            return autoDelegate.get(key);
        }

        @Override
        public V getUnchecked(K key) {
            return autoDelegate.getUnchecked(key);
        }

        @Override
        public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
            return autoDelegate.getAll(keys);
        }

        @Override
        public final V apply(K key) {
            return autoDelegate.apply(key);
        }

        @Override
        public void refresh(K key) {
            autoDelegate.refresh(key);
        }

        private Object readResolve() {
            return autoDelegate;
        }
    }

    static class LocalManualCache<K, V> implements Cache<K, V>, Serializable {
        final LocalCache<K, V> localCache;

        LocalManualCache(CacheBuilder<? super K, ? super V> builder) {
            this(new LocalCache<>(builder, null));
        }

        private LocalManualCache(LocalCache<K, V> localCache) {
            this.localCache = localCache;
        }

        // Cache methods

        @Override
        @Nullable
        public V getIfPresent(Object key) {
            return localCache.getIfPresent(key);
        }

        @Override
        public V get(K key, final Callable<? extends V> valueLoader) throws ExecutionException {
            checkNotNull(valueLoader);
            return localCache.get(key, new CacheLoader<Object, V>() {
                @Override
                public V load(Object key) throws Exception {
                    return valueLoader.call();
                }
            });
        }

        @Override
        public ImmutableMap<K, V> getAllPresent(Iterable<?> keys) {
            return localCache.getAllPresent(keys);
        }

        @Override
        public void put(K key, V value) {
            localCache.put(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> m) {
            localCache.putAll(m);
        }

        @Override
        public void invalidate(Object key) {
            checkNotNull(key);
            localCache.remove(key);
        }

        @Override
        public void invalidateAll(Iterable<?> keys) {
            localCache.invalidateAll(keys);
        }

        @Override
        public void invalidateAll() {
            localCache.clear();
        }

        @Override
        public long size() {
            return localCache.longSize();
        }

        @Override
        public ConcurrentMap<K, V> asMap() {
            return localCache;
        }

        @Override
        public CacheStats stats() {
            SimpleStatsCounter aggregator = new SimpleStatsCounter();
            aggregator.incrementBy(localCache.globalStatsCounter);
            for (Segment<K, V> segment : localCache.segments) {
                aggregator.incrementBy(segment.statsCounter);
            }
            return aggregator.snapshot();
        }

        @Override
        public void cleanUp() {
            localCache.cleanUp();
        }

        // Serialization Support

        private static final long serialVersionUID = 1;

        Object writeReplace() {
            return new ManualSerializationProxy<>(localCache);
        }
    }

    static class LocalLoadingCache<K, V>
            extends LocalManualCache<K, V> implements LoadingCache<K, V> {

        LocalLoadingCache(CacheBuilder<? super K, ? super V> builder,
                          CacheLoader<? super K, V> loader) {
            super(new LocalCache<>(builder, checkNotNull(loader)));
        }

        // LoadingCache methods

        @Override
        public V get(K key) throws ExecutionException {
            return localCache.getOrLoad(key);
        }

        @Override
        public V getUnchecked(K key) {
            try {
                return get(key);
            } catch (ExecutionException e) {
                throw new UncheckedExecutionException(e.getCause());
            }
        }

        @Override
        public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
            return localCache.getAll(keys);
        }

        @Override
        public void refresh(K key) {
            localCache.refresh(key);
        }

        @Override
        public final V apply(K key) {
            return getUnchecked(key);
        }

        // Serialization Support

        private static final long serialVersionUID = 1;

        @Override
        Object writeReplace() {
            return new LoadingSerializationProxy<>(localCache);
        }
    }
}
