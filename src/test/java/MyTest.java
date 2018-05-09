import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unfortunately, can't yet test 100k connections with vert.x, due to max-TCP-connections-per-source-ip. This is
 * unrelated to blocking-vs-nonblocking discussion. See here:
 * https://groups.google.com/forum/#!topic/vertx/Jq9awwf6sGM
 *
 * However, managed to overcome with HttpClient. It's a blocking client & I got OutOfMemory as expect.
 *
 * I.e., next TODO is to overcome the max TCP connections problem in vertx to see if it'll manage 100k simultaneous
 * connections or no.
 *
 * Update: Managed to set local address in Vert.x client using `setLocalAddress`. However,:
 * (1) sometimes getting `cannot assign requested address 127.0.0.1`, if # of requests is 100k, although I'm sure
 *     `setLocalAddress` is working in the 10k case (checked with `netstat -peant`).
 * (2) getting test timeout (simple, will increase it, but when I first solve the problem above. And will also
 *     increase SERVER_RESPONSE_DELAY to simulate totally-concurrent requests)
 *
 * Update: The problem isn't vertx binding to 127.0.0.1 instead of 127.x.x.x. The exception was thrown was:
 * io.vertx.core.http.impl.HttpClientRequestImpl
 * SEVERE: io.netty.channel.AbstractChannel$AnnotatedSocketException: Cannot assign requested address: localhost/127.0.0.1:8080
 * By checking AnnotatedSocketException the `127.0.0.1:8080` is the server ip, as it should be. The problem is "Cannot
 * assign requested address:localhost".
 *
 * Credits for vertx test structure: https://github.com/vert-x3/vertx-examples/blob/master/unit-examples
 */
@RunWith(VertxUnitRunner.class)
public class MyTest {

  Vertx vertx;
  HttpServer server;
  HttpClient[] clients;

  final int CONCURRENT_REQUESTS = 100000;
  final int REQUESTS_PER_SOURCE_IP = 1000;
  final int N_CLIENTS = (CONCURRENT_REQUESTS + REQUESTS_PER_SOURCE_IP - 1) / REQUESTS_PER_SOURCE_IP;  // ceiling
  final int SERVER_RESPONSE_DELAY = 10000;

  @Before
  public void before(TestContext tc) {
    vertx = Vertx.vertx();

    server = vertx.createHttpServer()
        .requestHandler(req -> vertx.setTimer(SERVER_RESPONSE_DELAY, i -> req.response().end("foo")))
        .listen(8080);

    tc.assertTrue(N_CLIENTS <= 254);
    clients = new HttpClient[N_CLIENTS];
    for (int i = 0; i < N_CLIENTS; ++i)
      clients[i] = vertx.createHttpClient(new HttpClientOptions()
          .setDefaultHost("localhost")
          .setDefaultPort(8080)
          .setIdleTimeout(0)
          .setMaxPoolSize(REQUESTS_PER_SOURCE_IP)
          .setLocalAddress("127.0.4." + requestSourceIp(i))); // change 4 to any random number [1-254] between runs
  }

  @After
  public void after(TestContext tc) {
    for (HttpClient client : clients)
      client.close();
    vertx.close(tc.asyncAssertSuccess());
  }

  @Test
  public void testNonBlockingClient(TestContext tc) {
    long startTime = System.currentTimeMillis();
    for (int i = 0; i < CONCURRENT_REQUESTS; ++i) {
      final int ii = i; // Needs to be final to reference it inside the callback
      Async async = tc.async();
      clients[requestSourceIp(i) - 1].getNow("/", resp ->
        resp.bodyHandler(body -> {
          tc.assertEquals("foo", body.toString());
          async.complete();
          System.out.println("Finished " + ii + " after ~" + Math.round((System.currentTimeMillis() - startTime) / 1000.0) + "seconds");
        })
        .exceptionHandler(t -> async.complete()));
      System.out.println("Started " + ii + " after ~" + Math.round((System.currentTimeMillis() - startTime) / 1000.0) + "seconds");
    }
  }

  @Test
  public void testBlockingClient(TestContext tc) {

    long startTime = System.currentTimeMillis();
    for (int i = 0; i < CONCURRENT_REQUESTS; ++i) {
      final int ii = i; // Needs to be final to reference it inside the callback
      Async async = tc.async();
      new Thread(() -> {
        try {
          System.out.println("Started " + ii + " after ~" + Math.round((System.currentTimeMillis() - startTime) / 1000.0) + "seconds");
          // Credits: https://stackoverflow.com/a/39412290/6690391
          DefaultHttpClient httpClient = new DefaultHttpClient();
          org.apache.http.params.HttpParams params = httpClient.getParams();
          params.setParameter(ConnRoutePNames.LOCAL_ADDRESS, java.net.InetAddress.getByName("127.0.0." + requestSourceIp(ii)));//for pseudo 'ip spoofing'
          httpClient.execute(new HttpGet("http://localhost:8080/"));
          async.complete();
          System.out.println("Finished " + ii + " after ~" + Math.round((System.currentTimeMillis() - startTime) / 1000.0) + "seconds");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }).start();
    }
  }

  private int requestSourceIp(int reqNumber) {
    return reqNumber / REQUESTS_PER_SOURCE_IP + 1;
  }
}
