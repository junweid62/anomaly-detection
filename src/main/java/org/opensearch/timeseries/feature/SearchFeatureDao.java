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

package org.opensearch.timeseries.feature;

import static org.opensearch.ad.settings.AnomalyDetectorSettings.AD_PAGE_SIZE;
import static org.opensearch.ad.settings.AnomalyDetectorSettings.MAX_ENTITIES_FOR_PREVIEW;
import static org.opensearch.ad.settings.AnomalyDetectorSettings.PREVIEW_TIMEOUT_IN_MILLIS;
import static org.opensearch.timeseries.util.ParseUtils.batchFeatureQuery;

import java.io.IOException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.ad.model.AnomalyDetector;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.RangeQueryBuilder;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.search.aggregations.Aggregation;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.aggregations.Aggregations;
import org.opensearch.search.aggregations.PipelineAggregatorBuilders;
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregation;
import org.opensearch.search.aggregations.bucket.composite.InternalComposite;
import org.opensearch.search.aggregations.bucket.composite.TermsValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.range.InternalDateRange;
import org.opensearch.search.aggregations.bucket.range.InternalDateRange.Bucket;
import org.opensearch.search.aggregations.bucket.terms.Terms;
import org.opensearch.search.aggregations.metrics.Min;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.FieldSortBuilder;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.timeseries.AnalysisType;
import org.opensearch.timeseries.common.exception.TimeSeriesException;
import org.opensearch.timeseries.constant.CommonName;
import org.opensearch.timeseries.model.Config;
import org.opensearch.timeseries.model.Entity;
import org.opensearch.timeseries.model.IntervalTimeConfiguration;
import org.opensearch.timeseries.util.ParseUtils;
import org.opensearch.timeseries.util.SecurityClientUtil;
import org.opensearch.transport.client.Client;

/**
 * DAO for features from search.
 */
public class SearchFeatureDao extends AbstractRetriever {
    private static final Logger logger = LogManager.getLogger(SearchFeatureDao.class);

    protected static final String AGG_NAME_TOP = "top_agg";
    protected static final String AGG_NAME_MIN = "min_timefield";

    // Dependencies
    private final Client client;
    private final NamedXContentRegistry xContent;
    private final SecurityClientUtil clientUtil;
    private volatile int maxEntitiesForPreview;
    private volatile int pageSize;
    private final int minimumDocCountForPreview;
    private long previewTimeoutInMilliseconds;
    private Clock clock;

    // used for testing as we can mock clock
    public SearchFeatureDao(
        Client client,
        NamedXContentRegistry xContent,
        SecurityClientUtil clientUtil,
        ClusterService clusterService,
        int minimumDocCount,
        Clock clock,
        int maxEntitiesForPreview,
        int pageSize,
        long previewTimeoutInMilliseconds
    ) {
        this.client = client;
        this.xContent = xContent;
        this.clientUtil = clientUtil;
        this.maxEntitiesForPreview = maxEntitiesForPreview;

        this.pageSize = pageSize;

        if (clusterService != null) {
            clusterService.getClusterSettings().addSettingsUpdateConsumer(MAX_ENTITIES_FOR_PREVIEW, it -> this.maxEntitiesForPreview = it);
            clusterService.getClusterSettings().addSettingsUpdateConsumer(AD_PAGE_SIZE, it -> this.pageSize = it);
        }
        this.minimumDocCountForPreview = minimumDocCount;
        this.previewTimeoutInMilliseconds = previewTimeoutInMilliseconds;
        this.clock = clock;
    }

    /**
     * Constructor injection.
     *
     * @param client ES client for queries
     * @param xContent ES XContentRegistry
     * @param clientUtil utility for ES client
     * @param clusterService ES ClusterService
     * @param minimumDocCount minimum doc count required for an entity; used to
     *   make sure an entity has enough samples for preview
     */
    public SearchFeatureDao(
        Client client,
        NamedXContentRegistry xContent,
        SecurityClientUtil clientUtil,
        Settings settings,
        ClusterService clusterService,
        int minimumDocCount
    ) {
        this(
            client,
            xContent,
            clientUtil,
            clusterService,
            minimumDocCount,
            Clock.systemUTC(),
            MAX_ENTITIES_FOR_PREVIEW.get(settings),
            AD_PAGE_SIZE.get(settings),
            PREVIEW_TIMEOUT_IN_MILLIS
        );
    }

