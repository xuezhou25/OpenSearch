/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.geo.search.aggregations.bucket;

import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectIntMap;
import com.carrotsearch.hppc.cursors.ObjectIntCursor;
import org.opensearch.Version;
import org.opensearch.action.index.IndexRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.geo.GeoPoint;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.geo.GeoModulePluginIntegTestCase;
import org.opensearch.geo.search.aggregations.bucket.geogrid.GeoGrid;
import org.opensearch.geo.tests.common.AggregationBuilders;
import org.opensearch.index.query.GeoBoundingBoxQueryBuilder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.bucket.filter.Filter;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.VersionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.opensearch.geometry.utils.Geohash.PRECISION;
import static org.opensearch.geometry.utils.Geohash.stringEncode;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertSearchResponse;

@OpenSearchIntegTestCase.SuiteScopeTestCase
public class GeoHashGridIT extends GeoModulePluginIntegTestCase {

    @Override
    protected boolean forbidPrivateIndexSettings() {
        return false;
    }

    private Version version = VersionUtils.randomIndexCompatibleVersion(random());

    static ObjectIntMap<String> expectedDocCountsForGeoHash = null;
    static ObjectIntMap<String> multiValuedExpectedDocCountsForGeoHash = null;
    static int numDocs = 100;

    static String smallestGeoHash = null;

    private static IndexRequestBuilder indexCity(String index, String name, List<String> latLon) throws Exception {
        XContentBuilder source = jsonBuilder().startObject().field("city", name);
        if (latLon != null) {
            source = source.field("location", latLon);
        }
        source = source.endObject();
        return client().prepareIndex(index).setSource(source);
    }

    private static IndexRequestBuilder indexCity(String index, String name, String latLon) throws Exception {
        return indexCity(index, name, Arrays.<String>asList(latLon));
    }

    @Override
    public void setupSuiteScopeCluster() throws Exception {
        createIndex("idx_unmapped");

        Settings settings = Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, version).build();

        assertAcked(prepareCreate("idx").setSettings(settings).setMapping("location", "type=geo_point", "city", "type=keyword"));

        List<IndexRequestBuilder> cities = new ArrayList<>();
        Random random = random();
        expectedDocCountsForGeoHash = new ObjectIntHashMap<>(numDocs * 2);
        for (int i = 0; i < numDocs; i++) {
            // generate random point
            double lat = (180d * random.nextDouble()) - 90d;
            double lng = (360d * random.nextDouble()) - 180d;
            String randomGeoHash = stringEncode(lng, lat, PRECISION);
            // Index at the highest resolution
            cities.add(indexCity("idx", randomGeoHash, lat + ", " + lng));
            expectedDocCountsForGeoHash.put(randomGeoHash, expectedDocCountsForGeoHash.getOrDefault(randomGeoHash, 0) + 1);
            // Update expected doc counts for all resolutions..
            for (int precision = PRECISION - 1; precision > 0; precision--) {
                String hash = stringEncode(lng, lat, precision);
                if ((smallestGeoHash == null) || (hash.length() < smallestGeoHash.length())) {
                    smallestGeoHash = hash;
                }
                expectedDocCountsForGeoHash.put(hash, expectedDocCountsForGeoHash.getOrDefault(hash, 0) + 1);
            }
        }
        indexRandom(true, cities);

        assertAcked(
            prepareCreate("multi_valued_idx").setSettings(settings).setMapping("location", "type=geo_point", "city", "type=keyword")
        );

