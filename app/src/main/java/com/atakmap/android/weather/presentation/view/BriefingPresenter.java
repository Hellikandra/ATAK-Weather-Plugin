package com.atakmap.android.weather.presentation.view;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.atakmap.android.weather.domain.service.BriefingDocument;
import com.atakmap.coremap.log.Log;

/**
 * Shows a generated briefing, and offers the things a user can do with it.
 *
 * <p>Copying and sharing used to be methods on {@link BriefingDocument} itself —
 * a domain type raising Toasts and starting Intents, which is finding F27. They
 * were also unreachable: the drop-down had reimplemented the clipboard copy
 * inline rather than call them, so the domain-layer versions ran for nobody.
 * Both now live here, once, and are actually wired to buttons.</p>
 *
 * <h3>Contexts</h3>
 * Both are needed and they are not interchangeable. The clipboard is a system
 * service on the host's context; Toasts and dialogs load plugin resources and
 * need the plugin's. Passing one where the other belongs is the failure mode
 * described at length in the project README.
 */
public final class BriefingPresenter {

    private static final String TAG = "BriefingPresenter";

    private final Context pluginContext;
    private final Context appContext;

    /**
     * @param pluginContext plugin APK context — dialogs, Toasts, string resources
     * @param appContext    host activity context — the clipboard service and
     *                      the Activity the share chooser is started from
     */
    public BriefingPresenter(Context pluginContext, Context appContext) {
        this.pluginContext = pluginContext;
        this.appContext    = appContext;
    }

    /**
     * Show the briefing with Copy and Share actions.
     *
     * @param dialogContext the context the dialog itself is built against —
     *                      must be an Activity context, so
     *                      {@code mapView.getContext()} rather than the plugin's
     */
    public void show(Context dialogContext, final BriefingDocument doc) {
        if (doc == null) return;
        new AlertDialog.Builder(dialogContext)
                .setTitle(doc.getTitle())
                .setMessage(doc.getPlainText())
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy", (d, w) -> copyToClipboard(doc))
                .setNegativeButton("Share", (d, w) -> share(doc))
                .show();
    }

    /** Put the plain-text briefing on the system clipboard. */
    public void copyToClipboard(BriefingDocument doc) {
        if (doc == null) return;
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    appContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                toast("Clipboard unavailable");
                return;
            }
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("Weather Briefing", doc.getPlainText()));
            toast("Briefing copied to clipboard");
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy briefing to clipboard", e);
            toast("Copy failed: " + e.getMessage());
        }
    }

    /** Hand the briefing to whatever the user picks from the share sheet. */
    public void share(BriefingDocument doc) {
        if (doc == null) return;
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, doc.getTitle());
            shareIntent.putExtra(Intent.EXTRA_TEXT, doc.getPlainText());

            Intent chooser = Intent.createChooser(shareIntent, "Share Weather Briefing");
            // The chooser is started from the host Activity's context, which is
            // already an Activity — NEW_TASK would put it on its own stack and
            // leave the map behind it.
            appContext.startActivity(chooser);
        } catch (Exception e) {
            Log.e(TAG, "Failed to share briefing", e);
            toast("Share failed: " + e.getMessage());
        }
    }

    private void toast(String message) {
        Toast.makeText(pluginContext, message, Toast.LENGTH_SHORT).show();
    }
}
