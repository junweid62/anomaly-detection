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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.opensearch.ResourceAlreadyExistsException;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.ad.constant.ADCommonName;
import org.opensearch.ad.indices.ADIndex;
import org.opensearch.ad.indices.ADIndexManagement;
import org.opensearch.ad.model.AnomalyResult;
import org.opensearch.ad.settings.AnomalyDetectorSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.timeseries.NodeStateManager;
import org.opensearch.timeseries.TestHelpers;
import org.opensearch.timeseries.transport.handler.ResultIndexingHandler;

public class AnomalyResultHandlerTests extends AbstractIndexHandlerTest {
    @Mock
    private NodeStateManager nodeStateManager;

    @Mock
    private Clock clock;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        super.setUpLog4jForJUnit(ResultIndexingHandler.class);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        super.tearDownLog4jForJUnit();
    }

    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    @Test
    public void testSavingAdResult() throws IOException {
        setUpSavingAnomalyResultIndex(false);
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            assertTrue(
                String.format(Locale.ROOT, "The size of args is %d.  Its content is %s", args.length, Arrays.toString(args)),
                args.length >= 2
            );
            IndexRequest request = invocation.getArgument(0);
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            assertTrue(request != null && listener != null);
            listener.onResponse(mock(IndexResponse.class));
            return null;
        }).when(client).index(any(IndexRequest.class), ArgumentMatchers.<ActionListener<IndexResponse>>any());
        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, null);
        assertEquals(1, testAppender.countMessage(ResultIndexingHandler.SUCCESS_SAVING_MSG, true));
    }

    @Test
    public void testSavingFailureNotRetry() throws InterruptedException, IOException {
        savingFailureTemplate(false, 1, true);

        assertEquals(1, testAppender.countMessage(ResultIndexingHandler.FAIL_TO_SAVE_ERR_MSG, true));
        assertTrue(!testAppender.containsMessage(ResultIndexingHandler.SUCCESS_SAVING_MSG, true));
        assertTrue(!testAppender.containsMessage(ResultIndexingHandler.RETRY_SAVING_ERR_MSG, true));
    }

    @Test
    public void testSavingFailureRetry() throws InterruptedException, IOException {
        setWriteBlockAdResultIndex(false);
        savingFailureTemplate(true, 3, true);

        assertEquals(2, testAppender.countMessage(ResultIndexingHandler.RETRY_SAVING_ERR_MSG, true));
        assertEquals(1, testAppender.countMessage(ResultIndexingHandler.FAIL_TO_SAVE_ERR_MSG, true));
        assertTrue(!testAppender.containsMessage(ResultIndexingHandler.SUCCESS_SAVING_MSG, true));
    }

    @Test
    public void testIndexWriteBlock() {
        setWriteBlockAdResultIndex(true);
        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, null);

        assertTrue(testAppender.containsMessage(ResultIndexingHandler.CANNOT_SAVE_ERR_MSG, true));
    }

    @Test
    public void testCustomIndexWriteBlock() {
        setWriteBlockAdResultIndex(true);
        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, "test");

        assertTrue(testAppender.containsMessage(ResultIndexingHandler.CANNOT_SAVE_ERR_MSG, true));
    }

    @Test
    public void testAdResultIndexExist() throws IOException {
        setUpSavingAnomalyResultIndex(false, IndexCreation.RESOURCE_EXISTS_EXCEPTION);
        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, null);
        verify(client, times(1)).index(any(), any());
    }

    @Test
    public void testAdResultIndexOtherException() throws IOException {
        setUpSavingAnomalyResultIndex(false, IndexCreation.RUNTIME_EXCEPTION);
        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, null);
        verify(client, never()).index(any(), any());
        assertTrue(testAppender.containsMessage(String.format(Locale.ROOT, "Unexpected error creating index .opendistro-anomaly-results")));
    }

    /**
     * Template to test exponential backoff retry during saving anomaly result.
     *
     * @param throwOpenSearchRejectedExecutionException whether to throw
     *                                          OpenSearchRejectedExecutionException in the
     *                                          client::index mock or not
     * @param latchCount                        used for coordinating. Equal to
     *                                          number of expected retries plus 1.
     * @throws InterruptedException if thread execution is interrupted
     * @throws IOException          if IO failures
     */
    private void savingFailureTemplate(boolean throwOpenSearchRejectedExecutionException, int latchCount, boolean adResultIndexExists)
        throws InterruptedException,
        IOException {
        setUpSavingAnomalyResultIndex(adResultIndexExists);

        final CountDownLatch backoffLatch = new CountDownLatch(latchCount);

        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            assertTrue(
                String.format(Locale.ROOT, "The size of args is %d.  Its content is %s", args.length, Arrays.toString(args)),
                args.length >= 2
            );
            IndexRequest request = invocation.getArgument(0);
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            assertTrue(request != null && listener != null);
            if (throwOpenSearchRejectedExecutionException) {
                listener.onFailure(new OpenSearchRejectedExecutionException(""));
            } else {
                listener.onFailure(new IllegalArgumentException());
            }

            backoffLatch.countDown();
            return null;
        }).when(client).index(any(IndexRequest.class), ArgumentMatchers.<ActionListener<IndexResponse>>any());

        Settings backoffSettings = Settings
            .builder()
            .put("plugins.anomaly_detection.max_retry_for_backoff", 2)
            .put("plugins.anomaly_detection.backoff_initial_delay", TimeValue.timeValueMillis(1))
            .build();

        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            backoffSettings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );

        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, null);

        backoffLatch.await(1, TimeUnit.MINUTES);
    }

    @Test
    public void testCustomIndexCreate() {
        String testIndex = "test";
        setWriteBlockAdResultIndex(false);
        when(anomalyDetectionIndices.doesIndexExist(anyString())).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(anyString())).thenReturn(false);
        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(1);
            listener.onResponse(new CreateIndexResponse(true, true, testIndex));
            return null;
        }).when(anomalyDetectionIndices).initCustomResultIndexDirectly(eq(testIndex), any());

        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, testIndex);
        verify(client, times(1)).index(any(), any());
    }

    @Test
    public void testCustomIndexCreateNotAcked() {
        String testIndex = "test";
        setWriteBlockAdResultIndex(false);
        when(anomalyDetectionIndices.doesIndexExist(anyString())).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(anyString())).thenReturn(false);
        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(1);
            listener.onResponse(new CreateIndexResponse(false, false, testIndex));
            return null;
        }).when(anomalyDetectionIndices).initCustomResultIndexDirectly(eq(testIndex), any());

        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, testIndex);

        assertTrue(
            testAppender
                .containsMessage(
                    String.format(Locale.ROOT, "Creating custom result index %s with mappings call not acknowledged", testIndex)
                )
        );
    }

    @Test
    public void testCustomIndexCreateExists() {
        String testIndex = "test";
        setWriteBlockAdResultIndex(false);
        when(anomalyDetectionIndices.doesIndexExist(anyString())).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(anyString())).thenReturn(false);
        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(1);
            listener.onFailure(new ResourceAlreadyExistsException("index already exists"));
            return null;
        }).when(anomalyDetectionIndices).initCustomResultIndexDirectly(eq(testIndex), any());

        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, testIndex);
        verify(client, times(1)).index(any(), any());
    }

    @Test
    public void testCustomIndexOtherException() {
        String testIndex = "test";
        setWriteBlockAdResultIndex(false);
        when(anomalyDetectionIndices.doesIndexExist(anyString())).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(anyString())).thenReturn(false);

        Exception testException = new OpenSearchRejectedExecutionException("Test exception");

        doAnswer(invocation -> {
            ActionListener<CreateIndexResponse> listener = invocation.getArgument(1);
            listener.onFailure(testException);
            return null;
        }).when(anomalyDetectionIndices).initCustomResultIndexDirectly(eq(testIndex), any());

        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, testIndex);

        assertTrue(testAppender.containsMessage(String.format(Locale.ROOT, "cannot create result index %s", testIndex)));
    }

    @Test
    public void testInvalid() {
        String testIndex = "test";
        setWriteBlockAdResultIndex(false);
        when(anomalyDetectionIndices.doesIndexExist(anyString())).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(anyString())).thenReturn(true);

        doAnswer(invocation -> {
            ActionListener<Boolean> listener = invocation.getArgument(1);
            listener.onResponse(false);
            return null;
        }).when(anomalyDetectionIndices).validateResultIndexMapping(eq(testIndex), any());

        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );
        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, testIndex);

        assertTrue(testAppender.containsMessage("wrong index mapping of custom result index", false));
    }

    @Test
    public void testValid() {
        String testIndex = "test";
        setWriteBlockAdResultIndex(false);
        when(anomalyDetectionIndices.doesIndexExist(anyString())).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(anyString())).thenReturn(true);

        doAnswer(invocation -> {
            ActionListener<Boolean> listener = invocation.getArgument(1);
            listener.onResponse(true);
            return null;
        }).when(anomalyDetectionIndices).validateResultIndexMapping(eq(testIndex), any());

        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );

        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, testIndex);
        verify(client, times(1)).index(any(), any());
    }

    @Test
    public void testValidationException() {
        String testIndex = "test";
        setWriteBlockAdResultIndex(false);
        when(anomalyDetectionIndices.doesIndexExist(anyString())).thenReturn(false);
        when(anomalyDetectionIndices.doesAliasExist(anyString())).thenReturn(true);

        Exception testException = new OpenSearchRejectedExecutionException("Test exception");

        doAnswer(invocation -> {
            ActionListener<Boolean> listener = invocation.getArgument(1);
            listener.onFailure(testException);
            return null;
        }).when(anomalyDetectionIndices).validateResultIndexMapping(eq(testIndex), any());

        ResultIndexingHandler<AnomalyResult, ADIndex, ADIndexManagement> handler = new ResultIndexingHandler<>(
            client,
            settings,
            threadPool,
            ADCommonName.ANOMALY_RESULT_INDEX_ALIAS,
            anomalyDetectionIndices,
            clientUtil,
            indexUtil,
            clusterService,
            AnomalyDetectorSettings.AD_BACKOFF_INITIAL_DELAY,
            AnomalyDetectorSettings.AD_MAX_RETRY_FOR_BACKOFF
        );

        handler.index(TestHelpers.randomAnomalyDetectResult(), detectorId, testIndex);
        assertTrue(testAppender.containsMessage(String.format(Locale.ROOT, "cannot validate result index %s", testIndex), false));
    }
}
