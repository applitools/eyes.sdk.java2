package com.applitools.eyes;

import com.applitools.utils.ArgumentGuard;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.client.urlconnection.HttpURLConnectionFactory;
import com.sun.jersey.client.urlconnection.URLConnectionClientHandler;

import java.io.IOException;
import java.net.*;
import java.util.List;

/**
 * Provides common rest client functionality.
 */
class RestClient {

    /**
     * An interface used as base for anonymous classes wrapping Http Method
     * calls.
     */
    protected interface HttpMethodCall {
        public ClientResponse call();
    }

    private ProxySettings proxySettings;
    private int timeout; // seconds

    protected final Logger logger;
    protected Client restClient;
    protected URI serverUrl;
    protected WebResource endPoint;

    // Used for JSON serialization/de-serialization.
    protected ObjectMapper jsonMapper;

    /**
     * Internal class used for configuring a proxy, if needed.
     */
    private static class ConnectionFactory implements HttpURLConnectionFactory {

        Proxy proxy;

        ConnectionFactory(ProxySettings proxySettings) {
            if (proxySettings != null) {
                URI proxyUri;
                try {
                    proxyUri = new URI(proxySettings.getUri());
                } catch (URISyntaxException e) {
                    throw new EyesException("Invalid proxy URI: " + e.getMessage());
                }
                proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort()));
            }
        }


        public HttpURLConnection getHttpURLConnection(URL url) throws IOException {
            if (proxy != null) {
                return (HttpURLConnection) url.openConnection(proxy);
            }
            return (HttpURLConnection) url.openConnection();
        }
    }

    /**
     *
     * @param timeout Connect/Read timeout in milliseconds. 0 equals infinity.
     * @param proxySettings (optional) Setting for communicating via proxy.
     */
    private static Client buildRestClient(int timeout, ProxySettings proxySettings) {
        // Proxy
        URLConnectionClientHandler ch  = new URLConnectionClientHandler(new ConnectionFactory(proxySettings));

        Client client = new Client(ch);
        client.setConnectTimeout(timeout);
        client.setReadTimeout(timeout);
        return client;
    }

    /***
     * @param logger    Logger instance.
     * @param serverUrl The URI of the rest server.
     * @param timeout Connect/Read timeout in milliseconds. 0 equals infinity.

     */
    public RestClient(Logger logger, URI serverUrl, int timeout) {
        ArgumentGuard.notNull(serverUrl, "serverUrl");
        ArgumentGuard.greaterThanOrEqualToZero(timeout, "timeout");

        this.logger = logger;
        jsonMapper = new ObjectMapper();
        jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
        this.timeout = timeout;
        this.serverUrl = serverUrl;

        restClient = buildRestClient(timeout, proxySettings);
        endPoint = restClient.resource(serverUrl);
    }

    /**
     * Creates a rest client instance with timeout default of 5 minutes and
     * no proxy settings.
     * @param logger    A logger instance.
     * @param serverUrl The URI of the rest server.
     */
    public RestClient(Logger logger, URI serverUrl) {
        this(logger, serverUrl, 1000*60*5);
    }


    /**
     * Sets the proxy settings to be used by the rest client.
     * @param proxySettings The proxy settings to be used by the rest client.
     * If {@code null} then no proxy is set.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setProxyBase(ProxySettings proxySettings) {
        this.proxySettings = proxySettings;

        restClient = buildRestClient(timeout, proxySettings);
        endPoint = restClient.resource(serverUrl);
    }

    /**
     *
     * @return The current proxy settings used by the rest client,
     * or {@code null} if no proxy is set.
     */
    @SuppressWarnings("UnusedDeclaration")
    public ProxySettings getProxyBase() {
        return proxySettings;
    }

    /**
     * Sets the connect & read timeout for web requests.
     *
     * @param timeout Connect/Read timeout in milliseconds. 0 equals infinity.
     */
    public void setTimeout(int timeout) {
        ArgumentGuard.greaterThanOrEqualToZero(timeout, "timeout");
        this.timeout = timeout;

        restClient = buildRestClient(timeout, proxySettings);
        endPoint = restClient.resource(serverUrl);
    }

    /**
     *
     * @return The timeout for web requests (in seconds).
     */
    public int getTimeout() {
        return timeout;
    }


    /**
     * Sets the current server URL used by the rest client.
     * @param serverUrl The URI of the rest server.
     */
    @SuppressWarnings("UnusedDeclaration")
    protected void setServerUrlBase(URI serverUrl) {
        ArgumentGuard.notNull(serverUrl, "serverUrl");
        this.serverUrl = serverUrl;

        endPoint = restClient.resource(serverUrl);
    }

    /**
     *
     * @return The URI of the eyes server.
     */
    protected URI getServerUrlBase() {
        return serverUrl;
    }

    protected ClientResponse sendLongRequest(HttpMethodCall method, String name)
            throws EyesException {

        // Adding the long request headers
        int maxDelay = 10000;
        int delay = 2000;  // milliseconds
        ClientResponse response;
        while (true) {
            response = method.call();
            if (response.getStatus() != 202) {
                return response;
            }

            // Since we haven't read the entity, We must release the response
            // or the connection stays open (meaning it'll get stuck after two
            // requests).
            response.close();

            // Waiting a delay
            logger.verbose(String.format(
                    "%s: Still running... Retrying in %d ms", name, delay));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                throw new EyesException("Long request interrupted!", e);
            }

            // increasing the delay
            delay = Math.min(maxDelay, (int) Math.floor(delay * 1.5));
        }
    }


    /**
     * Builds an error message which includes the response http status etc.
     */
    protected String getReadResponseError(
            String errMsg, int statusCode, String statusPhrase,
            String responseBody) {
        ArgumentGuard.notNull(statusPhrase, "statusPhrase");

        if (errMsg == null) {
            errMsg = "";
        }

        if (responseBody == null) {
            responseBody = "";
        }

        return errMsg + " [" + statusCode + " " + statusPhrase + "] " + responseBody;
    }

    /**
     * Generic handling of response with data.
     * <p/>
     * Response Handling includes the following:
     * 1. Verify that we are able to read response data.
     * 2. verify that the status code is valid
     * 3. Parse the response data from JSON to the relevant type.
     */
    protected <T> T parseResponseWithJsonData(ClientResponse response,
                                              List<Integer> validHttpStatusCodes, Class<T> resultType)
            throws EyesException {
        ArgumentGuard.notNull(response, "response");
        ArgumentGuard.notNull(validHttpStatusCodes, "validHttpStatusCodes");
        ArgumentGuard.notNull(resultType, "resultType");

        T resultObject;
        int statusCode = response.getStatus();
        String statusPhrase =
                response.getClientResponseStatus().getReasonPhrase();
        String data = response.getEntity(String.class);
        response.close();
        // Validate the status code.
        if (!validHttpStatusCodes.contains(Integer.valueOf(statusCode))) {
            String errorMessage = getReadResponseError(
                    "Invalid status code",
                    statusCode,
                    statusPhrase,
                    data);

            throw new EyesException(errorMessage);
        }

        // Parse data.
        try {
            resultObject = jsonMapper.readValue(data, resultType);
        } catch (IOException e) {
            String errorMessage = getReadResponseError(
                    "Failed to de-serialize response body",
                    statusCode,
                    statusPhrase,
                    data);

            throw new EyesException(errorMessage, e);
        }

        return resultObject;
    }
}
