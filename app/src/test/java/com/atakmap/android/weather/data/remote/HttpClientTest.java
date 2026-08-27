package com.atakmap.android.weather.data.remote;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HttpClient}'s request lifecycle — findings F4, F5, F31.
 *
 * <p>These exist because F4 and F5 were fixed with no regression guard. F4 in
 * particular was a lost-callback race: a caller could be answered neither with
 * success nor failure, leaving its UI on a loading state forever. That is the
 * kind of bug that comes back.
 *
 * <p>The class was untestable until the seams landed. It built its connection
 * inline, and its main-thread Handler was created in a static initialiser, so
 * merely loading the class under plain JUnit threw on the android.jar Looper
 * stub. Both are now injected; these tests substitute a MockWebServer transport
 * and a same-thread dispatcher.
 *
 * <p>Deliberately avoided: timing assertions. F5 is about not occupying a thread
 * during backoff, and a wall-clock test of that is flaky by construction. What
 * is asserted instead is the observable contract — a retried request still
 * answers its caller, and answers it once.
 *
 * <p>{@link HttpClient#shutdown()} is never called here: the pool is a static
 * final, so shutting it down would poison every later test in the JVM.
 */
class HttpClientTest {

    private MockWebServer server;

    /** Runs delivery inline so assertions do not need to wait on a looper. */
    private static final HttpClient.Dispatcher SAME_THREAD = Runnable::run;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // Plain HTTP: the point of the transport seam is that tests need no
        // TLS handshake and no JVM-global SSL state.
        HttpClient.setTransportForTesting(
                url -> (HttpURLConnection) new URL(url).openConnection(),
                SAME_THREAD);
    }

    @AfterEach
    void tearDown() throws IOException {
        HttpClient.setTransportForTesting(null, null);   // restore production seams
        server.shutdown();
    }

    /** Collects callback outcomes; every caller must land in exactly one list. */
    private static final class Recorder implements HttpClient.Callback {
        final List<String> successes = new CopyOnWriteArrayList<>();
        final List<String> failures  = new CopyOnWriteArrayList<>();
        final CountDownLatch done;

        Recorder(int expected) { this.done = new CountDownLatch(expected); }

        @Override public void onSuccess(String body) { successes.add(body); done.countDown(); }
        @Override public void onFailure(String error) { failures.add(error); done.countDown(); }

        int answered() { return successes.size() + failures.size(); }

        void awaitAll() throws InterruptedException {
            assertTrue(done.await(15, TimeUnit.SECONDS),
                    "timed out with " + answered() + " of "
                            + (answered() + done.getCount()) + " callers answered — "
                            + "a caller was dropped, which is finding F4");
        }
    }

    // ── F4: every caller is answered ────────────────────────────────────────

    @Nested @DisplayName("Every caller is answered exactly once")
    class CallbackDelivery {

        @Test @DisplayName("Concurrent callers for one URL all get the body")
        void concurrentCallersAllAnswered() throws Exception {
            server.enqueue(new MockResponse().setBody("payload").setBodyDelay(300, TimeUnit.MILLISECONDS));
            String url = server.url("/forecast").toString();

            final int callers = 8;
            Recorder rec = new Recorder(callers);
            final CountDownLatch startTogether = new CountDownLatch(1);

            for (int i = 0; i < callers; i++) {
                Thread t = new Thread(() -> {
                    try { startTogether.await(); } catch (InterruptedException ignored) { }
                    HttpClient.get(url, rec);
                });
                t.setDaemon(true);
                t.start();
            }
            startTogether.countDown();   // release them all at once

            rec.awaitAll();
            assertEquals(callers, rec.answered(),
                    "every caller must be answered exactly once");
            assertEquals(callers, rec.successes.size(),
                    "all callers share one successful response");
            rec.successes.forEach(b -> assertEquals("payload", b.trim()));
        }

        @Test @DisplayName("Sharing a request does not duplicate it on the wire")
        void sharedRequestHitsServerOnce() throws Exception {
            server.enqueue(new MockResponse().setBody("once").setBodyDelay(300, TimeUnit.MILLISECONDS));
            String url = server.url("/dedup").toString();

            Recorder rec = new Recorder(4);
            for (int i = 0; i < 4; i++) HttpClient.get(url, rec);

            rec.awaitAll();
            assertEquals(4, rec.successes.size(), "all four callers answered");
            assertEquals(1, server.getRequestCount(),
                    "four callers for one URL must produce one request — the point of "
                            + "deduplication, and it matters against a rate-limited API");
        }

        @Test @DisplayName("A permanent failure answers the caller too")
        void permanentFailureIsDelivered() throws Exception {
            server.enqueue(new MockResponse().setResponseCode(404));
            Recorder rec = new Recorder(1);

            HttpClient.get(server.url("/missing").toString(), rec);

            rec.awaitAll();
            assertEquals(1, rec.failures.size(), "a 4xx must call onFailure, not vanish");
            assertTrue(rec.failures.get(0).contains("404"), rec.failures.get(0));
        }
    }

    // ── F5: retries still answer, and answer once ───────────────────────────

    @Nested @DisplayName("Retry")
    class Retry {

        @Test @DisplayName("A 429 is retried and the caller gets the eventual body")
        void rateLimitedRequestRetriesThenSucceeds() throws Exception {
            server.enqueue(new MockResponse().setResponseCode(429));
            server.enqueue(new MockResponse().setBody("recovered"));

            Recorder rec = new Recorder(1);
            HttpClient.get(server.url("/limited").toString(), rec);

            rec.awaitAll();
            assertEquals(1, rec.successes.size(),
                    "the caller must be answered after the retry, not dropped");
            assertEquals("recovered", rec.successes.get(0).trim());
            assertEquals(2, server.getRequestCount(), "one rejected attempt, one retry");
        }

        @Test @DisplayName("A retried request answers its caller exactly once")
        void retryDoesNotDoubleDeliver() throws Exception {
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setBody("ok"));

            AtomicInteger calls = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);
            HttpClient.get(server.url("/flaky").toString(), new HttpClient.Callback() {
                @Override public void onSuccess(String body) { calls.incrementAndGet(); done.countDown(); }
                @Override public void onFailure(String error) { calls.incrementAndGet(); done.countDown(); }
            });

            assertTrue(done.await(15, TimeUnit.SECONDS), "caller was never answered");
            Thread.sleep(200);   // give any stray second delivery a chance to land
            assertEquals(1, calls.get(), "exactly one delivery per caller");
        }
    }

    // ── No exception may strand a caller ────────────────────────────────────

    @Test @DisplayName("A transport that throws unexpectedly still answers the caller")
    void unexpectedThrowableStillAnswersCaller() throws Exception {
        // This is not hypothetical. A throwing android.util.Log stub inside the
        // retry path escaped executeWithRetry's IOException-only catch, unwound
        // into the executor, and left the caller waiting forever — the same
        // silent hang as F4 by a different route. runGuarded() closes it.
        HttpClient.setTransportForTesting(url -> { throw new IllegalStateException("boom"); },
                SAME_THREAD);

        Recorder rec = new Recorder(1);
        HttpClient.get("https://example.invalid/anything", rec);

        rec.awaitAll();
        assertEquals(1, rec.failures.size(),
                "an unchecked exception must surface as onFailure, not as silence");
        assertTrue(rec.failures.get(0).contains("boom"), rec.failures.get(0));
    }

    // ── F31: the default transport still refuses plain HTTP ─────────────────

    @Test @DisplayName("The production opener rejects non-HTTPS URLs")
    void productionOpenerIsHttpsOnly() {
        // Widening the connection type for testability must not have quietly
        // dropped the HTTPS enforcement the old ClassCastException provided.
        IOException e = assertThrows(IOException.class,
                () -> HttpClient.HTTPS_ONLY.open("http://example.com/insecure"));
        assertTrue(e.getMessage().contains("non-HTTPS"), e.getMessage());

        assertThrows(IOException.class, () -> HttpClient.HTTPS_ONLY.open(null));
    }
}
