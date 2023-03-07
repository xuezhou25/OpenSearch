/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.snapshots;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import org.hamcrest.MatcherAssert;
import org.opensearch.action.admin.cluster.node.stats.NodeStats;
import org.opensearch.action.admin.cluster.node.stats.NodesStatsRequest;
import org.opensearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.opensearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.opensearch.action.admin.cluster.snapshots.delete.DeleteSnapshotRequest;
import org.opensearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest;
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequestBuilder;
import org.opensearch.action.index.IndexRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.master.AcknowledgedResponse;
import org.opensearch.client.Client;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.block.ClusterBlockException;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.GroupShardsIterator;
import org.opensearch.cluster.routing.ShardIterator;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.collect.Map;
import org.opensearch.common.io.PathUtils;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.ByteSizeUnit;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.Index;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.store.remote.file.CleanerDaemonThreadLeakFilter;
import org.opensearch.index.store.remote.filecache.FileCacheStats;
import org.opensearch.monitor.fs.FsInfo;
import org.opensearch.repositories.fs.FsRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.opensearch.action.admin.cluster.node.stats.NodesStatsRequest.Metric.FS;
import static org.opensearch.common.util.CollectionUtils.iterableAsArrayList;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;

@ThreadLeakFilters(filters = CleanerDaemonThreadLeakFilter.class)
public final class SearchableSnapshotIT extends AbstractSnapshotIntegTestCase {

    @Override
    protected boolean addMockInternalEngine() {
        return false;
    }

    @Override
    protected Settings featureFlagSettings() {
        return Settings.builder().put(FeatureFlags.SEARCHABLE_SNAPSHOT, "true").build();
    }

    @Override
    protected Settings.Builder randomRepositorySettings() {
        final Settings.Builder settings = Settings.builder();
        settings.put("location", randomRepoPath()).put("compress", randomBoolean());
        return settings;
    }

    private Settings.Builder chunkedRepositorySettings() {
        final Settings.Builder settings = Settings.builder();
        settings.put("location", randomRepoPath()).put("compress", randomBoolean());
        settings.put("chunk_size", 2 << 23, ByteSizeUnit.BYTES);
        return settings;
    }

    /**
     * Tests a happy path scenario for searchable snapshots by creating 2 indices,
     * taking a snapshot, restoring them as searchable snapshots.
     * Ensures availability of sufficient data nodes and search capable nodes.
     */
    public void testCreateSearchableSnapshot() throws Exception {
        final String snapshotName = "test-snap";
        final String repoName = "test-repo";
        final String indexName1 = "test-idx-1";
        final String restoredIndexName1 = indexName1 + "-copy";
        final String indexName2 = "test-idx-2";
        final String restoredIndexName2 = indexName2 + "-copy";
        final int numReplicasIndex1 = randomIntBetween(1, 4);
        final int numReplicasIndex2 = randomIntBetween(0, 2);
        final Client client = client();

        internalCluster().ensureAtLeastNumDataNodes(Math.max(numReplicasIndex1, numReplicasIndex2) + 1);
        createIndexWithDocsAndEnsureGreen(numReplicasIndex1, 100, indexName1);
        createIndexWithDocsAndEnsureGreen(numReplicasIndex2, 100, indexName2);

        createRepositoryWithSettings(null, repoName);
        takeSnapshot(client, snapshotName, repoName, indexName1, indexName2);
        deleteIndicesAndEnsureGreen(client, indexName1, indexName2);

        internalCluster().ensureAtLeastNumSearchNodes(Math.max(numReplicasIndex1, numReplicasIndex2) + 1);
        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);

