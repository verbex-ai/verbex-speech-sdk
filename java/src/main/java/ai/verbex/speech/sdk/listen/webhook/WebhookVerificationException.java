package ai.verbex.speech.sdk.listen.webhook;

import ai.verbex.speech.sdk.error.VerbexException;

/** {@code Webhooks.parse} with a signature that does not verify (spec {@code WebhookVerificationError}). */
public class WebhookVerificationException extends VerbexException {
    public WebhookVerificationException(String message) {
        super(message);
    }
}
