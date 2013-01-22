package org.jboss.jdf.example.ticketmonster.rhq;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.Asynchronous;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;

import org.jboss.jdf.example.ticketmonster.model.Booking;

/**
 * Sample class that reports metrics actively into a RHQ server.
 * For this to work, the ticketmonster-rhq-plugin.xml must be deployed
 * in a RHQ server and the rhq.properties file must be filled correctly
 *
 * @author Heiko W. Rupp
 */
@Startup
@Singleton
public class RhqClient {

    private static final String ACCEPT_HTTP_HEADER = "Accept";
    private static final String CONTENT_TYPE_HTTP_HEADER = "Content-Type";
    private static final String JSON_MIME_TYPE = "application/json";


    private boolean reportTo;
    private String rhqUrl;
    private String rhqUser;
    private String rhqPass;
    private boolean initialized = false;
    private ObjectMapper mapper = new ObjectMapper();

    @Inject
    private Logger log;
    private int platformId;
    private int ticketMonsterServerId;

    @PostConstruct
    private void init() {

        Properties props = new Properties();
        InputStream propsStream = getClass().getClassLoader().getResourceAsStream("rhq.properties");
        try {
            props.load(propsStream);
        } catch (IOException e) {
            log.info("Could not load the 'rhq.properties'. Reporting will be disabled");
            reportTo = false;
            initialized = true;
            return;
        }

        String tmp = props.getProperty("rhq.sendto.enabled");
        reportTo = Boolean.valueOf(tmp);
        rhqUrl = props.getProperty("rhq.server.rest.url");
        rhqUser = props.getProperty("rhq.server.user");
        rhqPass = props.getProperty("rhq.server.password");

        if (reportTo) {
            createPlatform();
            createTicketMonsterInstance();
            reportAvailability(ticketMonsterServerId, true);
        }
        initialized=true;
    }


    @PreDestroy
    private void shutdown() {

        System.out.println(" ..##.. shutting down");

        if (reportTo && initialized)
            reportAvailability(ticketMonsterServerId,false);

    }


    @Asynchronous
    public void reportBooking(Booking booking) {

        if (reportTo && initialized) {
            List<Metric> metrics = new ArrayList<Metric>(2);
            Metric m = new Metric("tickets", System.currentTimeMillis(), (double) booking.getTickets().size());
            metrics.add(m);
            m = new Metric("price", System.currentTimeMillis(), (double) booking.getTotalTicketPrice());
            metrics.add(m);

            sendMetrics(metrics, ticketMonsterServerId);
        }
    }


    private void reportAvailability(int resourceId, boolean isUp) {

        ObjectNode op = mapper.createObjectNode();
        op.put("since",System.currentTimeMillis());
        op.put("type",isUp?"UP":"DOWN");
        op.put("until",(JsonNode)null);
        op.put("resourceId",resourceId);

        sendToServer("PUT", "/resource/" + resourceId + "/availability", op);
    }



    private void createTicketMonsterInstance() {
        // create the TM server
        ObjectNode op = mapper.createObjectNode();
        op.put("value","TicketMonster");

        JsonNode node = sendToServer("POST", "/resource/" + "tm" + "?plugin=TicketMonster&parentId=" + platformId, op);

        ticketMonsterServerId = Integer.parseInt(node.get("resourceId").getTextValue());
    }


    private void createPlatform() {

        String hostname = null;
        try {
            hostname = InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            log.fine("Was not able to determine the hostname. Taking localhost. Cause: " + e.getMessage() );
            hostname = "localhost";
        }
        String osType = System.getProperty("os.name"); // TODO convert to RHQ specific types?

        JsonNode node = getPlatform(hostname);
        if (node!=null) {
            platformId = Integer.parseInt(node.get(0).get("resourceId").getTextValue());
        } else {
            node = createPlatform(hostname, osType); // creates a "REST-platform"
            if (node!=null) {
                platformId = Integer.parseInt(node.get("resourceId").getTextValue());
            }
        }
        // we may want to persist the id in the future and retrieve it on startup
        if (platformId==0) {
            log.info("Was not able to contact the RHQ server. Reporting will be disabled");
        }
    }

    private JsonNode createPlatform(String hostname, String osType) {

        ObjectNode op = mapper.createObjectNode();
        op.put("value",osType);
        JsonNode node = sendToServer("POST", "/resource/platform/" + hostname, op);

        return node;
    }

