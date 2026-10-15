import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Container process liveness only: never call providers or expose credentials. */
public final class HealthProbe {
    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("SERVER_PORT", "8081");
        if (!port.matches("[0-9]{1,5}")) System.exit(1);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health/live"))
                .timeout(Duration.ofSeconds(4)).GET().build();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        System.exit(status == 200 ? 0 : 1);
    }
}
