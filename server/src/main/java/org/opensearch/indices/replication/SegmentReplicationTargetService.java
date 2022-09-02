/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.ExceptionsHelper;
import org.opensearch.OpenSearchException;
import org.opensearch.action.ActionListener;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.Nullable;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.CancellableThreads;
import org.opensearch.index.shard.IndexEventListener;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.ShardId;
import org.opensearch.indices.recovery.FileChunkRequest;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.indices.replication.checkpoint.ReplicationCheckpoint;
import org.opensearch.indices.replication.common.ReplicationCollection;
import org.opensearch.indices.replication.common.ReplicationCollection.ReplicationRef;
import org.opensearch.indices.replication.common.ReplicationListener;
import org.opensearch.indices.replication.common.ReplicationState;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportChannel;
import org.opensearch.transport.TransportRequestHandler;
import org.opensearch.transport.TransportService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service class that orchestrates replication events on replicas.
 *
 * @opensearch.internal
 */
public class SegmentReplicationTargetService implements IndexEventListener {

    private static final Logger logger = LogManager.getLogger(SegmentReplicationTargetService.class);

    private final ThreadPool threadPool;
    private final RecoverySettings recoverySettings;

    private final ReplicationCollection<SegmentReplicationTarget> onGoingReplications;

    private final SegmentReplicationSourceFactory sourceFactory;

    private final Map<ShardId, ReplicationCheckpoint> latestReceivedCheckpoint = new HashMap<>();

    // Empty Implementation, only required while Segment Replication is under feature flag.
    public static final SegmentReplicationTargetService NO_OP = new SegmentReplicationTargetService() {
        @Override
        public void beforeIndexShardClosed(ShardId shardId, IndexShard indexShard, Settings indexSettings) {
            // NoOp;
        }

        @Override
        public synchronized void onNewCheckpoint(ReplicationCheckpoint receivedCheckpoint, IndexShard replicaShard) {
            // noOp;
        }

        @Override
        public void shardRoutingChanged(IndexShard indexShard, @Nullable ShardRouting oldRouting, ShardRouting newRouting) {
            // noOp;
        }
    };

    // Used only for empty implementation.
    private SegmentReplicationTargetService() {
        threadPool = null;
        recoverySettings = null;
        onGoingReplications = null;
        sourceFactory = null;
    }

    public ReplicationRef<SegmentReplicationTarget> get(long replicationId) {
        return onGoingReplications.get(replicationId);
    }

    /**
     * The internal actions
     *
     * @opensearch.internal
     */
    public static class Actions {
        public static final String FILE_CHUNK = "internal:index/shard/replication/file_chunk";
    }

    public SegmentReplicationTargetService(
        final ThreadPool threadPool,
        final RecoverySettings recoverySettings,
        final TransportService transportService,
        final SegmentReplicationSourceFactory sourceFactory
    ) {
        this.threadPool = threadPool;
        this.recoverySettings = recoverySettings;
        this.onGoingReplications = new ReplicationCollection<>(logger, threadPool);
        this.sourceFactory = sourceFactory;

        transportService.registerRequestHandler(
            Actions.FILE_CHUNK,
            ThreadPool.Names.GENERIC,
            FileChunkRequest::new,
            new FileChunkTransportRequestHandler()
        );
    }

    /**
     * Cancel any replications on this node for a replica that is about to be closed.
     */
    @Override
    public void beforeIndexShardClosed(ShardId shardId, @Nullable IndexShard indexShard, Settings indexSettings) {
        if (indexShard != null) {
            onGoingReplications.cancelForShard(shardId, "shard closed");
        }
    }

    /**
     * Cancel any replications on this node for a replica that has just been promoted as the new primary.
     */
    @Override
    public void shardRoutingChanged(IndexShard indexShard, @Nullable ShardRouting oldRouting, ShardRouting newRouting) {
        if (oldRouting != null && oldRouting.primary() == false && newRouting.primary()) {
            onGoingReplications.cancelForShard(indexShard.shardId(), "shard has been promoted to primary");
        }
    }

