package org.hibernate.ogm.dialect.hotrod.atomic;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.atomic.AtomicHashMapDelta;
import org.infinispan.atomic.AtomicHashMapProxy;
import org.infinispan.atomic.AtomicMap;
import org.infinispan.atomic.AtomicMapLookup;
import org.infinispan.atomic.Delta;
import org.infinispan.atomic.DeltaAware;
import org.infinispan.atomic.FineGrainedAtomicHashMapProxy;
import org.infinispan.atomic.NullDelta;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.impl.operations.ClearOperation;
import org.infinispan.client.hotrod.impl.operations.PutOperation;
import org.infinispan.client.hotrod.impl.operations.RemoveOperation;
import org.infinispan.commons.marshall.AbstractExternalizer;
import org.infinispan.commons.util.FastCopyHashMap;
import org.infinispan.commons.util.Util;
import org.infinispan.marshall.core.Ids;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * The default implementation of {@link AtomicMap}. Note that this map cannot be constructed directly, and callers
 * should obtain references to AtomicHashMaps via the {@link AtomicMapLookup} helper. This helper will ensure proper
 * concurrent construction and registration of AtomicMaps in Infinispan's data container. E.g.: <br />
 * <br />
 * <code>
 *    AtomicMap&lt;String, Integer&gt; map = AtomicMapLookup.getAtomicMap(cache, "my_atomic_map_key");
 * </code> <br />
 * <br />
 * Note that for replication to work properly, AtomicHashMap updates <b><i>must always</i></b> take place within the
 * scope of an ongoing JTA transaction or batch (see {@link Cache#startBatch()}).
 * <p/>
 * 
 * @author (various)
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 * @see AtomicMap
 * @see AtomicMapLookup
 * @since 4.0
 */
public final class HotRodAtomicHashMap<K, V> implements AtomicMap<K, V>, DeltaAware, Cloneable {

	private static final Log log = LogFactory.getLog( HotRodAtomicHashMap.class );
	private static final boolean trace = log.isTraceEnabled();

	protected final FastCopyHashMap<K, V> delegate;
	private AtomicHashMapDelta delta = null;
	private volatile AtomicHashMapProxy<K, V> proxy;
	volatile boolean copied = false;
	volatile boolean removed = false;

	/**
	 * Construction only allowed through this factory method. This factory is intended for use internally by the
	 * CacheDelegate. User code should use {@link AtomicMapLookup#getAtomicMap(Cache, Object)}.
	 */
	public static <K, V> HotRodAtomicHashMap<K, V> newInstance(RemoteCache<Object, Object> cache, Object cacheKey) {
		HotRodAtomicHashMap<K, V> value = new HotRodAtomicHashMap<K, V>();
		Object oldValue = cache.putIfAbsent( cacheKey, value );
		if ( oldValue != null ) {
			value = (HotRodAtomicHashMap<K, V>) oldValue;
		}
		return value;
	}

	public HotRodAtomicHashMap() {
		this.delegate = new FastCopyHashMap<K, V>();
	}

	private HotRodAtomicHashMap(FastCopyHashMap<K, V> delegate) {
		this.delegate = delegate;
	}

	public HotRodAtomicHashMap(boolean isCopy) {
		this();
		this.copied = isCopy;
	}

	private HotRodAtomicHashMap(FastCopyHashMap<K, V> newDelegate, AtomicHashMapProxy<K, V> proxy) {
		this.delegate = newDelegate;
		this.proxy = proxy;
		this.copied = true;
	}

	@Override
	public void commit() {
		copied = false;
		delta = null;
	}

	@Override
	public int size() {
		return delegate.size();
	}

	@Override
	public boolean isEmpty() {
		return delegate.isEmpty();
	}

	@Override
	public boolean containsKey(Object key) {
		return delegate.containsKey( key );
	}

	@Override
	public boolean containsValue(Object value) {
		return delegate.containsValue( value );
	}

	@Override
	public V get(Object key) {
		V v = delegate.get( key );
		if ( trace )
			log.tracef( "Atomic hash map get(key=%s) returns %s", key, v );

		return v;
	}

	@Override
	public Set<K> keySet() {
		return delegate.keySet();
	}

	@Override
	public Collection<V> values() {
		return delegate.values();
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		return delegate.entrySet();
	}

	@Override
	public V put(K key, V value) {
		V oldValue = delegate.put( key, value );
		PutOperation op = new PutOperation( key, oldValue, value );
		getDelta().addOperation( op );
		return oldValue;
	}

	@Override
	@SuppressWarnings("unchecked")
	public V remove(Object key) {
		V oldValue = delegate.remove( key );
		RemoveOperation op = new RemoveOperation<K, V>( (K) key, oldValue );
		getDelta().addOperation( op );
		return oldValue;
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> t) {
		// this is crappy - need to do this more efficiently!
		for ( Entry<? extends K, ? extends V> e : t.entrySet() )
			put( e.getKey(), e.getValue() );
	}

	@Override
	@SuppressWarnings("unchecked")
	public void clear() {
		FastCopyHashMap<K, V> originalEntries = delegate.clone();
		ClearOperation<K, V> op = new ClearOperation<K, V>( originalEntries );
		getDelta().addOperation( op );
		delegate.clear();
	}

	/**
	 * Builds a thread-safe proxy for this instance so that concurrent reads are isolated from writes.
	 * 
	 * @return an instance of AtomicHashMapProxy
	 */
	AtomicHashMapProxy<K, V> getProxy(AdvancedCache<Object, Object> cache, Object mapKey, boolean fineGrained) {
		// construct the proxy lazily
		if ( proxy == null ) // DCL is OK here since proxy is volatile (and we live in a post-JDK 5 world)
		{
			synchronized ( this ) {
				if ( proxy == null )
					if ( fineGrained ) {
						proxy = new FineGrainedAtomicHashMapProxy<K, V>( cache, mapKey );
					}
					else {
						proxy = new AtomicHashMapProxy<K, V>( cache, mapKey );
					}
			}
		}
		return proxy;
	}

	public void markRemoved(boolean b) {
		removed = b;
	}

	@Override
	public Delta delta() {
		Delta toReturn = delta == null ? NullDelta.INSTANCE : delta;
		delta = null; // reset
		return toReturn;
	}

	@SuppressWarnings("unchecked")
	public HotRodAtomicHashMap<K, V> copy() {
		FastCopyHashMap<K, V> newDelegate = delegate.clone();
		return new HotRodAtomicHashMap( newDelegate, proxy );
	}

	@Override
	public String toString() {
		// Sanne: Avoid iterating on the delegate as that might lead to
		// exceptions from concurrent iterators: not nice to have during a toString!
		//
		// Galder: Sure, but we need a way to track the contents of the atomic
		// hash map somehow, so, we need to log each operation that affects its
		// contents, and when its state is restored.
		return "AtomicHashMap";
	}

	/**
	 * Initializes the delta instance to start recording changes.
	 */
	public void initForWriting() {
		delta = new AtomicHashMapDelta();
	}

	AtomicHashMapDelta getDelta() {
		if ( delta == null )
			delta = new AtomicHashMapDelta();
		return delta;
	}

	public static class Externalizer extends AbstractExternalizer<HotRodAtomicHashMap> {

		@Override
		public void writeObject(ObjectOutput output, HotRodAtomicHashMap map) throws IOException {
			output.writeObject( map.delegate );
		}

		@Override
		@SuppressWarnings("unchecked")
		public HotRodAtomicHashMap readObject(ObjectInput input) throws IOException, ClassNotFoundException {
			FastCopyHashMap<?, ?> delegate = (FastCopyHashMap<?, ?>) input.readObject();
			if ( trace )
				log.tracef( "Restore atomic hash map from %s", delegate );

			return new HotRodAtomicHashMap( delegate );
		}

		@Override
		public Integer getId() {
			return Ids.ATOMIC_HASH_MAP;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Set<Class<? extends HotRodAtomicHashMap>> getTypeClasses() {
			return Util.<Class<? extends HotRodAtomicHashMap>> asSet( HotRodAtomicHashMap.class );
		}
	}
}
