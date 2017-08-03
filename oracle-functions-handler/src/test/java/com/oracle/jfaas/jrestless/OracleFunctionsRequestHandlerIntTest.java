package com.oracle.jfaas.jrestless;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrestless.core.container.dpi.InstanceBinder;
import com.jrestless.core.filter.ApplicationPathFilter;
import com.oracle.faas.api.InputEvent;
import com.oracle.faas.api.OutputEvent;
import com.oracle.faas.api.RuntimeContext;
import com.oracle.faas.runtime.HeadersImpl;
import com.oracle.faas.runtime.QueryParametersImpl;
import com.oracle.faas.runtime.ReadOnceInputEvent;
import jersey.repackaged.com.google.common.collect.ImmutableMap;
import org.glassfish.hk2.utilities.Binder;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.Before;
import org.junit.Test;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class OracleFunctionsRequestHandlerIntTest {
    private OracleFunctionsRequestObjectHandler handler;
    private TestService testService;
    private RuntimeContext runtimeContext = mock(RuntimeContext.class);
    private ByteArrayInputStream defaultBody;

    @Before
    public void setUp() {
        testService = mock(TestService.class);
        handler = createAndStartHandler(new ResourceConfig(), testService);
        defaultBody = new ByteArrayInputStream(new byte[]{});
    }

    private OracleFunctionsRequestObjectHandler createAndStartHandler(ResourceConfig config, TestService testService) {
        Binder binder = new InstanceBinder.Builder().addInstance(testService, TestService.class).build();
        config.register(binder);
        config.register(TestResource.class);
        config.register(ApplicationPathFilter.class);
        OracleFunctionsRequestObjectHandler handler = new OracleFunctionsRequestObjectHandler();
        handler.init(config);
        handler.start();
        handler.setRuntimeContext(runtimeContext);
        return handler;
    }

    @Test
    public void testRuntimeContextInjection() {
        InputEvent inputEvent = new ReadOnceInputEvent("myApp",
                "/",
                "www.example.com",
                "DELETE",
                defaultBody,
                new HeadersImpl(new HashMap<>()),
                new QueryParametersImpl());
        handler.handleRequest(inputEvent);
        verify(testService).injectRuntimeContext(runtimeContext);
    }

    @Test
    public void testInputEventInjection() {
        InputEvent inputEvent = new ReadOnceInputEvent("myApp",
                "/inject-input-event",
                "www.example.com",
                "PUT",
                defaultBody,
                new HeadersImpl(new HashMap<>()),
                new QueryParametersImpl());
        handler.handleRequest(inputEvent);
        verify(testService).injectInputEvent(same(inputEvent));
    }

    @Test
    public void testBaseUriWithoutHost() {
        InputEvent inputEvent = new ReadOnceInputEvent("myApp",
                "/uris",
                "www.example.com",
                "GET",
                defaultBody,
                new HeadersImpl(new HashMap<>()),
                new QueryParametersImpl());
        handler.handleRequest(inputEvent);
        verify(testService).baseUri(URI.create("/"));
        verify(testService).requestUri(URI.create("/uris"));
    }

    @Test
    public void testBaseUriWithHost() {
        Map<String, String> inputHeaders = ImmutableMap.of(HttpHeaders.HOST, "www.example.com");
        InputEvent inputEvent = new ReadOnceInputEvent("myApp",
                "/uris",
                "www.example.com",
                "GET",
                defaultBody,
                new HeadersImpl(inputHeaders),
                new QueryParametersImpl());
        handler.handleRequest(inputEvent);
        verify(testService).baseUri(URI.create("https://www.example.com/"));
        verify(testService).requestUri(URI.create("https://www.example.com/uris"));
    }

    @Test
    public void testAppPathWithoutHost() {
        OracleFunctionsRequestObjectHandler handlerWithAppPath = createAndStartHandler(new ApiResourceConfig(), testService);
        InputEvent inputEvent = new ReadOnceInputEvent("myApp",
                "/api/uris",
                "www.example.com",
                "GET",
                defaultBody,
                new HeadersImpl(new HashMap<>()),
                new QueryParametersImpl());
        handlerWithAppPath.handleRequest(inputEvent);
        verify(testService).baseUri(URI.create("/api/"));
        verify(testService).requestUri(URI.create("/api/uris"));
    }

    @Test
    public void testAppPathWithHost() {
        Map<String, String> inputHeaders = ImmutableMap.of(HttpHeaders.HOST, "www.example.com");
        OracleFunctionsRequestObjectHandler handlerWithAppPath = createAndStartHandler(new ApiResourceConfig(), testService);
        InputEvent inputEvent = new ReadOnceInputEvent("myApp",
                "/api/uris",
                "www.example.com",
                "GET",
                defaultBody,
                new HeadersImpl(inputHeaders),
                new QueryParametersImpl());
        handlerWithAppPath.handleRequest(inputEvent);
        verify(testService).baseUri(URI.create("https://www.example.com/api/"));
        verify(testService).requestUri(URI.create("https://www.example.com/api/uris"));
    }

    // Note: There is a better version of this test in OracleFunctionsFutureRequestHandlerTest
    @Test
    public void testRoundTripBasic() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> inputHeaders = ImmutableMap.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON,
                HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        String contents = mapper.writeValueAsString(new OracleFunctionsRequestHandlerIntTest.AnObject("123"));
        ByteArrayInputStream body = new ByteArrayInputStream(contents.getBytes());

        InputEvent inputEvent = new ReadOnceInputEvent("myApp",
                "/round-trip",
                "www.example.com",
                "POST",
                body,
                new HeadersImpl(inputHeaders),
                new QueryParametersImpl());
        OutputEvent outputEvent = handler.handleRequest(inputEvent);

        assertEquals(MediaType.APPLICATION_JSON, outputEvent.getContentType().get());
    }

    private interface TestService{
        void injectRuntimeContext(RuntimeContext context);
        void injectInputEvent(InputEvent request);
        void baseUri(URI baseUri);
        void requestUri(URI baseUri);
    }

    @Path("/")
    @Singleton // singleton in order to test proxies
    public static class TestResource {

        private final TestService service;
        private final UriInfo uriInfo;

        @Inject
        public TestResource(TestService service, UriInfo uriInfo) {
            this.service = service;
            this.uriInfo = uriInfo;
        }

        @DELETE
        public Response injectRuntimeContext(@javax.ws.rs.core.Context RuntimeContext runtimeContext) {
            service.injectRuntimeContext(runtimeContext);
            return Response.ok().build();
        }

        @Path("/inject-input-event")
        @PUT
        public Response injectInputEvent(@javax.ws.rs.core.Context InputEvent inputEvent) {
            service.injectInputEvent(inputEvent);
            return Response.ok().build();
        }

        @Path("/round-trip")
        @POST
        public Response putSomething(AnObject entity) {
            return Response.ok(entity).build();
        }

        @Path("uris")
        @GET
        public void getBaseUri() {
            service.baseUri(uriInfo.getBaseUri());
            service.requestUri(uriInfo.getRequestUri());
        }
    }

    private static class AnObject {
        private String value;

        @JsonCreator
        private AnObject(@JsonProperty("value") String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    @ApplicationPath("api")
    public static class ApiResourceConfig extends ResourceConfig {
    }


}