        assertDocCount(restoredIndexName1, 100L);
        assertDocCount(restoredIndexName2, 100L);
        assertIndexDirectoryDoesNotExist(restoredIndexName1, restoredIndexName2);
    }

    /**
     * Tests a chunked repository scenario for searchable snapshots by creating an index,
     * taking a snapshot, restoring it as a searchable snapshot index.
     */
    public void testCreateSearchableSnapshotWithChunks() throws Exception {
        final int numReplicasIndex = randomIntBetween(1, 4);
        final String indexName = "test-idx";
        final String restoredIndexName = indexName + "-copy";
        final String repoName = "test-repo";
        final String snapshotName = "test-snap";
        final Client client = client();

        Settings.Builder repositorySettings = chunkedRepositorySettings();

        internalCluster().ensureAtLeastNumSearchAndDataNodes(numReplicasIndex + 1);
        createIndexWithDocsAndEnsureGreen(numReplicasIndex, 1000, indexName);
        createRepositoryWithSettings(repositorySettings, repoName);
        takeSnapshot(client, snapshotName, repoName, indexName);

        deleteIndicesAndEnsureGreen(client, indexName);
        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);

        assertDocCount(restoredIndexName, 1000L);
    }

    /**
     * Tests the functionality of remote shard allocation to
     * ensure it can assign remote shards to a node with local shards given it has the
     * search role capabilities.
     */
    public void testSearchableSnapshotAllocationForLocalAndRemoteShardsOnSameNode() throws Exception {
        final int numReplicasIndex = randomIntBetween(1, 4);
        final String indexName = "test-idx";
        final String restoredIndexName = indexName + "-copy";
        final String repoName = "test-repo";
        final String snapshotName = "test-snap";
        final Client client = client();

        internalCluster().ensureAtLeastNumSearchAndDataNodes(numReplicasIndex + 1);
        createIndexWithDocsAndEnsureGreen(numReplicasIndex, 100, indexName);
        createRepositoryWithSettings(null, repoName);
        takeSnapshot(client, snapshotName, repoName, indexName);

        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);

        assertDocCount(restoredIndexName, 100L);
        assertDocCount(indexName, 100L);
    }

    /**
     * Tests the functionality of remote shard allocation to
     * ensure it can handle node drops for failover scenarios and the cluster gets back to a healthy state when
     * nodes with search capabilities are added back to the cluster.
     */
    public void testSearchableSnapshotAllocationForFailoverAndRecovery() throws Exception {
        final int numReplicasIndex = 1;
        final String indexName = "test-idx";
        final String restoredIndexName = indexName + "-copy";
        final String repoName = "test-repo";
        final String snapshotName = "test-snap";
        final Client client = client();

        internalCluster().ensureAtLeastNumDataNodes(numReplicasIndex + 1);
        createIndexWithDocsAndEnsureGreen(numReplicasIndex, 100, indexName);

        createRepositoryWithSettings(null, repoName);
        takeSnapshot(client, snapshotName, repoName, indexName);
        deleteIndicesAndEnsureGreen(client, indexName);

        internalCluster().ensureAtLeastNumSearchNodes(numReplicasIndex + 1);
        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);
        assertDocCount(restoredIndexName, 100L);

        logger.info("--> stop a random search node");
        internalCluster().stopRandomSearchNode();
        ensureYellow(restoredIndexName);
        assertDocCount(restoredIndexName, 100L);

        logger.info("--> stop the last search node");
        internalCluster().stopRandomSearchNode();
        ensureRed(restoredIndexName);

        logger.info("--> add 3 new search nodes");
        internalCluster().ensureAtLeastNumSearchNodes(numReplicasIndex + 2);
        ensureGreen(restoredIndexName);
        assertDocCount(restoredIndexName, 100);

        logger.info("--> stop a random search node");
        internalCluster().stopRandomSearchNode();
        ensureGreen(restoredIndexName);
        assertDocCount(restoredIndexName, 100);
    }

    /**
     * Tests the functionality of index write block on a searchable snapshot index.
     */
    public void testSearchableSnapshotIndexIsReadOnly() throws Exception {
        final String indexName = "test-index";
        final String restoredIndexName = indexName + "-copy";
        final String repoName = "test-repo";
        final String snapshotName = "test-snap";
        final Client client = client();

        createIndexWithDocsAndEnsureGreen(0, 100, indexName);
        createRepositoryWithSettings(null, repoName);
        takeSnapshot(client, snapshotName, repoName, indexName);
        deleteIndicesAndEnsureGreen(client, indexName);

        internalCluster().ensureAtLeastNumSearchNodes(1);
        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);

        assertIndexingBlocked(restoredIndexName);
        assertTrue(client.admin().indices().prepareDelete(restoredIndexName).get().isAcknowledged());
        assertThrows(
            "Expect index to not exist",
            IndexNotFoundException.class,
            () -> client.admin().indices().prepareGetIndex().setIndices(restoredIndexName).execute().actionGet()
        );
    }

    public void testDeleteSearchableSnapshotBackingIndexThrowsException() throws Exception {
        final String indexName = "test-index";
        final Client client = client();
        final String repoName = "test-repo";
        final String snapshotName = "test-snap";
        createRepositoryWithSettings(null, repoName);
        createIndexWithDocsAndEnsureGreen(0, 100, indexName);
        takeSnapshot(client, snapshotName, repoName, indexName);
        internalCluster().ensureAtLeastNumSearchNodes(1);
        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);
        assertThrows(
            SnapshotInUseDeletionException.class,
            () -> client().admin().cluster().deleteSnapshot(new DeleteSnapshotRequest(repoName, snapshotName)).actionGet()
        );
    }

    public void testDeleteSearchableSnapshotBackingIndex() throws Exception {
        final String indexName1 = "test-index1";
        final String indexName2 = "test-index2";
        final Client client = client();
        final String repoName = "test-repo";
        final String snapshotName1 = "test-snapshot1";
        final String snapshotName2 = "test-snap";
        createRepositoryWithSettings(null, repoName);
        createIndexWithDocsAndEnsureGreen(0, 100, indexName1);
        createIndexWithDocsAndEnsureGreen(0, 100, indexName2);
        takeSnapshot(client, snapshotName1, repoName, indexName1);
        takeSnapshot(client, snapshotName2, repoName, indexName2);
        internalCluster().ensureAtLeastNumSearchNodes(1);
        restoreSnapshotAndEnsureGreen(client, snapshotName2, repoName);
        client().admin().cluster().deleteSnapshot(new DeleteSnapshotRequest(repoName, snapshotName1)).actionGet();
    }

    private void createIndexWithDocsAndEnsureGreen(int numReplicasIndex, int numOfDocs, String indexName) throws InterruptedException {
        createIndex(
            indexName,
            Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, Integer.toString(numReplicasIndex))
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, "1")
                .build()
        );
        ensureGreen();

        indexRandomDocs(indexName, numOfDocs);
        ensureGreen();
    }

    private void takeSnapshot(Client client, String snapshotName, String repoName, String... indices) {
        logger.info("--> Take a snapshot");
        final CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(repoName, snapshotName)
            .setWaitForCompletion(true)
            .setIndices(indices)
            .get();

        MatcherAssert.assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        MatcherAssert.assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );
    }

    private void createRepositoryWithSettings(Settings.Builder repositorySettings, String repoName) {
        logger.info("--> Create a repository");
        if (repositorySettings == null) {
            createRepository(repoName, FsRepository.TYPE);
        } else {
            createRepository(repoName, FsRepository.TYPE, repositorySettings);
        }
    }

    private void deleteIndicesAndEnsureGreen(Client client, String... indices) {
        assertTrue(client.admin().indices().prepareDelete(indices).get().isAcknowledged());
        ensureGreen();
    }

    private void restoreSnapshotAndEnsureGreen(Client client, String snapshotName, String repoName) {
        logger.info("--> restore indices as 'remote_snapshot'");
        client.admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName)
            .setRenamePattern("(.+)")
            .setRenameReplacement("$1-copy")
            .setStorageType(RestoreSnapshotRequest.StorageType.REMOTE_SNAPSHOT)
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        ensureGreen();
    }

    private void assertIndexingBlocked(String index) {
        try {
            final IndexRequestBuilder builder = client().prepareIndex(index);
            builder.setSource("foo", "bar");
            builder.execute().actionGet();
            fail("Expected operation to throw an exception");
        } catch (ClusterBlockException e) {
            MatcherAssert.assertThat(e.blocks(), contains(IndexMetadata.REMOTE_READ_ONLY_ALLOW_DELETE));
        }
    }

    public void testUpdateIndexSettings() throws InterruptedException {
        final String indexName = "test-index";
        final String restoredIndexName = indexName + "-copy";
        final String repoName = "test-repo";
        final String snapshotName = "test-snap";
        final Client client = client();

        createIndexWithDocsAndEnsureGreen(0, 100, indexName);
        createRepositoryWithSettings(null, repoName);
        takeSnapshot(client, snapshotName, repoName, indexName);
        deleteIndicesAndEnsureGreen(client, indexName);

        internalCluster().ensureAtLeastNumSearchNodes(1);
        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);

        testUpdateIndexSettingsOnlyNotAllowedSettings(restoredIndexName);
        testUpdateIndexSettingsOnlyAllowedSettings(restoredIndexName);
        testUpdateIndexSettingsAtLeastOneNotAllowedSettings(restoredIndexName);
    }

    private void testUpdateIndexSettingsOnlyNotAllowedSettings(String index) {
        try {
            final UpdateSettingsRequestBuilder builder = client().admin().indices().prepareUpdateSettings(index);
            builder.setSettings(Map.of("index.refresh_interval", 10));
            builder.execute().actionGet();
            fail("Expected operation to throw an exception");
        } catch (ClusterBlockException e) {
            MatcherAssert.assertThat(e.blocks(), contains(IndexMetadata.REMOTE_READ_ONLY_ALLOW_DELETE));
        }
    }

    private void testUpdateIndexSettingsOnlyAllowedSettings(String index) {
        final UpdateSettingsRequestBuilder builder = client().admin().indices().prepareUpdateSettings(index);
        builder.setSettings(Map.of("index.max_result_window", 1000, "index.search.slowlog.threshold.query.warn", "10s"));
        AcknowledgedResponse settingsResponse = builder.execute().actionGet();
        assertThat(settingsResponse, notNullValue());
    }

    private void testUpdateIndexSettingsAtLeastOneNotAllowedSettings(String index) {
        try {
            final UpdateSettingsRequestBuilder builder = client().admin().indices().prepareUpdateSettings(index);
            builder.setSettings(
                Map.of("index.max_result_window", 5000, "index.search.slowlog.threshold.query.warn", "15s", "index.refresh_interval", 10)
            );
            builder.execute().actionGet();
            fail("Expected operation to throw an exception");
        } catch (ClusterBlockException e) {
            MatcherAssert.assertThat(e.blocks(), contains(IndexMetadata.REMOTE_READ_ONLY_ALLOW_DELETE));
        }
    }

    /**
     * Picks a shard out of the cluster state for each given index and asserts
     * that the 'index' directory does not exist in the node's file system.
     * This assertion is digging a bit into the implementation details to
     * verify that the Lucene segment files are not copied from the snapshot
     * repository to the node's local disk for a remote snapshot index.
     */
    private void assertIndexDirectoryDoesNotExist(String... indexNames) {
        final ClusterState state = client().admin().cluster().prepareState().get().getState();
        for (String indexName : indexNames) {
            final Index index = state.metadata().index(indexName).getIndex();
            // Get the primary shards for the given index
            final GroupShardsIterator<ShardIterator> shardIterators = state.getRoutingTable()
                .activePrimaryShardsGrouped(new String[] { indexName }, false);
            // Randomly pick one of the shards
            final List<ShardIterator> iterators = iterableAsArrayList(shardIterators);
            final ShardIterator shardIterator = RandomPicks.randomFrom(random(), iterators);
            final ShardRouting shardRouting = shardIterator.nextOrNull();
            assertNotNull(shardRouting);
            assertTrue(shardRouting.primary());
            assertTrue(shardRouting.assignedToNode());
            // Get the file system stats for the assigned node
            final String nodeId = shardRouting.currentNodeId();
            final NodesStatsResponse nodeStats = client().admin().cluster().prepareNodesStats(nodeId).addMetric(FS.metricName()).get();
            for (FsInfo.Path info : nodeStats.getNodes().get(0).getFs()) {
                // Build the expected path for the index data for a "normal"
                // index and assert it does not exist
                final String path = info.getPath();
                final Path file = PathUtils.get(path)
                    .resolve("indices")
                    .resolve(index.getUUID())
                    .resolve(Integer.toString(shardRouting.getId()))
                    .resolve("index");
                MatcherAssert.assertThat("Expect file not to exist: " + file, Files.exists(file), is(false));
            }
        }
    }

    public void testFileCacheStats() throws Exception {
        final String snapshotName = "test-snap";
        final String repoName = "test-repo";
        final String indexName1 = "test-idx-1";
        final Client client = client();
        final int numNodes = 2;

        internalCluster().ensureAtLeastNumDataNodes(numNodes);
        createIndexWithDocsAndEnsureGreen(1, 100, indexName1);

        createRepositoryWithSettings(null, repoName);
        takeSnapshot(client, snapshotName, repoName, indexName1);
        deleteIndicesAndEnsureGreen(client, indexName1);
        assertAllNodesFileCacheEmpty();

        internalCluster().ensureAtLeastNumSearchNodes(numNodes);
        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);
        assertNodesFileCacheNonEmpty(numNodes);
    }

    private void assertAllNodesFileCacheEmpty() {
        NodesStatsResponse response = client().admin().cluster().nodesStats(new NodesStatsRequest().all()).actionGet();
        for (NodeStats stats : response.getNodes()) {
            FileCacheStats fcstats = stats.getFileCacheStats();
            if (fcstats != null) {
                assertTrue(isFileCacheEmpty(fcstats));
            }
        }
    }

    private void assertNodesFileCacheNonEmpty(int numNodes) {
        NodesStatsResponse response = client().admin().cluster().nodesStats(new NodesStatsRequest().all()).actionGet();
        int nonEmptyFileCacheNodes = 0;
        for (NodeStats stats : response.getNodes()) {
            FileCacheStats fcStats = stats.getFileCacheStats();
            if (stats.getNode().isSearchNode()) {
                if (!isFileCacheEmpty(fcStats)) {
                    nonEmptyFileCacheNodes++;
                }
            } else {
                assertNull(fcStats);
            }

        }
        assertEquals(numNodes, nonEmptyFileCacheNodes);
    }

    private boolean isFileCacheEmpty(FileCacheStats stats) {
        return stats.getUsed().getBytes() == 0L && stats.getActive().getBytes() == 0L;
    }

    public void testPruneFileCacheOnIndexDeletion() throws Exception {
        final String snapshotName = "test-snap";
        final String repoName = "test-repo";
        final String indexName1 = "test-idx-1";
        final String restoredIndexName1 = indexName1 + "-copy";
        final Client client = client();
        final int numNodes = 2;

        internalCluster().ensureAtLeastNumSearchNodes(numNodes);
        createIndexWithDocsAndEnsureGreen(1, 100, indexName1);

        createRepositoryWithSettings(null, repoName);
        takeSnapshot(client, snapshotName, repoName, indexName1);
        deleteIndicesAndEnsureGreen(client, indexName1);

        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);
        assertNodesFileCacheNonEmpty(numNodes);

        deleteIndicesAndEnsureGreen(client, restoredIndexName1);
        assertAllNodesFileCacheEmpty();
    }

    public void testCacheFilesAreClosedAfterUse() throws Exception {
        final int numReplicasIndex = randomIntBetween(1, 4);
        final String indexName = "test-idx";
        final String restoredIndexName = indexName + "-copy";
        final String repoName = "test-repo";
        final String snapshotName = "test-snap";
        final String id = randomAlphaOfLength(5);
        final Client client = client();

        internalCluster().ensureAtLeastNumSearchAndDataNodes(numReplicasIndex + 1);
        createIndex(indexName);
        client().prepareIndex(indexName).setId(id).setSource("field", "test").get();
        ensureGreen();
        createRepositoryWithSettings(null, repoName);
        takeSnapshot(client, snapshotName, repoName, indexName);
        restoreSnapshotAndEnsureGreen(client, snapshotName, repoName);

        // Search document to make the index fetch data from the remote snapshot to local storage
        SearchResponse searchResponse = client().prepareSearch(restoredIndexName).setQuery(QueryBuilders.termQuery("field", "test")).get();
        assertHitCount(searchResponse, 1);

        // The local cache files should be closed by deleting the restored index
        deleteIndicesAndEnsureGreen(client, restoredIndexName);

        logger.info("--> validate all the cache files are closed");
        // Get path of cache files
        final NodeEnvironment nodeEnv = internalCluster().getInstance(NodeEnvironment.class);
        Path fileCachePath = nodeEnv.fileCacheNodePath().fileCachePath;
        // Find all the files in the path
        try (Stream<Path> paths = Files.walk(fileCachePath)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                // Testing moving the file to check the file is closed or not.
                try {
                    Files.move(path, path, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    fail("No exception is expected. The file can't be moved, so it may not be closed.");
                }
            });
        } catch (NoSuchFileException e) {
            logger.debug("--> the path for the cache files doesn't exist");
        }
    }
}
