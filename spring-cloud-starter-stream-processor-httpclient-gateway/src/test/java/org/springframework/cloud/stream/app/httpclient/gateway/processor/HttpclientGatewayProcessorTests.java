package org.springframework.cloud.stream.app.httpclient.gateway.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gaul.httpbin.HttpBin;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.cloud.stream.app.httpclient.gateway.processor.HttpclientGatewayProcessorConfiguration.HttpclientGatewayProcessor;
import org.springframework.cloud.stream.test.binder.MessageCollector;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.hamcrest.core.AllOf.allOf;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.springframework.cloud.stream.test.matcher.MessageQueueMatcher.receivesMessageThat;
import static org.springframework.cloud.stream.test.matcher.MessageQueueMatcher.receivesPayloadThat;
import static org.springframework.integration.test.matcher.PayloadAndHeaderMatcher.sameExceptIgnorableHeaders;

@RunWith(SpringRunner.class)
@ContextConfiguration(initializers = HttpclientGatewayProcessorTests.Initializer.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
public abstract class HttpclientGatewayProcessorTests {

    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    private static final byte[] LARGE_BYTE_ARRAY = new byte[1_000_000];

    private static ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    protected HttpclientGatewayProcessor channels;

    @Autowired
    protected MessageCollector messageCollector;

    @TestPropertySource(properties = {"httpclient-gateway.requestPerSecond=1",
            "httpclient-gateway.resourceLocationUri=file://tmp/{key}{extension}",
            "httpclient-gateway.contentLengthToExternalize=1000000"})
    public static class HttpClientGatewayProcessor200ResponseTest extends HttpclientGatewayProcessorTests {

        @Test
        public void testHttpClientGatewayProcessor200Response() {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "1234");
            map.put("http_requestMethod", "GET");
            map.put("http_requestUrl", "http://some.domain/get?foo=1&bar=2");
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage(EMPTY_BYTE_ARRAY, messageHeaders);
            channels.input().send(message);
            assertThat(messageCollector.forChannel(channels.output()),
                    receivesPayloadThat(allOf(
                            hasJsonPath("$.url"),
                            hasJsonPath("$.headers.User-Agent", is("ReactorNetty/0.8.5.RELEASE")),
                            hasJsonPath("$.origin"),
                            hasJsonPath("$.args.foo", is("1")),
                            hasJsonPath("$.args.bar", is("2"))
                    )));
            map = new HashMap<>();
            map.put("request_id", "1234");
            map.put("http_method", "GET");
            map.put("status_code", 200);
            map.put("host", "127.0.0.1");
            map.put("path", "/get");
            map.put("query", "foo=1&bar=2");
            messageHeaders = new MessageHeaders(map);
            message = MessageBuilder.createMessage("", messageHeaders);

            assertThat(messageCollector.forChannel(channels.analytics()),
                    receivesMessageThat(sameExceptIgnorableHeaders(message,"response_timestamp", MessageHeaders.CONTENT_TYPE)
                    ));
        }

    }

    @TestPropertySource(properties = {"httpclient-gateway.requestPerSecond=1",
            "httpclient-gateway.resourceLocationUri=file://tmp/{key}{extension}",
            "httpclient-gateway.contentLengthToExternalize=1000000"})
    public static class HttpClientGatewayProcessor403ResponseTest extends HttpclientGatewayProcessorTests {

        @Test
        public void testHttpClientGatewayProcessor403Response() {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "5678");
            map.put("http_requestMethod", "GET");
            map.put("http_requestUrl", "http://some.domain/status/403");
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage(EMPTY_BYTE_ARRAY, messageHeaders);
            channels.input().send(message);

            map = new HashMap<>();
            map.put("continuation_id", "5678");
            map.put("http_requestMethod", "GET");
            map.put("http_statusCode", HttpStatus.FORBIDDEN.value());
            messageHeaders = new MessageHeaders(map);
            message = MessageBuilder.createMessage("", messageHeaders);

            assertThat(messageCollector.forChannel(channels.output()),
                    receivesMessageThat(sameExceptIgnorableHeaders(message,
                            "http_requestUrl",
                            "error_response_time",
                            HttpHeaders.SERVER,
                            HttpHeaders.CONTENT_LENGTH,
                            HttpHeaders.DATE,
                            MessageHeaders.CONTENT_TYPE)));


            map = new HashMap<>();
            map.put("request_id", "5678");
            map.put("http_method", "GET");
            map.put("status_code", 403);
            map.put("reason_phrase", "Forbidden");
            map.put("host", "127.0.0.1");
            map.put("path", "/status/403");
            messageHeaders = new MessageHeaders(map);
            message = MessageBuilder.createMessage("", messageHeaders);

            assertThat(messageCollector.forChannel(channels.analytics()),
                    receivesMessageThat(sameExceptIgnorableHeaders(message, "response_timestamp", MessageHeaders.CONTENT_TYPE)
                    ));
        }
    }

    @TestPropertySource(properties = {"httpclient-gateway.requestPerSecond=1",
            "httpclient-gateway.resourceLocationUri=file://tmp/{key}{extension}",
            "httpclient-gateway.contentLengthToExternalize=1000000",
            "httpclient-gateway.retryErrorStatusCodes=429,500",
            "httpclient-gateway.retryUrlRegex=^.+\\/500$"})
    public static class DefaultHttpclientGatewayProcessorTests extends HttpclientGatewayProcessorTests {

        @Test
        public void testHttpStatus429() throws Exception {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "1111");
            map.put("http_requestMethod", "GET");
            map.put("http_requestUrl", "http://some.domain/status/429");
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage(EMPTY_BYTE_ARRAY, messageHeaders);
            channels.input().send(message);
            Message<?> messageToRetry = messageCollector.forChannel(channels.httpErrorResponse()).take();
            assertThat(messageToRetry.getHeaders().get("http_statusCode"), is(HttpStatus.TOO_MANY_REQUESTS.value()));
        }

        @Test
        public void testHttpStatus500() throws Exception {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "1111");
            map.put("http_requestMethod", "POST");
            map.put("http_requestUrl", "http://some.domain/status/500");
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage(EMPTY_BYTE_ARRAY, messageHeaders);
            channels.input().send(message);
            Message<?> messageToRetry = messageCollector.forChannel(channels.httpErrorResponse()).take();
            assertThat(messageToRetry.getHeaders().get("http_statusCode"), is(HttpStatus.INTERNAL_SERVER_ERROR.value()));
        }

        @Test
        public void testMultiPart() throws Exception {
            Map<String, Object> map = new HashMap<>();
            map.put(MessageHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
            map.put("continuation_id", "2222");
            map.put("http_requestMethod", "POST");
            map.put("original_content_type", "multipart/form-data");
            map.put("http_requestUrl", "http://some.domain/post");
            String payload = "[{\"some-number\":4},{\"some-json-array\":[\"4\"]},"
                    + "{\"formParameterName\":\"data1\",\"originalFileName\":\"filename.txt\",\"contentType\":\"text/plain\",\"uri\":\"classpath:128b_file\"},"
                    + "{\"formParameterName\":\"data2\",\"originalFileName\":\"other-file-name.data\",\"contentType\":\"text/plain\",\"uri\":\"classpath:128b_file\"},"
                    + "{\"formParameterName\":\"json\",\"originalFileName\":\"test.json\",\"contentType\":\"application/json\",\"uri\":\"classpath:128b_file\"}]";
            JsonNode jsonNode = objectMapper.readTree(payload);
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage(jsonNode, messageHeaders);
            channels.input().send(message);
            assertThat(messageCollector.forChannel(channels.output()),
                    receivesPayloadThat(allOf(
                            hasJsonPath("$.data.some-json-array"),
                            hasJsonPath("$.data.data2"),
                            hasJsonPath("$.data.data1"),
                            hasJsonPath("$.data.json"),
                            hasJsonPath("$.data.some-number")
                    )));
        }

    }


    @TestPropertySource(properties = {"httpclient-gateway.requestPerSecond=1",
            "httpclient-gateway.resourceLocationUri=file://tmp/{key}{extension}",
            "httpclient-gateway.urlPatternsToExternalize=/post"
    })
    public static class HttpclientGatewayProcessorExternalizingTest1 extends HttpclientGatewayProcessorTests {

        @Test
        public void testResponseBodyExternalizedWhenURLPathMatches() throws Exception {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "3333");
            map.put("http_requestMethod", "POST");
            map.put("http_requestUrl", "http://some.domain/post");
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage(EMPTY_BYTE_ARRAY, messageHeaders);
            channels.input().send(message);
            assertThat(messageCollector.forChannel(channels.output()),
                    receivesPayloadThat(
                            allOf(hasJsonPath("$.http_requestUrl"), hasJsonPath("$.original_content_type", is("application/json")),
                                    hasJsonPath("$.uri", is("file://tmp/127.0.0.1/post/2be9bd7a-3434-3703-8ca2-7d1918de58bd.json")))));

        }
    }

    @TestPropertySource(properties = {"httpclient-gateway.requestPerSecond=1",
            "httpclient-gateway.resourceLocationUri=file://tmp/{key}{extension}",
            "httpclient-gateway.contentLengthToExternalize=1000000"
    })
    public static class HttpclientGatewayProcessorExternalizingTest2 extends HttpclientGatewayProcessorTests {
        @Test
        public void testLargeResponseBodyExternalized() throws Exception {
            Map<String, Object> map = new HashMap<>();
            map.put("continuation_id", "4444");
            map.put("http_requestMethod", "POST");
            map.put("http_requestUrl", "http://some.domain/post");
            MessageHeaders messageHeaders = new MessageHeaders(map);
            Message message = MessageBuilder.createMessage(LARGE_BYTE_ARRAY, messageHeaders);
            channels.input().send(message);
            assertThat(messageCollector.forChannel(channels.output()),
                    receivesPayloadThat(allOf(
                            hasJsonPath("$.http_requestUrl"),
                            hasJsonPath("$.original_content_type", is("application/json"))
                    )));
        }
    }

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext>,
            ApplicationListener<ContextClosedEvent> {

        URI httpBinEndpoint = URI.create("http://127.0.0.1:0");

        HttpBin httpBin;

        @Override
        public void initialize(ConfigurableApplicationContext configurableApplicationContext) {
            try {
                httpBin = new HttpBin(httpBinEndpoint);
                httpBin.start();
                URI uri = new URI(httpBinEndpoint.getScheme(),
                        httpBinEndpoint.getUserInfo(), httpBinEndpoint.getHost(),
                        httpBin.getPort(), httpBinEndpoint.getPath(),
                        httpBinEndpoint.getQuery(), httpBinEndpoint.getFragment());
                TestPropertyValues.of("httpclient-gateway.url=" + uri.toString())
                        .applyTo(configurableApplicationContext.getEnvironment());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            try {
                httpBin.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @SpringBootApplication
    public static class DefaultHttpclientGatewayProcessorApplication {

    }
}