    /**
     * Returns to listener the epoch time of the latest data under the detector.
     *
     * @param config info about the data
     * @param listener onResponse is called with the epoch time of the latest data under the detector
     */
    public void getLatestDataTime(Config config, Optional<Entity> entity, AnalysisType context, ActionListener<Optional<Long>> listener) {
        getLatestDataTime(null, config, entity, context, listener);
    }

    /**
     * Returns to listener the epoch time of the latest data under the detector.
     *
     * @param config info about the data
     * @param listener onResponse is called with the epoch time of the latest data under the detector
     */
    public void getLatestDataTime(
        User user,
        Config config,
        Optional<Entity> entity,
        AnalysisType context,
        ActionListener<Optional<Long>> listener
    ) {
        BoolQueryBuilder internalFilterQuery = QueryBuilders.boolQuery();
        if (entity.isPresent()) {
            for (TermQueryBuilder term : entity.get().getTermQueryForCustomerIndex()) {
                internalFilterQuery.filter(term);
            }
        }
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
            .query(internalFilterQuery)
            .aggregation(AggregationBuilders.max(CommonName.AGG_NAME_MAX_TIME).field(config.getTimeField()))
            .size(0);
        SearchRequest searchRequest = new SearchRequest().indices(config.getIndices().toArray(new String[0])).source(searchSourceBuilder);
        final ActionListener<SearchResponse> searchResponseListener = ActionListener
            .wrap(response -> listener.onResponse(ParseUtils.getLatestDataTime(response)), listener::onFailure);
        // using the original context in listener as user roles have no permissions for internal operations like fetching a
        // checkpoint
        if (user != null) {
            clientUtil
                .<SearchRequest, SearchResponse>asyncRequestWithInjectedSecurity(
                    searchRequest,
                    client::search,
                    user,
                    client,
                    context,
                    searchResponseListener
                );
        } else {
            clientUtil
                .<SearchRequest, SearchResponse>asyncRequestWithInjectedSecurity(
                    searchRequest,
                    client::search,
                    config.getId(),
                    client,
                    context,
                    searchResponseListener
                );
        }
    }

    /**
     * Get list of entities with high count in descending order within specified time range
     * @param detector detector config
     * @param startTime start time of time range
     * @param endTime end time of time range
     * @param listener listener to return back the entities
     */
    public void getHighestCountEntities(AnomalyDetector detector, long startTime, long endTime, ActionListener<List<Entity>> listener) {
        getHighestCountEntities(detector, startTime, endTime, maxEntitiesForPreview, minimumDocCountForPreview, pageSize, listener);
    }

