# Java: ai.verbex:verbex-speech-sdk

```xml
<dependency>
  <groupId>ai.verbex</groupId>
  <artifactId>verbex-speech-sdk</artifactId>
  <version>0.1.1</version>
</dependency>
```

Gradle: `implementation("ai.verbex:verbex-speech-sdk:0.1.1")`. Java 17 or later. All calls are
synchronous; the client is thread-safe, so build one and share it.

## Client

```java
import ai.verbex.speech.sdk.VerbexSpeech;

VerbexSpeech client = VerbexSpeech.builder().build();   // reads VERBEX_API_KEY and VERBEX_BASE_URL
// or .apiKey(...).baseUrl(...).timeout(Duration.ofSeconds(30)).maxRetries(2)
```

Packages: `ai.verbex.speech.sdk.listen.job` (`Job`, `Receipt`, `TranscribeRequest`),
`…listen.stream` (`ListenSession`, `ListenEvent`), `…listen.webhook` (`Webhooks`),
`…speak` (`SpeakRequest`, `AudioFormat`), `…speak.synthesis` (`SpeakAudio`, `SpeakAudioStream`),
`…speak.stream` (`SpeakSession`, `SayResult`), `…speak.voice` (`Voice`), `…error`.

## Transcribe a file or a URL

```java
Job job = client.listen().transcribe(Path.of("call.wav"), "verbex_jp_stt_x1", "ja");
if (job.isCompleted()) {
    System.out.println(job.text());
} else {
    System.out.println(job.status() + " " + job.error());     // failed or cancelled: not an exception
}

TranscribeRequest request = TranscribeRequest.builder()
        .url("https://storage.example.com/call.wav")  // or .file(Path), .bytes(byte[]), .stream(InputStream, size)
        .model("verbex_jp_stt_x1").language("ja")
        .hotwords(List.of("かいみょう"))                  // optional
        .metadata(Map.of("call_id", "c-42"))           // optional, returned with the job
        .webhookUrl("https://example.com/stt/done")    // optional
        .tags(List.of("campaign-7"))                   // optional
        .idempotencyKey("call-c-42")                   // optional; saved with the work
        .build();

Receipt receipt = client.listen().submit(request);    // jobId(), status(), replayed()
Job done = client.listen().waitFor(receipt.jobId(), Duration.ofSeconds(2), Duration.ofMinutes(10));
// client.listen().transcribe(request) = submit + waitFor
```

## Webhook

```java
import ai.verbex.speech.sdk.listen.webhook.Webhooks;

// rawBody: the exact bytes of the request body; headers: the request headers (any case)
if (!Webhooks.verify(apiKey, rawBody, headers)) {
    return 401;
}
Job job = Webhooks.parse(apiKey, rawBody, headers);   // throws WebhookVerificationException
```

## Live transcription

```java
try (ListenSession session = client.listen().stream("verbex_jp_stt_x1", "ja")) {
    sender.start();   // another thread: session.send(pcm) at the audio's pace, then session.finish()
    ListenEvent event;
    while ((event = session.nextEvent()) != null) {          // null after done
        if (event instanceof ListenEvent.SegmentEvent s) {
            System.out.println(s.text());
        }
    }
} catch (StreamException e) {
    if (e.retryable()) { /* reconnect and resume sending audio */ }
}
```

Audio: raw PCM, 16 kHz, mono, signed 16-bit little-endian. `configure(hotwords)` changes hotwords.

## Text-to-speech

```java
Voice voice = client.speak().voices().stream()
        .filter(v -> v.language().equals("ja")).findFirst().orElseThrow();
SpeakAudio audio = client.speak().synthesize(SpeakRequest.builder()
        .model(voice.model()).voice(voice.code()).text("こんにちは")
        .format(AudioFormat.WAV)                              // PCM (default) or WAV
        .idempotencyKey(UUID.randomUUID().toString())         // optional; one per utterance
        .build());
Files.write(Path.of("hello.wav"), audio.audio());
```

Play while it is synthesised:

```java
try (SpeakAudioStream in = client.speak().synthesizeStream(request)) {
    in.transferTo(player);
}
```

Many utterances on one socket, one at a time:

```java
try (SpeakSession session = client.speak().stream()) {
    SayResult first = session.say(voice.model(), voice.code(), "一つ目。");
    player.write(first.audio());
}
```

A failed utterance throws `SpeakException`; the session stays open.

## Errors

All unchecked, extending `VerbexException`. Most are in `ai.verbex.speech.sdk.error`; `SpeakException` is in
`…speak`, `UploadException` and `WaitTimeoutException` in `…listen.job`, and
`WebhookVerificationException` in `…listen.webhook`.

```java
try {
    client.listen().submit(request);
} catch (PaymentRequiredException e) {      // 402: no plan, not enough credit, overdue invoice
    ...
} catch (RateLimitException e) {            // 429 after the SDK's own retries; e.retryAfter()
    ...
} catch (ApiException e) {
    log.error("verbex {} {} requestId={}", e.status(), e.code(), e.requestId());
    throw e;
}
```

`ApiException` subtypes: `BadRequestException`, `AuthenticationException`,
`PaymentRequiredException`, `PermissionDeniedException`, `NotFoundException`,
`RateLimitException`, `ServerException`. Also `ConfigurationException`, `ApiConnectionException`,
`ApiTimeoutException`, `UploadException`, `StreamException`, `SpeakException`,
`WaitTimeoutException` (`lastJob()`), `WebhookVerificationException`. Mistakes in the call itself
(two sources, over 100 MiB, a bad idempotency key) are `IllegalArgumentException` or
`IllegalStateException`.
