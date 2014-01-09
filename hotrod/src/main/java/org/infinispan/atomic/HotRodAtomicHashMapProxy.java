package org.infinispan.atomic;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.infinispan.batch.AutoBatchSupport;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.commons.util.InfinispanCollections;
import org.infinispan.context.Flag;
import org.infinispan.marshall.core.MarshalledValue;

/**
 * A layer of indirection around an {@link HotRodAtomicHashMap} to provide consistency and isolation for concurrent
 * readers while writes may also be going on. The techniques used in this implementation are very similar to the
 * lock-free reader MVCC model used in the {@link org.infinispan.container.entries.MVCCEntry} implementations for the
 * core data container, which closely follow software transactional memory approaches to dealing with concurrency. <br />
 * <br />
 * Implementations of this class are rarely created on their own;
 * {@link HotRodAtomicHashMap#getProxy(org.infinispan.AdvancedCache, Object, boolean)} should be used to retrieve an
 * instance of this proxy. <br />
 * <br />
 * Typically proxies are only created by the {@link AtomicMapLookup} helper, and would not be created by end-user code
 * directly.
 *
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 * @author Manik Surtani
 * @see HotRodAtomicHashMap
 * @since 4.0
 */
public class HotRodAtomicHashMapProxy<K, V> extends AutoBatchSupport implements AtomicMap<K, V> {

	protected final Object deltaMapKey;
	protected final RemoteCache<Object, AtomicMap<K, V>> cache;
	protected final RemoteCache<Object, AtomicMap<K, V>> cacheForWriting;
	protected volatile boolean startedReadingMap = false;

	HotRodAtomicHashMapProxy(RemoteCache<Object, AtomicMap<K, V>> remoteCache, Object deltaMapKey) {
		Configuration configuration = remoteCache.getRemoteCacheManager().getConfiguration();
		this.cache = remoteCache;
		Flag[] writeFlags = new Flag[] { Flag.DELTA_WRITE };
		this.cacheForWriting = this.cache;
		this.deltaMapKey = deltaMapKey;
	}

	// internal helper, reduces lots of casts.
	@SuppressWarnings("unchecked")
	protected HotRodAtomicHashMap<K, V> toMap(Object object) {
		Object map = ( object instanceof MarshalledValue ) ? ( (MarshalledValue) object ).get() : object;
		return (HotRodAtomicHashMap<K, V>) map;
	}

	protected HotRodAtomicHashMap<K, V> getDeltaMapForRead() {
		HotRodAtomicHashMap<K, V> ahm = toMap( cache.get( deltaMapKey ) );
		if ( ahm != null && !startedReadingMap )
			startedReadingMap = true;
		assertValid( ahm );
		return ahm;
	}

	@SuppressWarnings("unchecked")
	protected HotRodAtomicHashMap<K, V> getDeltaMapForWrite() {
		RemoteCache<Object, AtomicMap<K, V>> cacheForRead = cache;
//			if ( cache.getCacheConfiguration().transaction().lockingMode() == LockingMode.PESSIMISTIC ) {
//				cacheForRead = cache.withFlags( Flag.FORCE_WRITE_LOCK );
//			}
		// acquire WL
		HotRodAtomicHashMap<K, V> map = toMap( cacheForRead.get( deltaMapKey ) );
		if ( map != null && !startedReadingMap ) {
			startedReadingMap = true;
		}
		assertValid( map );

		// copy for write
		HotRodAtomicHashMap<K, V> copy = map == null ? new HotRodAtomicHashMap<K, V>( true ) : map.copy();
		copy.initForWriting();
		cacheForWriting.put( deltaMapKey, copy );
		return copy;
	}

	// readers

	protected void assertValid(HotRodAtomicHashMap<?, ?> map) {
		if ( startedReadingMap && ( map == null || map.removed ) )
			throw new IllegalStateException( "AtomicMap stored under key " + deltaMapKey + " has been concurrently removed!" );
	}

	@Override
	public Set<K> keySet() {
		HotRodAtomicHashMap<K, V> map = getDeltaMapForRead();
		return map == null ? InfinispanCollections.<K> emptySet() : map.keySet();
	}

	@Override
	public Collection<V> values() {
		HotRodAtomicHashMap<K, V> map = getDeltaMapForRead();
		return map == null ? InfinispanCollections.<V> emptySet() : map.values();
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		HotRodAtomicHashMap<K, V> map = getDeltaMapForRead();
		return map == null ? InfinispanCollections.<Entry<K, V>> emptySet() : map.entrySet();
	}

	@Override
	public int size() {
		HotRodAtomicHashMap<K, V> map = getDeltaMapForRead();
		return map == null ? 0 : map.size();
	}

	@Override
	public boolean isEmpty() {
		HotRodAtomicHashMap<K, V> map = getDeltaMapForRead();
		return map == null || map.isEmpty();
	}

	@Override
	public boolean containsKey(Object key) {
		HotRodAtomicHashMap<K, V> map = getDeltaMapForRead();
		return map != null && map.containsKey( key );
	}

	@Override
	public boolean containsValue(Object value) {
		HotRodAtomicHashMap<K, V> map = getDeltaMapForRead();
		return map != null && map.containsValue( value );
	}

	@Override
	public V get(Object key) {
		HotRodAtomicHashMap<K, V> map = getDeltaMapForRead();
		return map == null ? null : map.get( key );
	}

	// writers
	@Override
	public V put(K key, V value) {
		HotRodAtomicHashMap<K, V> deltaMapForWrite;
		try {
			startAtomic();
			deltaMapForWrite = getDeltaMapForWrite();
			return deltaMapForWrite.put( key, value );
		}
		finally {
			endAtomic();
		}
	}

	@Override
	public V remove(Object key) {
		HotRodAtomicHashMap<K, V> deltaMapForWrite;
		try {
			startAtomic();
			deltaMapForWrite = getDeltaMapForWrite();
			return deltaMapForWrite.remove( key );
		}
		finally {
			endAtomic();
		}
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		HotRodAtomicHashMap<K, V> deltaMapForWrite;
		try {
			startAtomic();
			deltaMapForWrite = getDeltaMapForWrite();
			deltaMapForWrite.putAll( m );
		}
		finally {
			endAtomic();
		}
	}

	@Override
	public void clear() {
		HotRodAtomicHashMap<K, V> deltaMapForWrite;
		try {
			startAtomic();
			deltaMapForWrite = getDeltaMapForWrite();
			deltaMapForWrite.clear();
		}
		finally {
			endAtomic();
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder( "AtomicHashMapProxy{deltaMapKey=" );
		sb.append( deltaMapKey );
		sb.append( "}" );
		return sb.toString();
	}
}
