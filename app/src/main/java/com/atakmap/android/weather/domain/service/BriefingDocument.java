package com.atakmap.android.weather.domain.service;

/**
 * A generated weather briefing, in plain text and HTML.
 *
 * <p>Only that. It used to also copy itself to the clipboard, raise Toasts,
 * start a share Intent and write files to a hardcoded {@code /sdcard} path —
 * business logic in the domain layer deciding its own presentation, which is
 * finding F27. Those methods had no callers: the drop-down had reimplemented
 * the clipboard copy inline rather than use them. Copying and sharing now live
 * in {@code presentation.view.BriefingPresenter}, where an Android context
 * belongs.</p>
 *
 * <p>Pure Java, and the {@code ..domain..} ArchUnit rule now enforces that for
 * the whole layer rather than {@code domain.model} alone.</p>
 */
public class BriefingDocument {

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

}
