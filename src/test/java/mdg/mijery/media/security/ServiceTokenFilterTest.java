package mdg.mijery.media.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTokenFilterTest {

    private static boolean passed(ServiceTokenFilter filter, MockHttpServletRequest request,
                                  MockHttpServletResponse response) throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> called.set(true));
        return called.get();
    }

    @Test
    void apiCallsWithoutTheTokenAreRejectedBeforeTheController() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("s3cret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/assets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passed(filter, request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("Unauthorized service call");
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("s3cret");
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/products/x/logo");
        request.addHeader(ServiceTokenFilter.HEADER, "nope");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passed(filter, request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void theConfiguredTokenIsAccepted() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("s3cret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/assets");
        request.addHeader(ServiceTokenFilter.HEADER, "s3cret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passed(filter, request, response)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void publicMediaAndLogoRoutesAreNotFiltered() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("s3cret");

        assertThat(passed(filter, new MockHttpServletRequest("GET", "/media/abc/640.webp"),
                new MockHttpServletResponse())).isTrue();
        assertThat(passed(filter, new MockHttpServletRequest("GET", "/logo/mdg-chronicles"),
                new MockHttpServletResponse())).isTrue();
    }

    @Test
    void anEmptyConfiguredTokenKeepsDevelopmentOpen() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/assets");

        assertThat(passed(filter, request, new MockHttpServletResponse())).isTrue();
    }
}
