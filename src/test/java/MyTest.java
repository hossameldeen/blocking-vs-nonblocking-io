import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Credits: https://github.com/vert-x3/vertx-examples/blob/master/unit-examples
 */
@RunWith(VertxUnitRunner.class)
public class MyTest {

  Vertx vertx;
  HttpServer server;
  HttpClient client;

  @Before
  public void before(TestContext tc) {
    vertx = Vertx.vertx();

    server = vertx.createHttpServer()
        .requestHandler(req -> req.response().end("foo"))
        .listen(8080);

    client = vertx.createHttpClient(new HttpClientOptions()
        .setDefaultHost("localhost")
        .setDefaultPort(8080));
  }

  @After
  public void after(TestContext tc) {
    client.close(); // not sure if needed
    vertx.close(tc.asyncAssertSuccess());
  }

  @Test
  public void test1(TestContext tc) {
    Async async = tc.async();
    client.getNow(8080, "localhost", "/", resp -> {
      resp.bodyHandler(body -> {
        tc.assertEquals("foo", body.toString());
        async.complete();
      });
    });
  }
}
