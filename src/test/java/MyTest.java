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
 * Credits for vertx test structure: https://github.com/vert-x3/vertx-examples/blob/master/unit-examples
 */
@RunWith(VertxUnitRunner.class)
public class MyTest {

  Vertx vertx;
  HttpServer server;
  HttpClient client;

  final int CONCURRENT_REQUESTS = 100000;

  @Before
  public void before(TestContext tc) {
    vertx = Vertx.vertx();

    server = vertx.createHttpServer()
        .requestHandler(req -> vertx.setTimer(400000, i -> req.response().end("foo")))
        .listen(8080);

    client = vertx.createHttpClient(new HttpClientOptions()
        .setDefaultHost("localhost")
        .setDefaultPort(8080)
        .setMaxPoolSize(CONCURRENT_REQUESTS));
  }

  @After
  public void after(TestContext tc) {
    client.close();
    vertx.close(tc.asyncAssertSuccess());
  }

  @Test
  public void testNonBlockingClient(TestContext tc) {
    long startTime = System.currentTimeMillis();
    for (int i = 0; i < CONCURRENT_REQUESTS; ++i) {
      final int ii = i; // Needs to be final to reference it inside the callback
      Async async = tc.async();
      client.getNow("/", resp ->
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
          params.setParameter(ConnRoutePNames.LOCAL_ADDRESS, java.net.InetAddress.getByName("127.0.0." + (Math.random() * 255 + 1)));//for pseudo 'ip spoofing'
          httpClient.execute(new HttpGet("http://localhost:8080/"));
          async.complete();
          System.out.println("Finished " + ii + " after ~" + Math.round((System.currentTimeMillis() - startTime) / 1000.0) + "seconds");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }).start();
    }
  }
}
