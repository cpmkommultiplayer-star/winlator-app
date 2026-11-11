package com.winlator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

/**
 * Answers the page-level prompts that a browser has to handle for web apps to work:
 * file uploads, alert/confirm dialogs, window.prompt text input.
 *
 * Deliberately not handled: HTTP auth stays dismissed (there is no credential UI), and
 * touch/pointer prompts fall through to Gecko's defaults. Nothing here grants a device
 * permission; those still go through {@link BrowserActivity}'s permission delegate, which denies.
 */
public final class BrowserPrompts implements GeckoSession.PromptDelegate {
    static final int REQUEST_FILE = 4102;

    private final Activity activity;

    private GeckoResult<PromptResponse> pendingResult;
    private FilePrompt pendingPrompt;

    public BrowserPrompts(Activity activity) {
        this.activity = activity;
    }

    /**
     * A file input needs the system picker, so the result cannot be produced synchronously.
     * We hand back a result we complete from {@link #onActivityResult}.
     */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onFilePrompt(@NonNull GeckoSession session, @NonNull FilePrompt prompt) {
        cancelPending();

        GeckoResult<PromptResponse> result = new GeckoResult<>();
        pendingResult = result;
        pendingPrompt = prompt;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (prompt.mimeTypes != null && prompt.mimeTypes.length > 0) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, prompt.mimeTypes);
            if (prompt.mimeTypes.length == 1) intent.setType(prompt.mimeTypes[0]);
        }

        try {
            activity.startActivityForResult(intent, REQUEST_FILE);
        }
        catch (Exception e) {
            pendingResult = null;
            pendingPrompt = null;
            result.complete(prompt.dismiss());
        }
        return result;
    }

    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_FILE) return;

        GeckoResult<PromptResponse> result = pendingResult;
        FilePrompt prompt = pendingPrompt;
        pendingResult = null;
        pendingPrompt = null;
        if (result == null || prompt == null) return;

        Uri uri = data == null ? null : data.getData();
        result.complete(uri == null ? prompt.dismiss() : prompt.confirm(activity, uri));
    }

    /** alert() and confirm(): confirming sends the primary button, dismissing the dialog cancels. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(@NonNull GeckoSession session, @NonNull ButtonPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
            .setTitle(prompt.title == null ? "" : prompt.title)
            .setMessage(prompt.message == null ? "" : prompt.message)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> result.complete(prompt.confirm(0)))
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.complete(prompt.dismiss()))
            .setOnCancelListener(dialog -> result.complete(prompt.dismiss()))
            .show());
        return result;
    }

    /** window.prompt(). */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onTextPrompt(@NonNull GeckoSession session, @NonNull TextPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        activity.runOnUiThread(() -> {
            EditText input = new EditText(activity);
            input.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                             ViewGroup.LayoutParams.WRAP_CONTENT));
            if (prompt.defaultValue != null) input.setText(prompt.defaultValue);

            new AlertDialog.Builder(activity)
                .setTitle(prompt.title == null ? "" : prompt.title)
                .setMessage(prompt.message == null ? "" : prompt.message)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                    (dialog, which) -> result.complete(prompt.confirm(String.valueOf(input.getText()))))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.complete(prompt.dismiss()))
                .setOnCancelListener(dialog -> result.complete(prompt.dismiss()))
                .show();
        });
        return result;
    }

    /**
     * Leaving a page is always allowed. Blocking it would let a site trap the user in the
     * activity with nothing but a dead back gesture.
     */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onBeforeUnloadPrompt(@NonNull GeckoSession session,
                                                            @NonNull BeforeUnloadPrompt prompt) {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
    }

    /** Re-posting a form on reload is fine; the user asked for the reload. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onRepostConfirmPrompt(@NonNull GeckoSession session,
                                                             @NonNull RepostConfirmPrompt prompt) {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
    }

    /** No credential entry UI, so a server auth challenge is declined rather than guessed at. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(@NonNull GeckoSession session, @NonNull AuthPrompt prompt) {
        return GeckoResult.fromValue(prompt.dismiss());
    }

    /** Completes an unanswered picker so the page is not left waiting if the activity goes away. */
    public void cancelPending() {
        GeckoResult<PromptResponse> result = pendingResult;
        FilePrompt prompt = pendingPrompt;
        pendingResult = null;
        pendingPrompt = null;
        if (result != null && prompt != null) result.complete(prompt.dismiss());
    }
}