    /**
     * Get list of entities with high count in descending order within specified time range
     * @param detector detector config
     * @param startTime start time of time range
     * @param endTime end time of time range
     * @param maxEntitiesSize max top entities
     * @param minimumDocCount minimum doc count for top entities
     * @param pageSize page size when query multi-category HC detector's top entities
     * @param listener listener to return back the entities
     */
    public void getHighestCountEntities(
        AnomalyDetector detector,
        long startTime,
        long endTime,
        int maxEntitiesSize,
        int minimumDocCount,
        int pageSize,
        ActionListener<List<Entity>> listener
    ) {
        if (!detector.isHighCardinality()) {
            listener.onResponse(null);
            return;
        }

        RangeQueryBuilder rangeQuery = new RangeQueryBuilder(detector.getTimeField())
            .from(startTime)
            .to(endTime)
            .format("epoch_millis")
            .includeLower(true)
            .includeUpper(false);

        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery().filter(rangeQuery).filter(detector.getFilterQuery());
        AggregationBuilder bucketAggs = null;

        if (detector.getCategoryFields().size() == 1) {
            bucketAggs = AggregationBuilders.terms(AGG_NAME_TOP).size(maxEntitiesSize).field(detector.getCategoryFields().get(0));
        } else {
            /*
             * We don't have an efficient solution for terms aggregation on multiple fields.
             * Terms aggregation does not support collecting terms from multiple fields in the same document.
             *  We have to work around the limitation by using a script to retrieve terms from multiple fields.
             *  The workaround disables the global ordinals optimization and thus causes a markedly longer
             *  slowdown. This is because scripting is tugging on memory and has to iterate through
             *  all of the documents at least once to create run-time fields.
             *
             *  We evaluated composite and terms aggregation using a generated data set with one
             *  million entities.  Each entity has two documents. Composite aggregation finishes
             *  around 40 seconds.  Terms aggregation performs differently on different clusters.
             *  On a 3 data node cluster, terms aggregation does not finish running within 2 hours
             *  on a 5 primary shard index. On a 15 data node cluster, terms  aggregation needs 217 seconds
             *  on a 15 primary shard index. On a 30 data node cluster, terms aggregation needs 47 seconds
             *  on a 30 primary shard index.
             *
             * Here we work around the problem using composite aggregation. Composite aggregation cannot
             * give top entities without collecting all aggregated results. Paginated results are returned
             * in the natural order of composite keys. This is fine for Preview API. Preview API needs the
             * top entities to make sure there is enough data for training and showing the results. We
             * can paginate entities and filter out entities that do not have enough docs (e.g., 256 docs).
             * As long as we have collected the desired number of entities (e.g., 5 entities), we can stop
             * pagination.
             *
             * Example composite query:
             * {
             *       "size": 0,
             *       "query": {
             *          "bool": {
             *               "filter": [{
             *                   "range": {
             *                       "@timestamp": {
             *                           "from": 1626118340000,
             *                           "to": 1626294912000,
             *                           "include_lower": true,
             *                           "include_upper": false,
             *                           "format": "epoch_millis",
             *                           "boost": 1.0
             *                       }
             *                   }
             *               }, {
             *                   "match_all": {
             *                       "boost": 1.0
             *                   }
             *               }],
             *               "adjust_pure_negative": true,
             *               "boost": 1.0
             *           }
             *       },
             *       "track_total_hits": -1,
             *       "aggregations": {
             *           "top_agg": {
             *               "composite": {
             *                   "size": 1,
             *                   "sources": [{
             *                       "service": {
             *                           "terms": {
             *                               "field": "service",
             *                               "missing_bucket": false,
             *                               "order": "asc"
             *                           }
             *                       }
             *                   }, {
             *                       "host": {
             *                           "terms": {
             *                               "field": "host",
             *                               "missing_bucket": false,
             *                               "order": "asc"
             *                           }
             *                       }
             *                   }]
             *               },
             *               "aggregations": {
             *                   "bucketSort": {
             *                       "bucket_sort": {
             *                           "sort": [{
             *                               "_count": {
             *                                   "order": "desc"
             *                               }
             *                           }],
             *                           "from": 0,
             *                           "size": 5,
             *                           "gap_policy": "SKIP"
             *                       }
             *                   }
             *               }
             *           }
             *       }
             *   }
             *
             */
            bucketAggs = AggregationBuilders
                .composite(
                    AGG_NAME_TOP,
                    detector.getCategoryFields().stream().map(f -> new TermsValuesSourceBuilder(f).field(f)).collect(Collectors.toList())
                )
                .size(pageSize)
                .subAggregation(
                    PipelineAggregatorBuilders
                        .bucketSort("bucketSort", Arrays.asList(new FieldSortBuilder("_count").order(SortOrder.DESC)))
                        .size(maxEntitiesSize)
                );
        }

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
            .query(boolQueryBuilder)
            .aggregation(bucketAggs)
            .trackTotalHits(false)
            .size(0);
        SearchRequest searchRequest = new SearchRequest().indices(detector.getIndices().toArray(new String[0])).source(searchSourceBuilder);
        final ActionListener<SearchResponse> searchResponseListener = new TopEntitiesListener(
            listener,
            detector,
            searchSourceBuilder,
            // TODO: tune timeout for historical analysis based on performance test result
            clock.millis() + previewTimeoutInMilliseconds,
            maxEntitiesSize,
            minimumDocCount
        );
        // using the original context in listener as user roles have no permissions for internal operations like fetching a
        // checkpoint
        clientUtil
            .<SearchRequest, SearchResponse>asyncRequestWithInjectedSecurity(
                searchRequest,
                client::search,
                detector.getId(),
                client,
                AnalysisType.AD,
                searchResponseListener
            );
    }

    class TopEntitiesListener implements ActionListener<SearchResponse> {
        private ActionListener<List<Entity>> listener;
        private AnomalyDetector detector;
        private List<Entity> topEntities;
        private SearchSourceBuilder searchSourceBuilder;
        private long expirationEpochMs;
        private long minimumDocCount;
        private int maxEntitiesSize;

