/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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
package org.xbib.elasticsearch.support;

import org.elasticsearch.ElasticSearchTimeoutException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.settings.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.status.IndicesStatusRequest;
import org.elasticsearch.action.admin.indices.status.IndicesStatusResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.indices.IndexAlreadyExistsException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

/**
 * Transport client support
 *
 * @author <a href="mailto:joergprante@gmail.com">J&ouml;rg Prante</a>
 */
public abstract class TransportClientSupport {

    private final static ESLogger logger = Loggers.getLogger(TransportClientSupport.class);

    // the default cluster name
    private final static String DEFAULT_CLUSTER_NAME = "elasticsearch";
    // the default connection specification
    private final static URI DEFAULT_URI = URI.create("es://hostname:9300");
    // the transport addresses
    private final Set<InetSocketTransportAddress> addresses = new HashSet();
    // singleton
    protected TransportClient client;
    // the settings
    protected Settings settings;
    // if the client has connectd nodes or not
    private boolean connected;

    /**
     * Optional settings
     */
    private ImmutableSettings.Builder settingsBuilder;
    /**
     * An optional mapping
     */
    private String mapping;

    private boolean dateDetection = false;

    private boolean timeStampFieldEnabled = true;

    private String timeStampField = "@timestamp";

    public abstract String getIndex();

    public abstract String getType();

    protected abstract Settings initialSettings(URI uri, int poolsize);

    public TransportClientSupport newClient() {
        return newClient(findURI());
    }

    public synchronized TransportClientSupport newClient(URI uri) {
        return newClient(uri, Runtime.getRuntime().availableProcessors() * 4);
    }

