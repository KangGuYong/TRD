package kr.trendstage.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 한국어 특화 임베딩(KURE-v1, 1024-dim) 서버 클라이언트. HuggingFace Text Embeddings
 * Inference(TEI)를 온프레미스로 띄워 호출한다(M2) — 외부로 텍스트를 보내지 않는다.
 */
@Component
public class EmbeddingClient {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmbeddingClient(@Value("${embedding.service.url:http://localhost:6000}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** @return 1024차원 임베딩 벡터 */
    public float[] embed(String text) {
        try {
            String body = mapper.writeValueAsString(Map.of("inputs", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embed"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new EmbeddingServiceException("임베딩 서버 응답 " + res.statusCode() + ": " + res.body());
            }
            JsonNode root = mapper.readTree(res.body());
            JsonNode row = root.get(0);
            float[] vec = new float[row.size()];
            for (int i = 0; i < row.size(); i++) vec[i] = (float) row.get(i).asDouble();
            return vec;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new EmbeddingServiceException("임베딩 서버 호출 실패: " + e.getMessage(), e);
        }
    }
}