        TopEntitiesListener(
            ActionListener<List<Entity>> listener,
            AnomalyDetector detector,
            SearchSourceBuilder searchSourceBuilder,
            long expirationEpochMs,
            int maxEntitiesSize,
            int minimumDocCount
        ) {
            this.listener = listener;
            this.detector = detector;
            this.topEntities = new ArrayList<>();
            this.searchSourceBuilder = searchSourceBuilder;
            this.expirationEpochMs = expirationEpochMs;
            this.maxEntitiesSize = maxEntitiesSize;
            this.minimumDocCount = minimumDocCount;
        }

        @Override
        public void onResponse(SearchResponse response) {
            try {
                Aggregations aggs = response.getAggregations();
                if (aggs == null) {
                    // This would indicate some bug or some opensearch core changes that we are not aware of (we don't keep up-to-date with
                    // the large amounts of changes there). For example, they may change to if there are results return it; otherwise return
                    // null instead of an empty Aggregations as they currently do.
                    logger.warn("Unexpected null aggregation.");
                    listener.onResponse(topEntities);
                    return;
                }

                Aggregation aggrResult = aggs.get(AGG_NAME_TOP);
                if (aggrResult == null) {
                    listener.onFailure(new IllegalArgumentException("Fail to find valid aggregation result"));
                    return;
                }

                if (detector.getCategoryFields().size() == 1) {
                    topEntities = ((Terms) aggrResult)
                        .getBuckets()
                        .stream()
                        .map(bucket -> bucket.getKeyAsString())
                        .collect(Collectors.toList())
                        .stream()
                        .map(entityValue -> Entity.createSingleAttributeEntity(detector.getCategoryFields().get(0), entityValue))
                        .collect(Collectors.toList());
                    listener.onResponse(topEntities);
                } else {
                    CompositeAggregation compositeAgg = (CompositeAggregation) aggrResult;
                    List<Entity> pageResults = compositeAgg
                        .getBuckets()
                        .stream()
                        .filter(bucket -> bucket.getDocCount() >= minimumDocCount)
                        .map(bucket -> Entity.createEntityByReordering(bucket.getKey()))
                        .collect(Collectors.toList());
                    // we only need at most maxEntitiesForPreview
                    int amountToWrite = maxEntitiesSize - topEntities.size();
                    for (int i = 0; i < amountToWrite && i < pageResults.size(); i++) {
                        topEntities.add(pageResults.get(i));
                    }
                    Map<String, Object> afterKey = compositeAgg.afterKey();
                    if (topEntities.size() >= maxEntitiesSize || afterKey == null) {
                        listener.onResponse(topEntities);
                    } else if (expirationEpochMs < clock.millis()) {
                        if (topEntities.isEmpty()) {
                            listener.onFailure(new TimeSeriesException("timeout to get preview results.  Please retry later."));
                        } else {
                            logger.info("timeout to get preview results. Send whatever we have.");
                            listener.onResponse(topEntities);
                        }
                    } else {
                        updateSourceAfterKey(afterKey, searchSourceBuilder);
                        // using the original context in listener as user roles have no permissions for internal operations like fetching a
                        // checkpoint
                        clientUtil
                            .<SearchRequest, SearchResponse>asyncRequestWithInjectedSecurity(
                                new SearchRequest().indices(detector.getIndices().toArray(new String[0])).source(searchSourceBuilder),
                                client::search,
                                detector.getId(),
                                client,
                                AnalysisType.AD,
                                this
                            );
                    }
                }
            } catch (Exception e) {
                onFailure(e);
            }
        }

        @Override
        public void onFailure(Exception e) {
            logger.error("Fail to paginate", e);
            listener.onFailure(e);
        }
    }

    /**
     * Get the entity's earliest timestamps
     * @param config analysis config
     * @param entity the entity's information
     * @param listener listener to return back the requested timestamps
     */
    public void getMinDataTime(Config config, Optional<Entity> entity, AnalysisType context, ActionListener<Optional<Long>> listener) {
        BoolQueryBuilder internalFilterQuery = QueryBuilders.boolQuery();

        if (entity.isPresent()) {
            for (TermQueryBuilder term : entity.get().getTermQueryForCustomerIndex()) {
                internalFilterQuery.filter(term);
            }
        }

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
            .query(internalFilterQuery)
            .aggregation(AggregationBuilders.min(AGG_NAME_MIN).field(config.getTimeField()))
            .trackTotalHits(false)
            .size(0);
        SearchRequest searchRequest = new SearchRequest().indices(config.getIndices().toArray(new String[0])).source(searchSourceBuilder);
        final ActionListener<SearchResponse> searchResponseListener = ActionListener.wrap(response -> {
            listener.onResponse(parseMinDataTime(response));
        }, listener::onFailure);
        // inject user role while searching.
        clientUtil
            .<SearchRequest, SearchResponse>asyncRequestWithInjectedSecurity(
                searchRequest,
                client::search,
                config.getId(),
                client,
                context,
                searchResponseListener
            );
    }