    public synchronized TransportClientSupport newClient(URI uri, int poolsize) {
        if (client != null) {
            client.close();
            client = null;
        }
        if (client == null) {
            this.connected = false;
            this.settings = initialSettings(uri, poolsize);
            logger.info("settings={}", settings.getAsMap());
            this.client = new TransportClient(settings);
            try {
                connect(uri);
                connected = !client.connectedNodes().isEmpty();
            } catch (UnknownHostException e) {
                logger.error(e.getMessage(), e);
            } catch (SocketException e) {
                logger.error(e.getMessage(), e);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        return this;
    }

    public Client client() {
        return client;
    }

    public boolean isConnected() {
        return connected;
    }

    public synchronized void shutdown() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (addresses != null) {
            addresses.clear();
        }
    }

    protected static URI findURI() {
        URI uri = DEFAULT_URI;
        String hostname = "localhost";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
            logger.debug("the hostname is {}", hostname);
            URL url = TransportClientSupport.class.getResource("/org/xbib/elasticsearch/cluster.properties");
            if (url != null) {
                InputStream in = url.openStream();
                Properties p = new Properties();
                p.load(in);
                in.close();
                // the properties contains default URIs per hostname
                if (p.containsKey(hostname)) {
                    uri = URI.create(p.getProperty(hostname));
                    logger.debug("URI found in cluster.properties for hostname {}: {}", hostname, uri);
                    return uri;
                }
            }
        } catch (UnknownHostException e) {
            logger.warn("can't resolve host name, probably something wrong with network config: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        logger.debug("URI for hostname {}: {}", hostname, uri);
        return uri;
    }

    protected String findClusterName(URI uri) {
        String clustername;
        try {
            // look for URI parameters
            Map<String, String> map = parseQueryString(uri, "UTF-8");
            clustername = map.get("es.cluster.name");
            if (clustername != null) {
                logger.info("cluster name found in URI {}: {}", uri, clustername);
                return clustername;
            }
            clustername = map.get("cluster.name");
            if (clustername != null) {
                logger.info("cluster name found in URI {}: {}", uri, clustername);
                return clustername;
            }
        } catch (UnsupportedEncodingException ex) {
            logger.warn(ex.getMessage(), ex);
        }
        logger.info("cluster name not found in URI {}, parameter es.cluster.name", uri);
        clustername = System.getProperty("es.cluster.name");
        if (clustername != null) {
            logger.info("cluster name found in es.cluster.name system property: {}", clustername);
            return clustername;
        }
        clustername = System.getProperty("cluster.name");
        if (clustername != null) {
            logger.info("cluster name found in cluster.name system property: {}", clustername);
            return clustername;
        }
        logger.info("cluster name not found, falling back to default: {}", DEFAULT_CLUSTER_NAME);
        clustername = DEFAULT_CLUSTER_NAME;
        return clustername;
    }

    protected void connect(URI uri) throws IOException {
        String hostname = uri.getHost();
        int port = uri.getPort();
        boolean newaddresses = false;
        if (!"es".equals(uri.getScheme())) {
            logger.warn("please specify URI scheme 'es'");
        }
        if ("hostname".equals(hostname)) {
            InetSocketTransportAddress address = new InetSocketTransportAddress(InetAddress.getLocalHost().getHostName(), port);
            if (!addresses.contains(address)) {
                logger.info("adding hostname address for transport client: {}", address);
                client.addTransportAddress(address);
                addresses.add(address);
                newaddresses = true;
            }
        } else if ("interfaces".equals(hostname)) {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                logger.info("checking network interface = {}", netint.getName());
                Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
                for (InetAddress addr : Collections.list(inetAddresses)) {
                     logger.info("checking address = {}", addr.getHostAddress());
                     InetSocketTransportAddress address = new InetSocketTransportAddress(addr, port);
                     if (!addresses.contains(address)) {
                         logger.info("adding address to transport client: {}", address);
                         client.addTransportAddress(address);
                         addresses.add(address);
                         newaddresses = true;
                     }
                }
            }
        } else if ("inet4".equals(hostname)) {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                logger.info("checking network interface = {}", netint.getName());
                Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
                for (InetAddress addr : Collections.list(inetAddresses)) {
                    if (addr instanceof Inet4Address) {
                        logger.info("checking address = {}", addr.getHostAddress());
                        InetSocketTransportAddress address = new InetSocketTransportAddress(addr, port);
                        if (!addresses.contains(address)) {
                            logger.info("adding address for transport client: {}", address);
                            client.addTransportAddress(address);
                            addresses.add(address);
                            newaddresses = true;
                        }
                    }
                }
            }
        } else if ("inet6".equals(hostname)) {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                logger.info("checking network interface = {}", netint.getName());
                Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
                for (InetAddress addr : Collections.list(inetAddresses)) {
                    if (addr instanceof Inet6Address) {
                        logger.info("checking address = {}", addr.getHostAddress());
                        InetSocketTransportAddress address = new InetSocketTransportAddress(addr, port);
                        if (!addresses.contains(address)) {
                            logger.info("adding address for transport client: {}", address);
                            client.addTransportAddress(address);
                            addresses.add(address);
                            newaddresses = true;
                        }
                    }
                }
            }
        } else {
            InetSocketTransportAddress address = new InetSocketTransportAddress(hostname, port);
            if (!addresses.contains(address)) {
                logger.info("adding custom address for transport client: {}", address);
                client.addTransportAddress(address);
                addresses.add(address);
                newaddresses = true;
            }
        }
        logger.info("configured addresses to connect: {}", addresses);
        if (newaddresses) {
            List<DiscoveryNode> nodes = client.connectedNodes().asList();
            logger.info("connected nodes = {}", nodes);
            for (DiscoveryNode node : nodes) {
                logger.info("new connection to {} {}", node.getId(), node.getName());
            }
        }
    }

    public TransportClientSupport waitForHealthyCluster() throws IOException {
        return waitForHealthyCluster(ClusterHealthStatus.YELLOW, TimeValue.timeValueSeconds(30));
    }

    public TransportClientSupport waitForHealthyCluster(ClusterHealthStatus status, TimeValue timeout) throws IOException {
        try {
            logger.info("waiting for cluster health...");
            ClusterHealthResponse healthResponse =
                    client.admin().cluster().prepareHealth().setWaitForStatus(status).setTimeout(timeout).execute().actionGet();
            if (healthResponse.isTimedOut()) {
                throw new IOException("cluster not healthy, cowardly refusing to continue with operations: " + status.name());
            } else {
                logger.info("... cluster is healthy");
            }
        } catch (ElasticSearchTimeoutException e) {
            throw new IOException("timeout, cluster does not respond to health request, cowardly refusing to continue with operations");
        }
        return this;
    }

    public int waitForRecovery() {
        if (getIndex() == null) {
            return -1;
        }
        IndicesStatusResponse response = client.admin().indices()
                .status(new IndicesStatusRequest(getIndex()).recovery(true)).actionGet();
        return response.getTotalShards();
    }

    public TransportClientSupport shards(int shards) {
        return setting("index.number_of_shards", shards);
    }

    public TransportClientSupport replica(int replica) {
        return setting("index.number_of_replicas", replica);
    }

    public TransportClientSupport setting(String key, String value) {
        if (settingsBuilder == null) {
            settingsBuilder = ImmutableSettings.settingsBuilder();
        }
        settingsBuilder.put(key, value);
        return this;
    }

    public TransportClientSupport setting(String key, Boolean value) {
        if (settingsBuilder == null) {
            settingsBuilder = ImmutableSettings.settingsBuilder();
        }
        settingsBuilder.put(key, value);
        return this;
    }

    public TransportClientSupport setting(String key, Integer value) {
        if (settingsBuilder == null) {
            settingsBuilder = ImmutableSettings.settingsBuilder();
        }
        settingsBuilder.put(key, value);
        return this;
    }

    public ImmutableSettings.Builder settings() {
        return settingsBuilder != null ? settingsBuilder : null;
    }

    public TransportClientSupport dateDetection(boolean dateDetection) {
        this.dateDetection = dateDetection;
        return this;
    }

    public boolean dateDetection() {
        return dateDetection;
    }

    public TransportClientSupport timeStampField(String timeStampField) {
        this.timeStampField = timeStampField;
        return this;
    }

    public String timeStampField() {
        return timeStampField;
    }

    public TransportClientSupport mapping(String mapping) {
        this.mapping = mapping;
        return this;
    }

    public String mapping() {
        return mapping;
    }

    public String defaultMapping() {
        try {
            XContentBuilder b =
                    jsonBuilder()
                            .startObject()
                            .startObject("_default_")
                            .field("date_detection", dateDetection)
                            .startObject("_timestamp")
                            .field("enabled", timeStampFieldEnabled)
                            .field("path", timeStampField)
                            .endObject()
                            .startObject("properties")
                            .startObject("@fields")
                            .field("type", "object")
                            .field("dynamic", true)
                            .field("path", "full")
                            .endObject()
                            .startObject("@message")
                            .field("type", "string")
                            .field("index", "analyzed")
                            .endObject()
                            .startObject("@source")
                            .field("type", "string")
                            .field("index", "not_analyzed")
                            .endObject()
                            .startObject("@source_host")
                            .field("type", "string")
                            .field("index", "not_analyzed")
                            .endObject()
                            .startObject("@source_path")
                            .field("type", "string")
                            .field("index", "not_analyzed")
                            .endObject()
                            .startObject("@tags")
                            .field("type", "string")
                            .field("index", "not_analyzed")
                            .endObject()
                            .startObject("@timestamp")
                            .field("type", "date")
                            .endObject()
                            .startObject("@type")
                            .field("type", "string")
                            .field("index", "not_analyzed")
                            .endObject()
                            .endObject()
                            .endObject()
                            .endObject();
            return b.string();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return null;
    }

    protected TransportClientSupport enableRefreshInterval() {
        update("refresh_interval", 1000);
        return this;
    }

    protected TransportClientSupport disableRefreshInterval() {
        update("refresh_interval", -1);
        return this;
    }

    public synchronized TransportClientSupport newIndex(boolean ignoreException) {
        if (client == null) {
            logger.warn("no client");
            return this;
        }
        if (getIndex() == null) {
            logger.warn("no index name given to create");
            return this;
        }
        if (getType() == null) {
            logger.warn("no type name given to create");
            return this;
        }
        CreateIndexRequest request = new CreateIndexRequest(getIndex());
        if (settings() != null) {
            request.settings(settings());
        }
        if (mapping() == null) {
            mapping(defaultMapping());
        }
        request.mapping(getType(), mapping());
        logger.info("creating index = {} type = {} settings = {} mapping = {}",
                getIndex(),
                getType(),
                settings() != null ? settings().build().getAsMap() : "",
                mapping());
        try {
            client.admin().indices().create(request).actionGet();
        } catch (IndexAlreadyExistsException e) {
            if (!ignoreException) {
                throw new RuntimeException(e);
            }
        }
        return this;
    }

    protected void update(String key, Object value) {
        if (client == null) {
            return;
        }
        if (value == null) {
            return;
        }
        if (getIndex() == null) {
            return;
        }
        ImmutableSettings.Builder settingsBuilder = ImmutableSettings.settingsBuilder();
        settingsBuilder.put(key, value.toString());
        UpdateSettingsRequest updateSettingsRequest = new UpdateSettingsRequest(getIndex())
                .settings(settingsBuilder);
        client.admin().indices().updateSettings(updateSettingsRequest).actionGet();
    }

    private Map<String, String> parseQueryString(URI uri, String encoding)
            throws UnsupportedEncodingException {
        Map<String, String> m = new HashMap<String, String>();
        if (uri == null) {
            throw new IllegalArgumentException();
        }
        if (uri.getRawQuery() == null) {
            return m;
        }
        // use getRawQuery because we do our decoding by ourselves
        StringTokenizer st = new StringTokenizer(uri.getRawQuery(), "&");
        while (st.hasMoreTokens()) {
            String pair = st.nextToken();
            int pos = pair.indexOf('=');
            if (pos < 0) {
                m.put(pair, null);
            } else {
                m.put(pair.substring(0, pos), decode(pair.substring(pos + 1, pair.length()), encoding));
            }
        }
        return m;
    }

    private String decode(String s, String encoding) {
        StringBuilder sb = new StringBuilder();
        boolean fragment = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '+':
                    sb.append(' ');
                    break;
                case '#':
                    sb.append(ch);
                    fragment = true;
                    break;
                case '%':
                    if (!fragment) {
                        // fast hex decode
                        sb.append((char) ((Character.digit(s.charAt(++i), 16) << 4)
                                | Character.digit(s.charAt(++i), 16)));
                    } else {
                        sb.append(ch);
                    }
                    break;
                default:
                    sb.append(ch);
                    break;
            }
        }
        try {
            // URL default encoding is ISO-8859-1
            return new String(sb.toString().getBytes("ISO-8859-1"), encoding);
        } catch (UnsupportedEncodingException e) {
            throw new Error("encoding " + encoding + " not supported");
        }
    }
}