/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.store.remote.filecache;

import org.apache.lucene.store.IndexInput;
import org.junit.Before;
import org.opensearch.index.store.remote.utils.cache.CacheUsage;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileCacheTests extends OpenSearchTestCase {
    // need concurrency level to be static to make these tests more deterministic because capacity per segment is dependent on
    // (total capacity) / (concurrency level) so having high concurrency level might trigger early evictions which is tolerable in real life
    // but fatal to these tests
    private final static int CONCURRENCY_LEVEL = 16;
    private final static int MEGA_BYTES = 1024 * 1024;
    private final static int GIGA_BYTES = 1024 * 1024 * 1024;
    private final static String FAKE_PATH_SUFFIX = "Suffix";
    private Path path;

    @Before
    public void init() throws Exception {
        path = createTempDir("FileCacheTests");
    }

    private FileCache createFileCache(long capaticy) {
        return FileCacheFactory.createConcurrentLRUFileCache(capaticy, CONCURRENCY_LEVEL);
    }

    private Path createPath(String middle) {
        return path.resolve(middle).resolve(FAKE_PATH_SUFFIX);
    }

    public void testCreateCacheWithSmallSegments() {
        assertThrows(IllegalStateException.class, () -> { FileCacheFactory.createConcurrentLRUFileCache(1000, CONCURRENCY_LEVEL); });
    }

    // test get method
    public void testGet() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        for (int i = 0; i < 4; i++) {
            fileCache.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
        }
        // verify all blocks are put into file cache
        for (int i = 0; i < 4; i++) {
            assertNotNull(fileCache.get(createPath(Integer.toString(i))));
        }
    }

    public void testGetThrowException() {
        assertThrows(NullPointerException.class, () -> {
            FileCache fileCache = createFileCache(GIGA_BYTES);
            fileCache.get(null);
        });
    }

    public void testPutThrowException() {
        assertThrows(NullPointerException.class, () -> {
            FileCache fileCache = createFileCache(GIGA_BYTES);
            fileCache.put(null, null);
        });
    }

    public void testComputeIfPresent() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        Path path = createPath("0");
        fileCache.put(path, new FakeIndexInput(8 * MEGA_BYTES));
        fileCache.incRef(path);
        fileCache.computeIfPresent(path, (p, i) -> null);
        // item will be removed
        assertEquals(fileCache.size(), 0);
    }

    public void testComputeIfPresentThrowException() {
        assertThrows(NullPointerException.class, () -> {
            FileCache fileCache = createFileCache(GIGA_BYTES);
            fileCache.computeIfPresent(null, null);
        });
    }

    public void testPutAll() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        Map<Path, CachedIndexInput> blockMaps = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            blockMaps.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
        }
        fileCache.putAll(blockMaps);
        // verify all blocks are put into file cache
        for (int i = 0; i < 4; i++) {
            assertNotNull(fileCache.get(createPath(Integer.toString(i))));
        }
    }

    public void testRemove() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        for (int i = 0; i < 4; i++) {
            fileCache.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
        }

        fileCache.remove(createPath("0"));
        fileCache.remove(createPath("0"));
        fileCache.remove(createPath("1"));
        assertEquals(fileCache.size(), 2);
        assertNotNull(fileCache.get(createPath("2")));
        assertNotNull(fileCache.get(createPath("3")));
    }

    public void testRemoveThrowException() {
        assertThrows(NullPointerException.class, () -> {
            FileCache fileCache = createFileCache(GIGA_BYTES);
            fileCache.remove(null);
        });
    }

    public void testRemoveAll() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        List<Path> blockPathList = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Path blockPath = createPath(Integer.toString(i));
            fileCache.put(blockPath, new FakeIndexInput(8 * MEGA_BYTES));
            blockPathList.add(blockPath);
        }
        fileCache.removeAll(blockPathList);
        assertEquals(fileCache.size(), 0);
    }

    public void testIncDecRef() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        for (int i = 0; i < 4; i++) {
            fileCache.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
            fileCache.incRef(createPath(Integer.toString(i)));
        }

        // try to evict previous IndexInput
        for (int i = 1000; i < 3000; i++) {
            fileCache.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
        }

        // IndexInput with refcount greater than 0 will not be evicted
        for (int i = 0; i < 4; i++) {
            assertNotNull(fileCache.get(createPath(Integer.toString(i))));
        }

        // decrease ref
        for (int i = 0; i < 4; i++) {
            fileCache.decRef(createPath(Integer.toString(i)));
        }

        // try to evict previous IndexInput again
        for (int i = 3000; i < 5000; i++) {
            fileCache.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
        }

        for (int i = 0; i < 4; i++) {
            assertNull(fileCache.get(createPath(Integer.toString(i))));
        }
    }

    public void testIncRefThrowException() {
        assertThrows(NullPointerException.class, () -> {
            FileCache fileCache = createFileCache(GIGA_BYTES);
            fileCache.incRef(null);
        });

    }

    public void testDecRefThrowException() {
        assertThrows(NullPointerException.class, () -> {
            FileCache fileCache = createFileCache(GIGA_BYTES);
            fileCache.decRef(null);
        });

    }

    public void testCapacity() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        assertEquals(fileCache.capacity(), GIGA_BYTES);
    }

    public void testSize() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        for (int i = 0; i < 4; i++) {
            fileCache.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
        }
        // test file cache size
        assertEquals(fileCache.size(), 4);
    }

    public void testPrune() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        for (int i = 0; i < 4; i++) {
            fileCache.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
        }
        // before prune
        assertEquals(fileCache.size(), 4);

        fileCache.prune();
        // after prune
        assertEquals(fileCache.size(), 0);
    }

    public void testUsage() {
        // edge case, all Indexinput will be evicted as soon as they are put into file cache
        FileCache fileCache = FileCacheFactory.createConcurrentLRUFileCache(16 * MEGA_BYTES, 1);
        fileCache.put(createPath("0"), new FakeIndexInput(16 * MEGA_BYTES));

        CacheUsage expectedCacheUsage = new CacheUsage(0, 0);
        CacheUsage realCacheUsage = fileCache.usage();
        assertEquals(expectedCacheUsage.activeUsage(), realCacheUsage.activeUsage());
        assertEquals(expectedCacheUsage.usage(), realCacheUsage.usage());
    }

    public void testStats() {
        FileCache fileCache = createFileCache(GIGA_BYTES);
        for (int i = 0; i < 4; i++) {
            fileCache.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
        }
        // cache hits
        fileCache.get(createPath("0"));
        fileCache.get(createPath("1"));
        // cache misses
        fileCache.get(createPath("-1"));
        fileCache.get(createPath("-2"));

        assertEquals(fileCache.stats().hitCount(), 2);

        // do some eviction here
        for (int i = 0; i < 2000; i++) {
            fileCache.put(createPath(Integer.toString(i)), new FakeIndexInput(8 * MEGA_BYTES));
        }
        assertTrue(fileCache.stats().evictionCount() > 0);
        assertTrue(fileCache.stats().evictionWeight() > 0);

    }

    final class FakeIndexInput extends CachedIndexInput {

        private final long length;

        public FakeIndexInput(long length) {
            super("dummy");
            this.length = length;
        }

        @Override
        public void close() throws IOException {
            // no-op
        }

        @Override
        public long getFilePointer() {
            throw new UnsupportedOperationException("DummyIndexInput doesn't support getFilePointer().");
        }

        @Override
        public void seek(long pos) throws IOException {
            throw new UnsupportedOperationException("DummyIndexInput doesn't support seek().");
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
            throw new UnsupportedOperationException("DummyIndexInput couldn't be sliced.");
        }

        @Override
        public byte readByte() throws IOException {
            throw new UnsupportedOperationException("DummyIndexInput doesn't support read.");
        }

        @Override
        public void readBytes(byte[] b, int offset, int len) throws IOException {
            throw new UnsupportedOperationException("DummyIndexInput doesn't support read.");
        }

        @Override
        public IndexInput clone() {
            throw new UnsupportedOperationException("DummyIndexInput couldn't be cloned.");
        }

        @Override
        public boolean isClosed() {
            return true;
        }
    }
}