    private Optional<Long> parseMinDataTime(SearchResponse searchResponse) {
        Optional<Map<String, Aggregation>> mapOptional = Optional
            .ofNullable(searchResponse)
            .map(SearchResponse::getAggregations)
            .map(aggs -> aggs.asMap());

        return mapOptional.map(map -> (Min) map.get(AGG_NAME_MIN)).map(agg -> (long) agg.getValue());
    }

    /**
     * Returns to listener features for the given time period.
     *
     * @param detector info about indices, feature query
     * @param startTime epoch milliseconds at the beginning of the period
     * @param endTime epoch milliseconds at the end of the period
     * @param listener onResponse is called with features for the given time period.
     */
    public void getFeaturesForPeriod(AnomalyDetector detector, long startTime, long endTime, ActionListener<Optional<double[]>> listener) {
        SearchRequest searchRequest = createFeatureSearchRequest(detector, startTime, endTime, Optional.empty());
        final ActionListener<SearchResponse> searchResponseListener = ActionListener
            .wrap(response -> listener.onResponse(parseResponse(response, detector.getEnabledFeatureIds(), true)), listener::onFailure);
        // using the original context in listener as user roles have no permissions for internal operations like fetching a
        // checkpoint
        clientUtil
            .<SearchRequest, SearchResponse>asyncRequestWithInjectedSecurity(
                searchRequest,
                client::search,
                detector.getId(),
                client,
                AnalysisType.AD,
                searchResponseListener
            );
    }

    public void getFeaturesForPeriodByBatch(
        AnomalyDetector detector,
        Entity entity,
        long startTime,
        long endTime,
        ActionListener<Map<Long, Optional<double[]>>> listener
    ) throws IOException {
        SearchSourceBuilder searchSourceBuilder = batchFeatureQuery(detector, entity, startTime, endTime, xContent);
        logger.debug("Batch query for detector {}: {} ", detector.getId(), searchSourceBuilder);

        SearchRequest searchRequest = new SearchRequest(detector.getIndices().toArray(new String[0])).source(searchSourceBuilder);
        final ActionListener<SearchResponse> searchResponseListener = ActionListener.wrap(response -> {
            listener.onResponse(parseBucketAggregationResponse(response, detector.getEnabledFeatureIds(), true));
        }, listener::onFailure);
        // inject user role while searching.
        clientUtil
            .<SearchRequest, SearchResponse>asyncRequestWithInjectedSecurity(
                searchRequest,
                client::search,
                detector.getId(),
                client,
                AnalysisType.AD,
                searchResponseListener
            );
    }

    private Map<Long, Optional<double[]>> parseBucketAggregationResponse(
        SearchResponse response,
        List<String> featureIds,
        boolean keepMissingValue
    ) {
        Map<Long, Optional<double[]>> dataPoints = new HashMap<>();
        List<Aggregation> aggregations = response.getAggregations().asList();
        logger.debug("Feature aggregation result size {}", aggregations.size());
        for (Aggregation agg : aggregations) {
            List<InternalComposite.InternalBucket> buckets = ((InternalComposite) agg).getBuckets();
            buckets.forEach(bucket -> {
                Optional<double[]> featureData = parseAggregations(
                    Optional.ofNullable(bucket.getAggregations()),
                    featureIds,
                    keepMissingValue
                );
                dataPoints.put((Long) bucket.getKey().get(CommonName.DATE_HISTOGRAM), featureData);
            });
        }
        return dataPoints;
    }

    public Optional<double[]> parseResponse(SearchResponse response, List<String> featureIds, boolean keepMissingData) {
        return parseAggregations(Optional.ofNullable(response).map(resp -> resp.getAggregations()), featureIds, keepMissingData);
    }

