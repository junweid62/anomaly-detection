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

package org.opensearch.timeseries;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.opensearch.ad.ADUnitTestCase;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.env.Environment;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import io.protostuff.LinkedBuffer;

public class TimeSeriesPluginTests extends ADUnitTestCase {
    TimeSeriesAnalyticsPlugin plugin;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        plugin = new TimeSeriesAnalyticsPlugin();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        plugin.close();
    }

    /**
     * We have legacy setting. TimeSeriesAnalyticsPlugin's createComponents can trigger
     * warning when using these legacy settings.
     */
    @Override
    protected boolean enableWarningsCheck() {
        return false;
    }

    public void testDeserializeRCFBufferPool() throws Exception {
        Settings.Builder settingsBuilder = Settings.builder();
        List<Setting<?>> allSettings = plugin.getSettings();
        for (Setting<?> setting : allSettings) {
            Object defaultVal = setting.getDefault(Settings.EMPTY);
            if (defaultVal instanceof Boolean) {
                settingsBuilder.put(setting.getKey(), (Boolean) defaultVal);
            } else {
                settingsBuilder.put(setting.getKey(), defaultVal.toString());
            }
        }
        Settings settings = settingsBuilder.build();

        Setting<?>[] settingArray = new Setting<?>[allSettings.size()];
        settingArray = allSettings.toArray(settingArray);

        ClusterSettings clusterSettings = clusterSetting(settings, settingArray);
        ClusterService clusterService = new ClusterService(settings, clusterSettings, mock(ThreadPool.class), null);

        Environment environment = mock(Environment.class);
        when(environment.settings()).thenReturn(settings);
        plugin.createComponents(mock(Client.class), clusterService, null, null, null, null, environment, null, null, null, null);
        GenericObjectPool<LinkedBuffer> deserializeRCFBufferPool = plugin.serializeRCFBufferPool;
        deserializeRCFBufferPool.addObject();
        LinkedBuffer buffer = deserializeRCFBufferPool.borrowObject();
        assertTrue(null != buffer);
    }

    public void testOverriddenJobTypeAndIndex() {
        assertEquals("opensearch_time_series_analytics", plugin.getJobType());
        assertEquals(".opendistro-anomaly-detector-jobs", plugin.getJobIndex());
    }

}
