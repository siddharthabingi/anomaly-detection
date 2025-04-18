/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.ad.transport.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.opensearch.ad.constant.ADCommonName.ANOMALY_RESULT_INDEX_ALIAS;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;

import org.opensearch.ResourceAlreadyExistsException;
import org.opensearch.action.DocWriteRequest;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.bulk.BulkAction;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequestBuilder;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.ad.ADUnitTestCase;
import org.opensearch.ad.indices.ADIndex;
import org.opensearch.ad.indices.ADIndexManagement;
import org.opensearch.ad.model.AnomalyResult;
import org.opensearch.ad.settings.AnomalyDetectorSettings;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.engine.VersionConflictEngineException;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.timeseries.TestHelpers;
import org.opensearch.timeseries.common.exception.TimeSeriesException;
import org.opensearch.timeseries.transport.handler.ResultBulkIndexingHandler;
import org.opensearch.timeseries.util.ClientUtil;
import org.opensearch.timeseries.util.IndexUtils;
import org.opensearch.transport.client.Client;

import com.google.common.collect.ImmutableList;

public class AnomalyResultBulkIndexHandlerTests extends ADUnitTestCase {

    private ResultBulkIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> bulkIndexHandler;
    private Client client;
    private IndexUtils indexUtils;
    private ActionListener<BulkResponse> listener;
    private ADIndexManagement anomalyDetectionIndices;
    private String configId;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        anomalyDetectionIndices = mock(ADIndexManagement.class);
        client = mock(Client.class);
        Settings settings = Settings.EMPTY;
        Clock clock = mock(Clock.class);
        ThreadPool threadpool = mock(ThreadPool.class);
        ClientUtil clientUtil = new ClientUtil(client);
        indexUtils = mock(IndexUtils.class);
        ClusterService clusterService = mock(ClusterService.class);
        ThreadPool threadPool = mock(ThreadPool.class);
        bulkIndexHandler = new ResultBulkIndexingHandler(
            client,
            settings,
            threadPool,
            ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtils,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        listener = spy(new ActionListener<BulkResponse>() {
            @Override
            public void onResponse(BulkResponse bulkItemResponses) {}

            @Override
            public void onFailure(Exception e) {}
        });
        configId = "testId";
    }

    public void testNullAnomalyResults() {
        bulkIndexHandler.bulk(null, null, null, listener);
        verify(listener, times(1)).onResponse(null);
        verify(anomalyDetectionIndices, never()).doesConfigIndexExist();
    }

    public void testAnomalyResultBulkIndexHandler_IndexNotExist() {
        when(anomalyDetectionIndices.doesIndexExist("testIndex")).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist("testIndex")).thenReturn(false);
        AnomalyResult anomalyResult = mock(AnomalyResult.class);
        when(anomalyResult.getConfigId()).thenReturn(configId);

