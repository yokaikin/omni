package com.omni.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.ai.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 由 OllamaSupportLocalModelClient 抽取的唯一模型 HTTP 实现，不注册业务配置。 */
public final class OllamaAiModelClient implements AiModelClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaAiModelClient.class);
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final boolean enabled;
    private final String endpoint;
    private final String model;
    private final int timeoutMillis;
    private final int contextWindow;
    private final String apiKey;
    private final String keepAlive;
    private final ObjectMapper objectMapper;
    private final OllamaResponseParser parser;
    private final HttpClient httpClient;

    public OllamaAiModelClient(boolean enabled, String endpoint, String model, int timeoutMillis,
                               int contextWindow, String apiKey, ObjectMapper objectMapper) {
        this(enabled, endpoint, model, timeoutMillis, contextWindow, apiKey, null, objectMapper);
    }

    public OllamaAiModelClient(boolean enabled, String endpoint, String model, int timeoutMillis,
                               int contextWindow, String apiKey, String keepAlive, ObjectMapper objectMapper) {
        this(enabled, endpoint, model, timeoutMillis, contextWindow, apiKey, keepAlive, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(timeoutMillis, 1000))).build());
    }

    OllamaAiModelClient(boolean enabled, String endpoint, String model, int timeoutMillis,
                       int contextWindow, String apiKey, ObjectMapper objectMapper, HttpClient httpClient) {
        this(enabled, endpoint, model, timeoutMillis, contextWindow, apiKey, null, objectMapper, httpClient);
    }

    OllamaAiModelClient(boolean enabled, String endpoint, String model, int timeoutMillis,
                       int contextWindow, String apiKey, String keepAlive, ObjectMapper objectMapper, HttpClient httpClient) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.model = model;
        this.timeoutMillis = Math.max(timeoutMillis, 1000);
        this.contextWindow = contextWindow;
        this.apiKey = apiKey;
        this.keepAlive = keepAlive;
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.parser = new OllamaResponseParser(objectMapper);
        this.httpClient = httpClient;
    }

    @Override
    public AiResponse generate(AiRequest request, AiCancellationToken cancellation) {
        return execute(request, null, cancellation, false);
    }

    @Override
    public AiResponse stream(AiRequest request, Consumer<AiStreamChunk> onChunk, AiCancellationToken cancellation) {
        return execute(request, onChunk, cancellation, true);
    }

    private AiResponse execute(AiRequest request, Consumer<AiStreamChunk> onChunk,
                               AiCancellationToken cancellation, boolean stream) {
        long started = System.nanoTime();
        String requestId = request != null && hasText(request.getRequestId()) ? request.getRequestId() : UUID.randomUUID().toString();
        String effectiveModel = request != null && hasText(request.getModel()) ? request.getModel() : model;
        AiCancellationToken token = cancellation == null ? new AiCancellationToken() : cancellation;
        AtomicBoolean timedOut = new AtomicBoolean();
        AiHttpCall call = null;
        AutoCloseable registration = null;
        ScheduledFuture<?> deadline = null;
        String outcome = "success";
        boolean readingStream = false;
        try {
            check(token, timedOut, started, requestId);
            if (!enabled) throw new AiModelException(AiErrorCode.DISABLED, requestId);
            validate(request, effectiveModel, requestId);
            byte[] bytes = objectMapper.writeValueAsBytes(buildPayload(request, effectiveModel, stream));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            if (hasText(apiKey)) builder.header("Authorization", "Bearer " + apiKey.trim());
            call = new AiHttpCall(httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream()));
            AiHttpCall active = call;
            registration = token.register(active::close);
            deadline = DEADLINES.schedule(() -> {
                timedOut.set(true);
                active.close();
            }, Math.max(1, timeoutMillis - elapsed(started)), TimeUnit.MILLISECONDS);
            check(token, timedOut, started, requestId);
            HttpResponse<InputStream> httpResponse = call.response.get(Math.max(1, timeoutMillis - elapsed(started)), TimeUnit.MILLISECONDS);
            int status = httpResponse.statusCode();
            check(token, timedOut, started, requestId);
            if (status < 200 || status >= 300) throw new AiModelException(AiErrorCode.HTTP_ERROR, requestId, status);
            AiResponse response;
            try (InputStream input = httpResponse.body()) {
                if (stream) {
                    readingStream = true;
                    response = readStream(input, requestId, effectiveModel, started, onChunk, token, timedOut);
                } else {
                    JsonNode root = parser.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), requestId, false);
                    ThinkTagFilter filter = new ThinkTagFilter();
                    String text = (filter.accept(parser.text(root)) + filter.flush()).trim();
                    requireAnswer(text, requestId);
                    response = new AiResponse(requestId, effectiveModel, text, parser.finishReason(root),
                            parser.usage(root, new AiUsage(null, null, null)), elapsed(started));
                }
            }
            check(token, timedOut, started, requestId);
            return response;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable source = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            AiModelException failure;
            if (token.isCancelled() || Thread.currentThread().isInterrupted()) failure = new AiModelException(AiErrorCode.CANCELLED, requestId);
            else if (timedOut.get() || elapsed(started) >= timeoutMillis || source instanceof HttpTimeoutException || source instanceof TimeoutException) failure = new AiModelException(AiErrorCode.TIMEOUT, requestId);
            else if (e instanceof AiModelException) failure = (AiModelException) e;
            else if (readingStream && source instanceof IOException) failure = new AiModelException(AiErrorCode.SSE_ERROR, requestId);
            else failure = new AiModelException(AiErrorCode.UNAVAILABLE, requestId);
            outcome = failure.getCode().name();
            throw failure;
        } finally {
            if (deadline != null) deadline.cancel(false);
            if (registration != null) try { registration.close(); } catch (Exception ignored) { }
            if (call != null) call.close();
            log.info("AI模型调用: requestId={} model={} latencyMs={} success={} stream={} result={}",
                    logValue(requestId), logValue(effectiveModel), elapsed(started), "success".equals(outcome), stream, outcome);
        }
    }

    private AiResponse readStream(InputStream input, String requestId, String effectiveModel, long started,
                                  Consumer<AiStreamChunk> callback, AiCancellationToken token, AtomicBoolean timedOut) throws IOException {
        StreamState state = new StreamState(requestId, effectiveModel, started, callback, token, timedOut);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder event = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            check(token, timedOut, started, requestId);
            if (line.isEmpty()) {
                if (event.length() > 0) {
                    state.accept(event.toString());
                    event.setLength(0);
                    if (state.done) break;
                }
            } else if (line.startsWith("data:")) {
                if (event.length() > 0) event.append('\n');
                String data = line.substring(5);
                event.append(data.startsWith(" ") ? data.substring(1) : data);
            } else if (line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) {
                // SSE 元数据不属于模型正文。
            } else if (!line.isBlank()) {
                if (event.length() > 0) throw new AiModelException(AiErrorCode.SSE_ERROR, requestId);
                state.accept(line);
                if (state.done) break;
            }
        }
        if (event.length() > 0 && !state.done) state.accept(event.toString());
        check(token, timedOut, started, requestId);
        if (!state.done && state.finishReason == null) throw new AiModelException(AiErrorCode.SSE_ERROR, requestId);
        state.emit(state.filter.flush(), null);
        String answer = state.answer.toString().trim();
        requireAnswer(answer, requestId);
        String finish = state.finishReason == null ? "stop" : state.finishReason;
        state.emit("", finish);
        return new AiResponse(requestId, effectiveModel, answer, finish, state.usage, elapsed(started));
    }

    private final class StreamState {
        private final String requestId;
        private final String effectiveModel;
        private final long started;
        private final Consumer<AiStreamChunk> callback;
        private final AiCancellationToken token;
        private final AtomicBoolean timedOut;
        private final ThinkTagFilter filter = new ThinkTagFilter();
        private final StringBuilder answer = new StringBuilder();
        private AiUsage usage = new AiUsage(null, null, null);
        private String finishReason;
        private long sequence;
        private boolean done;

        private StreamState(String requestId, String model, long started, Consumer<AiStreamChunk> callback,
                            AiCancellationToken token, AtomicBoolean timedOut) {
            this.requestId = requestId;
            this.effectiveModel = model;
            this.started = started;
            this.callback = callback;
            this.token = token;
            this.timedOut = timedOut;
        }

        void accept(String payload) {
            if ("[DONE]".equals(payload.trim())) { done = true; return; }
            JsonNode root = parser.parse(payload, requestId, true);
            usage = parser.usage(root, usage);
            String reason = parser.finishReason(root);
            if (reason != null) finishReason = reason;
            emit(filter.accept(parser.text(root)), null);
            done = root.path("done").asBoolean(false)
                    || root.path("choices").path(0).path("message").hasNonNull("content");
        }

        void emit(String text, String finish) {
            check(token, timedOut, started, requestId);
            if (text.isEmpty() && finish == null) return;
            answer.append(text);
            AiStreamChunk chunk = new AiStreamChunk(requestId, effectiveModel, sequence++, text, finish, usage, elapsed(started));
            if (callback != null) {
                try { callback.accept(chunk); }
                catch (RuntimeException e) { throw new AiModelException(AiErrorCode.CALLBACK_ERROR, requestId); }
            }
            check(token, timedOut, started, requestId);
        }
    }

    private Map<String, Object> buildPayload(AiRequest request, String effectiveModel, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", effectiveModel);
        payload.put("stream", stream);
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null) messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
        for (AiMessage message : request.getMessages()) messages.add(Map.of("role", message.getRole(), "content", message.getContent()));
        payload.put("messages", messages);
        Map<String, Object> options = new LinkedHashMap<>();
        int context = request.getContextWindow() == null ? contextWindow : request.getContextWindow();
        if (context > 0) options.put("num_ctx", context);
        // 保留现有 num_ctx 请求；仅对已兼容的 Chat Completions 路径映射新增可选参数。
        boolean chatCompletions = URI.create(endpoint).getPath().replaceAll("/+$", "").endsWith("/chat/completions");
        if (request.getTemperature() != null) {
            (chatCompletions ? payload : options).put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            (chatCompletions ? payload : options).put(chatCompletions ? "max_tokens" : "num_predict", request.getMaxTokens());
        }
        if (!options.isEmpty()) payload.put("options", options);
        if (!chatCompletions && request.getResponseFormat() != null) {
            payload.put("format", request.getResponseFormat());
        }
        if (!chatCompletions && hasText(keepAlive)) {
            payload.put("keep_alive", keepAlive);
        }
        return payload;
    }

    private void validate(AiRequest request, String effectiveModel, String requestId) {
        boolean invalid = request == null || !hasText(endpoint) || !hasText(effectiveModel);
        if (!invalid) {
            invalid = request.getMessages().isEmpty()
                    || (request.getTemperature() != null && (!Double.isFinite(request.getTemperature()) || request.getTemperature() < 0))
                    || (request.getMaxTokens() != null && request.getMaxTokens() <= 0)
                    || (request.getContextWindow() != null && request.getContextWindow() <= 0);
            for (AiMessage message : request.getMessages()) {
                invalid |= message == null || message.getRole() == null || !Set.of("user", "assistant", "system").contains(message.getRole()) || !hasText(message.getContent());
            }
        }
        if (invalid) throw new AiModelException(AiErrorCode.INVALID_REQUEST, requestId);
        try {
            URI uri = URI.create(endpoint);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) { throw new AiModelException(AiErrorCode.INVALID_REQUEST, requestId); }
    }

    private void check(AiCancellationToken token, AtomicBoolean timedOut, long started, String requestId) {
        if (token.isCancelled() || Thread.currentThread().isInterrupted()) throw new AiModelException(AiErrorCode.CANCELLED, requestId);
        if (timedOut.get() || elapsed(started) >= timeoutMillis) throw new AiModelException(AiErrorCode.TIMEOUT, requestId);
    }

    private static void requireAnswer(String text, String requestId) {
        if (!hasText(text)) throw new AiModelException(AiErrorCode.EMPTY_RESULT, requestId);
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private static String logValue(String value) {
        if (value == null) return "";
        String sanitized = value.replaceAll("[^a-zA-Z0-9._:/-]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), 128));
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "ai-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