    /**
     * Gets features for the time ranges.
     *
     * If called by preview API, sampled features are not true features.
     * They are intended to be approximate results produced at low costs.
     *
     * @param config info about the indices, documents, feature query
     * @param ranges list of time ranges
     * @param keepMissingValues whether to keep missing values or not in the result
     * @param listener handle approximate features for the time ranges
     * @throws IOException if a user gives wrong query input when defining a detector
     */
    public void getFeatureSamplesForPeriods(
        Config config,
        List<Entry<Long, Long>> ranges,
        AnalysisType context,
        boolean keepMissingValues,
        ActionListener<List<Optional<double[]>>> listener
    ) throws IOException {
        SearchRequest request = createRangeSearchRequest(config, ranges);
        final ActionListener<SearchResponse> searchResponseListener = ActionListener.wrap(response -> {
            Aggregations aggs = response.getAggregations();
            if (aggs == null) {
                listener.onResponse(Collections.emptyList());
                return;
            }
            listener
                .onResponse(
                    aggs
                        .asList()
                        .stream()
                        .filter(InternalDateRange.class::isInstance)
                        .flatMap(agg -> ((InternalDateRange) agg).getBuckets().stream())
                        .map(bucket -> parseBucket(bucket, config.getEnabledFeatureIds(), keepMissingValues))
                        .collect(Collectors.toList())
                );
        }, listener::onFailure);
        // inject user role while searching
        clientUtil
            .<SearchRequest, SearchResponse>asyncRequestWithInjectedSecurity(
                request,
                client::search,
                config.getId(),
                client,
                context,
                searchResponseListener
            );
    }

    private SearchRequest createFeatureSearchRequest(AnomalyDetector detector, long startTime, long endTime, Optional<String> preference) {
        // TODO: FeatureQuery field is planned to be removed and search request creation will migrate to new api.
        try {
            SearchSourceBuilder searchSourceBuilder = ParseUtils.generateInternalFeatureQuery(detector, startTime, endTime, xContent);
            return new SearchRequest(detector.getIndices().toArray(new String[0]), searchSourceBuilder).preference(preference.orElse(null));
        } catch (IOException e) {
            logger.warn("Failed to create feature search request for " + detector.getId() + " from " + startTime + " to " + endTime, e);
            throw new IllegalStateException(e);
        }
    }

    private SearchRequest createRangeSearchRequest(Config config, List<Entry<Long, Long>> ranges) throws IOException {
        try {
            SearchSourceBuilder searchSourceBuilder = ParseUtils.generateRangeQuery(config, ranges, xContent);
            return new SearchRequest(config.getIndices().toArray(new String[0]), searchSourceBuilder);
        } catch (IOException e) {
            logger.warn("Failed to create feature search request for " + config.getId() + " for preview", e);
            throw e;
        }
    }

    /**
     * Retrieves cold start samples for specified time periods from Elasticsearch. This method is designed
     * to support anomaly detection by fetching historical data that can be used to initialize models
     * for new entities or periods without sufficient data.
     *
     * @param config The configuration object that contains settings and parameters used for the search.
     * @param ranges A list of time ranges (start and end timestamps) for which to fetch the samples.
     * @param entity An {@link Optional} containing the {@link Entity} for which to fetch samples.
     *        If the entity is not present, samples are fetched without filtering by entity.
     * @param includesEmptyBucket A boolean flag indicating whether to include time periods with no data.
     *        If true, periods without data will be included in the response with a default value.
     * @param context The {@link AnalysisType} indicating the context of the analysis, which may affect
     *        how samples are processed and returned.
     * @param listener An {@link ActionListener} that handles the response containing a list of
     *        {@link Optional} arrays of doubles, representing the sample data for each requested period.
     *        The listener also handles failure scenarios. If there is no data in a bucket, the optional
     *        is empty.
     *
     * <p>The method constructs a search request based on the provided parameters, executes the search,
     * and processes the response to extract and format the relevant sample data. The resulting list
     * of samples, ordered by time, is passed to the {@code listener} on successful retrieval.
     *
     * <p>In cases where the OpenSearch aggregations return null (e.g., no data matches the query),
     * the method responds with an empty list. This method also applies a document count threshold
     * to filter out buckets with insignificant data, based on the {@code includesEmptyBucket} parameter.
     *
     * <p>It's important to note that this method assumes ascending order for the date range bucket
     * aggregation results by default and treats the {@code config.getEnabledFeatureIds()} to parse
     * and format each bucket's data into the expected double array format.
     */
    public void getColdStartSamplesForPeriods(
        Config config,
        List<Entry<Long, Long>> ranges,
        Optional<Entity> entity,
        boolean includesEmptyBucket,
        AnalysisType context,
        ActionListener<List<Optional<double[]>>> listener
    ) {
        SearchRequest request = createColdStartFeatureSearchRequest(config, ranges, entity);
        final ActionListener<SearchResponse> searchResponseListener = ActionListener.wrap(response -> {
            listener.onResponse(parseColdStartSampleResp(response, includesEmptyBucket, config));
        }, listener::onFailure);

        // inject user role while searching.
        clientUtil
            .<SearchRequest, SearchResponse>asyncRequestWithInjectedSecurity(
                request,
                client::search,
                config.getId(),
                client,
                context,
                searchResponseListener
            );
    }

