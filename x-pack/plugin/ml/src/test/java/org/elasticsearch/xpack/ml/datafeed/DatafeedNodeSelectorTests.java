/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.datafeed;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.ml.MLMetadataField;
import org.elasticsearch.xpack.core.ml.MlMetadata;
import org.elasticsearch.xpack.core.ml.job.config.Job;
import org.elasticsearch.xpack.core.ml.job.config.JobState;
import org.elasticsearch.xpack.core.ml.job.config.JobTaskStatus;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.junit.Before;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.elasticsearch.xpack.ml.action.TransportOpenJobActionTests.addJobTask;
import static org.elasticsearch.xpack.ml.support.BaseMlIntegTestCase.createDatafeed;
import static org.elasticsearch.xpack.ml.support.BaseMlIntegTestCase.createScheduledJob;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class DatafeedNodeSelectorTests extends ESTestCase {

    private IndexNameExpressionResolver resolver;
    private DiscoveryNodes nodes;
    private ClusterState clusterState;
    private MlMetadata mlMetadata;
    private PersistentTasksCustomMetaData tasks;

    @Before
    public void init() {
        resolver = new IndexNameExpressionResolver(Settings.EMPTY);
        nodes = DiscoveryNodes.builder()
                .add(new DiscoveryNode("node_name", "node_id", new TransportAddress(InetAddress.getLoopbackAddress(), 9300),
                        Collections.emptyMap(), Collections.emptySet(), Version.CURRENT))
                .build();
    }

    public void testSelectNode_GivenJobIsOpened() {
        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("foo")), null);
        mlMetadata = mlMetadataBuilder.build();

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask(job.getId(), "node_id", JobState.OPENED, tasksBuilder);
        tasks = tasksBuilder.build();

        givenClusterState("foo", 1, 0);

        PersistentTasksCustomMetaData.Assignment result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertEquals("node_id", result.getExecutorNode());
        new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated();
    }

    public void testSelectNode_GivenJobIsOpening() {
        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("foo")), null);
        mlMetadata = mlMetadataBuilder.build();

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask(job.getId(), "node_id", null, tasksBuilder);
        tasks = tasksBuilder.build();

        givenClusterState("foo", 1, 0);

        PersistentTasksCustomMetaData.Assignment result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertEquals("node_id", result.getExecutorNode());
        new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated();
    }

    public void testNoJobTask() {
        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);

        // Using wildcard index name to test for index resolving as well
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("fo*")), null);
        mlMetadata = mlMetadataBuilder.build();

        tasks = PersistentTasksCustomMetaData.builder().build();

        givenClusterState("foo", 1, 0);

        PersistentTasksCustomMetaData.Assignment result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertNull(result.getExecutorNode());
        assertThat(result.getExplanation(), equalTo("cannot start datafeed [datafeed_id], because job's [job_id] state is " +
                "[closed] while state [opened] is required"));

        ElasticsearchException e = expectThrows(ElasticsearchException.class,
                () -> new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated());
        assertThat(e.getMessage(), containsString("No node found to start datafeed [datafeed_id], allocation explanation "
                + "[cannot start datafeed [datafeed_id], because job's [job_id] state is [closed] while state [opened] is required]"));
    }

    public void testSelectNode_GivenJobFailedOrClosed() {
        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("foo")), null);
        mlMetadata = mlMetadataBuilder.build();

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        JobState jobState = randomFrom(JobState.FAILED, JobState.CLOSED);
        addJobTask(job.getId(), "node_id", jobState, tasksBuilder);
        tasks = tasksBuilder.build();

        givenClusterState("foo", 1, 0);

        PersistentTasksCustomMetaData.Assignment result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertNull(result.getExecutorNode());
        assertEquals("cannot start datafeed [datafeed_id], because job's [job_id] state is [" + jobState +
                "] while state [opened] is required", result.getExplanation());

        ElasticsearchException e = expectThrows(ElasticsearchException.class,
                () -> new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated());
        assertThat(e.getMessage(), containsString("No node found to start datafeed [datafeed_id], allocation explanation "
                + "[cannot start datafeed [datafeed_id], because job's [job_id] state is [" + jobState
                + "] while state [opened] is required]"));
    }

    public void testShardUnassigned() {
        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);

        // Using wildcard index name to test for index resolving as well
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("fo*")), null);
        mlMetadata = mlMetadataBuilder.build();

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask(job.getId(), "node_id", JobState.OPENED, tasksBuilder);
        tasks = tasksBuilder.build();

        List<Tuple<Integer, ShardRoutingState>> states = new ArrayList<>(2);
        states.add(new Tuple<>(0, ShardRoutingState.UNASSIGNED));

        givenClusterState("foo", 1, 0, states);

        PersistentTasksCustomMetaData.Assignment result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertNull(result.getExecutorNode());
        assertThat(result.getExplanation(), equalTo("cannot start datafeed [datafeed_id] because index [foo] " +
                "does not have all primary shards active yet."));

        new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated();
    }

    public void testShardNotAllActive() {
        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);

        // Using wildcard index name to test for index resolving as well
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("fo*")), null);
        mlMetadata = mlMetadataBuilder.build();

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask(job.getId(), "node_id", JobState.OPENED, tasksBuilder);
        tasks = tasksBuilder.build();

        List<Tuple<Integer, ShardRoutingState>> states = new ArrayList<>(2);
        states.add(new Tuple<>(0, ShardRoutingState.STARTED));
        states.add(new Tuple<>(1, ShardRoutingState.INITIALIZING));

        givenClusterState("foo", 2, 0, states);

        PersistentTasksCustomMetaData.Assignment result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertNull(result.getExecutorNode());
        assertThat(result.getExplanation(), equalTo("cannot start datafeed [datafeed_id] because index [foo] " +
                "does not have all primary shards active yet."));

        new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated();
    }

    public void testIndexDoesntExist() {
        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("not_foo")), null);
        mlMetadata = mlMetadataBuilder.build();

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask(job.getId(), "node_id", JobState.OPENED, tasksBuilder);
        tasks = tasksBuilder.build();

        givenClusterState("foo", 1, 0);

        PersistentTasksCustomMetaData.Assignment result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertNull(result.getExecutorNode());
        assertThat(result.getExplanation(), equalTo("cannot start datafeed [datafeed_id] because index [not_foo] " +
                "does not exist, is closed, or is still initializing."));

        ElasticsearchException e = expectThrows(ElasticsearchException.class,
                () -> new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated());
        assertThat(e.getMessage(), containsString("No node found to start datafeed [datafeed_id], allocation explanation "
                + "[cannot start datafeed [datafeed_id] because index [not_foo] does not exist, is closed, or is still initializing.]"));
    }

    public void testRemoteIndex() {
        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("remote:foo")), null);
        mlMetadata = mlMetadataBuilder.build();

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask(job.getId(), "node_id", JobState.OPENED, tasksBuilder);
        tasks = tasksBuilder.build();

        givenClusterState("foo", 1, 0);

        PersistentTasksCustomMetaData.Assignment result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertNotNull(result.getExecutorNode());
    }

    public void testSelectNode_jobTaskStale() {
        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("foo")), null);
        mlMetadata = mlMetadataBuilder.build();

        String nodeId = randomBoolean() ? "node_id2" : null;
        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask(job.getId(), nodeId, JobState.OPENED, tasksBuilder);
        // Set to lower allocationId, so job task is stale:
        tasksBuilder.updateTaskStatus(MlMetadata.jobTaskId(job.getId()), new JobTaskStatus(JobState.OPENED, 0));
        tasks = tasksBuilder.build();

        givenClusterState("foo", 1, 0);

        PersistentTasksCustomMetaData.Assignment result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertNull(result.getExecutorNode());
        assertEquals("cannot start datafeed [datafeed_id], job [job_id] status is stale",
                result.getExplanation());

        ElasticsearchException e = expectThrows(ElasticsearchException.class,
                () -> new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated());
        assertThat(e.getMessage(), containsString("No node found to start datafeed [datafeed_id], allocation explanation "
                + "[cannot start datafeed [datafeed_id], job [job_id] status is stale]"));

        tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask(job.getId(), "node_id1", JobState.OPENED, tasksBuilder);
        tasks = tasksBuilder.build();
        givenClusterState("foo", 1, 0);
        result = new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").selectNode();
        assertEquals("node_id1", result.getExecutorNode());
        new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated();
    }

    public void testSelectNode_GivenJobOpeningAndIndexDoesNotExist() {
        // Here we test that when there are 2 problems, the most critical gets reported first.
        // In this case job is Opening (non-critical) and the index does not exist (critical)

        MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder();
        Job job = createScheduledJob("job_id").build(new Date());
        mlMetadataBuilder.putJob(job, false);
        mlMetadataBuilder.putDatafeed(createDatafeed("datafeed_id", job.getId(), Collections.singletonList("not_foo")), null);
        mlMetadata = mlMetadataBuilder.build();

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask(job.getId(), "node_id", JobState.OPENING, tasksBuilder);
        tasks = tasksBuilder.build();

        givenClusterState("foo", 1, 0);

        ElasticsearchException e = expectThrows(ElasticsearchException.class,
                () -> new DatafeedNodeSelector(clusterState, resolver, "datafeed_id").checkDatafeedTaskCanBeCreated());
        assertThat(e.getMessage(), containsString("No node found to start datafeed [datafeed_id], allocation explanation "
                + "[cannot start datafeed [datafeed_id] because index [not_foo] does not exist, is closed, or is still initializing.]"));
    }

    private void givenClusterState(String index, int numberOfShards, int numberOfReplicas) {
        List<Tuple<Integer, ShardRoutingState>> states = new ArrayList<>(1);
        states.add(new Tuple<>(0, ShardRoutingState.STARTED));
        givenClusterState(index, numberOfShards, numberOfReplicas, states);
    }

    private void givenClusterState(String index, int numberOfShards, int numberOfReplicas, List<Tuple<Integer, ShardRoutingState>> states) {
        IndexMetaData indexMetaData = IndexMetaData.builder(index)
                .settings(settings(Version.CURRENT))
                .numberOfShards(numberOfShards)
                .numberOfReplicas(numberOfReplicas)
                .build();

        clusterState = ClusterState.builder(new ClusterName("cluster_name"))
                .metaData(new MetaData.Builder()
                        .putCustom(MLMetadataField.TYPE, mlMetadata)
                        .putCustom(PersistentTasksCustomMetaData.TYPE, tasks)
                        .put(indexMetaData, false))
                .nodes(nodes)
                .routingTable(generateRoutingTable(indexMetaData, states))
                .build();
    }

    private static RoutingTable generateRoutingTable(IndexMetaData indexMetaData, List<Tuple<Integer, ShardRoutingState>> states) {
        IndexRoutingTable.Builder rtBuilder = IndexRoutingTable.builder(indexMetaData.getIndex());

        final String index = indexMetaData.getIndex().getName();
        int counter = 0;
        for (Tuple<Integer, ShardRoutingState> state : states) {
            ShardId shardId = new ShardId(index, "_na_", counter);
            IndexShardRoutingTable.Builder shardRTBuilder = new IndexShardRoutingTable.Builder(shardId);
            ShardRouting shardRouting;

            if (state.v2().equals(ShardRoutingState.STARTED)) {
                shardRouting = TestShardRouting.newShardRouting(index, shardId.getId(),
                        "node_" + Integer.toString(state.v1()), null, true, ShardRoutingState.STARTED);
            } else if (state.v2().equals(ShardRoutingState.INITIALIZING)) {
                shardRouting = TestShardRouting.newShardRouting(index, shardId.getId(),
                        "node_" + Integer.toString(state.v1()), null, true, ShardRoutingState.INITIALIZING);
            } else if (state.v2().equals(ShardRoutingState.RELOCATING)) {
                shardRouting = TestShardRouting.newShardRouting(index, shardId.getId(),
                        "node_" + Integer.toString(state.v1()), "node_" + Integer.toString((state.v1() + 1) % 3),
                        true, ShardRoutingState.RELOCATING);
            } else {
                shardRouting = ShardRouting.newUnassigned(shardId, true,
                        RecoverySource.StoreRecoverySource.EMPTY_STORE_INSTANCE,
                        new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, ""));
            }

            shardRTBuilder.addShard(shardRouting);
            rtBuilder.addIndexShard(shardRTBuilder.build());
            counter += 1;
        }

        return new RoutingTable.Builder().add(rtBuilder).build();
    }
}