    private JsonNode getPlatform(String hostname) {

        JsonNode node = sendToServer("GET", "/resource?category=platform&q=" + hostname, null);

        return node;
    }

    private void sendMetrics(Collection<Metric> metrics, int resourceId) {

        ArrayNode root = mapper.createArrayNode();
        for (Metric metric : metrics ) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("timestamp",metric.getTimeStamp());
            obj.put("value", metric.getValue());
            obj.put("metric",metric.getName());
            root.add(obj);
        }

        JsonNode node = sendToServer("POST", "/metric/data/raw/" + resourceId, root);

    }

    /**
     * Sends a request to the RHQ server
     * @param method HttpMethod to use (GET, PUT, POST, DELETE)
     * @param subUri SubUri below the rhq server rest endpoint (typically http://localhost:7080/rest )
     * @param payload The data to send to the server. Can be null for GET or DELETE requests
     * @return Answer from server or null in case of error
     */
    private JsonNode sendToServer(String method, String subUri, JsonNode payload) {
        int timeoutSec = 10;

        String serverUrl = rhqUrl ;
        if (!rhqUrl.endsWith("/") && !subUri.startsWith("/"))
            serverUrl += "/";
        serverUrl+=subUri;

        HttpURLConnection conn;
        OutputStream out;
        try {
            URL url = new URL(serverUrl);

            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod(method.toUpperCase());
            conn.addRequestProperty(CONTENT_TYPE_HTTP_HEADER, JSON_MIME_TYPE);
            conn.addRequestProperty(ACCEPT_HTTP_HEADER, JSON_MIME_TYPE);
            conn.setInstanceFollowRedirects(false);
            String userPassword = rhqUser + ":" + rhqPass;
            String encoding = new sun.misc.BASE64Encoder().encode(userPassword.getBytes());
            conn.setRequestProperty("Authorization", "Basic " + encoding);

            int timeoutMillis = timeoutSec * 1000;
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            if (conn.getReadTimeout() != timeoutMillis) {
                log.info("Read timeout did not get set on HTTP connection - the JRE uses a broken timeout mechanism - nothing we can do.");
            }


        } catch (IOException e) {
            // This most likely just means the server is down.
            log.fine("Failed to open connection to [" + subUri + "] in order to invoke [" + payload + "]: "
                    + e);
            return null;
        }

        try {
            if (method.equalsIgnoreCase("post") || method.equalsIgnoreCase("put")) {
                out = conn.getOutputStream(); // This would set the method to post if applied to a get
                out.write(payload.toString().getBytes());
                out.flush();
                out.close();

            }

            String responseBody = getResponseBody(conn);

            JsonNode node = mapper.readTree(responseBody);

            return node;

        }
        catch (Exception e) {
            log.info(e.getMessage());
            return null;
        }

    }

    private static final String CONTENT_LENGTH_HTTP_HEADER = "Content-Length";


    private String getResponseBody(HttpURLConnection connection) {
        InputStream inputStream;
        try {
            inputStream = connection.getInputStream();
        } catch (IOException e) {
            // This means the server returned a 4xx (client error) or 5xx (server error) response, e.g.:
            // "java.io.IOException: Server returned HTTP response code: 500 for URL: http://127.0.0.1:9990/management"
            // Unfortunately, AS7 incorrectly returns 500 responses for client errors (e.g. invalid resource path,
            // attribute name, etc.).
            inputStream = null;
        }
        if (inputStream == null) {
            inputStream = connection.getErrorStream();
        }
        if (inputStream == null) {
            return "";
        }

        int contentLength = connection.getHeaderFieldInt(CONTENT_LENGTH_HTTP_HEADER, -1);

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringWriter stringWriter = (contentLength != -1) ? new StringWriter(contentLength) : new StringWriter();
        BufferedWriter writer = new BufferedWriter(stringWriter);
        try {
            long numCharsCopied = 0;
            char[] buffer = new char[1024];

            int cnt;
            while (((contentLength == -1) || (numCharsCopied < contentLength)) && ((cnt = reader.read(buffer)) != -1)) {
                numCharsCopied += cnt;
                writer.write(buffer, 0, cnt);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read response.", e);
        } finally {
            try {
                writer.close();
            } catch (IOException ioe) {
                log.fine("Failed to close writer." + ioe.getMessage());
            }

            try {
                reader.close();
            } catch (IOException ioe) {
                log.fine("Failed to close reader." + ioe);
            }
        }

        return stringWriter.getBuffer().toString();
    }


}