    /**
     * Parses the response from a search query for cold start samples, extracting and processing
     * the relevant buckets to obtain their parsed values.
     *
     * This method processes the aggregations from the provided search response to extract and sort the
     * buckets. Only buckets that meet certain criteria are included:
     * - The `from` field must be a non-null instance of `ZonedDateTime`.
     * - The bucket's document count must be greater than the specified threshold, which is determined
     *   by the `includesEmptyBucket` parameter.
     *
     * The method returns a list of Optional double array containing the parsed values of the buckets.
     * Buckets for which the parseBucket method returns Optional.empty() are excluded from the final list.
     *
     * @param response the search response containing the aggregations
     * @param includesEmptyBucket a boolean flag indicating whether to include buckets with a document
     *        count of zero (if true, the threshold is set to -1; otherwise, it is set to 0)
     * @param config the configuration object containing necessary settings and feature IDs
     * @return a list of Optional double array containing the parsed values of the valid buckets, or an empty
     *         list if the aggregations are null or no valid buckets are found
     */
    public List<Optional<double[]>> parseColdStartSampleResp(SearchResponse response, boolean includesEmptyBucket, Config config) {
        Aggregations aggs = response.getAggregations();
        if (aggs == null) {
            logger.warn("Unexpected empty response");
            return Collections.emptyList();
        }

        long docCountThreshold = includesEmptyBucket ? -1 : 0;

        // Extract buckets and order by from_as_string. Currently by default it is ascending. Better not to assume it.
        // Example responses from date range bucket aggregation:
        // "aggregations":{"date_range":{"buckets":[{"key":"1598865166000-1598865226000","from":1.598865166E12,"
        // from_as_string":"1598865166000","to":1.598865226E12,"to_as_string":"1598865226000","doc_count":3,
        // "deny_max":{"value":154.0}},{"key":"1598869006000-1598869066000","from":1.598869006E12,
        // "from_as_string":"1598869006000","to":1.598869066E12,"to_as_string":"1598869066000","doc_count":3,
        // "deny_max":{"value":141.0}},
        // We don't want to use default 0 for sum/count aggregation as it might cause false positives during scoring.
        // Terms aggregation only returns non-zero count values. If we use a lot of 0s during cold start,
        // we will see alarming very easily.
        return aggs
            .asList()
            .stream()
            .filter(InternalDateRange.class::isInstance)
            .flatMap(agg -> ((InternalDateRange) agg).getBuckets().stream())
            .filter(bucket -> bucket.getFrom() != null && bucket.getFrom() instanceof ZonedDateTime)
            .filter(bucket -> bucket.getDocCount() > docCountThreshold)
            .sorted(Comparator.comparing((Bucket bucket) -> (ZonedDateTime) bucket.getFrom()))
            .map(bucket -> parseBucket(bucket, config.getEnabledFeatureIds(), false))
            .collect(Collectors.toList());
    }