        bulkIndexHandler.bulk("testIndex", ImmutableList.of(anomalyResult), configId, listener);
        verify(anomalyDetectionIndices, times(1)).initCustomResultIndexDirectly(eq("testIndex"), any());
    }

    public void testAnomalyResultBulkIndexHandler_InValidResultIndexMapping() {
        when(anomalyDetectionIndices.doesIndexExist("testIndex")).thenReturn(true);
        doAnswer(invocation -> {
            ActionListener<Boolean> listener = invocation.getArgument(1);
            listener.onResponse(false);
            return null;
        }).when(anomalyDetectionIndices).validateResultIndexMapping(eq("testIndex"), any());

        AnomalyResult anomalyResult = mock(AnomalyResult.class);

        when(anomalyResult.getConfigId()).thenReturn(configId);

        bulkIndexHandler.bulk("testIndex", ImmutableList.of(anomalyResult), configId, listener);
        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        assertEquals("wrong index mapping of custom result index", exceptionCaptor.getValue().getMessage());
    }

    public void testAnomalyResultBulkIndexHandler_FailBulkIndexAnomaly() throws IOException {
        when(anomalyDetectionIndices.doesIndexExist("testIndex")).thenReturn(true);
        doAnswer(invocation -> {
            ActionListener<Boolean> listener = invocation.getArgument(1);
            listener.onResponse(true);
            return null;
        }).when(anomalyDetectionIndices).validateResultIndexMapping(eq("testIndex"), any());
        AnomalyResult anomalyResult = mock(AnomalyResult.class);
        when(anomalyResult.getConfigId()).thenReturn(configId);
        when(anomalyResult.toXContent(any(), any())).thenThrow(new RuntimeException());

        bulkIndexHandler.bulk("testIndex", ImmutableList.of(anomalyResult), configId, listener);
        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        assertEquals("Failed to prepare request to bulk index results", exceptionCaptor.getValue().getMessage());
    }

    public void testCreateADResultIndexNotAcknowledged() throws IOException {
        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(0);
            listener.onResponse(new CreateIndexResponse(false, false, ANOMALY_RESULT_INDEX_ALIAS));
            return null;
        }).when(anomalyDetectionIndices).initDefaultResultIndexDirectly(any());
        bulkIndexHandler.bulk(null, ImmutableList.of(mock(AnomalyResult.class)), configId, listener);
        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        assertEquals("Creating result index with mappings call not acknowledged", exceptionCaptor.getValue().getMessage());
    }

    public void testWrongAnomalyResult() {
        BulkRequestBuilder bulkRequestBuilder = new BulkRequestBuilder(client, BulkAction.INSTANCE);
        doReturn(bulkRequestBuilder).when(client).prepareBulk();
        doReturn(true).when(anomalyDetectionIndices).doesDefaultResultIndexExist();
        doAnswer(invocation -> {
            ActionListener<BulkResponse> listener = invocation.getArgument(1);
            BulkItemResponse[] bulkItemResponses = new BulkItemResponse[2];
            String indexName = ANOMALY_RESULT_INDEX_ALIAS;
            String type = "_doc";
            String idPrefix = "id";
            String uuid = "uuid";
            int shardIntId = 0;
            ShardId shardId = new ShardId(new Index(indexName, uuid), shardIntId);
            BulkItemResponse.Failure failure = new BulkItemResponse.Failure(
                ANOMALY_RESULT_INDEX_ALIAS,
                randomAlphaOfLength(5),
                new VersionConflictEngineException(new ShardId(ANOMALY_RESULT_INDEX_ALIAS, "", 1), "id", "test")
            );
            bulkItemResponses[0] = new BulkItemResponse(0, randomFrom(DocWriteRequest.OpType.values()), failure);
            bulkItemResponses[1] = new BulkItemResponse(
                1,
                randomFrom(DocWriteRequest.OpType.values()),
                new IndexResponse(shardId, idPrefix + 1, 1, 1, randomInt(), true)
            );
            BulkResponse bulkResponse = new BulkResponse(bulkItemResponses, 10);
            listener.onResponse(bulkResponse);
            return null;
        }).when(client).bulk(any(), any());
        bulkIndexHandler.bulk(null, ImmutableList.of(wrongAnomalyResult(), TestHelpers.randomAnomalyDetectResult()), configId, listener);
        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        assertTrue(exceptionCaptor.getValue().getMessage().contains("VersionConflictEngineException"));
    }

    public void testBulkSaveException() {
        BulkRequestBuilder bulkRequestBuilder = mock(BulkRequestBuilder.class);
        doReturn(bulkRequestBuilder).when(client).prepareBulk();
        doReturn(true).when(anomalyDetectionIndices).doesDefaultResultIndexExist();

        String testError = randomAlphaOfLength(5);
        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(1);
            listener.onFailure(new RuntimeException(testError));
            return null;
        }).when(client).bulk(any(), any());

        bulkIndexHandler.bulk(null, ImmutableList.of(TestHelpers.randomAnomalyDetectResult()), configId, listener);
        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        assertEquals(testError, exceptionCaptor.getValue().getMessage());
    }

    private AnomalyResult wrongAnomalyResult() {
        return new AnomalyResult(
            randomAlphaOfLength(5),
            null,
            randomDouble(),
            randomDouble(),
            randomDouble(),
            null,
            null,
            null,
            null,
            null,
            randomAlphaOfLength(5),
            Optional.empty(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            randomDoubleBetween(1.1, 10.0, true),
            null
        );
    }

    public void testResponseIsAcknowledgedTrue() throws InterruptedException {
        String testIndex = "testIndex";

        // Set up mocks for doesIndexExist and doesAliasExist
        when(anomalyDetectionIndices.doesIndexExist(testIndex)).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(testIndex)).thenReturn(false);

        // Mock initCustomResultIndexDirectly to simulate index creation and call the listener
        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(1);
            // Simulate immediate onResponse call
            listener.onResponse(new CreateIndexResponse(true, true, testIndex));
            return null;
        }).when(anomalyDetectionIndices).initCustomResultIndexDirectly(eq(testIndex), any());

        AnomalyResult result = mock(AnomalyResult.class);

        // Call bulk method
        bulkIndexHandler.bulk(testIndex, ImmutableList.of(result), configId, listener);

        // Verify that listener.onResponse is called
        verify(client, times(1)).prepareBulk();
    }

    public void testResponseIsAcknowledgedFalse() {
        String testIndex = "testIndex";
        when(anomalyDetectionIndices.doesIndexExist(testIndex)).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(testIndex)).thenReturn(false);

        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(1);
            listener.onResponse(new CreateIndexResponse(false, false, testIndex));
            return null;
        }).when(anomalyDetectionIndices).initCustomResultIndexDirectly(eq(testIndex), any());

        AnomalyResult result = mock(AnomalyResult.class);
        bulkIndexHandler.bulk(testIndex, ImmutableList.of(result), configId, listener);

        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        assertEquals("Creating custom result index with mappings call not acknowledged", exceptionCaptor.getValue().getMessage());
    }

    public void testResourceAlreadyExistsException() {
        String testIndex = "testIndex";
        when(anomalyDetectionIndices.doesIndexExist(testIndex)).thenReturn(false, true);
        when(anomalyDetectionIndices.doesAliasExist(testIndex)).thenReturn(false, false);

        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(1);
            listener.onFailure(new ResourceAlreadyExistsException("index already exists"));
            return null;
        }).when(anomalyDetectionIndices).initCustomResultIndexDirectly(eq(testIndex), any());

        doAnswer(invocation -> {
            ActionListener<Boolean> listener = invocation.getArgument(1);
            listener.onResponse(true);
            return null;
        }).when(anomalyDetectionIndices).validateResultIndexMapping(eq(testIndex), any());

        AnomalyResult result = mock(AnomalyResult.class);
        bulkIndexHandler.bulk(testIndex, ImmutableList.of(result), configId, listener);

        // Verify that listener.onResponse is called
        verify(client, times(1)).prepareBulk();
    }

    public void testOtherException() {
        String testIndex = "testIndex";
        when(anomalyDetectionIndices.doesIndexExist(testIndex)).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(testIndex)).thenReturn(false);

        Exception testException = new OpenSearchRejectedExecutionException("Test exception");

        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(1);
            listener.onFailure(testException);
            return null;
        }).when(anomalyDetectionIndices).initCustomResultIndexDirectly(eq(testIndex), any());

        AnomalyResult result = mock(AnomalyResult.class);
        bulkIndexHandler.bulk(testIndex, ImmutableList.of(result), configId, listener);

        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        assertEquals(testException, exceptionCaptor.getValue());
    }

    public void testTimeSeriesExceptionCaughtInBulk() {
        String testIndex = "testIndex";
        TimeSeriesException testException = new TimeSeriesException("Test TimeSeriesException");

        // Mock doesIndexExist to throw TimeSeriesException
        when(anomalyDetectionIndices.doesIndexExist(testIndex)).thenThrow(testException);

        AnomalyResult result = mock(AnomalyResult.class);

        // Call bulk method
        bulkIndexHandler.bulk(testIndex, ImmutableList.of(result), configId, listener);

        // Verify that listener.onFailure is called with the TimeSeriesException
        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        assertEquals(testException, exceptionCaptor.getValue());
    }

    public void testExceptionCaughtInBulk() {
        String testIndex = "testIndex";
        NullPointerException testException = new NullPointerException("Test NullPointerException");

        // Mock doesIndexExist to throw NullPointerException
        when(anomalyDetectionIndices.doesIndexExist(testIndex)).thenThrow(testException);

        AnomalyResult result = mock(AnomalyResult.class);

        // Call bulk method
        bulkIndexHandler.bulk(testIndex, ImmutableList.of(result), configId, listener);

        // Verify that listener.onFailure is called with a TimeSeriesException wrapping the original exception
        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        Exception capturedException = exceptionCaptor.getValue();
        assertTrue(capturedException instanceof TimeSeriesException);
        assertEquals("Failed to bulk index result", capturedException.getMessage());
        assertEquals(testException, capturedException.getCause());
    }
}
