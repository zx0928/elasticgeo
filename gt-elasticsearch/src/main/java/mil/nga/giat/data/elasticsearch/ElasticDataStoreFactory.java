/**
 * This file is hereby placed into the Public Domain. This means anyone is
 * free to do whatever they wish with this file.
 */
package mil.nga.giat.data.elasticsearch;

import org.apache.http.HttpHost;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFactorySpi;

import mil.nga.giat.data.elasticsearch.ElasticDataStore.ArrayEncoding;

import java.awt.RenderingHints.Key;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Data store factory that creates {@linkplain ElasticDataStore} instances.
 *
 */
public class ElasticDataStoreFactory implements DataStoreFactorySpi {

    /** Cluster hostname. **/
    public static final Param HOSTNAME = new Param("elasticsearch_host", String.class, "Elasticsearch host", false, "localhost");

    /** Cluster client port. **/
    public static final Param HOSTPORT = new Param("elasticsearch_port", Integer.class, 
            "Elasticsearch HTTP port", false, 9200);

    public static final Param SSL_ENABLED = new Param("ssl_enabled", Boolean.class, "Enable SSL. Use system properties to configure SSL. For example \"javax.net.ssl.trustStore\", "
            + "\"javax.net.ssl.trustStorePassword\", etc..", false, false);

    public static final Param SSL_REJECT_UNAUTHORIZED = new Param("ssl_reject_unauthorized", Boolean.class, "Whether to validate server certificate (ignored if ssl_enabled=false)", false, false);

    /** Index name. **/
    public static final Param INDEX_NAME = new Param("index_name", String.class, "Index defining type", true);

    public static final Param DEFAULT_MAX_FEATURES = new Param("default_max_features", Integer.class, "Default max features", false, 100);

    public static final Param SOURCE_FILTERING_ENABLED = new Param("source_filtering_enabled", Boolean.class, "Enable source field filtering", false, false);

    public static final Param SCROLL_ENABLED = new Param("scroll_enabled", Boolean.class, "Use scan search type instead of dfs_query_then_fetch", false, false);

    public static final Param SCROLL_SIZE = new Param("scroll_size", Long.class, "Scroll size (ignored if scroll_enabled=false)", false, 20);

    public static final Param SCROLL_TIME_SECONDS = new Param("scroll_time", Integer.class, "Time to keep the scroll open in seconds (ignored if scroll_enabled=false)", false, 120);

    public static final Param ARRAY_ENCODING = new Param("array_encoding", String.class, "Array encoding strategy. Allowed values are \"JSON\" (keep arrays) " 
            + " and \"CSV\" (URL encode and join array elements).", false, "JSON");

    public static final Param GRID_SIZE = new Param("grid_size", Long.class, "Hint for Geohash grid size (nrow*ncol)", false, 10000l);

    public static final Param GRID_THRESHOLD = new Param("grid_threshold",  Double.class, 
            "Geohash grid aggregation precision will be the minimum necessary to satisfy actual_grid_size/grid_size>grid_threshold", false, 0.05);

    protected static final Param[] PARAMS = {
            HOSTNAME,
            HOSTPORT,
            SSL_ENABLED,
            SSL_REJECT_UNAUTHORIZED,
            INDEX_NAME,
            DEFAULT_MAX_FEATURES,
            SOURCE_FILTERING_ENABLED,
            SCROLL_ENABLED,
            SCROLL_SIZE,
            SCROLL_TIME_SECONDS,
            ARRAY_ENCODING,
            GRID_SIZE,
            GRID_THRESHOLD
    };

    protected static final String DISPLAY_NAME = "Elasticsearch";

    protected static final String DESCRIPTION = "Elasticsearch Index";

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Param[] getParametersInfo() {
        return PARAMS;
    }

    @Override
    public boolean canProcess(Map<String, Serializable> params) {
        boolean result = false;
        try {
            final String searchHost = (String) HOSTNAME.lookUp(params);
            final String indexName = (String) INDEX_NAME.lookUp(params);
            final Integer hostport = (Integer) HOSTPORT.lookUp(params);

            if (searchHost != null && hostport != null && indexName != null) {
                result = true;
            }
        } catch (IOException e) {
            // ignore
        }
        return result;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Map<Key, ?> getImplementationHints() {
        return null;
    }

    @Override
    public DataStore createDataStore(Map<String, Serializable> params) throws IOException {
        final String searchHost = (String) getValue(HOSTNAME, params);
        final Integer hostPort = (Integer) getValue(HOSTPORT, params);
        final String indexName = (String) INDEX_NAME.lookUp(params);
        final String arrayEncoding = (String) getValue(ARRAY_ENCODING, params);
        final Boolean sslEnabled = (Boolean) getValue(SSL_ENABLED, params);
        final Boolean sslRejectUnauthorized = (Boolean) getValue(SSL_REJECT_UNAUTHORIZED, params);

        final String scheme = sslEnabled ? "https" : "http";
        final RestClientBuilder builder = RestClient.builder(new HttpHost(searchHost, hostPort, scheme));

        if (sslEnabled) {
            builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                @Override
                public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                    httpClientBuilder.useSystemProperties();
                    if (!sslRejectUnauthorized) {
                        httpClientBuilder.setSSLHostnameVerifier((host,session) -> true);
                        try {
                            httpClientBuilder.setSSLContext(SSLContextBuilder.create().loadTrustMaterial((chain,authType) -> true).build());
                        } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException e) {
                            throw new UncheckedIOException(new IOException("Unable to create SSLContext", e));
                        }
                    }
                    return httpClientBuilder;
                }
            });
        }

        final ElasticDataStore dataStore = new ElasticDataStore(builder.build(), indexName);
        dataStore.setDefaultMaxFeatures((Integer) getValue(DEFAULT_MAX_FEATURES, params));
        dataStore.setSourceFilteringEnabled((Boolean) getValue(SOURCE_FILTERING_ENABLED, params));
        dataStore.setScrollEnabled((Boolean)getValue(SCROLL_ENABLED, params));
        dataStore.setScrollSize(((Number)getValue(SCROLL_SIZE, params)).longValue());
        dataStore.setScrollTime((Integer)getValue(SCROLL_TIME_SECONDS, params));
        dataStore.setArrayEncoding(ArrayEncoding.valueOf(arrayEncoding.toUpperCase()));
        dataStore.setGridSize((Long) GRID_SIZE.lookUp(params));
        dataStore.setGridThreshold((Double) GRID_THRESHOLD.lookUp(params));
        return dataStore;
    }

    @Override
    public DataStore createNewDataStore(Map<String, Serializable> params) throws IOException {
        return null;
    }

    private Object getValue(Param param, Map<String, Serializable> params) throws IOException {
        final Object value;
        if (param.lookUp(params) != null) {
            value = param.lookUp(params);
        } else {
            value = param.sample;
        }
        return value;
    }

}
