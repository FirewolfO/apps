package top.lxvb.yuque;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ApiClientTest {
    @Test public void acceptsQuotedQuickTunnelUrl() {
        assertEquals("https://example-name.trycloudflare.com",
                ApiClient.normalizeDiscoveryAnswer("\"https://example-name.trycloudflare.com\""));
    }

    @Test public void rejectsUnexpectedDiscoveryHostsAndPaths() {
        assertEquals("", ApiClient.normalizeDiscoveryAnswer("https://example.com"));
        assertEquals("", ApiClient.normalizeDiscoveryAnswer("https://example.trycloudflare.com/admin"));
        assertEquals("", ApiClient.normalizeDiscoveryAnswer("http://example.trycloudflare.com"));
    }
}