    /**
     * Invoked when a new checkpoint is received from a primary shard.
     * It checks if a new checkpoint should be processed or not and starts replication if needed.
     *
     * @param receivedCheckpoint received checkpoint that is checked for processing
     * @param replicaShard       replica shard on which checkpoint is received
     */
    public synchronized void onNewCheckpoint(final ReplicationCheckpoint receivedCheckpoint, final IndexShard replicaShard) {
        logger.trace(() -> new ParameterizedMessage("Replica received new replication checkpoint from primary [{}]", receivedCheckpoint));
        // Checks if received checkpoint is already present and ahead then it replaces old received checkpoint
        if (latestReceivedCheckpoint.get(replicaShard.shardId()) != null) {
            if (receivedCheckpoint.isAheadOf(latestReceivedCheckpoint.get(replicaShard.shardId()))) {
                latestReceivedCheckpoint.replace(replicaShard.shardId(), receivedCheckpoint);
            }
        } else {
            latestReceivedCheckpoint.put(replicaShard.shardId(), receivedCheckpoint);
        }
        if (onGoingReplications.isShardReplicating(replicaShard.shardId())) {
            logger.trace(
                () -> new ParameterizedMessage(
                    "Ignoring new replication checkpoint - shard is currently replicating to checkpoint {}",
                    replicaShard.getLatestReplicationCheckpoint()
                )
            );
            return;
        }
        final Thread thread = Thread.currentThread();
        if (replicaShard.shouldProcessCheckpoint(receivedCheckpoint)) {
            startReplication(receivedCheckpoint, replicaShard, new SegmentReplicationListener() {
                @Override
                public void onReplicationDone(SegmentReplicationState state) {
                    logger.trace(
                        () -> new ParameterizedMessage(
                            "[shardId {}] [replication id {}] Replication complete, timing data: {}",
                            replicaShard.shardId().getId(),
                            state.getReplicationId(),
                            state.getTimingData()
                        )
                    );
                    // if we received a checkpoint during the copy event that is ahead of this
                    // try and process it.
                    if (latestReceivedCheckpoint.get(replicaShard.shardId()).isAheadOf(replicaShard.getLatestReplicationCheckpoint())) {
                        Runnable runnable = () -> onNewCheckpoint(latestReceivedCheckpoint.get(replicaShard.shardId()), replicaShard);
                        // Checks if we are using same thread and forks if necessary.
                        if (thread == Thread.currentThread()) {
                            threadPool.generic().execute(runnable);
                        } else {
                            runnable.run();
                        }
                    }
                }

                @Override
                public void onReplicationFailure(SegmentReplicationState state, OpenSearchException e, boolean sendShardFailure) {
                    logger.trace(
                        () -> new ParameterizedMessage(
                            "[shardId {}] [replication id {}] Replication failed, timing data: {}",
                            replicaShard.shardId().getId(),
                            state.getReplicationId(),
                            state.getTimingData()
                        )
                    );
                    if (sendShardFailure == true) {
                        logger.error("replication failure", e);
                        replicaShard.failShard("replication failure", e);
                    }
                }
            });

        }
    }

    public SegmentReplicationTarget startReplication(
        final ReplicationCheckpoint checkpoint,
        final IndexShard indexShard,
        final SegmentReplicationListener listener
    ) {
        final SegmentReplicationTarget target = new SegmentReplicationTarget(
            checkpoint,
            indexShard,
            sourceFactory.get(indexShard),
            listener
        );
        startReplication(target);
        return target;
    }

    // pkg-private for integration tests
    void startReplication(final SegmentReplicationTarget target) {
        final long replicationId = onGoingReplications.start(target, recoverySettings.activityTimeout());
        threadPool.generic().execute(new ReplicationRunner(replicationId));
    }

    /**
     * Listener that runs on changes in Replication state
     *
     * @opensearch.internal
     */
    public interface SegmentReplicationListener extends ReplicationListener {

        @Override
        default void onDone(ReplicationState state) {
            onReplicationDone((SegmentReplicationState) state);
        }

        @Override
        default void onFailure(ReplicationState state, OpenSearchException e, boolean sendShardFailure) {
            onReplicationFailure((SegmentReplicationState) state, e, sendShardFailure);
        }

        void onReplicationDone(SegmentReplicationState state);

        void onReplicationFailure(SegmentReplicationState state, OpenSearchException e, boolean sendShardFailure);
    }

    /**
     * Runnable implementation to trigger a replication event.
     */
    private class ReplicationRunner implements Runnable {

        final long replicationId;

        public ReplicationRunner(long replicationId) {
            this.replicationId = replicationId;
        }

        @Override
        public void run() {
            start(replicationId);
        }
    }

    private void start(final long replicationId) {
        try (ReplicationRef<SegmentReplicationTarget> replicationRef = onGoingReplications.get(replicationId)) {
            // This check is for handling edge cases where the reference is removed before the ReplicationRunner is started by the
            // threadpool.
            if (replicationRef == null) {
                return;
            }
            replicationRef.get().startReplication(new ActionListener<>() {
                @Override
                public void onResponse(Void o) {
                    onGoingReplications.markAsDone(replicationId);
                }

                @Override
                public void onFailure(Exception e) {
                    Throwable cause = ExceptionsHelper.unwrapCause(e);
                    if (cause instanceof CancellableThreads.ExecutionCancelledException) {
                        if (onGoingReplications.getTarget(replicationId) != null) {
                            // if the target still exists in our collection, the primary initiated the cancellation, fail the replication
                            // but do not fail the shard. Cancellations initiated by this node from Index events will be removed with
                            // onGoingReplications.cancel and not appear in the collection when this listener resolves.
                            onGoingReplications.fail(replicationId, (CancellableThreads.ExecutionCancelledException) cause, false);
                        }
                    } else {
                        onGoingReplications.fail(replicationId, new OpenSearchException("Segment Replication failed", e), true);
                    }
                }
            });
        }
    }

    private class FileChunkTransportRequestHandler implements TransportRequestHandler<FileChunkRequest> {

        // How many bytes we've copied since we last called RateLimiter.pause
        final AtomicLong bytesSinceLastPause = new AtomicLong();

        @Override
        public void messageReceived(final FileChunkRequest request, TransportChannel channel, Task task) throws Exception {
            try (ReplicationRef<SegmentReplicationTarget> ref = onGoingReplications.getSafe(request.recoveryId(), request.shardId())) {
                final SegmentReplicationTarget target = ref.get();
                final ActionListener<Void> listener = target.createOrFinishListener(channel, Actions.FILE_CHUNK, request);
                target.handleFileChunk(request, target, bytesSinceLastPause, recoverySettings.rateLimiter(), listener);
            }
        }
    }
}