        cities = new ArrayList<>();
        multiValuedExpectedDocCountsForGeoHash = new ObjectIntHashMap<>(numDocs * 2);
        for (int i = 0; i < numDocs; i++) {
            final int numPoints = random.nextInt(4);
            List<String> points = new ArrayList<>();
            Set<String> geoHashes = new HashSet<>();
            for (int j = 0; j < numPoints; ++j) {
                double lat = (180d * random.nextDouble()) - 90d;
                double lng = (360d * random.nextDouble()) - 180d;
                points.add(lat + "," + lng);
                // Update expected doc counts for all resolutions..
                for (int precision = PRECISION; precision > 0; precision--) {
                    final String geoHash = stringEncode(lng, lat, precision);
                    geoHashes.add(geoHash);
                }
            }
            cities.add(indexCity("multi_valued_idx", Integer.toString(i), points));
            for (String hash : geoHashes) {
                multiValuedExpectedDocCountsForGeoHash.put(hash, multiValuedExpectedDocCountsForGeoHash.getOrDefault(hash, 0) + 1);
            }
        }
        indexRandom(true, cities);

        ensureSearchable();
    }

    public void testSimple() throws Exception {
        for (int precision = 1; precision <= PRECISION; precision++) {
            SearchResponse response = client().prepareSearch("idx")
                .addAggregation(AggregationBuilders.geohashGrid("geohashgrid").field("location").precision(precision))
                .get();

            assertSearchResponse(response);

            GeoGrid geoGrid = response.getAggregations().get("geohashgrid");
            List<? extends GeoGrid.Bucket> buckets = geoGrid.getBuckets();
            Object[] propertiesKeys = (Object[]) ((InternalAggregation) geoGrid).getProperty("_key");
            Object[] propertiesDocCounts = (Object[]) ((InternalAggregation) geoGrid).getProperty("_count");
            for (int i = 0; i < buckets.size(); i++) {
                GeoGrid.Bucket cell = buckets.get(i);
                String geohash = cell.getKeyAsString();

                long bucketCount = cell.getDocCount();
                int expectedBucketCount = expectedDocCountsForGeoHash.get(geohash);
                assertNotSame(bucketCount, 0);
                assertEquals("Geohash " + geohash + " has wrong doc count ", expectedBucketCount, bucketCount);
                GeoPoint geoPoint = (GeoPoint) propertiesKeys[i];
                assertThat(stringEncode(geoPoint.lon(), geoPoint.lat(), precision), equalTo(geohash));
                assertThat((long) propertiesDocCounts[i], equalTo(bucketCount));
            }
        }
    }

    public void testMultivalued() throws Exception {
        for (int precision = 1; precision <= PRECISION; precision++) {
            SearchResponse response = client().prepareSearch("multi_valued_idx")
                .addAggregation(AggregationBuilders.geohashGrid("geohashgrid").field("location").precision(precision))
                .get();

            assertSearchResponse(response);

            GeoGrid geoGrid = response.getAggregations().get("geohashgrid");
            for (GeoGrid.Bucket cell : geoGrid.getBuckets()) {
                String geohash = cell.getKeyAsString();

                long bucketCount = cell.getDocCount();
                int expectedBucketCount = multiValuedExpectedDocCountsForGeoHash.get(geohash);
                assertNotSame(bucketCount, 0);
                assertEquals("Geohash " + geohash + " has wrong doc count ", expectedBucketCount, bucketCount);
            }
        }
    }

    public void testFiltered() throws Exception {
        GeoBoundingBoxQueryBuilder bbox = new GeoBoundingBoxQueryBuilder("location");
        bbox.setCorners(smallestGeoHash).queryName("bbox");
        for (int precision = 1; precision <= PRECISION; precision++) {
            SearchResponse response = client().prepareSearch("idx")
                .addAggregation(
                    org.opensearch.search.aggregations.AggregationBuilders.filter("filtered", bbox)
                        .subAggregation(AggregationBuilders.geohashGrid("geohashgrid").field("location").precision(precision))
                )
                .get();

            assertSearchResponse(response);

            Filter filter = response.getAggregations().get("filtered");

            GeoGrid geoGrid = filter.getAggregations().get("geohashgrid");
            for (GeoGrid.Bucket cell : geoGrid.getBuckets()) {
                String geohash = cell.getKeyAsString();
                long bucketCount = cell.getDocCount();
                int expectedBucketCount = expectedDocCountsForGeoHash.get(geohash);
                assertNotSame(bucketCount, 0);
                assertTrue("Buckets must be filtered", geohash.startsWith(smallestGeoHash));
                assertEquals("Geohash " + geohash + " has wrong doc count ", expectedBucketCount, bucketCount);

            }
        }
    }

    public void testUnmapped() throws Exception {
        for (int precision = 1; precision <= PRECISION; precision++) {
            SearchResponse response = client().prepareSearch("idx_unmapped")
                .addAggregation(AggregationBuilders.geohashGrid("geohashgrid").field("location").precision(precision))
                .get();

            assertSearchResponse(response);

            GeoGrid geoGrid = response.getAggregations().get("geohashgrid");
            assertThat(geoGrid.getBuckets().size(), equalTo(0));
        }

    }

    public void testPartiallyUnmapped() throws Exception {
        for (int precision = 1; precision <= PRECISION; precision++) {
            SearchResponse response = client().prepareSearch("idx", "idx_unmapped")
                .addAggregation(AggregationBuilders.geohashGrid("geohashgrid").field("location").precision(precision))
                .get();

            assertSearchResponse(response);

            GeoGrid geoGrid = response.getAggregations().get("geohashgrid");
            for (GeoGrid.Bucket cell : geoGrid.getBuckets()) {
                String geohash = cell.getKeyAsString();

                long bucketCount = cell.getDocCount();
                int expectedBucketCount = expectedDocCountsForGeoHash.get(geohash);
                assertNotSame(bucketCount, 0);
                assertEquals("Geohash " + geohash + " has wrong doc count ", expectedBucketCount, bucketCount);
            }
        }
    }

    public void testTopMatch() throws Exception {
        for (int precision = 1; precision <= PRECISION; precision++) {
            SearchResponse response = client().prepareSearch("idx")
                .addAggregation(
                    AggregationBuilders.geohashGrid("geohashgrid").field("location").size(1).shardSize(100).precision(precision)
                )
                .get();

            assertSearchResponse(response);

            GeoGrid geoGrid = response.getAggregations().get("geohashgrid");
            // Check we only have one bucket with the best match for that resolution
            assertThat(geoGrid.getBuckets().size(), equalTo(1));
            for (GeoGrid.Bucket cell : geoGrid.getBuckets()) {
                String geohash = cell.getKeyAsString();
                long bucketCount = cell.getDocCount();
                int expectedBucketCount = 0;
                for (ObjectIntCursor<String> cursor : expectedDocCountsForGeoHash) {
                    if (cursor.key.length() == precision) {
                        expectedBucketCount = Math.max(expectedBucketCount, cursor.value);
                    }
                }
                assertNotSame(bucketCount, 0);
                assertEquals("Geohash " + geohash + " has wrong doc count ", expectedBucketCount, bucketCount);
            }
        }
    }

    public void testSizeIsZero() {
        final int size = 0;
        final int shardSize = 10000;
        IllegalArgumentException exception = expectThrows(
            IllegalArgumentException.class,
            () -> client().prepareSearch("idx")
                .addAggregation(AggregationBuilders.geohashGrid("geohashgrid").field("location").size(size).shardSize(shardSize))
                .get()
        );
        assertThat(exception.getMessage(), containsString("[size] must be greater than 0. Found [0] in [geohashgrid]"));
    }

    public void testShardSizeIsZero() {
        final int size = 100;
        final int shardSize = 0;
        IllegalArgumentException exception = expectThrows(
            IllegalArgumentException.class,
            () -> client().prepareSearch("idx")
                .addAggregation(AggregationBuilders.geohashGrid("geohashgrid").field("location").size(size).shardSize(shardSize))
                .get()
        );
        assertThat(exception.getMessage(), containsString("[shardSize] must be greater than 0. Found [0] in [geohashgrid]"));
    }

}
