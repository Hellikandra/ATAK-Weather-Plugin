package com.atakmap.android.weather.domain.service;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Holds a generated weather briefing in plain text and HTML formats.
 *
 * <p>Sprint 12 (S12.3): Supports copying to clipboard, sharing via
 * Android Intent, and saving as an HTML file.</p>
 */
public class BriefingDocument {

    private static final String TAG = "BriefingDocument";
    private static final String BRIEFINGS_DIR = "/sdcard/atak/tools/weather_briefings";

    private final String plainText;
    private final String html;
    private final String title;
    private final long generatedTime;

    /**
     * Create a new BriefingDocument.
     *
     * @param plainText     the plain-text version of the briefing
     * @param html          the HTML version of the briefing
     * @param title         a descriptive title
     * @param generatedTime epoch milliseconds when the briefing was generated
     */
    public BriefingDocument(String plainText, String html, String title, long generatedTime) {
        this.plainText = plainText;
        this.html = html;
        this.title = title;
        this.generatedTime = generatedTime;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getPlainText()    { return plainText; }
    public String getHtml()         { return html; }
    public String getTitle()        { return title; }
    public long   getGeneratedTime() { return generatedTime; }

    // ── Actions ──────────────────────────────────────────────────────────────

    /**
     * Copy the plain text briefing to the system clipboard.
     *
     * @param context Android context
     */
    public void copyToClipboard(Context context) {
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("Weather Briefing", plainText);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Briefing copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy to clipboard", e);
            Toast.makeText(context, "Copy failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Share the briefing via Android's share intent.
     *
     * @param context Android context
     */
    public void share(Context context) {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
            shareIntent.putExtra(Intent.EXTRA_TEXT, plainText);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent chooser = Intent.createChooser(shareIntent, "Share Weather Briefing");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception e) {
            Log.e(TAG, "Failed to share briefing", e);
            Toast.makeText(context, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Save the HTML briefing to a file.
     *
     * @param context Android context
     * @return the file path of the saved briefing, or null on failure
     */
    public String saveToFile(Context context) {
        try {
            File dir = new File(BRIEFINGS_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create briefings directory: " + BRIEFINGS_DIR);
                Toast.makeText(context, "Failed to create directory", Toast.LENGTH_SHORT).show();
                return null;
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date(generatedTime));
            String filename = "briefing_" + timestamp + ".html";
            File file = new File(dir, filename);

            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer.write(html);
            writer.flush();
            writer.close();
            fos.close();

            String path = file.getAbsolutePath();
            Log.d(TAG, "Briefing saved to: " + path);
            Toast.makeText(context, "Saved: " + path, Toast.LENGTH_LONG).show();
            return path;

        } catch (Exception e) {
            Log.e(TAG, "Failed to save briefing", e);
            Toast.makeText(context, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    /**
     * Save the plain text briefing to a file.
     *
     * @param context Android context
     * @return the file path of the saved text briefing, or null on failure
     */
    public String saveTextToFile(Context context) {
        try {
            File dir = new File(BRIEFINGS_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create briefings directory: " + BRIEFINGS_DIR);
                return null;
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date(generatedTime));
            String filename = "briefing_" + timestamp + ".txt";
            File file = new File(dir, filename);

            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer.write(plainText);
            writer.flush();
            writer.close();
            fos.close();

            return file.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "Failed to save text briefing", e);
            return null;
        }
    }
}
