package ai.verbex.speech.sdk.transport;

import ai.verbex.speech.sdk.VerbexSpeech;

/**
 * Shared machinery of the SDK's {@code listen} and {@code speak} packages. Public only because Java
 * cannot share a type between packages otherwise: <b>not part of the API</b>, and it may change in any
 * release. Use {@link VerbexSpeech}.
 */
public enum CallKind {
    GET, UPLOAD_START, SPEAK, SUBMIT
}