    /**
     * Parses the timestamps of the buckets from a search response for cold start samples.
     *
     * This method processes the aggregations from the provided search response to extract and sort the
     * timestamps of the buckets. Only buckets that meet certain criteria are included:
     * - The `from` field must be a non-null instance of `ZonedDateTime`.
     * - The bucket's document count must be greater than the specified threshold, which is determined
     *   by the `includesEmptyBucket` parameter.
     * - The bucket must have a non-empty result from the `parseBucket` method.
     *
     * The method returns a list of epoch millisecond timestamps of the `from` fields of the valid buckets.
     *
     * @param response the search response containing the aggregations
     * @param includesEmptyBucket a boolean flag indicating whether to include buckets with a document
     *        count of zero (if true, the threshold is set to -1; otherwise, it is set to 0)
     * @param config the configuration object containing feature enabled information
     * @return a list of epoch millisecond timestamps of the valid bucket `from` fields, or an empty list
     *         if the aggregations are null or no valid buckets are found
     */
    public List<Long> parseColdStartSampleTimestamp(SearchResponse response, boolean includesEmptyBucket, Config config) {
        Aggregations aggs = response.getAggregations();
        if (aggs == null) {
            logger.warn("Unexpected empty response");
            return Collections.emptyList();
        }

        long docCountThreshold = includesEmptyBucket ? -1 : 0;

        // Extract buckets and order by from_as_string. Currently by default it is ascending. Better not to assume it.
        // Example responses from date range bucket aggregation:
        // "aggregations":{"date_range":{"buckets":[{"key":"1598865166000-1598865226000","from":1.598865166E12,"
        // from_as_string":"1598865166000","to":1.598865226E12,"to_as_string":"1598865226000","doc_count":3,
        // "deny_max":{"value":154.0}},{"key":"1598869006000-1598869066000","from":1.598869006E12,
        // "from_as_string":"1598869006000","to":1.598869066E12,"to_as_string":"1598869066000","doc_count":3,
        // "deny_max":{"value":141.0}},
        // We don't want to use default 0 for sum/count aggregation as it might cause false positives during scoring.
        // Terms aggregation only returns non-zero count values. If we use a lot of 0s during cold start,
        // we will see alarming very easily.
        return aggs
            .asList()
            .stream()
            .filter(InternalDateRange.class::isInstance)
            .flatMap(agg -> ((InternalDateRange) agg).getBuckets().stream())
            .filter(bucket -> bucket.getFrom() != null && bucket.getFrom() instanceof ZonedDateTime)
            .filter(bucket -> bucket.getDocCount() > docCountThreshold)
            .filter(bucket -> parseBucket(bucket, config.getEnabledFeatureIds(), false).isPresent())
            .sorted(Comparator.comparing((Bucket bucket) -> (ZonedDateTime) bucket.getFrom()))
            .map(bucket -> ((ZonedDateTime) bucket.getFrom()).toInstant().toEpochMilli())
            .collect(Collectors.toList());
    }

    public SearchRequest createColdStartFeatureSearchRequest(Config detector, List<Entry<Long, Long>> ranges, Optional<Entity> entity) {
        try {
            SearchSourceBuilder searchSourceBuilder = ParseUtils.generateColdStartQuery(detector, ranges, entity, xContent);
            return new SearchRequest(detector.getIndices().toArray(new String[0]), searchSourceBuilder);
        } catch (IOException e) {
            logger
                .warn(
                    "Failed to create cold start feature search request for "
                        + detector.getId()
                        + " from "
                        + ranges.get(0).getKey()
                        + " to "
                        + ranges.get(ranges.size() - 1).getKey(),
                    e
                );
            throw new IllegalStateException(e);
        }
    }

    public SearchRequest createColdStartFeatureSearchRequestForSingleFeature(
        Config detector,
        List<Entry<Long, Long>> ranges,
        Optional<Entity> entity,
        int featureIndex
    ) {
        try {
            SearchSourceBuilder searchSourceBuilder = ParseUtils
                .generateColdStartQueryForSingleFeature(detector, ranges, entity, xContent, featureIndex);
            return new SearchRequest(detector.getIndices().toArray(new String[0]), searchSourceBuilder);
        } catch (IOException e) {
            logger
                .warn(
                    "Failed to create cold start feature search request for "
                        + detector.getId()
                        + " from "
                        + ranges.get(0).getKey()
                        + " to "
                        + ranges.get(ranges.size() - 1).getKey(),
                    e
                );
            throw new IllegalStateException(e);
        }
    }

    /**
     * Get train samples within a time range.
     *
     * @param interval interval to compute ranges
     * @param startMilli range start
     * @param endMilli range end
     * @param numberOfSamples maximum training samples to fetch
     * @return list of sample time ranges in ascending order
     */
    public List<Entry<Long, Long>> getTrainSampleRanges(
        IntervalTimeConfiguration interval,
        long startMilli,
        long endMilli,
        int numberOfSamples
    ) {
        long bucketSize = interval.toDuration().toMillis();
        int numBuckets = (int) Math.floor((endMilli - startMilli) / (double) bucketSize);
        // adjust if numStrides is more than the max samples
        int numIntervals = Math.min(numBuckets, numberOfSamples);
        List<Entry<Long, Long>> sampleRanges = Stream
            .iterate(endMilli, i -> i - bucketSize)
            .limit(numIntervals)
            .map(time -> new SimpleImmutableEntry<>(time - bucketSize, time))
            .collect(Collectors.toList());

        // Reverse the list to get time ranges in ascending order
        Collections.reverse(sampleRanges);

        return sampleRanges;
    }
}
